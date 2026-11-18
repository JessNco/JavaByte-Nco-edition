package dev.javabyte.host;

import java.util.ArrayList;
import java.util.List;

/**
 * The host policy file as a list of rules with their context, for {@code javabyte policy --json}
 * and the desktop app's Tools screen. Reading is done here, on the tool's own file format, so that
 * nothing else has to parse text written for people.
 *
 * <p>The file's layout: section headings are comment lines of dashes followed by a name
 * ({@code # ------ core language types}); other comment lines directly above a group of rules
 * explain that group; a blank line ends a group. See {@link HostPolicy} for the rule syntax.
 */
public final class PolicyListing {

    /**
     * One rule.
     *
     * @param rule    the rule as written, without the leading {@code !} of a deny
     * @param allow   true for an allow rule, false for a deny (which always wins)
     * @param section the heading of the section the rule is in, or "" before any heading
     * @param note    the comment explaining the rule's group, or "" when there is none
     */
    public record Entry(String rule, boolean allow, String section, String note) {}

    private PolicyListing() {}

    /** The rules of a policy file, in order. */
    public static List<Entry> read(String text) {
        List<Entry> entries = new ArrayList<>();
        String section = "";
        StringBuilder note = new StringBuilder();
        boolean noteBelongsToGroup = false;
        for (String raw : text.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) {
                note.setLength(0);
                noteBelongsToGroup = false;
            } else if (line.startsWith("#")) {
                String comment = line.substring(1).strip();
                if (comment.startsWith("---")) {
                    section = comment.replaceFirst("^-+", "").strip();
                    note.setLength(0);
                } else {
                    // A comment after rules of the same group starts the next group's note.
                    if (noteBelongsToGroup) {
                        note.setLength(0);
                        noteBelongsToGroup = false;
                    }
                    if (note.length() > 0) {
                        note.append(' ');
                    }
                    note.append(comment);
                }
            } else {
                noteBelongsToGroup = true;
                boolean allow = !line.startsWith("!");
                entries.add(new Entry(allow ? line : line.substring(1).strip(), allow, section, note.toString()));
            }
        }
        return entries;
    }
}
