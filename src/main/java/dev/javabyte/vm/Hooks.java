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
 * matches any run of characters. They are matched once per method when its class is loaded, so
 * hooks cost nothing on the call path of methods they do not match.
 */
public final class Hooks {

    /** A compiled hook. {@code action} is one of return, skip, throw, trace. */
    public record Hook(Pattern pattern, String action, Object value) {}

    private final List<Hook> hooks = new ArrayList<>();
    private final List<Pattern> traceCalls = new ArrayList<>();
    private final List<Pattern> traceInstructions = new ArrayList<>();
    private final Map<String, Optional<Hook>> hostCache = new HashMap<>();

    Hooks(EmulatorConfig config) {
        for (EmulatorConfig.Hook hook : config.hooks) {
            if ("trace".equals(hook.action)) {
                traceCalls.add(glob(hook.method));
            } else {
                hooks.add(new Hook(glob(hook.method), hook.action, hook.value));
            }
        }
        config.trace.methods.forEach(pattern -> traceCalls.add(glob(pattern)));
        config.trace.instructions.forEach(pattern -> traceInstructions.add(glob(pattern)));
    }

    /** Adds a hook programmatically; used by plugins. */
    public void add(String methodGlob, String action, Object value) {
        hooks.add(new Hook(glob(methodGlob), action, value));
        hostCache.clear();
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

    private static boolean matches(Pattern pattern, String owner, String name, String desc) {
        String shortForm = owner + '.' + name;
        return pattern.matcher(shortForm).matches() || pattern.matcher(shortForm + desc).matches();
    }

    /** Converts a glob to a regular expression: {@code *} is any text, {@code ?} one character. */
    static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            regex.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(regex.toString());
    }
}
