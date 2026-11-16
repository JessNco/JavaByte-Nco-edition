package dev.javabyte.report;

import java.util.Map;

/**
 * One observed fact about the sample's behaviour.
 *
 * @param seq      position in the run, so events from different categories can be re-ordered
 * @param category coarse grouping used for filtering, e.g. {@code file}, {@code net}, {@code process}
 * @param action   what happened within the category, e.g. {@code write}, {@code http}, {@code exec}
 * @param caller   guest location that caused it, as {@code class.method:line}; null if none
 * @param data     details; values are JSON-friendly (strings, numbers, booleans, lists, maps)
 */
public record Event(long seq, String category, String action, String caller, Map<String, Object> data) {

    /** Compact single-line form for the human-readable trace. */
    public String toLine() {
        StringBuilder line = new StringBuilder(96);
        line.append('[').append(category).append('.').append(action).append("] ");
        data.forEach((key, value) -> line.append(key).append('=').append(value).append(' '));
        if (caller != null) {
            line.append("@ ").append(caller);
        }
        return line.toString().stripTrailing();
    }
}
