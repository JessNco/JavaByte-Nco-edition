package dev.javabyte.vm;

import dev.javabyte.host.CallContext;
import dev.javabyte.host.HostBridge;
import dev.javabyte.host.Stub;
import dev.javabyte.model.FieldRef;
import dev.javabyte.model.GuestClass;
import dev.javabyte.model.GuestLambda;
import dev.javabyte.model.GuestMethod;
import dev.javabyte.model.GuestProxy;
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
 * boundary for code. JDK code runs on the guest's behalf in three places only, all decided
 * here: a method through {@link #hostCall}, a constructor through {@link #constructHost}, and
 * the superclass constructor of a sample's own subclass through {@link #construct}. A stub
 * may pass its call on to the JDK ({@link CallContext#proceed}); that is the stub's
 * decision, made after this class chose the stub. The one other route to the JDK is field
 * access, which goes through {@link Fields}; it explains why that route needs less checking.
 *
 * <p>For each call the order of precedence is:
 * <ol>
 *   <li>a config hook on the method;
 *   <li>the sample's own bytecode, if an interpreted class implements the method;
 *   <li>a stub, which replaces a JDK member with a safe simulation;
 *   <li>the real JDK member, if the host policy allows it;
 *   <li>otherwise the call is blocked and reported.
 * </ol>
 * Steps 3 to 5 are {@link #hostCall}, which spells them out: there is no verifier, so the class
 * a call names is only the sample's claim, and the receiver's real class is consulted too.
 *
 * <p>Reflection, method handles and lambdas are all funnelled back through the same entry
 * points, so none of them can be used to side-step the order above.
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

    /** {@code invokestatic}: the sample's own static method, or else a call on the JDK class it names or inherits from. */
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

    /**
     * {@code invokevirtual} and {@code invokeinterface}. What the receiver is decides, in this
     * order: an instance of an interpreted class (its own method, then {@code Object}'s, then
     * its JDK ancestor's), a guest lambda, a guest proxy, an array, a guest lambda behind a
     * JDK proxy, and finally any other host object, which goes to {@link #hostCall}.
     */
    public Object invokeVirtual(String owner, String name, String desc, Object receiver, Object[] args) {
        if (receiver == null) {
            throw vm.implicitExceptions.nullPointer("Cannot invoke \"" + owner.replace('/', '.') + '.' + name
                    + "()\" because value is null");
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
        if (receiver instanceof GuestProxy proxy) {
            return invokeProxy(proxy, name, desc, args);
        }
        if (owner.charAt(0) == '[') {
            // Methods "of" an array type are Object's, except clone(), which arrays make public.
            if (name.equals("clone")) {
                return cloneArray(receiver);
            }
            return hostCall("java/lang/Object", name, desc, receiver, args, false);
        }
        GuestLambda behindProxy = vm.indy.lambdaBehind(receiver);
        if (behindProxy != null && name.equals(behindProxy.methodName())) {
            // A guest lambda for a JDK interface, called from guest code. Going straight to it
            // skips a round trip through the JDK proxy, which also could not carry arguments
            // that are emulator stand-ins (a method mirror handed to an InvocationHandler).
            return invokeLambda(behindProxy, name, desc, args);
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

    /**
     * Calls a method on a proxy for interpreted interfaces by handing it to the sample's
     * invocation handler, with boxed arguments and a reflective description of the method.
     */
    public Object invokeProxy(GuestProxy proxy, String name, String desc, Object[] args) {
        Object method = null;
        for (Object mirror : proxy.interfaces()) {
            if (mirror instanceof GuestClass iface) {
                for (GuestClass current : iface.selfAndSuperInterfaces()) {
                    GuestMethod declared = current.declared(name, desc);
                    if (declared != null) {
                        method = new Mirrors.MethodMirror(declared);
                    }
                }
            } else if (method == null) {
                method = findPublicMethod((Class<?>) mirror, name, desc);
            }
        }
        if (method == null) {
            // toString, hashCode and equals are routed to the handler too, as the JDK does.
            // The rest of Object (getClass, wait, notify) is final and never reaches a handler.
            boolean routed = name.equals("toString") || name.equals("hashCode") || name.equals("equals");
            if (!routed) {
                return hostCall("java/lang/Object", name, desc, proxy, args, false);
            }
            method = findPublicMethod(Object.class, name, desc);
        }
        Type[] types = Type.getArgumentTypes(desc);
        Object[] boxed = args.length == 0 ? null : new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            boxed[i] = Values.box(args[i], types[i]);
        }
        Object result = invokeVirtual("java/lang/reflect/InvocationHandler", "invoke",
                "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;",
                proxy.handler(), new Object[] {proxy, method, boxed});
        return Values.unbox(result, Type.getReturnType(desc));
    }

    /** The public method with exactly this name and descriptor, for the reflective description a proxy's handler gets. */
    private static java.lang.reflect.Method findPublicMethod(Class<?> type, String name, String desc) {
        for (java.lang.reflect.Method candidate : type.getMethods()) {
            if (candidate.getName().equals(name) && Type.getMethodDescriptor(candidate).equals(desc)) {
                return candidate;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ special (super and private)

    /** {@code invokespecial} other than a constructor: a {@code super.method()} or private call, never dispatched on the receiver's class. */
    public Object invokeSpecial(String owner, String name, String desc, Object receiver, Object[] args) {
        if (receiver == null) {
            throw vm.implicitExceptions.nullPointer("Cannot invoke \"" + owner.replace('/', '.') + '.' + name
                    + "()\" because value is null");
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
        if (pending.state == null) {
            pending.resolved = constructHost(owner, desc, args);
            return;
        }
        // A sample's subclass of a JDK class: its super(...) call runs the JDK constructor for
        // real, inside the generated shell. That constructor is judged exactly as if the sample
        // had written "new" on the JDK class: class PrintStream being allowed says nothing
        // about PrintStream(String fileName), which opens a file. No stub can stand in here,
        // because the object must be the shell itself, so a constructor that is not allowed
        // is not run: the class is built as if that superclass were unavailable.
        // A simulated base's constructors are the emulator's own code (SimBaseClassLoader and the
        // like), so there is no JDK constructor to judge.
        boolean permitted = vm.shells.hasSimulatedBase(pending.state.type) || mayRun(owner, "<init>", desc);
        if (!permitted) {
            blocked(owner, "<init>", desc, "constructor");
        }
        pending.resolved = vm.shells.instantiate(pending.state, permitted ? owner : null, desc, args);
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
        if (mayRun(owner, "<init>", desc)) {
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
            return callNative(method, receiver, args);
        }
        if (method.isAbstract) {
            throw vm.raise(new AbstractMethodError(method.id));
        }
        if (method.traceCalls) {
            vm.events.writeTrace("-> " + method.id + describeArgs(args));
        }
        Object result;
        if (method.isSynchronized) {
            // A synchronized method holds its receiver's monitor (its class's, if static).
            Object lock = method.isStatic ? method.owner : receiver;
            vm.scheduler.monitorEnter(lock);
            try {
                result = vm.interpreter.execute(method, receiver, args);
            } finally {
                vm.scheduler.monitorExit(lock);
            }
        } else {
            result = vm.interpreter.execute(method, receiver, args);
        }
        if (method.traceCalls) {
            vm.events.writeTrace("<- " + method.id + " = " + Values.describe(result));
        }
        if (result instanceof String text && !text.isEmpty()) {
            recordString(method, text);
        }
        return result;
    }

    /**
     * Handles a call to a {@code native} method. A plugin's handler runs if one is registered.
     * Otherwise there is no machine code to run: the call is reported, with its arguments and
     * the library that implements it if known, and answered with a zero value.
     */
    private Object callNative(GuestMethod method, Object receiver, Object[] args) {
        dev.javabyte.plugin.NativeHandler handler = vm.natives.find(method);
        if (handler != null) {
            Object result = handler.call(vm, method, receiver, args);
            if (result != dev.javabyte.plugin.NativeHandler.NOT_HANDLED) {
                vm.events.addOnce(method.id, "native", "handled", "method", method.id);
                return result;
            }
        }
        vm.events.addOnce(method.id, "native", "call", "method", method.id,
                "firstArguments", args.length == 0 ? null : describeArgs(args),
                "implementedBy", vm.natives.implementedBy(method),
                "result", method.returnType.getSort() == Type.VOID ? "not emulated"
                        : "not emulated; returned " + Values.describe(Values.zero(method.returnType)));
        return Values.zero(method.returnType);
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
                "method", method.id, "value", Values.truncate(text, vm.config.trace.previewLength));
    }

    // ------------------------------------------------------------------ the host boundary

    /**
     * Performs a call whose implementation is not guest bytecode. In order:
     * <ol>
     *   <li>{@code clone()} on an array;
     *   <li>refusal of the emulator's own generated members ({@code javabyte$...});
     *   <li>a config hook;
     *   <li>a stub registered for the named class or one of its supertypes;
     *   <li>a stub found from the receiver's real class;
     *   <li>phantom, plain-object and mirror receivers, answered neutrally;
     *   <li>a simulated receiver: its own methods run, JDK methods it merely inherits need the policy;
     *   <li>a class the analysis JDK lacks, answered neutrally;
     *   <li>a deny rule on the receiver's real class;
     *   <li>the policy for the named class, only if the method resolves there and the receiver
     *       is an instance of the class declaring it;
     *   <li>an allow-listed supertype of the receiver that has the method;
     *   <li>blocked.
     * </ol>
     * Wherever the policy is asked (steps 7, 10 and 11), a member declared to take a
     * {@code File}, a {@code Path} or a {@code ClassLoader} is refused as well.
     *
     * @param owner   internal name of the JDK class the call site names (a guest class already
     *                mapped to its JDK ancestor); a claim, not a fact about the receiver
     * @param special true for a non-virtual call
     */
    public Object hostCall(String owner, String name, String desc, Object receiver, Object[] args, boolean special) {
        if (receiver != null && receiver.getClass().isArray() && name.equals("clone") && desc.equals("()Ljava/lang/Object;")) {
            // An array's clone() is public whatever class the call names. Kotlin, older
            // compilers and code shrinkers name java/lang/Object, whose clone() is protected and
            // native, so it cannot be called from here; every route arrives at this point.
            return cloneArray(receiver);
        }
        if (name.startsWith(HostBridge.EMULATOR_MEMBER_PREFIX)) {
            // The accessors generated on shell classes are the emulator's own plumbing. They are
            // public so that the host bridge can call them, never so that a sample can.
            return blocked(owner, name, desc, "method");
        }
        Hooks.Hook hook = vm.hooks.forHost(owner, name, desc);
        if (hook != null) {
            return applyHook(hook, owner + '.' + name + desc, Type.getReturnType(desc));
        }
        Stub stub = vm.stubs.find(owner, name, desc, vm.host::findClass);
        if (stub != null) {
            return runStub(stub, new CallContext(vm, owner, name, desc, receiver, args, special));
        }
        // The class named in the bytecode is the sample's claim about the receiver, and nothing
        // has verified it. What decides is the object actually in hand: a stub or a deny rule
        // on its real class applies whatever name the call site used.
        ReceiverRules byReceiver = receiver == null ? null : receiverRules(owner, receiver, name, desc);
        if (byReceiver != null && byReceiver.stub() != null) {
            return runStub(byReceiver.stub(), new CallContext(vm, byReceiver.stubOwner(), name, desc, receiver, args, special));
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
            // "Safe by construction" covers the methods the simulation itself declares. One it
            // merely inherits from the JDK class it extends (a protected or final method it
            // could not override) is real JDK code, and is judged as a call on that class.
            String inherited = vm.host.jdkDeclarer(owner, name, desc, receiver, special);
            if (inherited != null && !mayRun(inherited, name, desc)) {
                return blocked(inherited, name, desc, "method");
            }
            traceHostCall(owner, name, desc, args);
            return vm.host.invoke(owner, name, desc, receiver, args, special);
        }
        if (vm.host.findClass(owner) == null) {
            vm.events.addOnce(owner, "gap", "missing-class", "class", owner);
            vm.events.addOnce(owner + name + desc, "policy", "phantom-call", "method", owner + '.' + name + desc);
            return neutralResult(desc);
        }
        if (byReceiver != null && byReceiver.denied()) {
            return blocked(byReceiver.stubOwner(), name, desc, "method");
        }
        // The policy's answer for the named class counts only if the method really is that
        // class's and the receiver really is an instance of it. Otherwise a call site naming
        // an allowed class (String, say) with some other object underneath would be approved
        // for the name and then run whatever same-named method the object has.
        if (vm.host.resolvesOn(owner, name, desc, receiver) && mayRun(owner, name, desc)) {
            traceHostCall(owner, name, desc, args);
            return vm.host.invoke(owner, name, desc, receiver, args, special);
        }
        String viaReceiver = receiver == null ? null : allowedSupertype(receiver.getClass(), name, desc);
        if (viaReceiver != null && !refusesParameters(viaReceiver, desc)) {
            traceHostCall(viaReceiver, name, desc, args);
            return vm.host.invoke(viaReceiver, name, desc, receiver, args, special);
        }
        return blocked(owner, name, desc, "method");
    }

    /**
     * What the receiver's real class says about a call, beyond what the named class said.
     *
     * @param stub      a stub registered on a JDK class or interface of the receiver, or null
     * @param stubOwner internal name of the receiver's JDK class the answer was found through
     * @param denied    true if a deny rule covers this member on one of the receiver's JDK types
     */
    private record ReceiverRules(Stub stub, String stubOwner, boolean denied) {}

    private static final ReceiverRules NO_RECEIVER_RULES = new ReceiverRules(null, null, false);

    private final java.util.Map<Class<?>, java.util.Map<String, ReceiverRules>> receiverRulesCache =
            new java.util.IdentityHashMap<>();

    /**
     * Looks the call up by the receiver's real class.
     *
     * <p>There is no verifier, so the receiver need not be an instance of the class the call
     * names, and even valid bytecode may name a supertype: {@code Object.hashCode()} on a
     * {@code URL}, {@code Runnable.run()} on a {@code Thread}. Stubs and deny rules are keyed
     * by class, so each must also be looked up from the object itself or naming another class
     * would be a way round it.
     *
     * <p>Emulator objects are left out. Simulations are safe by construction and override what
     * they inherit, and mirrors and phantoms have no JDK class of their own to consult.
     *
     * @return null when the receiver's class adds nothing to what the named class decided
     */
    private ReceiverRules receiverRules(String owner, Object receiver, String name, String desc) {
        Class<?> receiverClass = receiver.getClass();
        if (receiverClass == vm.host.findClass(owner) || receiver instanceof PhantomObject || receiver instanceof PlainObject
                || isMirror(receiver) || HostBridge.isSimulated(receiver)) {
            return null;
        }
        ReceiverRules rules = receiverRulesCache.computeIfAbsent(receiverClass, unused -> new java.util.HashMap<>())
                .computeIfAbsent(name.concat(desc), unused -> findReceiverRules(receiverClass, name, desc));
        return rules == NO_RECEIVER_RULES ? null : rules;
    }

    private ReceiverRules findReceiverRules(Class<?> receiverClass, String name, String desc) {
        Stub stub = null;
        String stubOwner = null;
        boolean denied = false;
        for (Class<?> current : dev.javabyte.host.StubRegistry.supertypes(receiverClass)) {
            String internalName = Type.getInternalName(current);
            if (vm.host.findClass(internalName) != null) {
                if (stub == null) {
                    // The registry walks this class's own supertypes, so the first JDK class
                    // reached on each branch is enough to ask.
                    stub = vm.stubs.find(internalName, name, desc, vm.host::findClass);
                    stubOwner = stub == null ? null : internalName;
                }
                denied |= vm.policy.denies(internalName, name, desc);
            }
        }
        if (stub == null && !denied) {
            return NO_RECEIVER_RULES;
        }
        return new ReceiverRules(stub, stub != null ? stubOwner : nearestJdkClass(receiverClass), denied);
    }

    /** Internal name of the nearest JDK class of a class, for reports. */
    private String nearestJdkClass(Class<?> cls) {
        Class<?> current = cls;
        while (current != null && vm.host.findClass(Type.getInternalName(current)) == null) {
            current = current.getSuperclass();
        }
        return current == null ? "java/lang/Object" : Type.getInternalName(current);
    }

    /** Answers of {@link #allowedSupertype}, by receiver class and method signature. */
    private final java.util.Map<String, java.util.Optional<String>> allowedSupertypeCache = new java.util.HashMap<>();

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
        return allowedSupertypeCache.computeIfAbsent(receiverClass.getName() + '.' + name + desc, key -> {
            for (Class<?> current : dev.javabyte.host.StubRegistry.supertypes(receiverClass)) {
                String internalName = Type.getInternalName(current);
                if (java.lang.reflect.Modifier.isPublic(current.getModifiers()) && vm.host.findClass(internalName) != null
                        && vm.policy.allows(internalName, name, desc) && declares(current, name, desc)) {
                    return java.util.Optional.of(internalName);
                }
            }
            return java.util.Optional.empty();
        }).orElse(null);
    }

    /** True if the class has a public method of this name taking these parameters, whatever it returns. */
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

    /** JDK classes that only store a class loader they are given, never asking it for anything. */
    private static final java.util.Set<String> LOADER_INERT_OWNERS = java.util.Set.of("java/security/ProtectionDomain");

    /**
     * A JDK method declared to take a class loader would ask that loader for classes or
     * resources from inside host code, where no stub sees the request, and might hand classes
     * to the JDK to construct. {@code ResourceBundle.Control.newBundle} is one such method.
     * Every loader the guest can hold is simulated and answers such requests only from the
     * guest's world (see {@link dev.javabyte.shell.ShellFactory}'s simulated bases); this
     * check is the second line of defence, so the request is not made at all. Calls the stubs
     * handle are unaffected, since stubs run before this check.
     */
    private static boolean passesClassLoader(String owner, String desc) {
        return desc.substring(0, desc.indexOf(')')).contains("Ljava/lang/ClassLoader;")
                && !LOADER_INERT_OWNERS.contains(owner);
    }

    /**
     * The one test for running a JDK member for real: the policy allows it, and it is not
     * declared to take a file, a path or a class loader ({@link #refusesParameters}). Every
     * place that runs JDK code on the policy's word asks here, so none can forget a part.
     */
    private boolean mayRun(String owner, String name, String desc) {
        return vm.policy.allows(owner, name, desc) && !refusesParameters(owner, desc);
    }

    /**
     * True if a JDK member with this descriptor must not be run for real whatever the policy
     * says of it: it is declared to take a file or path ({@link #touchesFileSystem}) or a
     * class loader ({@link #passesClassLoader}). For code outside the router that performs a
     * JDK call itself, such as a stub passing a call through.
     */
    public static boolean refusesParameters(String owner, String desc) {
        return touchesFileSystem(owner, desc) || passesClassLoader(owner, desc);
    }

    private static boolean isMirror(Object value) {
        return value instanceof GuestClass || value instanceof Mirrors.MethodMirror
                || value instanceof Mirrors.FieldMirror || value instanceof Mirrors.ConstructorMirror
                || value instanceof Mirrors.LookupMirror || value instanceof GuestLambda || value instanceof GuestProxy;
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
        boolean jdkBase = state.type.shellClass != null
                && dev.javabyte.shell.ShellFactory.jdkBase(state.type.shellClass) != Object.class;
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
                return cloneGuest(receiver, state, desc, args);
            case "finalize()V":
                return null;
            case "notify()V", "notifyAll()V":
                vm.scheduler.objectNotify(receiver, name.equals("notifyAll"));
                return null;
            case "wait()V":
                vm.scheduler.objectWait(receiver, 0);
                return null;
            case "wait(J)V", "wait(JI)V":
                vm.scheduler.objectWait(receiver, (Long) args[0]);
                return null;
            default:
                return NOT_HANDLED;
        }
    }

    /**
     * {@code clone()} on a guest object whose class does not override it, or {@code super.clone()}
     * from one that does. {@code Object.clone()} is protected and native, so only the object's
     * own class can run it; the shell class generated for a guest class has a public accessor
     * that does ({@code javabyte$super$clone}), and that also gives the copy its own guest
     * fields (see {@code ShellDispatch.cloned}).
     *
     * <p>A class that is not {@code Cloneable} gets {@code CloneNotSupportedException} naming
     * the guest class, as on a real JVM; letting the JDK throw it would name the shell instead.
     * That check applies only where the JDK's clone would be {@code Object}'s own: a JDK
     * superclass with its own {@code clone()} decides for itself.
     */
    private Object cloneGuest(Object receiver, InstanceState state, String desc, Object[] args) {
        if (receiver instanceof PlainObject) {
            // A plain object has no JDK supertypes at all, so it is never Cloneable.
            throw vm.raise(new CloneNotSupportedException(state.type.dottedName()));
        }
        if (!(receiver instanceof Cloneable) && jdkCloneIsObjects(receiver.getClass())) {
            throw vm.raise(new CloneNotSupportedException(state.type.dottedName()));
        }
        return hostCall("java/lang/Object", "clone", desc, receiver, args, true);
    }

    /** Whether the {@code clone()} a shell inherits from the JDK is {@code Object}'s own. */
    private static boolean jdkCloneIsObjects(Class<?> shell) {
        for (Class<?> current = shell.getSuperclass(); current != null && current != Object.class; current = current.getSuperclass()) {
            for (java.lang.reflect.Method method : current.getDeclaredMethods()) {
                if (method.getName().equals("clone") && method.getParameterCount() == 0) {
                    return false;
                }
            }
        }
        return true;
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
                    vm.fields.putHostStatic(insn.owner, insn.name, all[0]);
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
