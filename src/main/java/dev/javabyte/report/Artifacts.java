package dev.javabyte.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Saves binary evidence (classes defined at run time, dropped files, network payloads) under
 * the output directory.
 *
 * <p>Saved files are inert copies for later study. Names come from the sample, so each one is
 * sanitised and confined to the output directory before anything is written.
 */
public final class Artifacts {

    private final Path root;
    private final List<String> saved = new ArrayList<>();
    /** Bytes that {@link #save} may still write; see the two-argument constructor. */
    private long budget;
    private int skipped;

    /** @param root output directory, or null to keep nothing (used by tests) */
    public Artifacts(Path root) {
        this(root, Long.MAX_VALUE);
    }

    /**
     * @param root   output directory, or null to keep nothing
     * @param budget the most bytes {@link #save} writes in all. Evidence is saved the moment
     *               the sample produces it (each class it defines, each request body it
     *               sends), so without a bound a sample in a loop could fill the analysis
     *               machine's disk within its time limit.
     */
    public Artifacts(Path root, long budget) {
        this.root = root;
        this.budget = budget;
    }

    /**
     * Saves evidence produced during the run, within the budget.
     *
     * @param group subdirectory, e.g. {@code defined-classes}
     * @param name  name suggested by the sample; may contain any characters
     * @return the path written, relative to the output directory, or null if nothing was
     *         written (no output directory, or the budget is used up; see {@link #skipped})
     */
    public String save(String group, String name, byte[] data) {
        if (root == null) {
            return null;
        }
        if (data.length > budget) {
            skipped++;
            return null;
        }
        budget -= data.length;
        return write(group, name, data);
    }

    /**
     * Saves a file the sample left on the virtual disk. These are not counted against the
     * budget: the virtual disk has its own bound ({@code limits.maxWrittenBytes}).
     */
    public String saveDropped(String name, byte[] data) {
        return root == null ? null : write("dropped", name, data);
    }

    /** How many times {@link #save} wrote nothing because the budget was used up. */
    public int skipped() {
        return skipped;
    }

    private String write(String group, String name, byte[] data) {
        Path target = root.resolve(group).resolve(sanitise(name)).normalize();
        if (!target.startsWith(root.resolve(group))) {
            // Belt and braces: sanitise() already removes traversal sequences.
            target = root.resolve(group).resolve("unnamed-" + saved.size());
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        } catch (IOException | RuntimeException clash) {
            // The sample chooses the names, and can choose ones that cannot coexist: a file
            // where another name needs a directory, or a name this file system refuses. One
            // such name must not cost the files saved after it, so it gets a neutral name.
            target = root.resolve(group).resolve("unnamed-" + saved.size());
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, data);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        // Always '/', whatever the system: reports are read on other systems too, and the
        // report format documents paths this way.
        String relative = root.relativize(target).toString().replace(java.io.File.separatorChar, '/');
        saved.add(relative);
        return relative;
    }

    /** Turns any guest-supplied path into a safe relative path made of ordinary characters. */
    static String sanitise(String name) {
        StringBuilder safe = new StringBuilder();
        for (String segment : name.replace('\\', '/').split("/")) {
            String cleaned = segment.replaceAll("[^A-Za-z0-9._$ -]", "_");
            if (cleaned.isEmpty() || cleaned.chars().allMatch(c -> c == '.')) {
                continue; // drops "", "." and ".."
            }
            if (safe.length() > 0) {
                safe.append('/');
            }
            // Very long obfuscated names can exceed file-system limits.
            safe.append(inert(cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned));
        }
        return safe.length() == 0 ? "unnamed" : safe.toString();
    }

    /**
     * Windows device names: a file of this name, with or without an extension, is the device.
     * Spaces before the extension do not change that ({@code "lpt9 .txt"} is still the port).
     */
    private static final Pattern DEVICE =
            Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]|CONIN\\$|CONOUT\\$) *(\\..*)?");

    /**
     * Names and extensions the Windows shell reads by itself when a folder is merely opened
     * or listed, to find an icon or folder settings. Such a file can name an icon on a remote
     * share, which makes Explorer contact that server with the user's credentials.
     */
    private static final java.util.Set<String> SHELL_READ_NAMES = java.util.Set.of("desktop.ini", "autorun.inf", "thumbs.db");
    private static final java.util.Set<String> SHELL_READ_EXTENSIONS = java.util.Set.of("lnk", "url", "scf", "pif",
            "library-ms", "searchconnector-ms", "search-ms", "website", "theme", "themepack", "contact");

    /**
     * One path segment as a name that is an ordinary, inert file on every system. Samples
     * choose these names, and obfuscators like {@code aux}, {@code con} and {@code nul} for
     * classes exactly because Windows tools trip over them: writing {@code aux.class} there
     * writes to a device, or fails, instead of saving the class. A trailing dot or space
     * (which Windows drops, or Java refuses) becomes {@code _}; a device name gets a leading
     * {@code _}; and a name the Windows shell would act on gets {@code .saved} appended, so
     * that browsing the output folder never makes the shell read a file the sample wrote.
     * The same on every system, so a run's folder can be copied to Windows and its report
     * still fits. The desktop app repeats this rule ({@code saved_name} in
     * {@code gui/crates/protocol/src/views.rs}); change the two together.
     */
    private static String inert(String segment) {
        String name = segment;
        if (name.endsWith(".") || name.endsWith(" ")) {
            name = name.substring(0, name.length() - 1) + '_';
        }
        if (DEVICE.matcher(name).matches()) {
            name = '_' + name;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (SHELL_READ_NAMES.contains(lower) || (dot >= 0 && SHELL_READ_EXTENSIONS.contains(lower.substring(dot + 1)))) {
            name += ".saved";
        }
        return name;
    }

    public List<String> saved() {
        return saved;
    }

    public Path root() {
        return root;
    }
}
