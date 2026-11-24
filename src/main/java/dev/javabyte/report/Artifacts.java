package dev.javabyte.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    /** @param root output directory, or null to keep nothing (used by tests) */
    public Artifacts(Path root) {
        this.root = root;
    }

    /**
     * @param group subdirectory, e.g. {@code dropped}
     * @param name  name suggested by the sample; may contain any characters
     * @return the path written, relative to the output directory, or null if nothing was written
     */
    public String save(String group, String name, byte[] data) {
        if (root == null) {
            return null;
        }
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

    /** Windows device names: a file called {@code CON} or {@code COM1.txt} is the device, not a file. */
    private static final java.util.regex.Pattern DEVICE_NAME = java.util.regex.Pattern.compile(
            "(?i)(CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|COM[0-9]|LPT[0-9])(\\..*)?");

    /**
     * Names and extensions the Windows shell reads by itself when a folder is merely opened
     * or listed, to find an icon or folder settings. Such a file can name an icon on a remote
     * share, which makes Explorer contact that server with the user's credentials.
     */
    private static final java.util.Set<String> SHELL_READ_NAMES = java.util.Set.of("desktop.ini", "autorun.inf", "thumbs.db");
    private static final java.util.Set<String> SHELL_READ_EXTENSIONS = java.util.Set.of("lnk", "url", "scf", "pif",
            "library-ms", "searchconnector-ms", "search-ms", "website", "theme", "themepack", "contact");

    /**
     * Makes one path segment a plain file name on every system. A device name gets a leading
     * underscore; a trailing dot or space, which Windows silently drops, becomes an
     * underscore; and a name the Windows shell would act on gets {@code .saved} appended, so
     * that browsing the output folder never makes the shell read a file the sample wrote.
     */
    private static String inert(String segment) {
        String name = segment;
        if (name.endsWith(".") || name.endsWith(" ")) {
            name = name.substring(0, name.length() - 1) + '_';
        }
        if (DEVICE_NAME.matcher(name).matches()) {
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
