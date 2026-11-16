package dev.javabyte;

import dev.javabyte.plugin.EmulatorPlugin;
import dev.javabyte.vm.VirtualMachine;
import java.util.Map;

/** A plugin used by the tests: it supplies the result of one native method from its settings. */
public final class TestNativePlugin implements EmulatorPlugin {

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
}
