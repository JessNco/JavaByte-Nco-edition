package dev.javabyte.host;

import dev.javabyte.vm.GuestThrow;
import dev.javabyte.vm.VirtualMachine;
import org.objectweb.asm.Type;

/** Everything a {@link Stub} needs to know about the call it is replacing. */
public final class CallContext {

    public final VirtualMachine vm;
    /** Internal name of the JDK class the call resolved to. */
    public final String owner;
    public final String name;
    public final String desc;
    /** Null for static calls and constructors. */
    public final Object receiver;
    public final Object[] args;
    /** True for a non-virtual call ({@code super.method()}). */
    public final boolean special;

    public CallContext(VirtualMachine vm, String owner, String name, String desc, Object receiver,
            Object[] args, boolean special) {
        this.vm = vm;
        this.owner = owner;
        this.name = name;
        this.desc = desc;
        this.receiver = receiver;
        this.args = args;
        this.special = special;
    }

    /**
     * Performs the real JDK call. Used by stubs that only observe a call (for example to log a
     * cipher key) or that handle some receivers themselves and pass the rest through.
     * The stub takes responsibility for the call being safe: policy is not consulted again.
     *
     * <p>One check is repeated, because a stub registered for every overload of a name cannot
     * be expected to know them all: an overload declared to take a file, a path or a class
     * loader is refused here as the router refuses it (see
     * {@link dev.javabyte.vm.InvocationRouter#refusesParameters}). Such an overload acts on
     * the real disk, or asks the loader for classes from inside JDK code, whatever else the
     * stub checked.
     */
    public Object proceed() {
        if (dev.javabyte.vm.InvocationRouter.refusesParameters(owner, desc)) {
            Object neutral = vm.router.blocked(owner, name, desc, "<init>".equals(name) ? "constructor" : "method");
            return "<init>".equals(name) ? new dev.javabyte.model.PhantomObject(owner) : neutral;
        }
        return "<init>".equals(name)
                ? vm.host.construct(owner, desc, args)
                : vm.host.invoke(owner, name, desc, receiver, args, special);
    }

    public Object arg(int index) {
        return args[index];
    }

    public int intArg(int index) {
        return (Integer) args[index];
    }

    public boolean boolArg(int index) {
        return (Integer) args[index] != 0;
    }

    public String stringArg(int index) {
        return (String) args[index];
    }

    public int argCount() {
        return args.length;
    }

    public Type returnType() {
        return Type.getReturnType(desc);
    }

    /** Wraps a host exception so it is delivered to the guest with a guest stack trace. */
    public GuestThrow raise(Throwable throwable) {
        return vm.raise(throwable);
    }

    public void event(String category, String action, Object... keyValues) {
        vm.events.add(category, action, keyValues);
    }

    public String signature() {
        return owner + '.' + name + desc;
    }
}
