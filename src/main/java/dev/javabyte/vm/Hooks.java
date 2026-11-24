package dev.javabyte.vm;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.model.GuestClass;
import dev.javabyte.model.GuestMethod;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Config-driven per-method overrides and trace selections.
 *
 * <p>Patterns are globs over {@code owner.name} or {@code owner.name(desc)}, where {@code *}
 * matches any run of characters and {@code ?} matches one character. They are matched once per
 * method when its class is loaded, so hooks cost nothing on the call path of methods they do not
 * match.
 */
public final class Hooks {

    /**
     * A compiled hook. {@code action} is one of return, skip, throw. A trace request is not a
     * hook: it only marks matching methods, so it is kept in a list of its own.
     */
    public record Hook(Pattern pattern, String action, Object value) {}

    private final List<Hook> hooks = new ArrayList<>();
    private final List<Pattern> traceCalls = new ArrayList<>();
    private final List<Pattern> traceInstructions = new ArrayList<>();
    private final Map<String, Optional<Hook>> hostCache = new HashMap<>();

    /** Reads the {@code hooks} section of the config into patterns. */
    Hooks(EmulatorConfig config) {
        for (EmulatorConfig.Hook hook : config.hooks) {
            register(hook.method, hook.action, hook.value);
        }
        config.trace.methods.forEach(pattern -> traceCalls.add(glob(pattern)));
        config.trace.instructions.forEach(pattern -> traceInstructions.add(glob(pattern)));
    }

    /**
     * Adds a hook programmatically; used by plugins. The action is one of return, skip, throw
     * or trace, as in the config.
     *
     * <p>Hooks are stamped onto a class's methods once, when the class loads, so a hook added
     * here reaches only classes loaded after the call (and every JDK method). Plugins add theirs
     * in {@link dev.javabyte.plugin.EmulatorPlugin#install}, before the sample is loaded.
     *
     * @throws IllegalArgumentException if the action is not one of the four above
     */
    public void add(String methodGlob, String action, Object value) {
        register(methodGlob, action, value);
        hostCache.clear();
    }

    /**
     * Files one hook under the right list. Shared by the config and by plugins so that both
     * treat an action the same way: a trace request must never become a hook, because any
     * hook action the router does not recognise skips the method and changes the behaviour
     * under analysis.
     */
    private void register(String methodGlob, String action, Object value) {
        switch (action == null ? "" : action) {
            case "trace" -> traceCalls.add(glob(methodGlob));
            case "return", "skip", "throw" -> hooks.add(new Hook(glob(methodGlob), action, value));
            default -> throw new IllegalArgumentException("Unknown hook action '" + action + "' for " + methodGlob
                    + ": use return, skip, throw or trace");
        }
    }

    /** Stamps matching hooks and trace flags onto the methods of a newly loaded class. */
    void attach(GuestClass cls) {
        for (GuestMethod method : cls.declaredMethods()) {
            method.hook = find(cls.name, method.name, method.desc);
            method.traceCalls = matchesAny(traceCalls, cls.name, method.name, method.desc);
            method.traceInstructions = matchesAny(traceInstructions, cls.name, method.name, method.desc);
        }
    }

    /** The hook for a JDK method, or null. Cached because it is consulted on every host call. */
    Hook forHost(String owner, String name, String desc) {
        if (hooks.isEmpty()) {
            return null;
        }
        return hostCache.computeIfAbsent(owner + '.' + name + desc,
                key -> Optional.ofNullable(find(owner, name, desc))).orElse(null);
    }

    private Hook find(String owner, String name, String desc) {
        for (Hook hook : hooks) {
            if (matches(hook.pattern(), owner, name, desc)) {
                return hook;
            }
        }
        return null;
    }

    private static boolean matchesAny(List<Pattern> patterns, String owner, String name, String desc) {
        for (Pattern pattern : patterns) {
            if (matches(pattern, owner, name, desc)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if a compiled glob matches a method in its short form ({@code owner.name}) or its
     * long form ({@code owner.name(desc)}). Shared with the native registry so that plugin
     * patterns behave exactly like config hooks.
     */
    public static boolean matches(Pattern pattern, String owner, String name, String desc) {
        String shortForm = owner + '.' + name;
        return pattern.matcher(shortForm).matches() || pattern.matcher(shortForm + desc).matches();
    }

    /**
     * Converts a glob to a regular expression: {@code *} is any text, {@code ?} one character,
     * and everything else stands for itself. Public so that every method pattern in the
     * emulator uses this one syntax.
     */
    public static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            regex.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(regex.toString());
    }
}
