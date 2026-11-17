package dev.javabyte.plugin;

import dev.javabyte.config.ConfigSchema;
import dev.javabyte.vm.VirtualMachine;
import java.util.List;
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

    /** What the plugin does, in one sentence, for {@code javabyte schema} and settings forms. */
    default String description() {
        return "";
    }

    /**
     * The settings the plugin reads from its section of the config, described like config
     * options ({@code path} relative to the section), so {@code javabyte schema} lists them and
     * the desktop app can build a form for them.
     */
    default List<ConfigSchema.Option> settings() {
        return List.of();
    }

    /**
     * Called once, after the built-in stubs are installed and before the sample is loaded.
     *
     * @param settings the plugin's section of the config; never null
     */
    void install(VirtualMachine vm, Map<String, Object> settings);

    /**
     * Called once when the run is over, whatever its outcome. A plugin that holds something
     * outside the JVM (a helper process, a temporary directory) releases it here.
     */
    default void close() {
    }
}
