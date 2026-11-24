package dev.javabyte.report;

/**
 * Makes text that came from a sample safe to print for a person or to place in a config.
 *
 * <p>Class names, command lines, URLs and decrypted strings are chosen by the sample's author
 * and may hold any character. Printed as they are, a line break forges report lines (a fake
 * "Calls blocked by policy (0)" section, say), terminal escape sequences move the cursor,
 * recolour or retitle the terminal and can hide what was printed before, and bidirectional
 * controls reorder what the reader sees. The JSON report needs none of this: its encoder
 * escapes control characters itself.
 */
public final class Text {

    private Text() {}

    /**
     * The text on one line, with every control, line-breaking, bidirectional and invisible
     * formatting character written as a {@code \}{@code uXXXX} escape.
     */
    public static String visible(String text) {
        return escape(text, false);
    }

    /** As {@link #visible}, but keeps line feeds and tabs, for text the tool itself laid out in lines. */
    public static String visibleLines(String text) {
        return escape(text, true);
    }

    private static String escape(String text, boolean keepLayout) {
        if (text == null) {
            return null;
        }
        StringBuilder safe = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean keep = keepLayout && (c == '\n' || c == '\t');
            if (!keep && needsEscape(c)) {
                if (safe == null) {
                    safe = new StringBuilder(text.length() + 16).append(text, 0, i);
                }
                safe.append(String.format("\\u%04x", (int) c));
            } else if (safe != null) {
                safe.append(c);
            }
        }
        return safe == null ? text : safe.toString();
    }

    private static boolean needsEscape(char c) {
        return c < 0x20 || (c >= 0x7f && c <= 0x9f)       // C0 and C1 controls, DEL
                || c == 0x061c || c == 0x200e || c == 0x200f // bidirectional marks
                || (c >= 0x2028 && c <= 0x202e)              // line and paragraph separators, bidi embeddings
                || (c >= 0x2066 && c <= 0x2069)              // bidi isolates
                || c == 0xfeff;                              // byte order mark
    }

    /**
     * The text as a double-quoted YAML scalar. Everything outside printable ASCII is escaped,
     * so the value stays one scalar whatever it holds: a line break or a {@code #} in a sample's
     * path or class name cannot end the value and start a config key of its own.
     */
    public static String yamlString(String text) {
        StringBuilder quoted = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                quoted.append('\\').append(c);
            } else if (c >= 0x20 && c < 0x7f) {
                quoted.append(c);
            } else {
                quoted.append(String.format("\\u%04x", (int) c));
            }
        }
        return quoted.append('"').toString();
    }
}
