package dev.javabyte.vm;

import dev.javabyte.host.CallContext;
import dev.javabyte.host.HostBridge;
import dev.javabyte.host.Stub;
import dev.javabyte.model.FieldRef;
import dev.javabyte.model.GuestClass;
import dev.javabyte.model.GuestLambda;
import dev.javabyte.model.GuestMethod;
import dev.javabyte.model.InstanceState;
import dev.javabyte.model.Mirrors;
import dev.javabyte.model.PendingInstance;
import dev.javabyte.model.PhantomObject;
import dev.javabyte.model.PlainObject;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldInsnNode;

/**
 * Decides what every call made by guest code actually does. This is the emulator's security
 * boundary: no guest call reaches the real JDK except through {@link #hostCall}.
 *
 * <p>For each call the order of precedence is:
 * <ol>
 *   <li>a config hook on the method;
 *   <li>the sample's own bytecode, if an interpreted class implements the method;
 *   <li>a stub, which replaces a JDK member with a safe simulation;
 *   <li>the real JDK member, if the host policy allows it;
 *   <li>otherwise the call is blocked and reported.
 * </ol>
 * Reflection, method handles and lambdas are all funnelled back through the same entry points,
 * so none of them can be used to side-step the order above.
 */
public final class InvocationRouter {

    private final VirtualMachine vm;

    InvocationRouter(VirtualMachine vm) {
        this.vm = vm;
    }

    /** Entry point for the four invoke instructions. */
    public Object invoke(int opcode, String owner, String name, String desc, Object receiver, Object[] args) {
        switch (opcode) {
            case Opcodes.INVOKESTATIC:
                return invokeStatic(owner, name, desc, args);
            case Opcodes.INVOKESPECIAL:
                if ("<init>".equals(name)) {
                    construct(owner, desc, receiver, args);
                    return null;
                }
                return invokeSpecial(owner, name, desc, receiver, args);
            default:
                return invokeVirtual(owner, name, desc, receiver, args);
        }
    }

    // ------------------------------------------------------------------ static

    public Object invokeStatic(String owner, String name, String desc, Object[] args) {
        GuestClass cls = vm.classes.find(owner);
        if (cls == null) {
            return hostCall(owner, name, desc, null, args, false);
        }
        GuestMethod method = cls.findStatic(name, desc);
        if (method == null) {
            // A static inherited from a JDK superclass, e.g. MyThread.sleep(...).
            return hostCall(cls.hostSuperName(), name, desc, null, args, false);
        }
        vm.ensureInitialised(method.owner);
        return callGuest(method, null, args);
    }

    // ------------------------------------------------------------------ virtual and interface

    public Object invokeVirtual(String owner, String name, String desc, Object receiver, Object[] args) {
        if (receiver == null) {
            throw vm.raise(new NullPointerException("Cannot invoke \"" + owner.replace('/', '.') + '.' + name
                    + "()\" because value is null"));
        }
        InstanceState state = vm.stateOf(receiver);
        if (state != null) {
            GuestMethod method = state.type.findVirtual(name, desc);
            if (method != null) {
                return callGuest(method, receiver, args);
            }
            Object builtin = objectMethod(receiver, state, name, desc, args);
            if (builtin != NOT_HANDLED) {
                return builtin;
            }
            return hostCall(hostOwner(owner, state.type), name, desc, receiver, args, false);
        }
        if (receiver instanceof GuestLambda lambda) {
            return invokeLambda(lambda, name, desc, args);
        }
        if (owner.charAt(0) == '[') {
            // Methods "of" an array type are Object's, except clone(), which arrays make public.
            if (name.equals("clone")) {
                return cloneArray(receiver);
            }
            return hostCall("java/lang/Object", name, desc, receiver, args, false);
        }
        GuestClass guestOwner = vm.classes.find(owner);
        return hostCall(guestOwner == null ? owner : guestOwner.hostSuperName(), name, desc, receiver, args, false);
    }

    /** Calls a lambda created by guest code for a guest functional interface. */
    public Object invokeLambda(GuestLambda lambda, String name, String desc, Object[] args) {
        if (name.equals(lambda.methodName())) {
            Object[] all = new Object[lambda.captured().length + args.length];
            System.arraycopy(lambda.captured(), 0, all, 0, lambda.captured().length);
            System.arraycopy(args, 0, all, lambda.captured().length, args.length);
            return invokeHandle(lambda.implementation(), all);
        }
        GuestClass iface = vm.classes.find(lambda.interfaceName());
        GuestMethod defaultMethod = iface == null ? null : iface.findVirtual(name, desc);
        if (defaultMethod != null) {
            return callGuest(defaultMethod, lambda, args);
        }
        return hostCall("java/lang/Object", name, desc, lambda, args, false);
    }

    // ------------------------------------------------------------------ special (super and private)

    public Object invokeSpecial(String owner, String name, String desc, Object receiver, Object[] args) {
        if (receiver == null) {
            throw vm.raise(new NullPointerException("Cannot invoke \"" + name + "()\" because value is null"));
        }
        GuestClass cls = vm.classes.find(owner);
        if (cls != null) {
            // Lookup starts at the named class, not at the receiver's class: that is what makes
            // the call non-virtual.
            GuestMethod method = cls.findVirtual(name, desc);
            if (method != null) {
                return callGuest(method, receiver, args);
            }
        }
        InstanceState state = vm.stateOf(receiver);
        if (state != null) {
            Object builtin = objectMethod(receiver, state, name, desc, args);
            if (builtin != NOT_HANDLED) {
                return builtin;
            }
        }
        return hostCall(cls == null ? owner : cls.hostSuperName(), name, desc, receiver, args, true);
    }

    // ------------------------------------------------------------------ constructors

    /**
     * Implements {@code invokespecial <init>}.
     *
     * @param target the value produced by {@code NEW}: a {@link PlainObject} or {@link PendingInstance}
     */
    public void construct(String owner, String desc, Object target, Object[] args) {
        GuestClass cls = vm.classes.find(owner);
        if (cls != null) {
            GuestMethod constructor = cls.declared("<init>", desc);
            if (constructor == null) {
                throw vm.raise(new NoSuchMethodError(cls.dottedName() + ".<init>" + desc));
            }
            callGuest(constructor, target, args);
            return;
        }
        if (target instanceof PlainObject plain) {
            // A plain guest object reaching a JDK constructor: either Object's (nothing to do)
            // or that of a superclass which is unavailable or blocked.
            if (!"java/lang/Object".equals(owner)) {
                vm.events.addOnce(plain.javabyte$state().type.name + owner, "gap", "phantom-superclass",
                        "class", plain.javabyte$state().type.name, "superclass", owner);
            }
            return;
        }
        if (!(target instanceof PendingInstance pending)) {
            // Constructor invoked on an already constructed object; legal bytecode never does this.
            return;
        }
        pending.resolved = pending.state != null
                ? vm.shells.instantiate(pending.state, owner, desc, args)
                : constructHost(owner, desc, args);
    }

    /** Creates an instance of a JDK class, subject to hooks, stubs and policy. */
    private Object constructHost(String owner, String desc, Object[] args) {
        Stub stub = vm.stubs.find(owner, "<init>", desc, vm.host::findClass);
        if (stub != null) {
            return runStub(stub, new CallContext(vm, owner, "<init>", desc, null, args, false));
        }
        if (vm.host.findClass(owner) == null) {
            vm.events.addOnce(owner, "gap", "missing-class", "class", owner);
            return new PhantomObject(owner);
        }
        if (vm.policy.allows(owner, "<init>", desc) && !touchesFileSystem(owner, desc)) {
            traceHostCall(owner, "<init>", desc, args);
            return vm.host.construct(owner, desc, args);
        }
        blocked(owner, "<init>", desc, "constructor");
        return new PhantomObject(owner);
    }

    // ------------------------------------------------------------------ guest methods

    /** Runs an interpreted method, applying hooks, native handling and tracing. */
    public Object callGuest(GuestMethod method, Object receiver, Object[] args) {
        if (method.hook != null) {
            return applyHook((Hooks.Hook) method.hook, method.id, method.returnType);
        }
        if (method.isNative) {
            // There is no machine code to run. Reporting the call is the useful part: it shows
            // which native library functions the Java side depends on.
            vm.events.addOnce(method.id, "native", "call", "method", method.id);
            return Values.zero(method.returnType);
        }
        if (method.isAbstract) {
            throw vm.raise(new AbstractMethodError(method.id));
        }
        if (method.traceCalls) {
            vm.events.writeTrace("-> " + method.id + describeArgs(args));
        }
        Object result = vm.interpreter.execute(method, receiver, args);
        if (method.traceCalls) {
            vm.events.writeTrace("<- " + method.id + " = " + Values.describe(result));
        }
        if (result instanceof String text && !text.isEmpty()) {
            recordString(method, text);
        }
        return result;
    }

    /**
     * Records String results of guest methods. Obfuscators hide string constants behind
     * decryption helpers, so the values those helpers return are, in effect, the sample's
     * decrypted string table.
     */
    private void recordString(GuestMethod method, String text) {
        String mode = vm.config.trace.strings;
        if ("none".equals(mode) || ("static".equals(mode) && !method.isStatic)) {
            return;
        }
        vm.events.addOnce(method.id + '\u0000' + text, "string", "returned",
                "method", method.id, "value", Values.truncate(text, 2000));
    }

    // ------------------------------------------------------------------ the host boundary

    /**
     * Performs a call whose implementation is not guest bytecode.
     *
     * @param owner   internal name of the JDK class the call resolves to
     * @param special true for a non-virtual call
     */
    public Object hostCall(String owner, String name, String desc, Object receiver, Object[] args, boolean special) {
        Hooks.Hook hook = vm.hooks.forHost(owner, name, desc);
        if (hook != null) {
            return applyHook(hook, owner + '.' + name + desc, Type.getReturnType(desc));
        }
        Stub stub = vm.stubs.find(owner, name, desc, vm.host::findClass);
        if (stub != null) {
            return runStub(stub, new CallContext(vm, owner, name, desc, receiver, args, special));
        }
        if (receiver instanceof PhantomObject phantom) {
            vm.events.addOnce(phantom.type + name + desc, "policy", "phantom-call",
                    "method", phantom.type + '.' + name + desc);
            return neutralResult(desc);
        }
        if (receiver instanceof PlainObject plain) {
            // The method belongs to a superclass the sample expects but which is not available.
            vm.events.addOnce(owner + name + desc, "gap", "phantom-superclass-call",
                    "class", plain.javabyte$state().type.name, "method", owner + '.' + name + desc);
            return neutralResult(desc);
        }
        if (isMirror(receiver) && !"java/lang/Object".equals(owner)) {
            return gap("unsupported operation on " + vm.types.nameOf(receiver) + ": " + name + desc, desc);
        }
        // Objects supplied by the emulator's own simulations are safe by construction. This is
        // checked before class availability because a stand-in may imitate a class that the
        // analysis JDK no longer ships.
        if (HostBridge.isSimulated(receiver)) {
            traceHostCall(owner, name, desc, args);
            return vm.host.invoke(owner, name, desc, receiver, args, special);
        }
        if (vm.host.findClass(owner) == null) {
            vm.events.addOnce(owner, "gap", "missing-class", "class", owner);
            vm.events.addOnce(owner + name + desc, "policy", "phantom-call", "method", owner + '.' + name + desc);
            return neutralResult(desc);
        }
        if (vm.policy.allows(owner, name, desc) && !touchesFileSystem(owner, desc)) {
            traceHostCall(owner, name, desc, args);
            return vm.host.invoke(owner, name, desc, receiver, args, special);
        }
        String viaReceiver = receiver == null ? null : allowedSupertype(receiver.getClass(), name, desc);
        if (viaReceiver != null && !touchesFileSystem(viaReceiver, desc)) {
            traceHostCall(viaReceiver, name, desc, args);
            return vm.host.invoke(viaReceiver, name, desc, receiver, args, special);
        }
        return blocked(owner, name, desc, "method");
    }

    private final java.util.Map<String, java.util.Optional<String>> receiverCache = new java.util.HashMap<>();

    /**
     * Finds an allow-listed class or interface of the receiver that has the method.
     *
     * <p>Bytecode may name a class the policy does not list while the object in hand is of an
     * allowed type: a stub answers {@code new FileWriter(...)} with a plain
     * {@code OutputStreamWriter}, yet the call site still says {@code FileWriter.append}. The
     * guest could make the same call by naming the allowed supertype, so honouring it here
     * grants nothing new.
     *
     * @return the internal name to call through, or null
     */
    private String allowedSupertype(Class<?> receiverClass, String name, String desc) {
        return receiverCache.computeIfAbsent(receiverClass.getName() + '.' + name + desc, key -> {
            java.util.Deque<Class<?>> work = new java.util.ArrayDeque<>(java.util.List.of(receiverClass));
            java.util.Set<Class<?>> visited = new java.util.HashSet<>();
            while (!work.isEmpty()) {
                Class<?> current = work.poll();
                if (!visited.add(current)) {
                    continue;
                }
                String internalName = Type.getInternalName(current);
                if (java.lang.reflect.Modifier.isPublic(current.getModifiers()) && vm.host.findClass(internalName) != null
                        && vm.policy.allows(internalName, name, desc) && declares(current, name, desc)) {
                    return java.util.Optional.of(internalName);
                }
                if (current.getSuperclass() != null) {
                    work.add(current.getSuperclass());
                }
                work.addAll(java.util.List.of(current.getInterfaces()));
            }
            return java.util.Optional.empty();
        }).orElse(null);
    }

    private static boolean declares(Class<?> cls, String name, String desc) {
        String parameters = desc.substring(0, desc.indexOf(')') + 1);
        for (java.lang.reflect.Method method : cls.getMethods()) {
            if (method.getName().equals(name) && Type.getMethodDescriptor(method).startsWith(parameters)) {
                return true;
            }
        }
        return false;
    }

    private Object runStub(Stub stub, CallContext context) {
        try {
            return stub.call(context);
        } catch (GuestThrow | EmulationAbort intentional) {
            throw intentional;
        } catch (RuntimeException | Error unchecked) {
            // A stub that called back into guest code (running a Runnable, say) sees the guest's
            // exception in raw form.
            if (vm.isGuestOrigin(unchecked)) {
                throw new GuestThrow(unchecked);
            }
            if (unchecked instanceof Error) {
                throw unchecked;
            }
            // Otherwise the stub itself failed, typically on an argument the real API would also
            // reject (a null name, a wrong type). The guest receives the exception, as it would
            // from the JDK, and the failure is listed as a gap so a defect cannot hide.
            StackTraceElement origin = unchecked.getStackTrace().length > 0 ? unchecked.getStackTrace()[0] : null;
            vm.events.addOnce("stub:" + context.signature() + origin, "gap", "stub-exception",
                    "stub", context.signature(), "exception", unchecked.toString(), "at", String.valueOf(origin));
            throw vm.raise(unchecked);
        } catch (Exception checked) {
            // By contract, a checked exception from a stub is the simulated API's own failure,
            // unless guest code threw it during a callback, in which case its trace is kept.
            throw vm.isGuestOrigin(checked) ? new GuestThrow(checked) : vm.raise(checked);
        }
    }

    /** JDK classes that merely remember a file for a launch which is itself simulated. */
    private static final java.util.Set<String> FILE_INERT_OWNERS =
            java.util.Set.of("java/lang/ProcessBuilder", "java/lang/ProcessBuilder$Redirect");

    /**
     * A JDK method declared to take a file or path would act on the real disk. Simulated file
     * objects are real {@code File}/{@code Path} subclasses, so this check stops them leaking
     * into any allow-listed API that happens to have a file-taking overload.
     *
     * <p>The declared parameter types are what count. A file passed where {@code Object} is
     * declared (added to a list, appended to a string) is only being stored or printed.
     */
    private static boolean touchesFileSystem(String owner, String desc) {
        String parameters = desc.substring(0, desc.indexOf(')'));
        boolean takesFile = parameters.contains("Ljava/io/File;") || parameters.contains("Ljava/nio/file/Path;");
        return takesFile && !FILE_INERT_OWNERS.contains(owner);
    }

    private static boolean isMirror(Object value) {
        return value instanceof GuestClass || value instanceof Mirrors.MethodMirror
                || value instanceof Mirrors.FieldMirror || value instanceof Mirrors.ConstructorMirror
                || value instanceof Mirrors.LookupMirror || value instanceof GuestLambda;
    }

    private void traceHostCall(String owner, String name, String desc, Object[] args) {
        if (vm.config.trace.hostCalls) {
            vm.events.writeTrace("[host] " + owner + '.' + name + desc + describeArgs(args));
        }
    }

    /**
     * Handles a call that policy does not permit.
     *
     * @param kind "method", "constructor" or "field", for the report
     * @return the neutral value to continue with, when the configured reaction is to continue
     */
    public Object blocked(String owner, String name, String desc, String kind) {
        vm.events.addOnce(owner + name + desc, "policy", "blocked", "kind", kind, "member", owner + '.' + name + desc);
        switch (vm.config.policy.onBlocked) {
            case "throw":
                throw vm.raise(new SecurityException("blocked by emulator policy: " + owner + '.' + name));
            case "abort":
                throw new EmulationAbort(EmulationAbort.Reason.POLICY, owner + '.' + name + desc);
            default:
                return neutralResult(desc);
        }
    }

    /**
     * Records that the emulator could not faithfully perform something (as opposed to refusing
     * it on policy grounds) and returns a neutral value. Gaps are listed separately in the
     * report because they mark where results may differ from a real run.
     */
    public Object gap(String message, String desc) {
        vm.events.addOnce(message, "gap", "unsupported", "detail", message);
        return neutralResult(desc);
    }

    /**
     * The value a blocked or unavailable call returns: zero for primitives; for object types a
     * {@link PhantomObject} when the type itself is off-limits (so chained calls keep being
     * reported), otherwise null.
     */
    private Object neutralResult(String desc) {
        Type type = Type.getReturnType(desc);
        if (type.getSort() != Type.OBJECT) {
            return Values.zero(type);
        }
        String name = type.getInternalName();
        boolean usable = vm.classes.contains(name) || (vm.host.findClass(name) != null && vm.policy.knowsClass(name));
        return usable ? null : new PhantomObject(name);
    }

    private Object applyHook(Hooks.Hook hook, String id, Type returnType) {
        vm.events.addOnce(id, "hook", hook.action(), "method", id, "value", String.valueOf(hook.value()));
        switch (hook.action()) {
            case "throw": {
                String type = hook.value() == null ? "java/lang/RuntimeException" : hook.value().toString().replace('.', '/');
                Object exception = vm.instantiate(type, "()V", new Object[0]);
                throw new GuestThrow(exception);
            }
            case "return":
                return configValue(hook.value(), returnType);
            default: // skip
                return Values.zero(returnType);
        }
    }

    /** Converts a YAML/JSON scalar into the interpreter value for a JVM type. */
    public static Object configValue(Object value, Type type) {
        if (value == null) {
            return Values.zero(type);
        }
        switch (type.getSort()) {
            case Type.BOOLEAN:
                return Boolean.parseBoolean(value.toString()) || "1".equals(value.toString()) ? 1 : 0;
            case Type.CHAR:
                return value instanceof Number n ? (Object) n.intValue() : (Object) (int) value.toString().charAt(0);
            case Type.BYTE, Type.SHORT, Type.INT:
                return ((Number) value).intValue();
            case Type.LONG:
                return ((Number) value).longValue();
            case Type.FLOAT:
                return ((Number) value).floatValue();
            case Type.DOUBLE:
                return ((Number) value).doubleValue();
            case Type.VOID:
                return null;
            case Type.ARRAY:
                if (type.getDescriptor().equals("[Ljava/lang/String;") && value instanceof java.util.List<?> list) {
                    return list.stream().map(String::valueOf).toArray(String[]::new);
                }
                if (type.getDescriptor().equals("[B") && value instanceof String base64) {
                    return java.util.Base64.getDecoder().decode(base64);
                }
                return null;
            default:
                return type.getInternalName().equals("java/lang/String") ? value.toString() : value;
        }
    }

    // ------------------------------------------------------------------ Object's methods on guest objects

    private static final Object NOT_HANDLED = new Object();

    /**
     * Provides {@code java.lang.Object}'s methods for guest objects that do not override them.
     *
     * <p>They cannot be delegated to the host object because that would expose emulator details:
     * {@code toString()} would print the name of an emulator class, and {@code getClass()} would
     * return a class the guest program has never heard of.
     */
    private Object objectMethod(Object receiver, InstanceState state, String name, String desc, Object[] args) {
        // A shell that extends a real JDK class inherits that class's behaviour instead.
        boolean jdkBase = state.type.shellClass != null && state.type.shellClass.getSuperclass() != Object.class;
        switch (name + desc) {
            case "getClass()Ljava/lang/Class;":
                return state.type;
            case "hashCode()I":
                return jdkBase ? NOT_HANDLED : (Object) System.identityHashCode(receiver);
            case "equals(Ljava/lang/Object;)Z":
                return jdkBase ? NOT_HANDLED : (Object) (receiver == args[0] ? 1 : 0);
            case "toString()Ljava/lang/String;":
                return jdkBase ? NOT_HANDLED
                        : state.type.dottedName() + '@' + Integer.toHexString(System.identityHashCode(receiver));
            case "clone()Ljava/lang/Object;":
                if (receiver instanceof PlainObject) {
                    return new PlainObject(new InstanceState(state));
                }
                return jdkBase ? NOT_HANDLED : gap("clone() of " + state.type.name, desc);
            case "notify()V", "notifyAll()V", "finalize()V":
                return null;
            case "wait()V", "wait(J)V", "wait(JI)V":
                // With one thread nothing can ever call notify() while we wait. Returning is
                // permitted by the specification (spurious wake-up); queued tasks get a turn first.
                vm.scheduler.yieldToOthers("Object.wait");
                return null;
            default:
                return NOT_HANDLED;
        }
    }

    private static Object cloneArray(Object array) {
        int length = java.lang.reflect.Array.getLength(array);
        Object copy = java.lang.reflect.Array.newInstance(array.getClass().getComponentType(), length);
        System.arraycopy(array, 0, copy, 0, length);
        return copy;
    }

    private static String hostOwner(String owner, GuestClass receiverClass) {
        // When bytecode names a guest class but no guest class implements the method, it must
        // come from the nearest JDK ancestor.
        return receiverClass.repository.contains(owner) ? receiverClass.hostSuperName() : owner;
    }

    // ------------------------------------------------------------------ method handles

    /**
     * Invokes a constant-pool method handle with all arguments in one array (the receiver, if
     * any, first). Used for lambdas, method references and bootstrap methods.
     */
    public Object invokeHandle(Handle handle, Object[] all) {
        String owner = handle.getOwner();
        switch (handle.getTag()) {
            case Opcodes.H_INVOKESTATIC:
                return invokeStatic(owner, handle.getName(), handle.getDesc(), all);
            case Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE:
                return invokeVirtual(owner, handle.getName(), handle.getDesc(), all[0], tail(all));
            case Opcodes.H_INVOKESPECIAL:
                return invokeSpecial(owner, handle.getName(), handle.getDesc(), all[0], tail(all));
            case Opcodes.H_NEWINVOKESPECIAL:
                return vm.instantiate(owner, handle.getDesc(), all);
            default:
                return fieldHandle(handle, all);
        }
    }

    private Object fieldHandle(Handle handle, Object[] all) {
        GuestClass cls = vm.classes.find(handle.getOwner());
        FieldRef ref = cls == null ? null : cls.resolveField(handle.getName(), handle.getDesc());
        int opcode = switch (handle.getTag()) {
            case Opcodes.H_GETFIELD -> Opcodes.GETFIELD;
            case Opcodes.H_PUTFIELD -> Opcodes.PUTFIELD;
            case Opcodes.H_GETSTATIC -> Opcodes.GETSTATIC;
            default -> Opcodes.PUTSTATIC;
        };
        FieldInsnNode insn = new FieldInsnNode(opcode, handle.getOwner(), handle.getName(), handle.getDesc());
        switch (opcode) {
            case Opcodes.GETFIELD:
                return vm.fields.get(all[0], ref, insn);
            case Opcodes.PUTFIELD:
                vm.fields.put(all[0], ref, insn, all[1]);
                return null;
            case Opcodes.GETSTATIC:
                return ref != null ? vm.fields.getStatic(ref)
                        : vm.fields.getHostStatic(insn.owner, insn.name, insn.desc);
            default:
                if (ref != null) {
                    vm.fields.putStatic(ref, all[0]);
                } else {
                    vm.fields.putHostStatic(insn.owner, insn.name, insn.desc, all[0]);
                }
                return null;
        }
    }

    private static Object[] tail(Object[] all) {
        Object[] rest = new Object[all.length - 1];
        System.arraycopy(all, 1, rest, 0, rest.length);
        return rest;
    }

    private static String describeArgs(Object[] args) {
        StringBuilder text = new StringBuilder("(");
        for (int i = 0; i < args.length; i++) {
            text.append(i == 0 ? "" : ", ").append(Values.describe(args[i]));
        }
        return text.append(')').toString();
    }
}
