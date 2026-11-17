package dev.javabyte.cli;

import dev.javabyte.RunListener;
import java.io.PrintStream;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

/** Progress for people: one line on standard error every so often while a run is going. */
final class ProgressPrinter implements RunListener {

    private final PrintStream err;

    ProgressPrinter(PrintStream err) {
        this.err = err;
    }

    @Override
    public void progress(Progress progress) {
        String top = progress.eventCounts().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(4)
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", "));
        err.printf("[%5.1f s] %,d instructions, %,d events%s  (%s)%n", progress.elapsedMillis() / 1000.0,
                progress.instructions(), progress.events(), top.isEmpty() ? "" : ": " + top, progress.entry());
    }
}
