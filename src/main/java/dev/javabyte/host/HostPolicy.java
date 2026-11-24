package dev.javabyte.host;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which real JDK members guest code may call directly.
 *
 * <p>The model is deny-by-default: a member can be used only if a rule allows it and no rule
 * denies it. The emulator's safety rests on this list containing only side-effect-free code
 * (strings, collections, maths, cryptography, in-memory streams). Anything that could touch the
 * disk, the network or another process is either replaced by a stub or left off the list.
 *
 * <p>Rule syntax, one per line ({@code #} at the start of a line begins a comment):
 * <pre>
 *   java/lang/String                 every member of one class
 *   java/util/**                     every class in a package tree
 *   java/lang/*Exception             * matches within one name segment
 *   java/lang/Thread#getName,setName only the listed members ("&lt;init&gt;" is the constructor)
 *   java/io/PrintStream#&lt;init&gt;(Ljava/lang/String;)V   one exact overload
 *   !java/util/Timer                 deny; a deny always wins over an allow
 * </pre>
 */
public final class HostPolicy {

    /** One parsed rule line. An empty member set means "all members". */
    private record Rule(Pattern classPattern, Set<String> members) {

        boolean matchesClass(String owner) {
            return classPattern.matcher(owner).matches();
        }

        boolean matchesMember(String name, String desc) {
            return members.isEmpty() || members.contains(name) || (desc != null && members.contains(name + desc));
        }
    }

    private final List<Rule> allow = new ArrayList<>();
    private final List<Rule> deny = new ArrayList<>();
    private final Map<String, Boolean> cache = new HashMap<>();

    /** Loads the built-in list shipped with the emulator. */
    public static HostPolicy defaults() {
        HostPolicy policy = new HostPolicy();
        try (InputStream in = HostPolicy.class.getResourceAsStream("/dev/javabyte/default-policy.txt")) {
            if (in == null) {
                throw new IllegalStateException("default-policy.txt is missing from the build");
            }
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().forEach(policy::addLine);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return policy;
    }

    /** Adds one rule line; blank lines and comments are ignored. */
    public void addLine(String line) {
        String rule = line.strip();
        if (rule.isEmpty() || rule.startsWith("#")) {
            return;
        }
        boolean isDeny = rule.startsWith("!");
        if (isDeny) {
            rule = rule.substring(1).strip();
        }
        int hash = rule.indexOf('#');
        String classGlob = hash < 0 ? rule : rule.substring(0, hash);
        Set<String> members = new HashSet<>();
        if (hash >= 0) {
            for (String member : rule.substring(hash + 1).split(",")) {
                members.add(member.strip());
            }
        }
        (isDeny ? deny : allow).add(new Rule(globToPattern(classGlob.strip()), members));
        cache.clear();
    }

    /** Adds an allow rule from the config ({@code policy.allow}); same syntax as a line of the policy file. */
    public void allow(String rule) {
        addLine(rule);
    }

    /** Adds a deny rule from the config ({@code policy.deny}); a deny always wins over an allow. */
    public void deny(String rule) {
        addLine("!" + rule);
    }

    /**
     * @param desc method descriptor, or null when checking a field or "any overload"
     * @return true if guest code may use this member of the real JDK class
     */
    public boolean allows(String owner, String name, String desc) {
        return cache.computeIfAbsent(owner + '#' + name + (desc == null ? "" : desc),
                key -> matches(allow, owner, name, desc) && !matches(deny, owner, name, desc));
    }

    /**
     * True if a deny rule covers this member. Unlike {@link #allows}, this says nothing about
     * allow rules: the router uses it to apply a deny rule on the class an object really has
     * when the call site named some other, allowed, class.
     */
    public boolean denies(String owner, String name, String desc) {
        return matches(deny, owner, name, desc);
    }

    /** True if at least one member of the class is usable, i.e. the class is not wholly off-limits. */
    public boolean knowsClass(String owner) {
        return allow.stream().anyMatch(rule -> rule.matchesClass(owner))
                && deny.stream().noneMatch(rule -> rule.matchesClass(owner) && rule.members().isEmpty());
    }

    private static boolean matches(List<Rule> rules, String owner, String name, String desc) {
        for (Rule rule : rules) {
            if (rule.matchesClass(owner) && rule.matchesMember(name, desc)) {
                return true;
            }
        }
        return false;
    }

    private static Pattern globToPattern(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                boolean doubled = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                regex.append(doubled ? ".*" : "[^/]*");
                if (doubled) {
                    i++;
                }
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
