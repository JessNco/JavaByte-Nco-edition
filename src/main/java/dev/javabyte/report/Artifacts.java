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
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String relative = root.relativize(target).toString();
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
            safe.append(cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned);
        }
        return safe.length() == 0 ? "unnamed" : safe.toString();
    }

    public List<String> saved() {
        return saved;
    }

    public Path root() {
        return root;
    }
}
