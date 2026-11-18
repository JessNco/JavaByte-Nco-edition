package dev.javabyte.vm;

import dev.javabyte.host.StandIn;
import dev.javabyte.model.GuestClass;
import dev.javabyte.model.GuestLambda;
import dev.javabyte.model.InstanceState;
import dev.javabyte.model.Mirrors;
import dev.javabyte.model.PendingInstance;
import dev.javabyte.model.PhantomObject;
import org.objectweb.asm.Type;

/**
 * Answers type questions ({@code instanceof}, casts, catch matching, class literals) across the
 * two worlds: interpreted classes, which have no host {@code Class}, and real JDK classes.
 */
public final class TypeSystem {

    /**
     * JDK types that guest code may hold as emulator mirrors rather than real instances. Arrays
     * of these are created as {@code Object[]}, because a real {@code Class[]} could not store
     * the mirror of an interpreted class.
     */
    private static final java.util.Set<String> MIRRORED_TYPES = java.util.Set.of("java/lang/Class",
            "java/lang/reflect/Method", "java/lang/reflect/Field", "java/lang/reflect/Constructor",
            "java/lang/reflect/Type", "java/lang/invoke/MethodHandles$Lookup");

    private final VirtualMachine vm;

    /**
     * Rules that name the concrete JDK class an emulator object stands for, such as
     * {@code java.lang.ProcessImpl} for a simulated process; see {@link #addImitation}.
     */
    private final java.util.List<java.util.function.Function<Object, String[]>> imitations =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Hash codes handed out by {@link #identityHash}, so each object keeps its own. */
    private final java.util.Map<Object, Integer> identityHashes = new java.util.IdentityHashMap<>();

    TypeSystem(VirtualMachine vm) {
        this.vm = vm;
    }

    /**
     * Adds a rule naming the JDK class that a kind of emulator object stands for. The rule
     * returns null for objects it does not cover, or candidate internal names, best first:
     * the class the advertised machine would really have, then stand-ins for it on an analysis
     * JDK that lacks it. {@link #reportedClass} takes the first candidate the host has, and
     * {@link #nameOf} always names the first, so text matches the advertised machine even when
     * no class object for it exists here.
     */
    public void addImitation(java.util.function.Function<Object, String[]> rule) {
        imitations.add(rule);
    }

    /**
     * A stand-in for {@code System.identityHashCode} in text the guest can read, such as the
     * {@code @1b6d3586} in a class loader's {@code toString()}. A real identity hash differs
     * between runs, so a report that showed one would never compare equal to the next. These
     * are fixed by the order in which objects are first asked about: the same sample always
     * gets the same values, and each looks like a typical HotSpot hash (31 bits, usually seven
     * or eight hex digits).
     */
    public int identityHash(Object value) {
        synchronized (identityHashes) {
            return identityHashes.computeIfAbsent(value, unused -> {
                long z = 0x9e3779b97f4a7c15L * (identityHashes.size() + 1) + 0x1b6d3586L;
                z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
                z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
                int hash = (int) (z ^ (z >>> 31)) & 0x7fffffff;
                return hash < 0x01000000 ? hash | 0x01000000 : hash;
            });
        }
    }

    /** The candidate class names the imitation rules give for a value, or null if none applies. */
    private String[] imitatedNames(Object value) {
        for (java.util.function.Function<Object, String[]> rule : imitations) {
            String[] names = rule.apply(value);
            if (names != null && names.length > 0) {
                return names;
            }
        }
        return null;
    }

    /**
     * @param type internal class name, or an array descriptor
     * @return whether a non-null {@code value} is an instance of {@code type}
     */
    public boolean isInstance(Object value, String type) {
        if ("java/lang/Object".equals(type)) {
            return true;
        }
        if (type.charAt(0) == '[') {
            return isArrayInstance(value, type);
        }
        InstanceState state = vm.stateOf(value);
        if (state != null) {
            if (state.type.isSubtypeOf(type)) {
                return true;
            }
            // Not a guest relationship; the shell may still inherit the type from the JDK
            // (a guest class extending ArrayList is a List).
            Class<?> host = vm.classes.contains(type) ? null : vm.host.findClass(type);
            return host != null && host.isInstance(value);
        }
        if (value instanceof GuestLambda lambda) {
            GuestClass iface = vm.classes.find(lambda.interfaceName());
            return lambda.interfaceName().equals(type) || (iface != null && iface.isSubtypeOf(type));
        }
        if (value instanceof dev.javabyte.model.GuestProxy proxy) {
            for (Object mirror : proxy.interfaces()) {
                Class<?> host = mirror instanceof Class<?> cls ? cls : null;
                Class<?> wanted = host == null ? null : vm.host.findClass(type);
                if ((mirror instanceof GuestClass iface && iface.isSubtypeOf(type))
                        || (wanted != null && wanted.isAssignableFrom(host))) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof PhantomObject) {
            // Nothing is known about a phantom's hierarchy. Saying yes keeps the sample on the
            // path it would take if the real class were present.
            return true;
        }
        if (value instanceof PendingInstance pending) {
            return type.equals(pending.hostClass);
        }
        Class<?> host = vm.host.findClass(type);
        if (host == null) {
            return false;
        }
        Class<?> imitated = imitatedClass(value);
        if (imitated != null) {
            return host.isAssignableFrom(imitated);
        }
        return host.isInstance(value);
    }

    /** For emulator objects that imitate a JDK type without extending it, the type they imitate. */
    private Class<?> imitatedClass(Object value) {
        if (value instanceof GuestClass) {
            return Class.class;
        }
        if (value instanceof Mirrors.MethodMirror) {
            return java.lang.reflect.Method.class;
        }
        if (value instanceof Mirrors.ConstructorMirror) {
            return java.lang.reflect.Constructor.class;
        }
        if (value instanceof Mirrors.FieldMirror) {
            return java.lang.reflect.Field.class;
        }
        if (value instanceof Mirrors.LookupMirror) {
            return java.lang.invoke.MethodHandles.Lookup.class;
        }
        if (value instanceof StandIn standIn) {
            return vm.host.findClass(standIn.standsFor());
        }
        return null;
    }

    private boolean isArrayInstance(Object value, String descriptor) {
        if (!value.getClass().isArray()) {
            return false;
        }
        if (value.getClass() == Object[].class) {
            // A plain Object[] is what the emulator creates for arrays of guest classes and of
            // mirrored types such as Class, so its declared element type cannot be checked.
            return !descriptor.startsWith("[[") && descriptor.length() > 2;
        }
        Class<?> host = vm.host.findClass(descriptor);
        if (host != null) {
            return host.isInstance(value);
        }
        // The element type is a guest or unknown class, whose arrays are Object arrays here.
        // Element types cannot be told apart, so only the shape is checked.
        return value instanceof Object[];
    }

    /**
     * A readable class name for any value, as the guest would name it. It appears in text the
     * guest can read (cast failures, {@code ClassLoader.toString}), so it never names one of
     * the emulator's own classes: those are reported as the JDK class they stand for.
     */
    public String nameOf(Object value) {
        if (value == null) {
            return "null";
        }
        InstanceState state = vm.stateOf(value);
        if (state != null) {
            return state.type.dottedName();
        }
        if (value instanceof PhantomObject phantom) {
            return phantom.type.replace('/', '.');
        }
        String[] imitated = value instanceof StandIn ? null : imitatedNames(value);
        if (imitated != null) {
            return imitated[0].replace('/', '.');
        }
        return reportedClass(value).getName();
    }

    /**
     * The JDK class the guest is told a value belongs to, for any value that is not an
     * instance of an interpreted class. This is the single rule for both {@code getClass()} and
     * {@link #nameOf}, so the two always agree.
     *
     * <p>Emulator objects must not reveal themselves: a simulated {@code File} is a
     * {@code dev.javabyte.env.SimFile} on the host, and a sample that found that package name in
     * a cast message would know it is being emulated. Such objects are reported as their
     * nearest JDK ancestor, or as the JDK type they imitate. Where an imitation rule (see
     * {@link #addImitation}) names a concrete class, such as {@code java.lang.ProcessImpl},
     * that class is reported if the host has it.
     */
    public Class<?> reportedClass(Object value) {
        if (value instanceof dev.javabyte.model.GuestProxy) {
            // The class of a real proxy is generated and extends Proxy; this is the nearest
            // honest answer, and Proxy.isProxyClass recognises it.
            return java.lang.reflect.Proxy.class;
        }
        if (value instanceof GuestLambda) {
            return Object.class;
        }
        if (value instanceof PhantomObject phantom) {
            Class<?> host = vm.host.findClass(phantom.type);
            return host != null ? host : Object.class;
        }
        Class<?> imitated = imitatedClass(value);
        if (imitated != null) {
            return imitated;
        }
        if (value instanceof StandIn) {
            return Object.class; // stands for a class this JDK lacks
        }
        String[] candidates = imitatedNames(value);
        if (candidates != null) {
            for (String candidate : candidates) {
                Class<?> found = vm.host.findClass(candidate);
                if (found != null) {
                    return found;
                }
            }
        }
        Class<?> cls = value.getClass();
        while (cls.getName().startsWith("dev.javabyte.")) {
            cls = cls.getSuperclass();
        }
        return cls;
    }

    /**
     * The host class to use as an array element type: the JDK class if there is one, else
     * {@code Object}.
     */
    public Class<?> hostClassOrObject(String internalNameOrDescriptor) {
        if (internalNameOrDescriptor.charAt(0) == '[') {
            // Nested array: an array class exists only if the innermost element is a JDK type.
            Class<?> host = vm.host.findClass(internalNameOrDescriptor);
            if (host != null) {
                return host;
            }
            int depth = internalNameOrDescriptor.lastIndexOf('[') + 1;
            return java.lang.reflect.Array.newInstance(Object.class, new int[depth]).getClass();
        }
        if (vm.classes.contains(internalNameOrDescriptor) || MIRRORED_TYPES.contains(internalNameOrDescriptor)) {
            return Object.class;
        }
        Class<?> host = vm.host.findClass(internalNameOrDescriptor);
        // A class that policy blocks outright can only ever be represented by phantom objects,
        // which a correctly typed array would refuse to store.
        return host != null && vm.policy.knowsClass(internalNameOrDescriptor) ? host : Object.class;
    }

    public Class<?> primitiveClass(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> boolean.class;
            case Type.BYTE -> byte.class;
            case Type.CHAR -> char.class;
            case Type.SHORT -> short.class;
            case Type.INT -> int.class;
            case Type.LONG -> long.class;
            case Type.FLOAT -> float.class;
            case Type.DOUBLE -> double.class;
            default -> void.class;
        };
    }

    /**
     * The guest-visible class object for a type: a {@link GuestClass} for interpreted classes, a
     * real {@code Class} otherwise. Returns null if the class does not exist anywhere.
     */
    public Object classMirror(Type type) {
        switch (type.getSort()) {
            case Type.OBJECT: {
                GuestClass guest = vm.classes.find(type.getInternalName());
                return guest != null ? guest : vm.host.findClass(type.getInternalName());
            }
            case Type.ARRAY:
                return hostClassOrObject(type.getDescriptor());
            default:
                return primitiveClass(type);
        }
    }

    /** Erases a class mirror to a real class, for JDK APIs that need one (method types, arrays). */
    public Class<?> erase(Object mirror) {
        return mirror instanceof Class<?> cls ? cls : Object.class;
    }

    /** Descriptor of the type a class mirror denotes. */
    public String descriptorOf(Object mirror) {
        return mirror instanceof GuestClass guest ? 'L' + guest.name + ';' : Type.getDescriptor((Class<?>) mirror);
    }

    public String internalNameOf(Object mirror) {
        return mirror instanceof GuestClass guest ? guest.name : Type.getInternalName((Class<?>) mirror);
    }
}
