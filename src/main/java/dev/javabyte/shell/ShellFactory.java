package dev.javabyte.shell;

import static org.objectweb.asm.Opcodes.*;

import dev.javabyte.env.SimBaseClassLoader;
import dev.javabyte.env.SimSecureClassLoader;
import dev.javabyte.env.SimURLClassLoader;
import dev.javabyte.host.HostBridge;
import dev.javabyte.model.GuestClass;
import dev.javabyte.model.InstanceState;
import dev.javabyte.vm.EmulationAbort;
import dev.javabyte.vm.GuestThrow;
import dev.javabyte.vm.Values;
import dev.javabyte.vm.VirtualMachine;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * Generates "shell" classes: real host classes that stand for instances of a guest class which
 * extends a JDK class or implements JDK interfaces.
 *
 * <p>Why this exists. A guest class such as {@code class Beacon extends Thread implements
 * Comparator} must produce objects that real JDK code accepts as a {@code Thread} and a
 * {@code Comparator}. A shell genuinely extends and implements those types, but contains none of
 * the sample's code. Each overridable method is a small trampoline:
 *
 * <pre>
 *   if the guest class implements this method -> run it in the interpreter
 *   otherwise                                  -> call the JDK superclass as usual
 * </pre>
 *
 * The alternative, a full VM that also interprets the JDK itself, avoids shells but has to
 * re-implement every native method in the JDK. Shells keep the emulator small and let the real
 * JDK do the work wherever policy considers it safe.
 */
public final class ShellFactory {

    private static final String DISPATCH = Type.getInternalName(ShellDispatch.class);
    private static final String STATE = Type.getInternalName(InstanceState.class);
    private static final String STATE_DESC = Type.getDescriptor(InstanceState.class);
    private static final String STATE_FIELD = "javabyte$stateField";
    private static final String FACTORY_METHOD = "javabyte$create";

    /** How the non-guest path of a trampoline finishes. */
    private enum Fallback { SUPER_CLASS, SUPER_INTERFACE, ABSTRACT }

    private record Overridable(Method method, Fallback fallback, Class<?> fallbackOwner) {}

    /** Defines generated classes. A separate loader lets them be collected with the VM. */
    private static final class Loader extends ClassLoader {
        Loader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private final VirtualMachine vm;
    private final Loader loader = new Loader(ShellFactory.class.getClassLoader());
    private final Map<Class<?>, GuestClass> guestByShell = new java.util.HashMap<>();
    private int counter;

    public ShellFactory(VirtualMachine vm) {
        this.vm = vm;
    }

    /** The guest class a generated shell class stands for, or null if it is not a shell. */
    public GuestClass guestFor(Class<?> shell) {
        return guestByShell.get(shell);
    }

    /** The loader that defines shells; also used for JDK proxies created on the guest's behalf. */
    public ClassLoader loader() {
        return loader;
    }

    /**
     * Returns the shell class for a guest class, or null if its instances need no JDK supertypes
     * and can be plain objects. The answer is computed once per class.
     */
    public Class<?> shellFor(GuestClass guest) {
        if (!guest.shellResolved) {
            guest.shellResolved = true;
            guest.shellClass = build(guest);
        }
        return guest.shellClass;
    }

    /**
     * Creates the shell object for a guest instance whose constructor has reached its JDK
     * superclass constructor call.
     *
     * @param ctorOwner the JDK class named by that call
     */
    public Object instantiate(InstanceState state, String ctorOwner, String desc, Object[] args) {
        Class<?> shell = state.type.shellClass;
        Constructor<?> constructor = findConstructor(shell, desc);
        Object[] effectiveArgs = args;
        if (constructor == null || !reportedBaseName(shell).equals(ctorOwner)) {
            // The sample's direct superclass is unavailable or blocked, so the shell extends
            // Object instead and there is nothing to pass the arguments to.
            constructor = findConstructor(shell, "()V");
            effectiveArgs = new Object[0];
            vm.events.addOnce(state.type.name + ctorOwner, "gap", "phantom-superclass",
                    "class", state.type.name, "superclass", ctorOwner);
        }
        if (constructor == null) {
            throw vm.raise(new InstantiationError(state.type.dottedName()));
        }
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        Object[] hostArgs = new Object[effectiveArgs.length];
        for (int i = 0; i < hostArgs.length; i++) {
            hostArgs[i] = Values.toHost(effectiveArgs[i], parameterTypes[i]);
        }
        ShellDispatch.beginConstruction(state);
        try {
            ShellInstance created = (ShellInstance) newShell(shell, constructor, hostArgs);
            created.javabyte$bind(state);
            if (created instanceof Throwable throwable) {
                vm.stampTrace(throwable);
            }
            return created;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof EmulationAbort abort) {
                throw abort;
            }
            throw e.getCause() instanceof GuestThrow guest ? guest : vm.raise(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate shell for " + state.type.name, e);
        } finally {
            ShellDispatch.endConstruction();
        }
    }

    /**
     * Creates the shell object. Reflection refuses to construct a class flagged as an enum, so
     * an enum shell is created through its generated static factory method instead.
     */
    private static Object newShell(Class<?> shell, Constructor<?> constructor, Object[] hostArgs)
            throws ReflectiveOperationException {
        if (!shell.isEnum()) {
            return constructor.newInstance(hostArgs);
        }
        return shell.getMethod(FACTORY_METHOD, constructor.getParameterTypes()).invoke(null, hostArgs);
    }

    private static Constructor<?> findConstructor(Class<?> cls, String desc) {
        for (Constructor<?> candidate : cls.getConstructors()) {
            if (Type.getConstructorDescriptor(candidate).equals(desc)) {
                return candidate;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ analysis

    private Class<?> build(GuestClass guest) {
        Class<?> base = eligibleBase(guest.hostSuperName());
        List<Class<?>> interfaces = new ArrayList<>();
        for (String name : guest.allHostInterfaces()) {
            Class<?> iface = vm.host.findClass(name);
            if (iface != null && iface.isInterface() && isUsable(iface) && !iface.isSealed()) {
                interfaces.add(iface);
            }
        }
        if (base == Object.class && interfaces.isEmpty()) {
            return null;
        }
        // If the guest superclass has a shell, extend that shell. The generated classes then
        // mirror the guest hierarchy, which JDK code can observe: Enum.compareTo, for one,
        // accepts two constants only if their classes share the enum as a common superclass.
        Class<?> parentShell = guest.superClass() == null ? null : shellFor(guest.superClass());
        try {
            String name = "dev/javabyte/shell/gen/" + guest.name.replaceAll("[^A-Za-z0-9_$]", "_") + "$Shell" + counter++;
            // A top-level enum's shell is marked as an enum itself, so JDK code that asks a
            // class for its constants (EnumMap, EnumSet) gets an answer.
            boolean asEnum = parentShell == null && base == Enum.class && guest.isEnum();
            byte[] bytes = parentShell == null ? generate(name, base, interfaces, asEnum) : generateChild(name, parentShell, interfaces);
            Class<?> shell = loader.define(name.replace('/', '.'), bytes);
            guestByShell.put(shell, guest);
            return shell;
        } catch (LinkageError | RuntimeException e) {
            // Some JDK types cannot be subclassed from outside (package-private abstract
            // methods, for instance). Falling back to a plain object loses JDK interop for this
            // one class but keeps the run going; the gap is reported so it can be investigated.
            vm.events.add("gap", "shell-failed", "class", guest.name, "base", base.getName(), "error", e.toString());
            return null;
        }
    }

    /**
     * JDK classes that a shell never extends for real, mapped to the simulated class it extends
     * instead.
     *
     * <p>A real {@code URLClassLoader} fetches from its URLs whenever JDK code asks it for a
     * class or resource, which no stub would see; {@link SimURLClassLoader} only remembers
     * them, and the class-loader stubs read the archives from the virtual disk or the network
     * simulator, as for {@code new URLClassLoader(...)}.
     *
     * <p>A real {@code ClassLoader} or {@code SecureClassLoader} has a real parent, by default
     * the emulator's own application loader, so JDK code calling the sample's loader directly
     * could reach the emulator's class path and real resources. {@link SimBaseClassLoader} and
     * {@link SimSecureClassLoader} have the bootstrap loader as their real parent and answer
     * only from the guest's virtual world.
     *
     * <p>Each simulated class has a public constructor for every shape of the JDK class's
     * constructors, so the sample's {@code super(...)} call finds its match.
     */
    private static final Map<String, Class<?>> SIMULATED_BASES = Map.of(
            "java/lang/ClassLoader", SimBaseClassLoader.class,
            "java/security/SecureClassLoader", SimSecureClassLoader.class,
            "java/net/URLClassLoader", SimURLClassLoader.class);

    /**
     * The JDK class a shell class stands in for as its superclass: the class the sample's
     * bytecode names, which is the shell's JDK base unless that base is simulated.
     */
    private static String reportedBaseName(Class<?> shell) {
        Class<?> base = jdkBase(shell);
        for (Map.Entry<String, Class<?>> entry : SIMULATED_BASES.entrySet()) {
            if (entry.getValue() == base) {
                return entry.getKey();
            }
        }
        return Type.getInternalName(base);
    }

    /**
     * A JDK class can be the shell's superclass only if policy lets guest code construct it.
     * Otherwise extending it would run its constructor, and with it whatever the policy was
     * trying to keep out (opening a window, say). Such classes become "phantom" superclasses.
     * A class listed in {@link #SIMULATED_BASES} is replaced by its simulated counterpart.
     */
    private Class<?> eligibleBase(String internalName) {
        if ("java/lang/Object".equals(internalName)) {
            return Object.class;
        }
        Class<?> simulated = SIMULATED_BASES.get(internalName);
        if (simulated != null) {
            return vm.policy.allows(internalName, "<init>", null) ? simulated : Object.class;
        }
        Class<?> cls = vm.host.findClass(internalName);
        boolean eligible = cls != null && !cls.isInterface() && !Modifier.isFinal(cls.getModifiers())
                && isUsable(cls) && !cls.isSealed() && vm.policy.allows(internalName, "<init>", null);
        return eligible ? cls : Object.class;
    }

    /** True if generated code in another module may name this type. */
    private static boolean isUsable(Class<?> type) {
        Class<?> element = type;
        while (element.isArray()) {
            element = element.getComponentType();
        }
        return element.isPrimitive()
                || (Modifier.isPublic(element.getModifiers()) && element.getModule().isExported(element.getPackageName()));
    }

    private static boolean signatureUsable(Class<?> returnType, Class<?>[] parameterTypes) {
        if (!isUsable(returnType)) {
            return false;
        }
        for (Class<?> parameter : parameterTypes) {
            if (!isUsable(parameter)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Collects every method a subclass may override. The first occurrence of a signature, walking
     * from the most derived class upward, decides: if that occurrence is final or inaccessible
     * the signature must not be overridden even though an ancestor declares it open.
     */
    private static List<Overridable> overridables(Class<?> base, List<Class<?>> interfaces) {
        Map<String, Overridable> result = new LinkedHashMap<>();
        Set<String> decided = new HashSet<>();
        for (Class<?> current = base; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                consider(method, Fallback.SUPER_CLASS, base, result, decided);
            }
        }
        // Interface methods the base class inherits without implementing.
        for (Method method : base.getMethods()) {
            consider(method, Fallback.SUPER_CLASS, base, result, decided);
        }
        for (Class<?> iface : interfaces) {
            for (Method method : iface.getMethods()) {
                consider(method, Fallback.SUPER_INTERFACE, iface, result, decided);
            }
        }
        return new ArrayList<>(result.values());
    }

    private static void consider(Method method, Fallback fallback, Class<?> owner,
            Map<String, Overridable> result, Set<String> decided) {
        int modifiers = method.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isPrivate(modifiers)) {
            return;
        }
        String key = method.getName() + Type.getMethodDescriptor(method);
        if (!decided.add(key)) {
            return;
        }
        boolean open = !Modifier.isFinal(modifiers) && (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers));
        // finalize() is skipped on purpose: overriding it would have the garbage collector's
        // finalizer thread call into guest code at an arbitrary later time.
        if (!open || method.getName().equals("finalize")
                || !signatureUsable(method.getReturnType(), method.getParameterTypes())) {
            return;
        }
        result.put(key, new Overridable(method, Modifier.isAbstract(modifiers) ? Fallback.ABSTRACT : fallback, owner));
    }

    // ------------------------------------------------------------------ generation

    /**
     * Generates a shell that extends a JDK class (or a simulated stand-in for one): state
     * field, constructors, a trampoline for every overridable method and field accessors.
     */
    private byte[] generate(String name, Class<?> base, List<Class<?>> interfaces, boolean asEnum) {
        ClassWriter writer = newWriter();
        String baseName = Type.getInternalName(base);
        List<String> interfaceNames = new ArrayList<>();
        for (Class<?> iface : interfaces) {
            interfaceNames.add(Type.getInternalName(iface));
        }
        interfaceNames.add(Type.getInternalName(ShellInstance.class));
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER | (asEnum ? ACC_ENUM : 0), name, null, baseName,
                interfaceNames.toArray(String[]::new));
        writer.visitField(ACC_PRIVATE, STATE_FIELD, STATE_DESC, null, null).visitEnd();
        if (asEnum) {
            emitEnumValues(writer, name);
            emitFactories(writer, name, base);
        }

        emitStateAccessors(writer, name);
        emitConstructors(writer, base, baseName);
        for (Overridable overridable : overridables(base, interfaces)) {
            emitTrampoline(writer, baseName, overridable);
            if (overridable.fallback() != Fallback.ABSTRACT) {
                emitSuperAccessor(writer, baseName, overridable);
            }
        }
        emitFieldAccessors(writer, base);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * The class writer for every shell. COMPUTE_FRAMES asks ASM to merge types at branch joins,
     * which by default loads classes by name. The only joins here are in trampolines whose two
     * arms leave an empty stack, so "Object" is always an adequate answer and avoids loading
     * anything.
     */
    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
    }

    /**
     * Generates a shell that extends the shell of the guest superclass. Everything the parent
     * shell provides (state, constructors' targets, trampolines for the JDK base class) is
     * inherited; only JDK interfaces that this guest class adds need new trampolines.
     */
    private byte[] generateChild(String name, Class<?> parentShell, List<Class<?>> interfaces) {
        ClassWriter writer = newWriter();
        List<Class<?>> added = new ArrayList<>();
        for (Class<?> iface : interfaces) {
            if (!iface.isAssignableFrom(parentShell)) {
                added.add(iface);
            }
        }
        String parentName = Type.getInternalName(parentShell);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, parentName,
                added.stream().map(Type::getInternalName).toArray(String[]::new));
        emitConstructors(writer, parentShell, parentName);
        for (Overridable overridable : overridables(parentShell, added)) {
            // Methods reachable through the parent shell already have trampolines there.
            if (added.contains(overridable.fallbackOwner())) {
                emitTrampoline(writer, parentName, overridable);
                if (overridable.fallback() != Fallback.ABSTRACT) {
                    emitSuperAccessor(writer, parentName, overridable);
                }
            }
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** The JDK class a shell ultimately extends, skipping any parent shells in between. */
    public static Class<?> jdkBase(Class<?> shell) {
        Class<?> base = shell.getSuperclass();
        while (ShellInstance.class.isAssignableFrom(base)) {
            base = base.getSuperclass();
        }
        return base;
    }

    /**
     * Emits {@code public static Shell[] values()}, which is how the JDK obtains an enum's
     * constants. It asks the interpreter to run the guest enum's own {@code values()}.
     */
    private static void emitEnumValues(ClassWriter writer, String name) {
        String arrayType = "[L" + name + ";";
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "values", "()" + arrayType, null, null);
        method.visitCode();
        method.visitLdcInsn(Type.getObjectType(name));
        method.visitMethodInsn(INVOKESTATIC, DISPATCH, "enumValues", "(Ljava/lang/Class;)[Ljava/lang/Object;", false);
        method.visitTypeInsn(CHECKCAST, arrayType);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    /** One static {@code javabyte$create(args)} per constructor; see {@link #newShell}. */
    private static void emitFactories(ClassWriter writer, String name, Class<?> base) {
        for (Constructor<?> constructor : base.getDeclaredConstructors()) {
            int modifiers = constructor.getModifiers();
            if (!(Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers))) {
                continue;
            }
            String desc = Type.getConstructorDescriptor(constructor);
            Type[] argTypes = Type.getArgumentTypes(desc);
            MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC, FACTORY_METHOD,
                    Type.getMethodDescriptor(Type.getObjectType(name), argTypes), null, null);
            method.visitCode();
            method.visitTypeInsn(NEW, name);
            method.visitInsn(DUP);
            int local = 0; // static method: arguments start at slot 0
            for (Type argType : argTypes) {
                method.visitVarInsn(argType.getOpcode(ILOAD), local);
                local += argType.getSize();
            }
            method.visitMethodInsn(INVOKESPECIAL, name, "<init>", desc, false);
            method.visitInsn(ARETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
    }

    private static void emitStateAccessors(ClassWriter writer, String name) {
        // javabyte$state(): the bound state, or the state under construction if not bound yet.
        MethodVisitor getter = writer.visitMethod(ACC_PUBLIC, "javabyte$state", "()" + STATE_DESC, null, null);
        Label bound = new Label();
        getter.visitCode();
        getter.visitVarInsn(ALOAD, 0);
        getter.visitFieldInsn(GETFIELD, name, STATE_FIELD, STATE_DESC);
        getter.visitJumpInsn(IFNONNULL, bound);
        getter.visitMethodInsn(INVOKESTATIC, DISPATCH, "constructingState", "()" + STATE_DESC, false);
        getter.visitInsn(ARETURN);
        getter.visitLabel(bound);
        getter.visitVarInsn(ALOAD, 0);
        getter.visitFieldInsn(GETFIELD, name, STATE_FIELD, STATE_DESC);
        getter.visitInsn(ARETURN);
        getter.visitMaxs(0, 0);
        getter.visitEnd();

        MethodVisitor binder = writer.visitMethod(ACC_PUBLIC, "javabyte$bind", "(" + STATE_DESC + ")V", null, null);
        binder.visitCode();
        binder.visitVarInsn(ALOAD, 0);
        binder.visitVarInsn(ALOAD, 1);
        binder.visitFieldInsn(PUTFIELD, name, STATE_FIELD, STATE_DESC);
        binder.visitInsn(RETURN);
        binder.visitMaxs(0, 0);
        binder.visitEnd();
    }

    /** One pass-through constructor per accessible superclass constructor. */
    private static void emitConstructors(ClassWriter writer, Class<?> base, String baseName) {
        for (Constructor<?> constructor : base.getDeclaredConstructors()) {
            int modifiers = constructor.getModifiers();
            if (!(Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers))
                    || !signatureUsable(void.class, constructor.getParameterTypes())) {
                continue;
            }
            String desc = Type.getConstructorDescriptor(constructor);
            MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "<init>", desc, null, null);
            method.visitCode();
            method.visitVarInsn(ALOAD, 0);
            loadArguments(method, Type.getArgumentTypes(desc));
            method.visitMethodInsn(INVOKESPECIAL, baseName, "<init>", desc, false);
            method.visitInsn(RETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
    }

    private static void emitTrampoline(ClassWriter writer, String baseName, Overridable overridable) {
        Method target = overridable.method();
        String name = target.getName();
        String desc = Type.getMethodDescriptor(target);
        Type[] argTypes = Type.getArgumentTypes(desc);
        Type returnType = Type.getReturnType(desc);

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, name, desc, null, null);
        Label useSuper = new Label();
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitLdcInsn(name);
        method.visitLdcInsn(desc);
        method.visitMethodInsn(INVOKESTATIC, DISPATCH, "hasGuestImpl",
                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Z", false);
        method.visitJumpInsn(IFEQ, useSuper);

        // Guest path: box the arguments into an Object[] and call the interpreter.
        method.visitVarInsn(ALOAD, 0);
        method.visitLdcInsn(name);
        method.visitLdcInsn(desc);
        method.visitLdcInsn(argTypes.length);
        method.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        int local = 1;
        for (int i = 0; i < argTypes.length; i++) {
            method.visitInsn(DUP);
            method.visitLdcInsn(i);
            method.visitVarInsn(argTypes[i].getOpcode(ILOAD), local);
            box(method, argTypes[i]);
            method.visitInsn(AASTORE);
            local += argTypes[i].getSize();
        }
        method.visitMethodInsn(INVOKESTATIC, DISPATCH, "invoke",
                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        unboxAndReturn(method, returnType);

        // JDK path.
        method.visitLabel(useSuper);
        if (overridable.fallback() == Fallback.ABSTRACT) {
            method.visitTypeInsn(NEW, "java/lang/AbstractMethodError");
            method.visitInsn(DUP);
            method.visitLdcInsn(name + desc);
            method.visitMethodInsn(INVOKESPECIAL, "java/lang/AbstractMethodError", "<init>", "(Ljava/lang/String;)V", false);
            method.visitInsn(ATHROW);
        } else {
            emitSuperCall(method, baseName, overridable, argTypes, returnType);
        }
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    /**
     * Emits {@code javabyte$super$name}: a public method that calls the JDK implementation
     * non-virtually. The interpreter uses it for the sample's own {@code super.name()} calls,
     * which reflection cannot express.
     */
    private static void emitSuperAccessor(ClassWriter writer, String baseName, Overridable overridable) {
        String desc = Type.getMethodDescriptor(overridable.method());
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_SYNTHETIC,
                HostBridge.SUPER_PREFIX + overridable.method().getName(), desc, null, null);
        method.visitCode();
        emitSuperCall(method, baseName, overridable, Type.getArgumentTypes(desc), Type.getReturnType(desc));
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void emitSuperCall(MethodVisitor method, String baseName, Overridable overridable,
            Type[] argTypes, Type returnType) {
        boolean viaInterface = overridable.fallback() == Fallback.SUPER_INTERFACE;
        String owner = viaInterface ? Type.getInternalName(overridable.fallbackOwner()) : baseName;
        method.visitVarInsn(ALOAD, 0);
        loadArguments(method, argTypes);
        method.visitMethodInsn(INVOKESPECIAL, owner, overridable.method().getName(),
                Type.getMethodDescriptor(overridable.method()), viaInterface);
        if (overridable.method().getName().equals("toString") && argTypes.length == 0) {
            // Inherited toString() implementations print the runtime class name; swap the
            // shell's generated name for the guest's.
            method.visitVarInsn(ALOAD, 0);
            method.visitInsn(SWAP);
            method.visitMethodInsn(INVOKESTATIC, DISPATCH, "guestToString",
                    "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;", false);
        }
        method.visitInsn(returnType.getOpcode(IRETURN));
    }

    /** Public getters and setters for protected fields of the JDK superclass chain. */
    private static void emitFieldAccessors(ClassWriter writer, Class<?> base) {
        Set<String> seen = new HashSet<>();
        for (Class<?> current = base; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || !Modifier.isProtected(modifiers)
                        || !isUsable(field.getType()) || !seen.add(field.getName())) {
                    continue;
                }
                String owner = Type.getInternalName(current);
                Type type = Type.getType(field.getType());
                MethodVisitor getter = writer.visitMethod(ACC_PUBLIC | ACC_SYNTHETIC,
                        HostBridge.GETTER_PREFIX + field.getName(), "()" + type.getDescriptor(), null, null);
                getter.visitCode();
                getter.visitVarInsn(ALOAD, 0);
                getter.visitFieldInsn(GETFIELD, owner, field.getName(), type.getDescriptor());
                getter.visitInsn(type.getOpcode(IRETURN));
                getter.visitMaxs(0, 0);
                getter.visitEnd();
                if (Modifier.isFinal(modifiers)) {
                    continue;
                }
                MethodVisitor setter = writer.visitMethod(ACC_PUBLIC | ACC_SYNTHETIC,
                        HostBridge.SETTER_PREFIX + field.getName(), "(" + type.getDescriptor() + ")V", null, null);
                setter.visitCode();
                setter.visitVarInsn(ALOAD, 0);
                setter.visitVarInsn(type.getOpcode(ILOAD), 1);
                setter.visitFieldInsn(PUTFIELD, owner, field.getName(), type.getDescriptor());
                setter.visitInsn(RETURN);
                setter.visitMaxs(0, 0);
                setter.visitEnd();
            }
        }
    }

    private static void loadArguments(MethodVisitor method, Type[] argTypes) {
        int local = 1;
        for (Type argType : argTypes) {
            method.visitVarInsn(argType.getOpcode(ILOAD), local);
            local += argType.getSize();
        }
    }

    private static void box(MethodVisitor method, Type type) {
        String wrapper = switch (type.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };
        if (wrapper != null) {
            method.visitMethodInsn(INVOKESTATIC, wrapper, "valueOf", "(" + type.getDescriptor() + ")L" + wrapper + ";", false);
        }
    }

    private static void unboxAndReturn(MethodVisitor method, Type type) {
        String helper = switch (type.getSort()) {
            case Type.BOOLEAN -> "asBoolean";
            case Type.BYTE -> "asByte";
            case Type.CHAR -> "asChar";
            case Type.SHORT -> "asShort";
            case Type.INT -> "asInt";
            case Type.LONG -> "asLong";
            case Type.FLOAT -> "asFloat";
            case Type.DOUBLE -> "asDouble";
            default -> null;
        };
        if (type.getSort() == Type.VOID) {
            method.visitInsn(POP);
            method.visitInsn(RETURN);
        } else if (helper != null) {
            method.visitMethodInsn(INVOKESTATIC, DISPATCH, helper, "(Ljava/lang/Object;)" + type.getDescriptor(), false);
            method.visitInsn(type.getOpcode(IRETURN));
        } else {
            method.visitTypeInsn(CHECKCAST, type.getInternalName());
            method.visitInsn(ARETURN);
        }
    }
}
