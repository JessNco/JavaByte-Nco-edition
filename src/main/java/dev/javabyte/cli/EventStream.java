package dev.javabyte.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javabyte.RunListener;
import dev.javabyte.config.ConfigLoader;
import dev.javabyte.report.Event;
import dev.javabyte.report.Report;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes a run as JSON Lines: one self-contained JSON object per line, as it happens. This is the
 * format the desktop app reads, and it suits scripts and AI agents that want to react before a
 * run ends.
 *
 * <p>Record types: {@code start}, {@code event}, {@code progress}, {@code entry}, {@code finish}.
 * {@link #STREAM_VERSION} changes only when a reader could break; new fields may appear at any
 * time and readers should ignore fields they do not know. The format is described in
 * docs/gui/PHASE1-PLAN.md and AGENTS.md.
 */
final class EventStream implements RunListener {

    static final int STREAM_VERSION = 1;

    private final OutputStream out;
    /** Compact: the shared mapper indents, and a record must stay on one line. */
    private final ObjectMapper json = ConfigLoader.json().copy().disable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
    private final String outputDirectory;
    private final boolean withProgress;

    /**
     * @param out where the JSON lines go
     * @param outputDirectory the run's folder, named in the start record
     * @param withProgress whether to write progress records as well
     */
    EventStream(OutputStream out, String outputDirectory, boolean withProgress) {
        this.out = out;
        this.outputDirectory = outputDirectory;
        this.withProgress = withProgress;
    }

    @Override
    public void started(Report report) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("path", report.samplePath());
        sample.put("sha256", report.sampleSha256());
        sample.put("size", report.sampleSize());
        sample.put("mainClass", report.mainClass());
        write(record("start", "streamVersion", STREAM_VERSION,
                "tool", Map.of("name", Report.TOOL_NAME, "version", Report.TOOL_VERSION),
                "sample", sample, "output", outputDirectory));
    }

    @Override
    public void event(Event event) {
        write(record("event", "seq", event.seq(), "category", event.category(), "action", event.action(),
                "caller", event.caller(), "data", event.data()));
    }

    @Override
    public void progress(Progress progress) {
        if (withProgress) {
            write(record("progress", "elapsedMs", progress.elapsedMillis(), "instructions", progress.instructions(),
                    "entry", progress.entry(), "events", progress.events(), "eventCounts", progress.eventCounts()));
        }
    }

    @Override
    public void entryFinished(Report.Run run) {
        write(record("entry", "entry", run.entry(), "outcome", run.outcome(), "detail", run.detail(),
                "instructions", run.instructions()));
    }

    /** The last record: the exit code the process is about to return and where the report is. */
    void finish(Report report, int exitCode, String reportPath) {
        Map<String, Object> full = report.toMap();
        write(record("finish", "exitCode", exitCode, "report", reportPath, "runs", full.get("runs"),
                "summary", full.get("summary")));
    }

    private static Map<String, Object> record(String type, Object... keyValues) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", type);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            record.put((String) keyValues[i], keyValues[i + 1]);
        }
        return record;
    }

    /**
     * One line per record, flushed at once so that a reader sees it while the run continues.
     * Synchronised because the final records come from a different thread than the events.
     */
    private synchronized void write(Map<String, Object> record) {
        try {
            out.write(json.writeValueAsString(record).getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
