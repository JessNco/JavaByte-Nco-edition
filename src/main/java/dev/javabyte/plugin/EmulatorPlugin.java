package dev.javabyte.plugin;

import dev.javabyte.vm.VirtualMachine;
import java.util.Map;

/**
 * Extension point for behaviour that is specific to one malware family or one analysis need.
 *
 * <p>The core emulator stays generic; anything that knows about a particular sample (a custom
 * native bridge, canned protocol replies, family-specific hooks) belongs in a plugin. Plugins
 * are discovered with {@link java.util.ServiceLoader} and are opt-in: one is installed only if
 * the config has an entry for its id under {@code plugins}.
 *
 * <p>A plugin typically registers stubs on {@code vm.stubs}, adds hooks through
 * {@code vm.hooks}, widens or narrows {@code vm.policy}, and reports through {@code vm.events}.
 */
public interface EmulatorPlugin {

    /** Stable identifier used as the key under {@code plugins} in the config. */
    String id();

    /**
     * Called once, after the built-in stubs are installed and before the sample is loaded.
     *
     * @param settings the plugin's section of the config; never null
     */
    void install(VirtualMachine vm, Map<String, Object> settings);
}
