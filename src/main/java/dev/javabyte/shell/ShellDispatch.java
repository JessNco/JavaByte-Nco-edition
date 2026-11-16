package dev.javabyte.shell;

import dev.javabyte.model.GuestInstance;
import dev.javabyte.model.GuestMethod;
import dev.javabyte.model.InstanceState;
import dev.javabyte.vm.GuestThrow;
import dev.javabyte.vm.Values;
import dev.javabyte.vm.VirtualMachine;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Static entry points that generated shell classes (and {@code PlainObject}) call to hand control
 * from real JDK code back to the interpreter.
 *
 * <p>Everything is static and takes plain {@code Object}s because it is called from generated
 * bytecode, where the simplest possible call shape keeps the generator small and verifiable.
 */
public final class ShellDispatch {

    private ShellDispatch() {}

    /**
     * States of shells whose JDK superclass constructor is currently running. The superclass
     * constructor may call an overridable method before the shell has been bound to its state;
     * the innermost entry here is the state that call belongs to.
     */
    private static final ThreadLocal<Deque<InstanceState>> CONSTRUCTING = ThreadLocal.withInitial(ArrayDeque::new);

    static void beginConstruction(InstanceState state) {
        CONSTRUCTING.get().push(state);
    }

    static void endConstruction() {
        CONSTRUCTING.get().pop();
    }

    /** Called by a shell's {@code javabyte$state()} while its state field is still unset. */
    public static InstanceState constructingState() {
        return CONSTRUCTING.get().peek();
    }

    /**
     * True if the guest class of {@code self} implements the method, so the call must go to the
     * interpreter rather than to the JDK superclass.
     *
     * <p>Returns false on any thread other than the emulator thread. Guest code must never run
     * concurrently, and a foreign thread (a JVM cleaner or a debugger rendering a variable)
     * should simply see default JDK behaviour.
     */
    public static boolean hasGuestImpl(Object self, String name, String desc) {
        if (VirtualMachine.current() == null) {
            return false;
        }
        InstanceState state = ((GuestInstance) self).javabyte$state();
        return state != null && state.type.findVirtual(name, desc) != null;
    }

    /**
     * Runs the guest implementation of a method on behalf of JDK code.
     *
     * @param hostArgs arguments as the JDK passed them (primitives boxed in their exact type)
     * @return the result in interpreter representation; use the {@code as*} helpers to narrow it
     */
    public static Object invoke(Object self, String name, String desc, Object[] hostArgs) {
        VirtualMachine vm = VirtualMachine.current();
        GuestMethod method = ((GuestInstance) self).javabyte$state().type.findVirtual(name, desc);
        Object[] args = new Object[hostArgs.length];
        for (int i = 0; i < args.length; i++) {
            args[i] = Values.unbox(hostArgs[i], method.argTypes[i]);
        }
        try {
            return vm.router.callGuest(method, self, args);
        } catch (GuestThrow thrown) {
            // JDK code between here and the interpreter expects ordinary exceptions; the
            // bridge re-wraps whatever reaches it.
            throw sneakyThrow(vm.markGuestOrigin(thrown.asThrowable()));
        }
    }

    /**
     * Corrects the text of an inherited {@code toString()}. JDK implementations such as
     * {@code Throwable.toString()} print {@code getClass().getName()}, which for a shell is the
     * generated class. The guest must see its own class name there instead.
     */
    public static String guestToString(Object self, String inherited) {
        InstanceState state = ((GuestInstance) self).javabyte$state();
        return state == null || inherited == null
                ? inherited : inherited.replace(self.getClass().getName(), state.type.dottedName());
    }

    /** Throws any exception without declaring it; generated methods have no throws clauses. */
    @SuppressWarnings("unchecked")
    public static <T extends Throwable> RuntimeException sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }

    // Narrowing helpers for generated return paths. A null result (possible when a hook skipped
    // the method) is treated as zero rather than failing inside JDK code.

    public static boolean asBoolean(Object value) {
        return value instanceof Integer i ? i != 0 : value instanceof Boolean b && b;
    }

    public static byte asByte(Object value) {
        return value instanceof Number n ? n.byteValue() : 0;
    }

    public static char asChar(Object value) {
        return value instanceof Integer i ? (char) i.intValue() : value instanceof Character c ? c : 0;
    }

    public static short asShort(Object value) {
        return value instanceof Number n ? n.shortValue() : 0;
    }

    public static int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    public static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    public static float asFloat(Object value) {
        return value instanceof Number n ? n.floatValue() : 0f;
    }

    public static double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0d;
    }
}
