package dev.javabyte;

import dev.javabyte.plugin.EmulatorPlugin;
import dev.javabyte.vm.VirtualMachine;
import java.util.Map;

/** A plugin used by the tests: it supplies the result of one native method from its settings. */
public final class TestNativePlugin implements EmulatorPlugin {

    /**
     * How many times any instance has been closed. Tests read it to check that the emulator
     * closes plugins on every path out of a run; tests do not run in parallel.
     */
    static volatile int closed;

    @Override
    public String id() {
        return "test-native";
    }

    @Override
    public void install(VirtualMachine vm, Map<String, Object> settings) {
        int factor = ((Number) settings.getOrDefault("factor", 1)).intValue();
        vm.natives.register("dev/javabyte/guest/NativeUser.scramble*",
                (machine, method, receiver, args) -> (Integer) args[0] * factor);
    }

    /** Counts the call in {@link #closed}. */
    @Override
    public void close() {
        closed++;
    }
}
