package dev.javabyte.report;

import dev.javabyte.config.ConfigLoader;
import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.vm.VirtualMachine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The result of one emulation, in two renderings of the same data: JSON for tools and agents,
 * and a short text summary for people.
 *
 * <p>The JSON layout is a stable interface. Fields may be added, but existing ones should keep
 * their meaning, since scripts and prompts will be written against them.
 */
public final class Report {

    public static final String TOOL_NAME = "javabyte";
    public static final String TOOL_VERSION = "0.1.0";

    /** How one entry point (or the tasks it queued) ended. */
    public record Run(String entry, String outcome, String detail, long instructions) {}

    private final EmulatorConfig config;
    private final Path samplePath;
    private final String sampleSha256;
    private final long sampleSize;
    private final String mainClass;
    private final VirtualMachine vm;
    private final List<Run> runs = new ArrayList<>();

    public Report(EmulatorConfig config, Path samplePath, byte[] sampleBytes, String mainClass, VirtualMachine vm) {
        this.config = config;
        this.samplePath = samplePath;
        this.sampleSha256 = VirtualMachine.sha256(sampleBytes);
        this.sampleSize = sampleBytes.length;
        this.mainClass = mainClass;
        this.vm = vm;
    }

    public synchronized void addRun(String entry, String outcome, String detail, long instructions) {
        runs.add(new Run(entry, outcome, detail, instructions));
    }

    /** Called once execution has stopped; reserved for derived data. */
    public void complete() {
        vm.events.flush();
    }

    public List<Run> runs() {
        return runs;
    }

    public EventLog events() {
        return vm.events;
    }

    public boolean hasOutcome(String outcome) {
        return runs.stream().anyMatch(run -> run.outcome().equals(outcome));
    }

    /** True if any part of the run was cut short by a budget rather than ending by itself. */
    public boolean stoppedByLimit() {
        return hasOutcome("timeout") || hasOutcome("instruction-limit");
    }

    // ------------------------------------------------------------------ JSON

    public Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tool", Map.of("name", TOOL_NAME, "version", TOOL_VERSION));

        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("path", samplePath.toString());
        sample.put("sha256", sampleSha256);
        sample.put("size", sampleSize);
        sample.put("mainClass", mainClass);
        sample.put("classCount", vm.classes.classNames().size());
        sample.put("virtualPath", vm.env.samplePath());
        root.put("sample", sample);

        root.put("runs", runs);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("instructions", vm.instructionCount());
        summary.put("eventCounts", vm.events.counts());
        summary.put("processes", values("process", "exec", "command"));
        summary.put("httpRequests", values("net", "http", "url"));
        summary.put("connections", values("net", "connect", "endpoint"));
        summary.put("dnsLookups", values("net", "dns", "host"));
        summary.put("filesWritten", values("file", "write", "path"));
        summary.put("classesDefined", values("class", "define", "name"));
        summary.put("nativeLibraries", values("native", "load-library", "library"));
        summary.put("blockedCalls", values("policy", "blocked", "member"));
        summary.put("gaps", vm.events.byCategory("gap").size());
        root.put("summary", summary);

        root.put("artifacts", vm.artifacts.saved());
        root.put("events", vm.events.events());
        root.put("config", config);
        return root;
    }

    /** Distinct values of one data field across events of a kind, in first-seen order. */
    private List<Object> values(String category, String action, String field) {
        List<Object> values = new ArrayList<>();
        for (Event event : vm.events.events()) {
            Object value = event.data().get(field);
            if (event.category().equals(category) && event.action().equals(action) && value != null && !values.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    public void writeJson(Path file) throws IOException {
        Files.writeString(file, toJson());
    }

    public String toJson() {
        try {
            return ConfigLoader.json().writeValueAsString(toMap());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ text

    /** Maximum lines shown per section of the text summary; the JSON report has everything. */
    private static final int SECTION_LIMIT = 25;

    public String toText() {
        StringBuilder text = new StringBuilder();
        text.append("JavaByte ").append(TOOL_VERSION).append(" report\n");
        text.append("  sample   ").append(samplePath).append("  (").append(sampleSize).append(" bytes)\n");
        text.append("  sha256   ").append(sampleSha256).append('\n');
        text.append("  classes  ").append(vm.classes.classNames().size())
                .append(mainClass == null ? "" : "   Main-Class: " + mainClass).append('\n');
        text.append("  executed ").append(vm.instructionCount()).append(" instructions\n\n");

        text.append("Outcome\n");
        for (Run run : runs) {
            text.append("  ").append(run.entry()).append(": ").append(run.outcome());
            if (run.detail() != null) {
                text.append(" - ").append(run.detail().lines().findFirst().orElse(""));
            }
            text.append('\n');
        }

        section(text, "Processes the sample tried to launch", "process", "exec", "command");
        section(text, "HTTP requests", "net", "http", "url");
        section(text, "Socket connections", "net", "connect", "endpoint");
        section(text, "DNS lookups", "net", "dns", "host");
        section(text, "Files written", "file", "write", "path");
        section(text, "Classes defined at run time", "class", "define", "name");
        section(text, "Native libraries loaded", "native", "load-library", "library");
        section(text, "Classes looked up by name", "reflect", "forName", "name");
        section(text, "Strings returned by static methods (likely decrypted)", "string", "returned", "value");
        section(text, "Calls blocked by policy", "policy", "blocked", "member");
        section(text, "Emulation gaps (results here may differ from a real run)", "gap", null, null);
        return text.toString();
    }

    private void section(StringBuilder text, String title, String category, String action, String field) {
        List<String> lines = new ArrayList<>();
        for (Event event : vm.events.events()) {
            if (!event.category().equals(category) || (action != null && !event.action().equals(action))) {
                continue;
            }
            String line = field == null ? event.action() + " " + event.data() : String.valueOf(event.data().get(field));
            if (!lines.contains(line)) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        text.append('\n').append(title).append(" (").append(lines.size()).append(")\n");
        lines.stream().limit(SECTION_LIMIT).forEach(line -> text.append("  ").append(line).append('\n'));
        if (lines.size() > SECTION_LIMIT) {
            text.append("  ... ").append(lines.size() - SECTION_LIMIT).append(" more in report.json\n");
        }
    }
}
