package dev.javabyte.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Collects {@link Event}s for the report and mirrors them to a text trace as they happen.
 *
 * <p>Events are streamed to the trace immediately so that a run which is killed, or which hangs
 * inside a real JDK call, still leaves evidence of everything up to that point.
 */
public final class EventLog {

    private final List<Event> events = new ArrayList<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Set<String> seen = new HashSet<>();
    private final int maxPerCategory;
    private final Writer trace;
    private Supplier<String> callerSupplier = () -> null;
    private long seq;

    /**
     * @param maxPerCategory events stored per category before further ones are only counted
     * @param trace          text sink for the live trace, or null for none
     */
    public EventLog(int maxPerCategory, Writer trace) {
        this.maxPerCategory = maxPerCategory;
        this.trace = trace;
    }

    /** Supplies the current guest location; set once the interpreter exists. */
    public void setCallerSupplier(Supplier<String> supplier) {
        this.callerSupplier = supplier;
    }

    /** Records an event. {@code keyValues} alternates keys and values. */
    public void add(String category, String action, Object... keyValues) {
        record(category, action, toMap(keyValues));
    }

    /**
     * Records an event only the first time a given {@code dedupeKey} is seen.
     *
     * <p>String decryptors and polling loops repeat the same fact thousands of times. Keeping one
     * copy keeps the report readable and stops a hot loop from exhausting the category budget.
     */
    public void addOnce(String dedupeKey, String category, String action, Object... keyValues) {
        if (counts.getOrDefault(category, 0) >= maxPerCategory) {
            // The category is full, so the event would not be stored anyway. Skipping the
            // "seen" set here keeps memory bounded on runs that produce endless new values.
            counts.merge(category, 1, Integer::sum);
            return;
        }
        if (seen.add(category + '\u0000' + dedupeKey)) {
            record(category, action, toMap(keyValues));
        }
    }

    private void record(String category, String action, Map<String, Object> data) {
        int count = counts.merge(category, 1, Integer::sum);
        if (count > maxPerCategory) {
            return; // counted in the summary, but not stored
        }
        Event event = new Event(seq++, category, action, callerSupplier.get(), data);
        events.add(event);
        writeTrace(event.toLine());
    }

    /** Writes a free-form line to the trace only (method and instruction tracing). */
    public void writeTrace(String line) {
        if (trace == null) {
            return;
        }
        try {
            trace.write(line);
            trace.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void flush() {
        if (trace == null) {
            return;
        }
        try {
            trace.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<Event> events() {
        return events;
    }

    /** Total events observed per category, including those dropped by the per-category cap. */
    public Map<String, Integer> counts() {
        return counts;
    }

    public List<Event> byCategory(String category) {
        return events.stream().filter(event -> event.category().equals(category)).toList();
    }

    private static Map<String, Object> toMap(Object[] keyValues) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                data.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return data;
    }
}
