package dev.javabyte;

import dev.javabyte.report.Event;
import dev.javabyte.report.Report;
import java.util.Map;

/**
 * Receives a run's progress as it happens, for front ends that show a run live: the command
 * line's event stream and progress lines, and through them the desktop app.
 *
 * <p>Calls come from the emulator's own threads, one at a time per run. Implementations must
 * be quick and must not call back into the emulator; anything slow (network, a screen) belongs
 * behind a queue on the receiving side.
 */
public interface RunListener {

    /** The sample is loaded and execution is about to begin. */
    default void started(Report report) {}

    /** An event was recorded (and not dropped by the per-category cap). */
    default void event(Event event) {}

    /**
     * Periodic progress, at most every {@link RunControl#PROGRESS_INTERVAL_MILLIS} while guest
     * code runs. It comes from the emulator thread at its regular checkpoint, so the counts are
     * consistent; none arrives while the sample is stuck inside a JDK call.
     */
    default void progress(Progress progress) {}

    /** One entry point, or the queued work after it, has ended. */
    default void entryFinished(Report.Run run) {}

    /** A snapshot of a run in progress. */
    record Progress(long elapsedMillis, long instructions, String entry, long events, Map<String, Integer> eventCounts) {}
}
