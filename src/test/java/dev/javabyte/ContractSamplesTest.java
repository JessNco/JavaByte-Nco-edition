package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javabyte.cli.Main;
import dev.javabyte.config.ConfigLoader;
import dev.javabyte.guest.Basics;
import dev.javabyte.guest.NativeUser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Keeps the desktop app's idea of the tool's output formats honest.
 *
 * <p>This test produces real output from harmless fixtures (the event stream, a report,
 * {@code inspect}, {@code schema} and {@code validate}) and compares it with the copies committed
 * in {@code gui/crates/protocol/tests/tool-output/}. The Rust tests in that crate read the same
 * copies. A change to any format therefore fails here until the samples are regenerated, and
 * then fails in Rust until the GUI's types agree.
 *
 * <p>To regenerate after an intended change:
 * {@code mvn test -Dtest=ContractSamplesTest -Djavabyte.updateSamples=true}
 */
class ContractSamplesTest {

    /**
     * Not called "samples": the repository ignores every folder of that name, to keep live
     * malware out of git, and these files must be committed.
     */
    static final Path SAMPLES = Path.of("gui/crates/protocol/tests/tool-output");
    private static final boolean UPDATE = Boolean.getBoolean("javabyte.updateSamples")
            || "true".equals(System.getenv("JAVABYTE_UPDATE_SAMPLES"));

    /** Fixed so that two runs give the same output (see ContractTest.identicalRunsGiveIdenticalReports). */
    private static final String[] REPEATABLE = {"-s", "environment.clockStart=2024-03-01T09:00:00Z", "-s", "environment.randomSeed=7"};

    @TempDir
    Path temp;

    private String cli(String... args) {
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            Main.execute(args);
        } finally {
            System.setOut(original);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void samplesMatchCurrentOutput() throws IOException {
        Path basics = Fixtures.jar(Files.createDirectory(temp.resolve("basics")), Map.of(), Basics.class);
        Path out = temp.resolve("out");
        List<String> problems = new ArrayList<>();

        String stream = cli(concat(new String[] {"run", basics.toString(), "-o", out.toString(), "--events", "jsonl", "--progress"}, REPEATABLE));
        compare("stream-basics.jsonl", normaliseStream(stream), problems);
        compare("report-basics.json", normalise(Files.readString(out.resolve("report.json"))), problems);

        byte[] library;
        try (InputStream in = getClass().getResourceAsStream("/native/testlib.dll")) {
            library = in.readAllBytes();
        }
        Path withNative = Fixtures.jar(Files.createDirectory(temp.resolve("native")), Map.of("native/testlib.dll", library), NativeUser.class);
        compare("inspect-native.json", normalise(cli("inspect", "--json", withNative.toString())), problems);

        compare("schema.json", normalise(cli("schema", "--json")), problems);
        compare("policy.json", normalise(cli("policy", "--json")), problems);

        Path bad = Files.writeString(temp.resolve("bad.yaml"),
                "limitz: {}\nlimits: {timeoutSeconds: soon}\nnetwork: {http: [{body: x}]}\n");
        compare("validate-problems.json", normalise(cli("validate", "-c", bad.toString(), "--json")), problems);

        if (!problems.isEmpty()) {
            fail(String.join("\n", problems) + "\nIf the change is intended, regenerate with "
                    + "mvn test -Dtest=ContractSamplesTest -Djavabyte.updateSamples=true, then make the Rust types in "
                    + "gui/crates/protocol agree.");
        }
    }

    private void compare(String name, String current, List<String> problems) throws IOException {
        Path file = SAMPLES.resolve(name);
        if (UPDATE) {
            Files.createDirectories(SAMPLES);
            Files.writeString(file, current);
            return;
        }
        if (!Files.exists(file)) {
            problems.add(file + " is missing");
            return;
        }
        String committed = Files.readString(file);
        List<String> want = committed.lines().toList();
        List<String> have = current.lines().toList();
        if (name.endsWith(".jsonl")) {
            if (want.size() != have.size()) {
                problems.add(name + ": " + have.size() + " records now, " + want.size() + " in the sample");
                return;
            }
            for (int i = 0; i < want.size(); i++) {
                if (!ConfigLoader.json().readTree(want.get(i)).equals(ConfigLoader.json().readTree(have.get(i)))) {
                    problems.add(name + " record " + (i + 1) + " differs:\n  sample: " + want.get(i) + "\n  now:    " + have.get(i));
                    return;
                }
            }
        } else if (!ConfigLoader.json().readTree(committed).equals(ConfigLoader.json().readTree(current))) {
            problems.add(name + " differs from the current output");
        }
    }

    /** Replaces what changes between machines and runs: the temporary folder and elapsed times. */
    private String normalise(String json) throws IOException {
        JsonNode tree = ConfigLoader.json().readTree(json.replace(temp.toString(), "<work>"));
        scrub(tree);
        return ConfigLoader.json().writeValueAsString(tree) + "\n";
    }

    /**
     * One record per line, as the stream is. Progress records come at timed intervals, so only
     * the last one (which has the final, repeatable counts) is kept.
     */
    private String normaliseStream(String stream) throws IOException {
        List<JsonNode> records = new ArrayList<>();
        JsonNode lastProgress = null;
        for (String line : stream.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode record = ConfigLoader.json().readTree(line.replace(temp.toString(), "<work>"));
            scrub(record);
            if (record.get("type").asText().equals("progress")) {
                lastProgress = record;
                continue;
            }
            if (record.get("type").asText().equals("finish") && lastProgress != null) {
                records.add(lastProgress);
            }
            records.add(record);
        }
        StringBuilder out = new StringBuilder();
        var compact = ConfigLoader.json().copy().disable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
        for (JsonNode record : records) {
            out.append(compact.writeValueAsString(record)).append('\n');
        }
        return out.toString();
    }

    private static void scrub(JsonNode node) {
        if (node instanceof ObjectNode object) {
            if (object.has("elapsedMs")) {
                object.put("elapsedMs", 0);
            }
            // The fixture jar records when it was built, so its hash changes from run to run, and
            // its size depends on the JDK that compiled the fixtures (Java 17 and 21 differ).
            if (object.has("sha256") && object.has("mainClass")) {
                object.put("sha256", "0".repeat(64));
                if (object.has("size")) {
                    object.put("size", 0);
                }
            }
            object.elements().forEachRemaining(ContractSamplesTest::scrub);
        } else if (node instanceof ArrayNode array) {
            array.elements().forEachRemaining(ContractSamplesTest::scrub);
        }
    }

    private static String[] concat(String[] a, String[] b) {
        String[] all = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    @Test
    void samplesFolderIsWhereTheRustTestsLook() {
        assertEquals(Path.of("gui", "crates", "protocol", "tests", "tool-output"), SAMPLES);
    }
}
