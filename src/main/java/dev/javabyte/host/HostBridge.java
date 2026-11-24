package dev.javabyte.host;

import dev.javabyte.model.GuestInstance;
import dev.javabyte.vm.EmulationAbort;
import dev.javabyte.vm.GuestThrow;
import dev.javabyte.vm.Values;
import dev.javabyte.vm.VirtualMachine;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.objectweb.asm.Type;

/**
 * Performs calls into the real JDK on behalf of guest code, by reflection.
 *
 * <p>This class does not decide <em>whether</em> a call is allowed; the router does that before
 * calling in. It only deals with the mechanics: finding the member, converting values in both
 * directions, and turning host exceptions into guest exceptions.
 */
public final class HostBridge {

    /** Prefix of generated accessors that let the interpreter make non-virtual calls on shells. */
    public static final String SUPER_PREFIX = "javabyte$super$";
    public static final String GETTER_PREFIX = "javabyte$get$";
    public static final String SETTER_PREFIX = "javabyte$set$";

    private final VirtualMachine vm;
    private final ClassLoader loader = HostBridge.class.getClassLoader();
    private final Map<String, Optional<Class<?>>> classes = new HashMap<>();
    private final Map<String, Optional<Method>> methods = new HashMap<>();
    private final Map<String, Optional<Constructor<?>>> constructors = new HashMap<>();

    public HostBridge(VirtualMachine vm) {
        this.vm = vm;
    }

    // ------------------------------------------------------------------ classes

    /**
     * Resolves an internal name or array descriptor to a JDK class, or null if there is none.
     *
     * <p>Only classes from the boot and platform loaders count. The emulator's own classes and
     * its libraries share the application class path, and a sample must not be able to reach
     * them merely by naming them.
     */
    public Class<?> findClass(String internalName) {
        return classes.computeIfAbsent(internalName, name -> {
            try {
                Class<?> cls = Class.forName(name.replace('/', '.'), false, loader);
                Class<?> element = cls;
                while (element.isArray()) {
                    element = element.getComponentType();
                }
                ClassLoader owner = element.getClassLoader();
                boolean isJdk = owner == null || owner == ClassLoader.getPlatformClassLoader();
                return isJdk ? Optional.of(cls) : Optional.empty();
            } catch (ClassNotFoundException | LinkageError e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    /**
     * True for objects the emulator itself supplies as replacements for JDK objects: any
     * {@link StandIn} implementation, wherever it lives (for example
     * {@code SystemStubs.LegacyBase64}), classes in the {@code env} package, and JDK proxies whose
     * handler is emulator code (guest lambdas and simulated management beans). Calls on such
     * objects are safe by construction.
     */
    public static boolean isSimulated(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof StandIn || value.getClass().getName().startsWith("dev.javabyte.env.")) {
            return true;
        }
        return java.lang.reflect.Proxy.isProxyClass(value.getClass())
                && java.lang.reflect.Proxy.getInvocationHandler(value).getClass().getName().startsWith("dev.javabyte.");
    }

    // ------------------------------------------------------------------ methods

    /**
     * Invokes a JDK method.
     *
     * @param special true for {@code super.method()} on a shell, which must bypass the shell's
     *                own override or the call would loop straight back into the interpreter
     */
    public Object invoke(String owner, String name, String desc, Object receiver, Object[] args, boolean special) {
        Method method = resolve(owner, name, desc, receiver, special);
        if (method == null) {
            return vm.router.gap("no host method " + owner + '.' + name + desc
                    + (receiver == null ? "" : " on " + receiver.getClass().getName()), desc);
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        Object[] hostArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            hostArgs[i] = toHostArgument(args[i], parameterTypes[i]);
        }
        vm.enterHostCall(owner, name);
        try {
            return fromHostResult(invokeAccessibly(method, receiver, hostArgs), method.getReturnType());
        } catch (InvocationTargetException e) {
            throw fromHostFailure(e.getCause());
        } catch (IllegalArgumentException | IllegalAccessException e) {
            // Usually a guest stand-in (such as a class mirror) reaching an API that needs the
            // real type. Report it as a gap rather than crash: the analyst can add a stub.
            return vm.router.gap(owner + '.' + name + desc + " not callable: " + e.getMessage(), desc);
        } finally {
            vm.exitHostCall();
        }
    }

    /**
     * Converts one argument for a real JDK call. Beyond the primitive conversions, a guest class
     * mirror is replaced by its generated shell class where the JDK wants a {@code Class}: that
     * is the nearest real class there is, and for enums it behaves like the enum itself.
     */
    private Object toHostArgument(Object value, Class<?> parameterType) {
        if (parameterType == Class.class && value instanceof dev.javabyte.model.GuestClass guest) {
            Class<?> shell = vm.shells.shellFor(guest);
            return shell != null ? shell : value;
        }
        return Values.toHost(value, parameterType);
    }

    /** The reverse: a shell class coming back from the JDK is shown to the guest as its own class. */
    private Object fromHostResult(Object value, Class<?> declaredType) {
        if (value instanceof Class<?> cls) {
            dev.javabyte.model.GuestClass guest = vm.shells.guestFor(cls);
            return guest != null ? guest : value;
        }
        return Values.fromHost(value, declaredType);
    }

    private Object invokeAccessibly(Method method, Object receiver, Object[] hostArgs)
            throws InvocationTargetException, IllegalAccessException {
        try {
            return method.invoke(receiver, hostArgs);
        } catch (IllegalAccessException denied) {
            // A public method inherited from a non-public class (a common JDK pattern) cannot be
            // invoked through java.lang.reflect, but a public lookup on the receiver's public
            // type can reach it.
            if (!method.trySetAccessible()) {
                return invokeThroughLookup(method, receiver, hostArgs, denied);
            }
            return method.invoke(receiver, hostArgs);
        }
    }

    private Object invokeThroughLookup(Method method, Object receiver, Object[] hostArgs,
            IllegalAccessException original) throws InvocationTargetException, IllegalAccessException {
        try {
            MethodHandle handle = MethodHandles.publicLookup().unreflect(method);
            Object[] all = hostArgs;
            if (!Modifier.isStatic(method.getModifiers())) {
                all = new Object[hostArgs.length + 1];
                all[0] = receiver;
                System.arraycopy(hostArgs, 0, all, 1, hostArgs.length);
            }
            return handle.invokeWithArguments(all);
        } catch (IllegalAccessException e) {
            throw original;
        } catch (GuestThrow | EmulationAbort e) {
            throw e;
        } catch (Throwable t) {
            throw new InvocationTargetException(t);
        }
    }

    private Method resolve(String owner, String name, String desc, Object receiver, boolean special) {
        if (special && receiver instanceof GuestInstance) {
            Method accessor = findMethod(receiver.getClass(), SUPER_PREFIX + name, desc);
            if (accessor != null) {
                return accessor;
            }
        }
        Method method = methods.computeIfAbsent(owner + '.' + name + desc, key -> {
            Class<?> cls = findClass(owner);
            return Optional.ofNullable(cls == null ? null : findMethod(cls, name, desc));
        }).orElse(null);
        if (receiver != null && (method == null
                || (!Modifier.isStatic(method.getModifiers()) && !method.getDeclaringClass().isInstance(receiver)))) {
            if (!isSimulated(receiver)) {
                // The object is not what the call site says it is. Looking the name up on
                // whatever is there instead would let a call approved for one class run a
                // same-named method of another, so there is no method to call.
                return null;
            }
            // The receiver is a stand-in that imitates the named class without extending it;
            // find the same-named method on what is actually there.
            String key = receiver.getClass().getName() + '>' + name + desc;
            method = methods.computeIfAbsent(key,
                    k -> Optional.ofNullable(findMethod(receiver.getClass(), name, desc))).orElse(null);
        }
        return method;
    }

    /**
     * True if the named JDK class has the method and, for an instance method, the receiver is
     * an instance of the class that declares it. Only then does the policy's answer for the
     * named class describe the code that would actually run.
     */
    public boolean resolvesOn(String owner, String name, String desc, Object receiver) {
        Method method = methods.computeIfAbsent(owner + '.' + name + desc, key -> {
            Class<?> cls = findClass(owner);
            return Optional.ofNullable(cls == null ? null : findMethod(cls, name, desc));
        }).orElse(null);
        if (method == null) {
            return false;
        }
        return Modifier.isStatic(method.getModifiers())
                || (receiver != null && method.getDeclaringClass().isInstance(receiver));
    }

    /**
     * For a call on a simulated object: the internal name of the JDK class whose code would
     * run and which the simulation had no say over, or null if the call stays within the
     * simulation (or there is no such method, which {@link #invoke} reports).
     *
     * <p>A simulation overrides every public instance method it can, and the public final ones
     * it cannot (such as {@code FileChannel.tryLock()}) only call back into those overrides.
     * What is left is JDK code proper: a static method found through the receiver's class
     * ({@code File.createTempFile} on a simulated file), and protected or private members of
     * the JDK superclass ({@code ClassLoader.defineClass}).
     */
    public String jdkDeclarer(String owner, String name, String desc, Object receiver, boolean special) {
        Method method = resolve(owner, name, desc, receiver, special);
        if (method == null || !isJdkClass(method.getDeclaringClass())) {
            return null;
        }
        int modifiers = method.getModifiers();
        if (Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers)) {
            return null;
        }
        return Type.getInternalName(method.getDeclaringClass());
    }

    /**
     * Finds a method by JVM signature. An exact descriptor match is preferred; failing that the
     * parameters alone are matched, which covers covariant return types.
     */
    private static Method findMethod(Class<?> cls, String name, String desc) {
        String parameters = desc.substring(0, desc.indexOf(')') + 1);
        Method loose = null;
        for (Method candidate : cls.getMethods()) {
            if (!candidate.getName().equals(name)) {
                continue;
            }
            String candidateDesc = Type.getMethodDescriptor(candidate);
            if (candidateDesc.equals(desc) && Modifier.isPublic(candidate.getDeclaringClass().getModifiers())) {
                return candidate;
            }
            if (candidateDesc.startsWith(parameters) && (loose == null || candidateDesc.equals(desc))) {
                loose = candidate;
            }
        }
        if (loose != null) {
            return loose;
        }
        // Protected and package members are not returned by getMethods(); walk the class chain.
        for (Class<?> current = cls; current != null; current = current.getSuperclass()) {
            for (Method candidate : current.getDeclaredMethods()) {
                if (candidate.getName().equals(name) && Type.getMethodDescriptor(candidate).startsWith(parameters)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ constructors

    /** Constructs a real JDK object. */
    public Object construct(String owner, String desc, Object[] args) {
        Constructor<?> constructor = constructors.computeIfAbsent(owner + desc, key -> {
            Class<?> cls = findClass(owner);
            if (cls != null) {
                for (Constructor<?> candidate : cls.getDeclaredConstructors()) {
                    if (Type.getConstructorDescriptor(candidate).equals(desc)) {
                        return Optional.of(candidate);
                    }
                }
            }
            return Optional.empty();
        }).orElse(null);
        if (constructor == null) {
            throw vm.raise(new NoSuchMethodError(owner.replace('/', '.') + ".<init>" + desc));
        }
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        Object[] hostArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            hostArgs[i] = toHostArgument(args[i], parameterTypes[i]);
        }
        vm.enterHostCall(owner, "<init>");
        try {
            Object created = constructor.newInstance(hostArgs);
            if (created instanceof Throwable throwable) {
                // An exception's stack trace is captured when it is constructed; make it the
                // guest's stack, which is what the sample would see and sometimes depends on.
                vm.stampTrace(throwable);
            }
            return created;
        } catch (InvocationTargetException e) {
            throw fromHostFailure(e.getCause());
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            vm.router.gap(owner + ".<init>" + desc + " not constructible: " + e, "()V");
            return new dev.javabyte.model.PhantomObject(owner);
        } finally {
            vm.exitHostCall();
        }
    }

    /**
     * Converts an exception that escaped real JDK code into a guest exception.
     *
     * <p>Aborts and guest exceptions pass through unchanged: they come from a nested interpreter
     * level (a JDK method called back into guest code) and already have the right form.
     */
    private RuntimeException fromHostFailure(Throwable cause) {
        if (cause instanceof EmulationAbort abort) {
            throw abort;
        }
        if (cause instanceof GuestThrow guest) {
            return guest;
        }
        if (cause instanceof java.lang.reflect.UndeclaredThrowableException wrapped && wrapped.getCause() != null) {
            // A JDK proxy wraps checked exceptions its interface does not declare; the guest
            // lambda behind the proxy threw the inner one.
            return fromHostFailure(wrapped.getCause());
        }
        if (cause instanceof UnsupportedOperationException && cause.getStackTrace().length > 0
                && cause.getStackTrace()[0].getClassName().startsWith("dev.javabyte.env.")) {
            // A simulation declined an operation it does not model. The guest still gets the
            // exception, but the report must show that this was the emulator's limit, not the
            // sample's environment.
            StackTraceElement origin = cause.getStackTrace()[0];
            vm.events.addOnce("sim:" + origin, "gap", "unsupported",
                    "detail", origin.getClassName() + '.' + origin.getMethodName() + ": " + cause.getMessage());
        }
        return vm.raiseKeepingTrace(cause);
    }

    // ------------------------------------------------------------------ fields

    public Object getStatic(String owner, String name) {
        Field field = findField(findClass(owner), name);
        if (field == null) {
            return vm.router.gap("no host field " + owner + '.' + name, "()Ljava/lang/Object;");
        }
        try {
            return Values.fromHost(field.get(null), field.getType());
        } catch (IllegalAccessException e) {
            return vm.router.gap("host field not readable: " + owner + '.' + name, "()Ljava/lang/Object;");
        }
    }

    public Object getField(Object target, String owner, String name) {
        try {
            if (target instanceof GuestInstance) {
                // Protected fields of a shell's JDK superclass are reached through generated
                // accessors, because reflection may not open JDK internals.
                Method getter = findMethodByName(target.getClass(), GETTER_PREFIX + name);
                if (getter != null) {
                    return Values.fromHost(getter.invoke(target), getter.getReturnType());
                }
            }
            Field field = findField(target.getClass(), name);
            if (field == null) {
                return vm.router.gap("no host field " + owner + '.' + name, "()Ljava/lang/Object;");
            }
            return Values.fromHost(field.get(target), field.getType());
        } catch (ReflectiveOperationException e) {
            return vm.router.gap("host field not readable: " + owner + '.' + name, "()Ljava/lang/Object;");
        }
    }

    public void putField(Object target, String owner, String name, Object value) {
        try {
            if (target instanceof GuestInstance) {
                Method setter = findMethodByName(target.getClass(), SETTER_PREFIX + name);
                if (setter != null) {
                    setter.invoke(target, Values.toHost(value, setter.getParameterTypes()[0]));
                    return;
                }
            }
            Field field = findField(target.getClass(), name);
            if (field == null) {
                vm.router.gap("no host field " + owner + '.' + name, "()V");
                return;
            }
            field.set(target, Values.toHost(value, field.getType()));
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            vm.router.gap("host field not writable: " + owner + '.' + name, "()V");
        }
    }

    /**
     * Finds a public field that a JDK class declares, or that a {@link StandIn} declares to
     * imitate one ({@code Dimension.width}). Fields of the emulator's other classes are never
     * found: the guest holds emulator objects (class mirrors, phantoms, simulated files)
     * wherever it expects JDK ones, and their fields are the emulator's state.
     */
    private static Field findField(Class<?> cls, String name) {
        if (cls == null) {
            return null;
        }
        try {
            Field field = cls.getField(name);
            Class<?> declaring = field.getDeclaringClass();
            return isJdkClass(declaring) || StandIn.class.isAssignableFrom(declaring) ? field : null;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /** True for classes of the boot and platform loaders, which is what "the JDK" means here. */
    private static boolean isJdkClass(Class<?> cls) {
        ClassLoader owner = cls.getClassLoader();
        return owner == null || owner == ClassLoader.getPlatformClassLoader();
    }

    private static Method findMethodByName(Class<?> cls, String name) {
        for (Method method : cls.getMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        return null;
    }
}
