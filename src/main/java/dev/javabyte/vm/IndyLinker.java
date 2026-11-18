package dev.javabyte.vm;

import dev.javabyte.model.FieldRef;
import dev.javabyte.model.GuestClass;
import dev.javabyte.model.GuestLambda;
import dev.javabyte.model.GuestMethod;
import dev.javabyte.model.InstanceState;
import dev.javabyte.model.Mirrors;
import dev.javabyte.shell.ShellDispatch;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;

/**
 * Links {@code invokedynamic} call sites and creates method handles for guest code.
 *
 * <p>The JDK's own bootstrap methods (lambdas, string concatenation, record methods) are
 * re-implemented here, since the real ones would try to spin classes that refer to guest types
 * the host JVM does not know. Bootstrap methods supplied by the sample itself, which obfuscators
 * use to hide call targets, are interpreted like any other guest code.
 *
 * <p>Every method handle given to guest code is a real {@link MethodHandle} that ends in
 * {@link #trampoline}, which re-enters the router. A handle therefore obeys exactly the same
 * policy as a direct call, however the sample combines or passes it around.
 */
public final class IndyLinker {

    /** A linked call site: maps the dynamic arguments to a result. */
    @FunctionalInterface
    private interface Linked {
        Object call(Object[] args);
    }

    private static final MethodHandle TRAMPOLINE;

    static {
        try {
            TRAMPOLINE = MethodHandles.lookup().findStatic(IndyLinker.class, "trampoline",
                    MethodType.methodType(Object.class, IndyLinker.class, Handle.class, Type[].class, Type.class,
                            Object[].class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final VirtualMachine vm;

    /**
     * Guest lambdas and method handles that a thread other than the emulator's tried to call,
     * waiting to be reported. The event log belongs to the emulator thread, so the other
     * thread only queues what it tried, and {@link #reportForeignCalls} records it.
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> foreignCalls =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    IndyLinker(VirtualMachine vm) {
        this.vm = vm;
    }

    /**
     * Records, as {@code gap.foreign-thread-call} events, the guest code that real JDK threads
     * tried to run since the last report. Runs on the emulator thread: whenever an
     * {@code invokedynamic} executes or a guest lambda or handle is called from the host, and
     * whenever the end of a run calls it.
     */
    public void reportForeignCalls() {
        for (String target; (target = foreignCalls.poll()) != null; ) {
            vm.events.addOnce("foreign-thread:" + target, "gap", "foreign-thread-call", "target", target,
                    "result", "IllegalStateException in the calling JDK thread");
        }
    }

    /**
     * Refuses a call into guest code from a thread that is not running the emulator, such as a
     * real ForkJoin worker that allow-listed JDK code started. Guest bytecode never runs
     * there. The call is queued for {@link #reportForeignCalls}, and the exception carries no
     * message: a message naming the emulator would reach any guest code that catches it.
     */
    private IllegalStateException refuseForeignCall(String target) {
        foreignCalls.add(target);
        return new IllegalStateException();
    }

    // ------------------------------------------------------------------ invokedynamic

    Object invoke(GuestMethod caller, int pc, InvokeDynamicInsnNode indy, Object[] args) {
        if (!foreignCalls.isEmpty()) {
            reportForeignCalls();
        }
        Linked linked = (Linked) caller.aux[pc];
        if (linked == null) {
            // Linking happens once per call site, as on a real JVM; bootstrap methods with
            // side effects must not be re-run on every execution.
            caller.aux[pc] = linked = link(caller, indy);
        }
        return linked.call(args);
    }

    private Linked link(GuestMethod caller, InvokeDynamicInsnNode indy) {
        Handle bootstrap = indy.bsm;
        switch (bootstrap.getOwner()) {
            case "java/lang/invoke/LambdaMetafactory": {
                String iface = Type.getReturnType(indy.desc).getInternalName();
                Handle implementation = (Handle) indy.bsmArgs[1];
                return captured -> newLambda(iface, indy.name, implementation, captured);
            }
            case "java/lang/invoke/StringConcatFactory":
                return linkStringConcat(indy);
            case "java/lang/runtime/ObjectMethods":
                return linkRecordMethod(indy);
            default:
                break;
        }
        if (!vm.classes.contains(bootstrap.getOwner())) {
            vm.router.gap("unsupported bootstrap method " + bootstrap.getOwner() + '.' + bootstrap.getName(), "()V");
            return args -> {
                throw vm.raise(new BootstrapMethodError("unsupported bootstrap " + bootstrap.getOwner()));
            };
        }
        return linkGuestBootstrap(caller, indy);
    }

    /**
     * Runs a bootstrap method that belongs to the sample. It receives a lookup, the call name
     * and type plus static arguments, and returns a call site whose target is then invoked.
     */
    private Linked linkGuestBootstrap(GuestMethod caller, InvokeDynamicInsnNode indy) {
        Handle bootstrap = indy.bsm;
        Object[] finalArgs = bootstrapArguments(caller, bootstrap, methodType(indy.desc), indy.name, indy.bsmArgs);
        Object site = vm.router.invokeStatic(bootstrap.getOwner(), bootstrap.getName(), bootstrap.getDesc(), finalArgs);
        vm.events.addOnce(caller.id + indy.name + indy.desc + System.identityHashCode(indy), "reflect", "indy-linked",
                "bootstrap", bootstrap.getOwner() + '.' + bootstrap.getName(), "site", indy.name + indy.desc);
        if (site instanceof CallSite callSite) {
            // dynamicInvoker() follows later retargeting of mutable call sites.
            MethodHandle target = callSite.dynamicInvoker();
            return args -> invokeMethodHandle(target, args);
        }
        if (site instanceof MethodHandle target) {
            return args -> invokeMethodHandle(target, args);
        }
        throw vm.raise(new BootstrapMethodError("bootstrap returned " + vm.types.nameOf(site)));
    }

    /**
     * Builds the argument list for a sample's bootstrap method, for both {@code invokedynamic}
     * and dynamic constants: a lookup for the caller, the name, the type (a method type or a
     * class mirror), then the resolved static arguments.
     *
     * <p>The JVM calls a bootstrap method with {@code invokeWithArguments}, which collects the
     * trailing arguments of a varargs method into an array. That is done here too, so a
     * bootstrap declared as {@code (Lookup, String, Class, Object...)} links whatever the
     * number of static arguments. Both callers share this method so that the two kinds of
     * bootstrap cannot drift apart again.
     */
    private Object[] bootstrapArguments(GuestMethod caller, Handle bootstrap, Object typeArg, String name, Object[] staticArgs) {
        Object[] args = new Object[3 + staticArgs.length];
        args[0] = new Mirrors.LookupMirror(caller.owner.name);
        args[1] = name;
        args[2] = typeArg;
        for (int i = 0; i < staticArgs.length; i++) {
            Object argument = staticArgs[i];
            args[3 + i] = argument instanceof String || argument instanceof Number ? argument : loadConstant(caller, argument);
        }
        Type[] declared = Type.getArgumentTypes(bootstrap.getDesc());
        if (declared.length == 0 || declared[declared.length - 1].getSort() != Type.ARRAY) {
            return args;
        }
        int fixed = declared.length - 1;
        Boolean varargs = isVarargs(bootstrap);
        boolean lastIsArray = args.length == declared.length && (args[fixed] == null || args[fixed].getClass().isArray());
        // When the flag cannot be read, a count mismatch is the only sign of varargs.
        boolean pack = varargs == null ? args.length != declared.length || !lastIsArray : varargs && !lastIsArray;
        if (!pack || args.length < fixed) {
            return args;
        }
        Object rest = java.lang.reflect.Array.newInstance(varargsElement(declared[fixed]), args.length - fixed);
        for (int i = fixed; i < args.length; i++) {
            java.lang.reflect.Array.set(rest, i - fixed, args[i]);
        }
        Object[] packed = new Object[declared.length];
        System.arraycopy(args, 0, packed, 0, fixed);
        packed[fixed] = rest;
        return packed;
    }

    /**
     * Whether a bootstrap method carries {@code ACC_VARARGS}, or null when its class is not
     * interpreted and the flag cannot be read.
     */
    private Boolean isVarargs(Handle bootstrap) {
        GuestClass owner = vm.classes.find(bootstrap.getOwner());
        GuestMethod method = owner == null ? null : owner.findStatic(bootstrap.getName(), bootstrap.getDesc());
        return method == null ? null : (method.node.access & Opcodes.ACC_VARARGS) != 0;
    }

    /**
     * Element class for a packed varargs array. Arrays of guest and mirrored types are
     * {@code Object[]} here, as everywhere else in the interpreter.
     */
    private Class<?> varargsElement(Type arrayType) {
        Type element = Type.getType(arrayType.getDescriptor().substring(1));
        return switch (element.getSort()) {
            case Type.OBJECT -> vm.types.hostClassOrObject(element.getInternalName());
            case Type.ARRAY -> vm.types.hostClassOrObject(element.getDescriptor());
            default -> vm.types.primitiveClass(element);
        };
    }

    /**
     * Implements {@code StringConcatFactory}: {@code "a" + b + c} compiled for Java 9 and later.
     * Each argument becomes the same text that {@code StringBuilder.append} would give it.
     */
    private Linked linkStringConcat(InvokeDynamicInsnNode indy) {
        Type[] types = Type.getArgumentTypes(indy.desc);
        // \1 marks an argument and \2 a constant; without a recipe, arguments are just joined.
        String recipe = indy.bsm.getName().equals("makeConcatWithConstants")
                ? (String) indy.bsmArgs[0] : "\u0001".repeat(types.length);
        Object[] constants = indy.bsmArgs;
        return args -> {
            StringBuilder text = new StringBuilder();
            int nextArg = 0;
            int nextConstant = 1;
            for (int i = 0; i < recipe.length(); i++) {
                char c = recipe.charAt(i);
                if (c == '\u0001') {
                    text.append(concatText(args[nextArg], types[nextArg]));
                    nextArg++;
                } else if (c == '\u0002') {
                    text.append(constants[nextConstant++]);
                } else {
                    text.append(c);
                }
            }
            return text.toString();
        };
    }

    /**
     * One concatenated value as text. A host {@code Date} or {@code Thread} would print the
     * host's own time zone or thread naming through its real {@code toString()}, so those go
     * through the router, where the time and thread stubs answer with the guest's. Anything
     * else is appended as it is, exactly as before.
     */
    private Object concatText(Object value, Type type) {
        Object boxed = Values.box(value, type);
        String owner = boxed instanceof java.util.Date ? "java/util/Date" : boxed instanceof Thread ? "java/lang/Thread" : null;
        if (owner == null) {
            return boxed;
        }
        return vm.router.invokeVirtual(owner, "toString", "()Ljava/lang/String;", boxed, new Object[0]);
    }

    /** Implements the compiler-generated {@code toString}/{@code hashCode}/{@code equals} of records. */
    private Linked linkRecordMethod(InvokeDynamicInsnNode indy) {
        String[] names = ((String) indy.bsmArgs[1]).isEmpty() ? new String[0] : ((String) indy.bsmArgs[1]).split(";");
        Handle[] getters = new Handle[names.length];
        for (int i = 0; i < names.length; i++) {
            getters[i] = (Handle) indy.bsmArgs[i + 2];
        }
        return args -> {
            Object[] mine = recordValues(args[0], getters);
            switch (indy.name) {
                case "hashCode": {
                    int hash = 0;
                    for (Object value : mine) {
                        hash = 31 * hash + Objects.hashCode(value);
                    }
                    return hash;
                }
                case "equals": {
                    InstanceState self = vm.stateOf(args[0]);
                    InstanceState other = vm.stateOf(args[1]);
                    boolean equal = other != null && other.type == self.type
                            && java.util.Arrays.equals(mine, recordValues(args[1], getters));
                    return equal ? 1 : 0;
                }
                default: {
                    // Class.getSimpleName(): the part after the package and any enclosing class.
                    String name = vm.stateOf(args[0]).type.name;
                    StringBuilder text = new StringBuilder(
                            name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('$')) + 1)).append('[');
                    for (int i = 0; i < names.length; i++) {
                        text.append(i == 0 ? "" : ", ").append(names[i]).append('=').append(mine[i]);
                    }
                    return text.append(']').toString();
                }
            }
        };
    }

    private Object[] recordValues(Object record, Handle[] getters) {
        InstanceState state = vm.stateOf(record);
        Object[] values = new Object[getters.length];
        for (int i = 0; i < getters.length; i++) {
            FieldRef ref = state.type.resolveField(getters[i].getName(), getters[i].getDesc());
            values[i] = ref == null ? null : Values.box(state.fields[ref.slot()], Type.getType(getters[i].getDesc()));
        }
        return values;
    }

    // ------------------------------------------------------------------ lambdas

    /**
     * Creates the object for a lambda or method reference.
     *
     * <p>If the functional interface is a JDK type the object must be usable by real JDK code
     * (a {@code Comparator} handed to {@code sort}), so a JDK proxy is used. For an interface
     * that is itself interpreted, a lightweight {@link GuestLambda} is enough.
     */
    public Object newLambda(String interfaceName, String methodName, Handle implementation, Object[] captured) {
        GuestLambda lambda = new GuestLambda(interfaceName, methodName, implementation, captured);
        Class<?> hostInterface = vm.classes.contains(interfaceName) ? null : vm.host.findClass(interfaceName);
        if (hostInterface == null || !hostInterface.isInterface()) {
            return lambda;
        }
        return Proxy.newProxyInstance(vm.shells.loader(), new Class<?>[] {hostInterface}, new LambdaHandler(lambda, this));
    }

    /** Handler of the JDK proxy that represents a guest lambda to real JDK code. */
    private record LambdaHandler(GuestLambda lambda, IndyLinker linker) implements InvocationHandler {
        /** Runs the guest lambda for a call that real JDK code made on the proxy. */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return linker.invokeLambdaFromHost(lambda, proxy, method, args);
        }
    }

    /** The guest lambda a value stands for, if it is one of the proxies made by {@link #newLambda}. */
    public GuestLambda lambdaBehind(Object value) {
        if (value != null && Proxy.isProxyClass(value.getClass())
                && Proxy.getInvocationHandler(value) instanceof LambdaHandler handler) {
            return handler.lambda();
        }
        return null;
    }

    /**
     * Runs a guest lambda that real JDK code called through its proxy. {@code Object}'s methods
     * and interface default methods are answered directly; anything else is interpreted, on
     * the emulator thread only.
     */
    private Object invokeLambdaFromHost(GuestLambda lambda, Object proxy, Method method, Object[] args)
            throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            switch (method.getName()) {
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                default: return lambda.toString();
            }
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        if (VirtualMachine.current() != vm) {
            throw refuseForeignCall(lambda.interfaceName() + '.' + method.getName() + " (lambda "
                    + lambda.implementation().getOwner() + '.' + lambda.implementation().getName() + ')');
        }
        reportForeignCalls();
        Handle implementation = lambda.implementation();
        Type[] parameterTypes = handleParameterTypes(implementation);
        Object[] hostArgs = args == null ? new Object[0] : args;
        Object[] all = new Object[lambda.captured().length + hostArgs.length];
        System.arraycopy(lambda.captured(), 0, all, 0, lambda.captured().length);
        for (int i = 0; i < hostArgs.length; i++) {
            int position = lambda.captured().length + i;
            all[position] = position < parameterTypes.length
                    ? Values.unbox(hostArgs[i], parameterTypes[position]) : hostArgs[i];
        }
        try {
            Object result = vm.router.invokeHandle(implementation, all);
            return Values.box(result, handleReturnType(implementation));
        } catch (GuestThrow thrown) {
            throw vm.markGuestOrigin(thrown.asThrowable());
        }
    }

    /** Parameter types of a handle as called, i.e. with the receiver first for instance methods. */
    private static Type[] handleParameterTypes(Handle handle) {
        Type[] declared = handle.getTag() <= Opcodes.H_PUTSTATIC
                ? new Type[0] : Type.getArgumentTypes(handle.getDesc());
        boolean hasReceiver = handle.getTag() == Opcodes.H_INVOKEVIRTUAL || handle.getTag() == Opcodes.H_INVOKESPECIAL
                || handle.getTag() == Opcodes.H_INVOKEINTERFACE || handle.getTag() == Opcodes.H_GETFIELD
                || handle.getTag() == Opcodes.H_PUTFIELD;
        List<Type> types = new ArrayList<>();
        if (hasReceiver) {
            types.add(Type.getObjectType(handle.getOwner()));
        }
        if (handle.getTag() == Opcodes.H_PUTFIELD || handle.getTag() == Opcodes.H_PUTSTATIC) {
            types.add(Type.getType(handle.getDesc()));
        }
        types.addAll(List.of(declared));
        return types.toArray(Type[]::new);
    }

    private static Type handleReturnType(Handle handle) {
        switch (handle.getTag()) {
            case Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC:
                return Type.getType(handle.getDesc());
            case Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC:
                return Type.VOID_TYPE;
            case Opcodes.H_NEWINVOKESPECIAL:
                return Type.getObjectType(handle.getOwner());
            default:
                return Type.getReturnType(handle.getDesc());
        }
    }

    // ------------------------------------------------------------------ method handles

    /**
     * Wraps a symbolic reference in a real method handle whose invocation is routed through
     * the emulator.
     */
    public MethodHandle handleFor(Handle handle) {
        Type[] parameterTypes = handleParameterTypes(handle);
        Type returnType = handleReturnType(handle);
        Class<?>[] erasedParameters = new Class<?>[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            erasedParameters[i] = erase(parameterTypes[i]);
        }
        MethodType type = MethodType.methodType(erase(returnType), erasedParameters);
        return MethodHandles.insertArguments(TRAMPOLINE, 0, this, handle, parameterTypes, returnType)
                .asCollector(Object[].class, parameterTypes.length)
                .asType(type);
    }

    /**
     * Target of every guest-visible method handle. Public only so the handle can be bound.
     *
     * @param linker the linker of the machine that made the handle; calls from any thread but
     *               that machine's are refused
     */
    public static Object trampoline(IndyLinker linker, Handle handle, Type[] parameterTypes, Type returnType,
            Object[] hostArgs) {
        VirtualMachine vm = linker.vm;
        if (VirtualMachine.current() != vm) {
            throw linker.refuseForeignCall("method handle " + handle.getOwner() + '.' + handle.getName() + handle.getDesc());
        }
        linker.reportForeignCalls();
        Object[] args = new Object[hostArgs.length];
        for (int i = 0; i < args.length; i++) {
            args[i] = Values.unbox(hostArgs[i], parameterTypes[i]);
        }
        try {
            return Values.box(vm.router.invokeHandle(handle, args), returnType);
        } catch (GuestThrow thrown) {
            throw ShellDispatch.sneakyThrow(vm.markGuestOrigin(thrown.asThrowable()));
        }
    }

    /** Invokes any method handle held by guest code, converting values at the boundary. */
    public Object invokeMethodHandle(MethodHandle handle, Object[] args) {
        return invokeMethodHandle(handle, args, null);
    }

    /**
     * Invokes a real method handle with guest values.
     *
     * @param siteDescriptor descriptor of the {@code invoke}/{@code invokeExact} call site, or
     *     null if unknown. {@code invoke} is signature-polymorphic: the JVM adapts from the types
     *     written at the call site to the handle's own. The guest keeps byte, short, char and
     *     boolean as ints, so without the call-site types a {@code byte} argument for a
     *     {@code Byte} parameter would arrive as an {@code Integer} and be refused.
     */
    public Object invokeMethodHandle(MethodHandle handle, Object[] args, String siteDescriptor) {
        MethodType type = handle.type();
        Type[] site = siteDescriptor == null ? new Type[0] : Type.getArgumentTypes(siteDescriptor);
        Object[] hostArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Class<?> sitePrimitive = i < site.length ? vm.types.primitiveClass(site[i]) : void.class;
            if (sitePrimitive != void.class) { // void.class marks a reference type
                hostArgs[i] = Values.toHost(args[i], sitePrimitive);
            } else {
                // A handle may be invoked with fewer declared parameters than arguments (varargs
                // collectors), so only convert positions the type describes.
                hostArgs[i] = i < type.parameterCount() ? Values.toHost(args[i], type.parameterType(i)) : args[i];
            }
        }
        Class<?> siteReturn = siteDescriptor == null ? void.class : vm.types.primitiveClass(Type.getReturnType(siteDescriptor));
        try {
            return Values.fromHost(handle.invokeWithArguments(hostArgs), siteReturn != void.class ? siteReturn : type.returnType());
        } catch (GuestThrow | EmulationAbort passThrough) {
            throw passThrough;
        } catch (Throwable thrown) {
            throw vm.raiseKeepingTrace(thrown);
        }
    }

    // ------------------------------------------------------------------ constants

    /** Resolves a non-trivial {@code ldc} constant: class literal, method type, handle or condy. */
    Object loadConstant(GuestMethod caller, Object constant) {
        if (constant instanceof Type type) {
            if (type.getSort() == Type.METHOD) {
                return methodType(type.getDescriptor());
            }
            Object mirror = vm.types.classMirror(type);
            if (mirror == null) {
                throw vm.raise(new NoClassDefFoundError(type.getClassName()));
            }
            return mirror;
        }
        if (constant instanceof Handle handle) {
            return handleFor(handle);
        }
        if (constant instanceof ConstantDynamic dynamic && vm.classes.contains(dynamic.getBootstrapMethod().getOwner())) {
            Handle bootstrap = dynamic.getBootstrapMethod();
            Object[] staticArgs = new Object[dynamic.getBootstrapMethodArgumentCount()];
            for (int i = 0; i < staticArgs.length; i++) {
                staticArgs[i] = dynamic.getBootstrapMethodArgument(i);
            }
            Object[] args = bootstrapArguments(caller, bootstrap, vm.types.classMirror(Type.getType(dynamic.getDescriptor())),
                    dynamic.getName(), staticArgs);
            return vm.router.invokeStatic(bootstrap.getOwner(), bootstrap.getName(), bootstrap.getDesc(), args);
        }
        return vm.router.gap("unsupported constant " + constant, "()Ljava/lang/Object;");
    }

    /**
     * Builds a real {@link MethodType} for a descriptor. Guest classes have no host class, so
     * they are erased to {@code Object}; the interpreter does not rely on static types anyway.
     */
    public MethodType methodType(String descriptor) {
        Type[] parameterTypes = Type.getArgumentTypes(descriptor);
        Class<?>[] parameters = new Class<?>[parameterTypes.length];
        for (int i = 0; i < parameters.length; i++) {
            parameters[i] = erase(parameterTypes[i]);
        }
        return MethodType.methodType(erase(Type.getReturnType(descriptor)), parameters);
    }

    private Class<?> erase(Type type) {
        switch (type.getSort()) {
            case Type.OBJECT:
                return vm.types.hostClassOrObject(type.getInternalName());
            case Type.ARRAY:
                return vm.types.hostClassOrObject(type.getDescriptor());
            default:
                return vm.types.primitiveClass(type);
        }
    }

    /** Erased descriptor of a guest method, for matching against a {@link MethodType}. */
    public String erasedDescriptor(String descriptor) {
        return methodType(descriptor).toMethodDescriptorString();
    }

}
