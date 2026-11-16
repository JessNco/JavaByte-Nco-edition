package dev.javabyte.plugin;

import dev.javabyte.model.GuestMethod;
import dev.javabyte.vm.VirtualMachine;

/**
 * Supplies the behaviour of a {@code native} method.
 *
 * <p>The core cannot run machine code, but a plugin can stand in for it: by returning recorded
 * answers, by re-implementing a small routine in Java, or by forwarding the call to an external
 * native emulator. Register handlers with {@link VirtualMachine#natives}.
 */
@FunctionalInterface
public interface NativeHandler {

    /**
     * Returned by a handler that turns out not to cover this call after all. The emulator then
     * reports the call as not emulated, exactly as if no handler had been registered.
     */
    Object NOT_HANDLED = new Object();

    /**
     * @param receiver {@code this} for instance methods, null for static ones
     * @param args     arguments in interpreter representation (int-like values are Integers)
     * @return the result in interpreter representation; ignored for void methods
     */
    Object call(VirtualMachine vm, GuestMethod method, Object receiver, Object[] args);
}
