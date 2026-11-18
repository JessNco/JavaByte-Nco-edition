package dev.javabyte.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * An interpreted class. Also serves as the guest's {@code java.lang.Class} object for that class,
 * since a real {@code Class} cannot exist for code the host JVM never loaded.
 *
 * <p>Supertypes are linked on first use rather than at load time. Loaders that define classes at
 * run time frequently define a subclass before its parent, and eager linking would wrongly treat
 * the not-yet-defined parent as missing.
 */
public final class GuestClass {

    /** Initialisation state, mirroring the JVM's class initialisation procedure (JVMS 5.5). */
    public enum InitState { UNINITIALISED, IN_PROGRESS, INITIALISED, FAILED }

    public final ClassRepository repository;
    public final ClassNode node;
    /** Internal (slash-separated) name. */
    public final String name;
    /** Original class file bytes, kept so run-time-defined classes can be dumped for later study. */
    public final byte[] bytes;
    /** Where the class came from: {@code sample}, {@code classpath}, {@code defineClass}, ... */
    public final String origin;

    public InitState initState = InitState.UNINITIALISED;
    /** The guest thread running the static initialiser, while {@link #initState} is IN_PROGRESS. */
    public Object initialisingThread;

    private final Map<String, GuestMethod> methods = new LinkedHashMap<>();
    private final Map<String, GuestMethod> virtualCache = new HashMap<>();
    private final Map<String, Boolean> subtypeCache = new HashMap<>();
    private final Map<String, Integer> instanceSlots = new HashMap<>();
    private final Map<String, Integer> staticSlots = new HashMap<>();
    private Object[] statics;

    private boolean linked;
    private GuestClass superClass;
    private List<GuestClass> guestInterfaces;
    private String hostSuperName;
    private Set<String> hostInterfaces;
    private Object[] fieldDefaults;

    /** Generated host class for instances, created on demand by the shell factory; null if not needed. */
    public Class<?> shellClass;
    /** True once the shell factory has decided, so the decision is made only once per class. */
    public boolean shellResolved;

    /** The {@link #origin} of a class object made by {@link #absentJdkClass}. */
    public static final String ABSENT_JDK_CLASS = "absent-jdk-class";

    /**
     * A class object for a JDK class that the advertised Java has but the analysis JDK lacks,
     * such as Java 8's {@code sun.misc.Launcher$AppClassLoader}. It answers {@code getName},
     * {@code getSuperclass}, {@code getModifiers}, {@code getSimpleName} and the like as the
     * real class would, and has no members of its own. It is never added to the repository,
     * so no guest lookup by name finds it and nothing can be loaded from it; the type system
     * hands it out only as the {@code getClass()} of an emulator object that imitates the class.
     *
     * @param name        internal name, for example {@code sun/misc/Launcher$AppClassLoader}
     * @param superName   internal name of its JDK superclass, which the analysis JDK has
     * @param outerName   for a nested class, the internal name of the class it is declared in; else null
     * @param innerAccess for a nested class, its modifiers as the InnerClasses attribute records them
     */
    public static GuestClass absentJdkClass(ClassRepository repository, String name, String superName,
            String outerName, int innerAccess) {
        ClassNode node = new ClassNode();
        node.visit(Opcodes.V1_8, Opcodes.ACC_SUPER, name, null, superName, null);
        if (outerName != null) {
            node.visitInnerClass(name, outerName, name.substring(outerName.length() + 1), innerAccess);
        }
        node.visitEnd();
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        node.accept(writer);
        return new GuestClass(repository, node, writer.toByteArray(), ABSENT_JDK_CLASS);
    }

    /** True for a class object made by {@link #absentJdkClass}: a JDK class, not the sample's. */
    public boolean isAbsentJdkClass() {
        return ABSENT_JDK_CLASS.equals(origin);
    }

    GuestClass(ClassRepository repository, ClassNode node, byte[] bytes, String origin) {
        this.repository = repository;
        this.node = node;
        this.name = node.name;
        this.bytes = bytes;
        this.origin = origin;
        for (MethodNode method : node.methods) {
            // putIfAbsent: a malformed class may repeat a signature; the JVM would use the first.
            methods.putIfAbsent(method.name + method.desc, new GuestMethod(this, method));
        }
    }

    // ------------------------------------------------------------------ hierarchy

    private void link() {
        if (linked) {
            return;
        }
        linked = true;
        superClass = node.superName == null ? null : repository.find(node.superName);
        guestInterfaces = new ArrayList<>();
        hostInterfaces = new LinkedHashSet<>();
        for (String iface : node.interfaces) {
            GuestClass guest = repository.find(iface);
            if (guest != null) {
                guestInterfaces.add(guest);
            } else {
                hostInterfaces.add(iface);
            }
        }
        if (superClass != null) {
            hostSuperName = superClass.hostSuperName();
        } else {
            hostSuperName = node.superName == null ? "java/lang/Object" : node.superName;
        }
        layoutFields();
    }

    /** The interpreted superclass, or null when the direct superclass is a JDK or unknown class. */
    public GuestClass superClass() {
        link();
        return superClass;
    }

    public List<GuestClass> guestInterfaces() {
        link();
        return guestInterfaces;
    }

    /** Internal name of the nearest ancestor that is not interpreted (often {@code java/lang/Object}). */
    public String hostSuperName() {
        link();
        return hostSuperName;
    }

    /** Every non-interpreted interface this class implements, directly or through guest supertypes. */
    public Set<String> allHostInterfaces() {
        link();
        Set<String> result = new LinkedHashSet<>();
        Deque<GuestClass> work = new ArrayDeque<>(List.of(this));
        Set<GuestClass> visited = new LinkedHashSet<>();
        while (!work.isEmpty()) {
            GuestClass current = work.pop();
            if (!visited.add(current)) {
                continue;
            }
            current.link();
            result.addAll(current.hostInterfaces);
            work.addAll(current.guestInterfaces);
            if (current.superClass != null) {
                work.add(current.superClass);
            }
        }
        return result;
    }

    /** True if this class is, extends or implements the interpreted class or named host type. */
    public boolean isSubtypeOf(String internalName) {
        // Asked on every instanceof, cast and catch involving a guest object, so the answer is
        // remembered. It cannot change: a class's supertypes are fixed once it is linked.
        return subtypeCache.computeIfAbsent(internalName, this::computeIsSubtypeOf);
    }

    private boolean computeIsSubtypeOf(String internalName) {
        Deque<GuestClass> work = new ArrayDeque<>(List.of(this));
        Set<GuestClass> visited = new LinkedHashSet<>();
        while (!work.isEmpty()) {
            GuestClass current = work.pop();
            if (!visited.add(current)) {
                continue;
            }
            current.link();
            if (current.name.equals(internalName) || current.hostInterfaces.contains(internalName)
                    || internalName.equals(current.node.superName)) {
                return true;
            }
            work.addAll(current.guestInterfaces);
            if (current.superClass != null) {
                work.add(current.superClass);
            }
        }
        return false;
    }

    /** This interface and every interpreted interface it extends, nearest first. */
    public List<GuestClass> selfAndSuperInterfaces() {
        List<GuestClass> result = new ArrayList<>();
        Deque<GuestClass> work = new ArrayDeque<>(List.of(this));
        while (!work.isEmpty()) {
            GuestClass current = work.poll();
            if (!result.contains(current)) {
                result.add(current);
                work.addAll(current.guestInterfaces());
            }
        }
        return result;
    }

    public boolean isInterface() {
        return (node.access & Opcodes.ACC_INTERFACE) != 0;
    }

    public boolean isEnum() {
        return (node.access & Opcodes.ACC_ENUM) != 0;
    }

    public String dottedName() {
        return name.replace('/', '.');
    }

    // ------------------------------------------------------------------ methods

    public Iterable<GuestMethod> declaredMethods() {
        return methods.values();
    }

    public GuestMethod declared(String name, String desc) {
        return methods.get(name + desc);
    }

    /**
     * Virtual method lookup as the JVM performs it for {@code invokevirtual}/{@code invokeinterface}:
     * the class chain first, then default methods of interfaces. Returns null when the method is
     * abstract or implemented only by a non-interpreted supertype.
     */
    public GuestMethod findVirtual(String name, String desc) {
        String key = name + desc;
        if (virtualCache.containsKey(key)) {
            return virtualCache.get(key);
        }
        GuestMethod found = null;
        for (GuestClass current = this; current != null && found == null; current = current.superClass()) {
            GuestMethod candidate = current.methods.get(key);
            if (candidate != null && !candidate.isStatic && !candidate.isAbstract) {
                found = candidate;
            }
        }
        if (found == null) {
            found = findInInterfaces(key, false);
        }
        virtualCache.put(key, found);
        return found;
    }

    /** Static method lookup: statics are inherited from superclasses and declared on interfaces. */
    public GuestMethod findStatic(String name, String desc) {
        String key = name + desc;
        for (GuestClass current = this; current != null; current = current.superClass()) {
            GuestMethod candidate = current.methods.get(key);
            if (candidate != null && candidate.isStatic) {
                return candidate;
            }
        }
        return findInInterfaces(key, true);
    }

    private GuestMethod findInInterfaces(String key, boolean wantStatic) {
        Deque<GuestClass> work = new ArrayDeque<>();
        Set<GuestClass> visited = new LinkedHashSet<>();
        for (GuestClass current = this; current != null; current = current.superClass()) {
            work.addAll(current.guestInterfaces());
        }
        while (!work.isEmpty()) {
            GuestClass iface = work.poll();
            if (!visited.add(iface)) {
                continue;
            }
            GuestMethod candidate = iface.methods.get(key);
            if (candidate != null && !candidate.isAbstract && candidate.isStatic == wantStatic) {
                return candidate;
            }
            work.addAll(iface.guestInterfaces());
        }
        return null;
    }

    // ------------------------------------------------------------------ fields

    /**
     * Assigns storage slots. Instance slots continue after the superclass's slots so a subclass
     * instance can be used wherever the superclass's field offsets are expected.
     */
    private void layoutFields() {
        Object[] inherited = superClass == null ? new Object[0] : superClass.fieldDefaultsTemplate();
        // Arrays.asList, not List.of: reference fields default to null, which List.of rejects.
        List<Object> defaults = new ArrayList<>(java.util.Arrays.asList(inherited));
        List<Object> staticValues = new ArrayList<>();
        for (FieldNode field : node.fields) {
            String key = field.name + ':' + field.desc;
            if ((field.access & Opcodes.ACC_STATIC) != 0) {
                staticSlots.putIfAbsent(key, staticValues.size());
                // A ConstantValue attribute initialises the field before <clinit> runs.
                staticValues.add(field.value != null ? field.value : defaultValue(field.desc));
            } else {
                instanceSlots.putIfAbsent(key, defaults.size());
                defaults.add(defaultValue(field.desc));
            }
        }
        fieldDefaults = defaults.toArray();
        statics = staticValues.toArray();
    }

    private Object[] fieldDefaultsTemplate() {
        link();
        return fieldDefaults;
    }

    Object[] newFieldArray() {
        return fieldDefaultsTemplate().clone();
    }

    public Object[] statics() {
        link();
        return statics;
    }

    /** Looks for a field declared by this exact class. */
    public FieldRef declaredField(String name, String desc) {
        link();
        String key = name + ':' + desc;
        Integer slot = instanceSlots.get(key);
        if (slot != null) {
            return new FieldRef(this, slot, false, name, desc);
        }
        slot = staticSlots.get(key);
        return slot == null ? null : new FieldRef(this, slot, true, name, desc);
    }

    /**
     * Field resolution per JVMS 5.4.3.2: this class, then its superinterfaces, then its superclass.
     * Returns null if no interpreted class declares the field (it may belong to a JDK supertype).
     */
    public FieldRef resolveField(String name, String desc) {
        FieldRef own = declaredField(name, desc);
        if (own != null) {
            return own;
        }
        for (GuestClass iface : guestInterfaces()) {
            FieldRef inherited = iface.resolveField(name, desc);
            if (inherited != null) {
                return inherited;
            }
        }
        return superClass() == null ? null : superClass().resolveField(name, desc);
    }

    /** The JVM's zero value for a field or array element of the given descriptor. */
    public static Object defaultValue(String desc) {
        return switch (Type.getType(desc).getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> 0;
            case Type.LONG -> 0L;
            case Type.FLOAT -> 0f;
            case Type.DOUBLE -> 0d;
            default -> null;
        };
    }

    /** Matches {@code Class.toString()} so guest code printing its own class sees the usual text. */
    @Override
    public String toString() {
        return (isInterface() ? "interface " : "class ") + dottedName();
    }
}
