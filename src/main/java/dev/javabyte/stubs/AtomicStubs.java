package dev.javabyte.stubs;

import dev.javabyte.env.SimFieldUpdaters;
import dev.javabyte.host.CallContext;
import dev.javabyte.model.FieldRef;
import dev.javabyte.model.GuestClass;
import dev.javabyte.model.GuestMethod;
import dev.javabyte.vm.VirtualMachine;
import java.lang.reflect.Modifier;
import java.util.function.Predicate;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldNode;

/**
 * {@code AtomicReferenceFieldUpdater.newUpdater}, {@code AtomicIntegerFieldUpdater.newUpdater}
 * and {@code AtomicLongFieldUpdater.newUpdater} for fields of interpreted classes.
 *
 * <p>Lock-free code uses these instead of {@code AtomicReference} to save an object per field:
 * queues, caches and the state machines of some loaders. The JDK's own versions find the field
 * by reflection on a real class, so they cannot take a guest class; the updaters made here
 * ({@link SimFieldUpdaters}) work on the guest's fields instead.
 *
 * <p>The checks match the JDK's, in its order, with the same exceptions, because code that
 * probes an emulator can make a bad updater on purpose and look at what is thrown:
 * <ol>
 *   <li>the field must be declared by the class itself (not inherited): otherwise a
 *       {@code RuntimeException} caused by {@code NoSuchFieldException};</li>
 *   <li>the caller must be able to access it: otherwise a {@code RuntimeException} caused by
 *       {@code IllegalAccessException}, worded as the advertised Java words it;</li>
 *   <li>its type must match ({@code IllegalArgumentException} "Must be integer type" or
 *       "Must be long type"; for references a {@code ClassCastException} if the value class is
 *       not exactly the field's type, and "Must be reference type" for a primitive);</li>
 *   <li>it must be volatile ("Must be volatile type") and not static.</li>
 * </ol>
 *
 * <p>An updater for a JDK class's field is made by the JDK as usual.
 */
final class AtomicStubs {

    private static final String ATOMIC = "java/util/concurrent/atomic/";

    private AtomicStubs() {}

    static void install(VirtualMachine vm) {
        vm.stubs.on(ATOMIC + "AtomicReferenceFieldUpdater", "newUpdater", ctx -> newUpdater(ctx, Kind.REFERENCE));
        vm.stubs.on(ATOMIC + "AtomicIntegerFieldUpdater", "newUpdater", ctx -> newUpdater(ctx, Kind.INT));
        vm.stubs.on(ATOMIC + "AtomicLongFieldUpdater", "newUpdater", ctx -> newUpdater(ctx, Kind.LONG));
        // getClass() names the JDK's implementation class, as a real JVM does.
        vm.types.addImitation(value -> value instanceof SimFieldUpdaters.Reference ? new String[] {Kind.REFERENCE.jdkName}
                : value instanceof SimFieldUpdaters.Int ? new String[] {Kind.INT.jdkName}
                : value instanceof SimFieldUpdaters.Long ? new String[] {Kind.LONG.jdkName} : null);
    }

    /** The three updaters, with the JDK class each imitates and its type-check message. */
    private enum Kind {
        REFERENCE(ATOMIC + "AtomicReferenceFieldUpdater$AtomicReferenceFieldUpdaterImpl", null),
        INT(ATOMIC + "AtomicIntegerFieldUpdater$AtomicIntegerFieldUpdaterImpl", "Must be integer type"),
        LONG(ATOMIC + "AtomicLongFieldUpdater$CASUpdater", "Must be long type");

        final String jdkName;
        final String wrongType;

        Kind(String jdkName, String wrongType) {
            this.jdkName = jdkName;
            this.wrongType = wrongType;
        }
    }

    private static Object newUpdater(CallContext ctx, Kind kind) throws Exception {
        VirtualMachine vm = ctx.vm;
        Object classArg = ctx.arg(0);
        if (!(classArg instanceof GuestClass type)) {
            // A JDK class: its fields are real, so the JDK's own updater works on them.
            return ctx.proceed();
        }
        Object valueClass = kind == Kind.REFERENCE ? ctx.arg(1) : null;
        String name = (String) ctx.arg(kind == Kind.REFERENCE ? 2 : 1);
        if (name == null || (kind == Kind.REFERENCE && valueClass == null)) {
            throw ctx.raise(new NullPointerException());
        }
        FieldNode field = declared(type, name);
        if (field == null) {
            throw ctx.raise(new RuntimeException(new NoSuchFieldException(name)));
        }
        GuestClass caller = caller(vm, type);
        String denied = accessProblem(vm, caller, type, field);
        if (denied != null) {
            throw ctx.raise(new RuntimeException(new IllegalAccessException(denied)));
        }

        Predicate<Object> accepts = null;
        switch (kind) {
            case INT, LONG -> {
                if (!field.desc.equals(kind == Kind.INT ? "I" : "J")) {
                    throw ctx.raise(new IllegalArgumentException(kind.wrongType));
                }
            }
            case REFERENCE -> {
                if (!field.desc.equals(descriptorOf(valueClass))) {
                    throw ctx.raise(new ClassCastException());
                }
                if (valueClass instanceof Class<?> c && c.isPrimitive()) {
                    throw ctx.raise(new IllegalArgumentException("Must be reference type"));
                }
                String valueType = internalNameOf(valueClass);
                accepts = value -> vm.types.isInstance(value, valueType);
            }
        }
        if ((field.access & Opcodes.ACC_VOLATILE) == 0) {
            throw ctx.raise(new IllegalArgumentException("Must be volatile type"));
        }
        if ((field.access & Opcodes.ACC_STATIC) != 0) {
            throw ctx.raise(new IllegalArgumentException());
        }

        FieldRef ref = type.declaredField(name, field.desc);
        SimFieldUpdaters.Target target = new SimFieldUpdaters.Target(vm, type, ref,
                restrictedTo(caller, type, field), kind.jdkName);
        vm.events.addOnce("updater:" + type.name + '.' + name, "reflect", "field-updater",
                "class", type.dottedName(), "field", name);
        return switch (kind) {
            case REFERENCE -> new SimFieldUpdaters.Reference(target, accepts);
            case INT -> new SimFieldUpdaters.Int(target);
            case LONG -> new SimFieldUpdaters.Long(target);
        };
    }

    /** The field {@code name} as the class itself declares it, as {@code getDeclaredField} finds it. */
    private static FieldNode declared(GuestClass type, String name) {
        for (FieldNode field : type.node.fields) {
            if (field.name.equals(name)) {
                return field;
            }
        }
        return null;
    }

    /**
     * The class that called {@code newUpdater}: the method running when the stub was reached.
     * When there is none (host code calling back), the field's own class stands in, which
     * passes every access check.
     */
    private static GuestClass caller(VirtualMachine vm, GuestClass fallback) {
        GuestMethod method = vm.currentMethod();
        return method == null ? fallback : method.owner;
    }

    /**
     * Why {@code caller} may not use the field, in the JDK's words, or null if it may. A
     * private field needs the same class or, from Java 11, a nestmate (classes compiled for
     * Java 8 have no nest, so this is the same rule there); a package-private field needs the
     * same package; a protected one, the same package or a subclass.
     */
    private static String accessProblem(VirtualMachine vm, GuestClass caller, GuestClass type, FieldNode field) {
        boolean allowed;
        if ((field.access & Opcodes.ACC_PUBLIC) != 0) {
            allowed = true;
        } else if ((field.access & Opcodes.ACC_PRIVATE) != 0) {
            allowed = nestHost(caller).equals(nestHost(type));
        } else {
            allowed = samePackage(caller, type)
                    || ((field.access & Opcodes.ACC_PROTECTED) != 0 && caller.isSubtypeOf(type.name));
        }
        if (allowed) {
            return null;
        }
        String modifiers = Modifier.toString(field.access & Modifier.fieldModifiers());
        return SystemStubs.legacyJava(vm.env)
                ? "Class " + caller.dottedName() + " can not access a member of class " + type.dottedName()
                        + " with modifiers \"" + modifiers + '"'
                : "class " + caller.dottedName() + " cannot access a member of class " + type.dottedName()
                        + " with modifiers \"" + modifiers + '"';
    }

    /**
     * For a protected field reached from a subclass in another package, the JDK lets the
     * updater work only on objects of the caller's class; null when no such limit applies.
     */
    private static GuestClass restrictedTo(GuestClass caller, GuestClass type, FieldNode field) {
        boolean protectedOnly = (field.access & Opcodes.ACC_PROTECTED) != 0;
        return protectedOnly && caller != type && !samePackage(caller, type) && caller.isSubtypeOf(type.name) ? caller : null;
    }

    private static String nestHost(GuestClass cls) {
        return cls.node.nestHostClass != null ? cls.node.nestHostClass : cls.name;
    }

    private static boolean samePackage(GuestClass a, GuestClass b) {
        return packageOf(a.name).equals(packageOf(b.name));
    }

    private static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    /** The descriptor of a class argument, which is a guest class or a real JDK class. */
    private static String descriptorOf(Object cls) {
        return cls instanceof GuestClass guest ? 'L' + guest.name + ';' : Type.getDescriptor((Class<?>) cls);
    }

    private static String internalNameOf(Object cls) {
        return cls instanceof GuestClass guest ? guest.name : Type.getInternalName((Class<?>) cls);
    }
}
