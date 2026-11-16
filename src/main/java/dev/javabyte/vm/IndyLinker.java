package dev.javabyte.vm;

import dev.javabyte.model.FieldRef;
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
                    MethodType.methodType(Object.class, Handle.class, Type[].class, Type.class, Object[].class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final VirtualMachine vm;

    IndyLinker(VirtualMachine vm) {
        this.vm = vm;
    }

    // ------------------------------------------------------------------ invokedynamic

    Object invoke(GuestMethod caller, int pc, InvokeDynamicInsnNode indy, Object[] args) {
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
        List<Object> bootstrapArgs = new ArrayList<>();
        bootstrapArgs.add(new Mirrors.LookupMirror(caller.owner.name));
        bootstrapArgs.add(indy.name);
        bootstrapArgs.add(methodType(indy.desc));
        for (Object constant : indy.bsmArgs) {
            bootstrapArgs.add(constant instanceof String || constant instanceof Number
                    ? constant : loadConstant(caller, constant));
        }
        Type[] declared = Type.getArgumentTypes(bootstrap.getDesc());
        Object[] finalArgs = bootstrapArgs.toArray();
        if (declared.length > 0 && declared[declared.length - 1].getSort() == Type.ARRAY
                && declared.length != finalArgs.length) {
            // A varargs bootstrap: the trailing static arguments are collected into one array.
            Object[] packed = new Object[declared.length];
            System.arraycopy(finalArgs, 0, packed, 0, declared.length - 1);
            Object[] rest = new Object[finalArgs.length - (declared.length - 1)];
            System.arraycopy(finalArgs, declared.length - 1, rest, 0, rest.length);
            packed[declared.length - 1] = rest;
            finalArgs = packed;
        }
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

    /** Implements {@code StringConcatFactory}: {@code "a" + b + c} compiled for Java 9 and later. */
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
                    text.append(Values.box(args[nextArg], types[nextArg]));
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
        return Proxy.newProxyInstance(vm.shells.loader(), new Class<?>[] {hostInterface}, new LambdaHandler(lambda));
    }

    /** Handler of the JDK proxy that represents a guest lambda to real JDK code. */
    private record LambdaHandler(GuestLambda lambda) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return invokeLambdaFromHost(lambda, proxy, method, args);
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

    private static Object invokeLambdaFromHost(GuestLambda lambda, Object proxy, Method method, Object[] args)
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
        VirtualMachine vm = VirtualMachine.current();
        if (vm == null) {
            throw new IllegalStateException("Guest lambda invoked outside the emulator thread: " + lambda);
        }
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
        return MethodHandles.insertArguments(TRAMPOLINE, 0, handle, parameterTypes, returnType)
                .asCollector(Object[].class, parameterTypes.length)
                .asType(type);
    }

    /** Target of every guest-visible method handle. Public only so the handle can be bound. */
    public static Object trampoline(Handle handle, Type[] parameterTypes, Type returnType, Object[] hostArgs) {
        VirtualMachine vm = VirtualMachine.current();
        if (vm == null) {
            throw new IllegalStateException("Guest method handle invoked outside the emulator thread");
        }
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
        MethodType type = handle.type();
        Object[] hostArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            // A handle may be invoked with fewer declared parameters than arguments (varargs
            // collectors), so only convert positions the type describes.
            hostArgs[i] = i < type.parameterCount() ? Values.toHost(args[i], type.parameterType(i)) : args[i];
        }
        try {
            return Values.fromHost(handle.invokeWithArguments(hostArgs), type.returnType());
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
            Object[] args = new Object[3 + dynamic.getBootstrapMethodArgumentCount()];
            args[0] = new Mirrors.LookupMirror(caller.owner.name);
            args[1] = dynamic.getName();
            args[2] = vm.types.classMirror(Type.getType(dynamic.getDescriptor()));
            for (int i = 3; i < args.length; i++) {
                Object argument = dynamic.getBootstrapMethodArgument(i - 3);
                args[i] = argument instanceof String || argument instanceof Number
                        ? argument : loadConstant(caller, argument);
            }
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
