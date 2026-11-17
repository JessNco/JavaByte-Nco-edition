package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.javabyte.cli.Main;
import dev.javabyte.config.ConfigLoader;
import dev.javabyte.config.ConfigLoader.ConfigException;
import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.guest.Basics;
import dev.javabyte.guest.Concurrency;
import dev.javabyte.guest.sim.Dice;
import dev.javabyte.guest.sim.HttpRat;
import dev.javabyte.guest.sim.Limits;
import dev.javabyte.report.Event;
import dev.javabyte.report.Report;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The promises made to people and programs that drive the tool: how a config is read, what
 * each command prints and returns, and what a report contains.
 *
 * <p>These are the parts scripts and AI agents depend on, so a failure here means a caller
 * somewhere would break, even if the emulation itself is still correct. Changing an
 * expectation in this class is a change to the public interface; see CONTRIBUTING.md.
 */
class ContractTest {

    @TempDir
    Path temp;

    private Path basics;

    @BeforeEach
    void buildSample() throws IOException {
        basics = Fixtures.jar(temp, Map.of(), Basics.class);
    }

    /** What one command-line invocation produced. */
    private record Cli(int exitCode, String out, String err) {
        JsonNode json() throws IOException {
            return ConfigLoader.json().readTree(out);
        }
    }

    /** Runs the command line in this JVM with both output streams captured. */
    private static Cli cli(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            int code = Main.execute(args);
            return new Cli(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private Path write(String name, String text) throws IOException {
        return Files.writeString(temp.resolve(name), text);
    }

    // ------------------------------------------------------------------ configuration

    @Test
    void yamlAndJsonConfigsAreEquivalent() throws IOException {
        EmulatorConfig yaml = ConfigLoader.load(write("a.yaml", "sample: x.jar\nlimits:\n  maxSleeps: 7\nenvironment: {os: linux}\n"), List.of());
        EmulatorConfig json = ConfigLoader.load(
                write("a.json", "{\"sample\":\"x.jar\",\"limits\":{\"maxSleeps\":7},\"environment\":{\"os\":\"linux\"}}"), List.of());
        assertEquals(ConfigLoader.toYaml(yaml), ConfigLoader.toYaml(json));
        assertEquals(7, yaml.limits.maxSleeps);
        assertEquals("linux", yaml.environment.os);
    }

    @Test
    void emptyConfigGivesTheDocumentedDefaults() throws IOException {
        EmulatorConfig config = ConfigLoader.load(write("empty.yaml", ""), List.of());
        assertEquals("windows", config.environment.os);
        assertEquals("respond", config.network.mode);
        assertEquals(120, config.limits.timeoutSeconds);
        assertEquals("phantom", config.policy.onBlocked);
        // The printed defaults must themselves be a loadable config.
        EmulatorConfig reloaded = ConfigLoader.load(write("defaults.yaml", ConfigLoader.toYaml(new EmulatorConfig())), List.of());
        assertEquals(ConfigLoader.toYaml(config), ConfigLoader.toYaml(reloaded));
    }

    @Test
    void overridesKeepTheirTypesAndCreateMissingSections() throws IOException {
        EmulatorConfig config = ConfigLoader.load(write("o.yaml", "limits: {maxSleeps: 7}\n"), List.of(
                "limits.timeoutSeconds=30",
                "limits.maxSleeps=9",
                "environment.os=linux",
                "threads.runShutdownHooks=false",
                "classpath=[a.jar, b.jar]",
                "environment.env={APPDATA: 'C:\\Temp'}"));
        assertEquals(30, config.limits.timeoutSeconds);
        assertEquals(9, config.limits.maxSleeps, "a later override wins over the file");
        assertEquals("linux", config.environment.os);
        assertFalse(config.threads.runShutdownHooks);
        assertEquals(List.of("a.jar", "b.jar"), config.classpath);
        // A key that itself contains dots (user.dir) cannot be addressed by a dotted path; the
        // whole map is given as an inline value instead.
        assertEquals(Map.of("APPDATA", "C:\\Temp"), config.environment.env);
    }

    /** Every mistake a config can contain must be refused before the run, naming the offending key. */
    @Test
    void invalidConfigsAreRejectedWithTheKeyNamed() {
        Map<String, String> cases = Map.ofEntries(
                Map.entry("limitz: {}", "limitz"),
                Map.entry("limits: {maxSleep: 3}", "maxSleep"),
                Map.entry("environment: {os: macos}", "environment.os"),
                Map.entry("network: {mode: live}", "network.mode"),
                Map.entry("policy: {onBlocked: ignore}", "policy.onBlocked"),
                Map.entry("threads: {mode: parallel}", "threads.mode"),
                Map.entry("limits: {timeoutSeconds: 0}", "timeoutSeconds"),
                Map.entry("limits: {maxSleeps: -1}", "maxSleeps"),
                Map.entry("entryPoints: [{type: agent}]", "entryPoints[].type"),
                Map.entry("entryPoints: [{type: method, class: A}]", "method"),
                Map.entry("hooks: [{action: return}]", "method"),
                Map.entry("hooks: [{method: 'A.b', action: explode}]", "hooks[].action"),
                Map.entry("network: {http: [{body: x}]}", "network.http[].url"),
                Map.entry("network: {http: [{url: '(unclosed'}]}", "network.http[].url"),
                Map.entry("processes: [{command: '['}]", "processes[].command"),
                Map.entry("- a\n- b", "mapping"),
                Map.entry("limits: {timeoutSeconds: soon}", "timeoutSeconds"));
        cases.forEach((text, expectedInMessage) -> {
            ConfigException failure = assertThrows(ConfigException.class,
                    () -> ConfigLoader.load(write("bad.yaml", text), List.of()), text);
            assertTrue(failure.getMessage().contains(expectedInMessage),
                    "message for [" + text + "] should mention " + expectedInMessage + ": " + failure.getMessage());
        });
        assertThrows(ConfigException.class, () -> ConfigLoader.load(null, List.of("no-equals-sign")));
        assertThrows(ConfigException.class, () -> ConfigLoader.load(temp.resolve("absent.yaml"), List.of()));
    }

    /** Examples that stop loading are worse than no examples. */
    @Test
    void shippedExampleConfigsLoad() throws IOException {
        try (Stream<Path> examples = Files.list(Path.of("examples"))) {
            List<Path> files = examples.filter(file -> file.toString().endsWith(".yaml")).toList();
            assertFalse(files.isEmpty());
            for (Path file : files) {
                ConfigLoader.load(file, List.of());
            }
        }
    }

    // ------------------------------------------------------------------ command line

    @Test
    void runWritesReportTraceAndArtifacts() throws IOException {
        Path out = temp.resolve("out");
        Cli result = cli("run", basics.toString(), "-o", out.toString(), "--json");
        assertEquals(0, result.exitCode(), result.err());

        JsonNode printed = result.json();
        JsonNode saved = ConfigLoader.json().readTree(out.resolve("report.json").toFile());
        assertEquals(saved, printed, "--json must print exactly what report.json holds");
        assertEquals(1, printed.get("schemaVersion").asInt());
        for (String field : List.of("tool", "sample", "runs", "summary", "artifacts", "events", "config")) {
            assertTrue(printed.has(field), "report is missing '" + field + "'");
        }
        for (String field : List.of("instructions", "eventCounts", "eventsDropped", "processes", "httpRequests", "connections",
                "dnsLookups", "filesWritten", "classesDefined", "nativeLibraries", "blockedCalls", "gaps")) {
            assertTrue(printed.get("summary").has(field), "summary is missing '" + field + "'");
        }
        assertEquals(64, printed.get("sample").get("sha256").asText().length());

        long previous = -1;
        for (JsonNode event : printed.get("events")) {
            for (String field : List.of("seq", "category", "action", "data")) {
                assertTrue(event.has(field), "event is missing '" + field + "': " + event);
            }
            assertTrue(event.get("seq").asLong() > previous, "event numbers must increase");
            previous = event.get("seq").asLong();
        }
        assertEquals(printed.get("events").size(), Files.readAllLines(out.resolve("trace.log")).size(),
                "the trace holds one line per event");
        for (JsonNode artifact : printed.get("artifacts")) {
            assertTrue(Files.isRegularFile(out.resolve(artifact.asText())), "listed artifact does not exist: " + artifact);
        }
    }

    @Test
    void textSummaryNamesTheReportFile() {
        Path out = temp.resolve("text-out");
        Cli result = cli("run", basics.toString(), "-o", out.toString());
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("report.json"), result.out());
        assertTrue(result.out().contains("completed"), result.out());
    }

    @Test
    void exitCodesFollowTheDocumentation() throws IOException {
        Path limits = Fixtures.jar(Files.createDirectory(temp.resolve("limits")), Map.of(), Limits.class);
        String out = temp.resolve("codes").toString();

        assertEquals(3, cli("run", limits.toString(), "-o", out, "-s", "entryPoints=[{args: [spin]}]",
                "-s", "limits.maxInstructions=100000", "-s", "output.console=quiet").exitCode(), "instruction limit");
        assertEquals(3, cli("run", limits.toString(), "-o", out, "-s", "entryPoints=[{args: [spin]}]",
                "-s", "limits.timeoutSeconds=1", "-s", "output.console=quiet").exitCode(), "time limit");
        assertEquals(0, cli("run", limits.toString(), "-o", out, "-s", "entryPoints=[{args: [beacon]}]",
                "-s", "output.console=quiet").exitCode(), "a sleep budget is a normal end, not a limit failure");

        Cli unknownKey = cli("run", basics.toString(), "-o", out, "-s", "limits.maxSleep=1");
        assertEquals(2, unknownKey.exitCode());
        assertTrue(unknownKey.err().contains("maxSleep"), unknownKey.err());

        Cli noSample = cli("run", "-o", out);
        assertEquals(2, noSample.exitCode(), noSample.err());
        Cli missingSample = cli("run", temp.resolve("nothing.jar").toString(), "-o", out);
        assertEquals(2, missingSample.exitCode(), missingSample.err());
        Cli notAJar = cli("run", write("notes.txt", "plain text").toString(), "-o", out);
        assertEquals(2, notAJar.exitCode(), notAJar.err());
        Cli badEntry = cli("run", basics.toString(), "-o", out, "-s", "entryPoints=[{type: method, class: No.Such, method: run}]",
                "-s", "output.console=quiet");
        assertEquals(2, badEntry.exitCode(), badEntry.err());

        assertEquals(2, cli().exitCode(), "no arguments prints usage");
        assertEquals(2, cli("frobnicate").exitCode(), "unknown command");
        assertEquals(0, cli("--help").exitCode());
        assertEquals(0, cli("--version").exitCode());
    }

    @Test
    void quietConsoleStillWritesTheReport() {
        Path out = temp.resolve("quiet");
        Cli result = cli("run", basics.toString(), "-o", out.toString(), "-s", "output.console=quiet");
        assertEquals("", result.out());
        assertTrue(Files.isRegularFile(out.resolve("report.json")));
    }

    @Test
    void inspectDescribesTheSampleWithoutRunningIt() throws IOException {
        Cli result = cli("inspect", "--json", basics.toString());
        assertEquals(0, result.exitCode(), result.err());
        JsonNode description = result.json();
        assertEquals(Basics.class.getName(), description.get("mainClass").asText().replace('/', '.'));
        for (String field : List.of("mainCandidates", "classLoaders", "nativeMethodCount", "largestResources")) {
            assertTrue(description.has(field), "inspect output is missing '" + field + "' (AGENTS.md names it)");
        }
        assertEquals(0, cli("inspect", basics.toString()).exitCode());
        assertEquals(2, cli("inspect", temp.resolve("nothing.jar").toString()).exitCode());
    }

    @Test
    void initPrintsAConfigThatRuns() throws IOException {
        Cli init = cli("init", basics.toString());
        assertEquals(0, init.exitCode(), init.err());
        Path config = write("generated.yaml", init.out());
        Cli run = cli("run", "-c", config.toString(), "-o", temp.resolve("init-out").toString(), "--json");
        assertEquals(0, run.exitCode(), run.err());
        assertEquals("completed", run.json().get("runs").get(0).get("outcome").asText());
    }

    @Test
    void defaultsAndPolicyArePrintable() throws IOException {
        Cli yaml = cli("defaults");
        assertEquals(0, yaml.exitCode());
        ConfigLoader.load(write("printed-defaults.yaml", yaml.out()), List.of());
        assertTrue(cli("defaults", "--json").json().has("limits"));

        Cli policy = cli("policy");
        assertEquals(0, policy.exitCode());
        assertTrue(policy.out().contains("java/lang/String"), "the allow-list should be printed");
    }

    // ------------------------------------------------------------------ determinism

    /** What an event says, without the sequence number. */
    private static List<String> lines(Report report) {
        return report.events().events().stream().map(Event::toLine).toList();
    }

    /**
     * Two runs of the same sample with the same config must give the same report. Analysts
     * compare runs to see the effect of a config change; noise between identical runs would
     * make that impossible. The fixtures cover hash ordering, network traffic, several threads
     * and, in {@link Dice}, every source of randomness and time.
     */
    @Test
    void identicalRunsGiveIdenticalReports() throws IOException {
        for (Class<?> fixture : List.of(Basics.class, Concurrency.class, HttpRat.class, Dice.class)) {
            Path jar = Fixtures.jar(Files.createTempDirectory(temp, "det"), Map.of(), fixture);
            Report first = Fixtures.run(repeatable(jar, 7L));
            Thread.onSpinWait();
            Report second = Fixtures.run(repeatable(jar, 7L));
            assertEquals(lines(first), lines(second), fixture.getSimpleName() + " differed between two identical runs");
            assertEquals(first.runs(), second.runs());
        }
    }

    private static EmulatorConfig repeatable(Path jar, Long seed) {
        EmulatorConfig config = Fixtures.config(jar);
        config.environment.clockStart = "2024-03-01T09:00:00Z";
        config.environment.randomSeed = seed;
        return config;
    }

    /** The seed must really drive the numbers: another seed, or none, gives another run. */
    @Test
    void randomSeedSelectsTheRandomNumbers() throws IOException {
        Path jar = Fixtures.jar(Files.createDirectory(temp.resolve("dice")), Map.of(), Dice.class);
        List<String> seven = Fixtures.stdout(Fixtures.run(repeatable(jar, 7L)));
        List<String> eight = Fixtures.stdout(Fixtures.run(repeatable(jar, 8L)));
        List<String> entropy = Fixtures.stdout(Fixtures.run(repeatable(jar, null)));
        assertEquals(8, seven.size(), seven.toString());
        for (int line : new int[] {0, 1, 3, 4, 5}) {
            assertFalse(seven.get(line).equals(eight.get(line)), "seed had no effect on: " + seven.get(line));
            assertFalse(seven.get(line).equals(entropy.get(line)), "real entropy matched the seeded value: " + seven.get(line));
        }
        // A generator the sample seeds itself keeps its own stream, as on a real JVM.
        assertEquals("seeded " + new java.util.Random(42).nextInt(1_000_000), seven.get(2));
        assertEquals(seven.get(2), entropy.get(2));
        assertEquals(seven.get(6), eight.get(6), "the clock does not depend on the seed");
        // clockStart plus the 90 simulated seconds, on every clock the JDK offers. The virtual
        // clock moves a millisecond each time it is read, hence the small tolerances.
        assertTrue(seven.get(6).matches("time 17092836900\\d\\d slept 900\\d\\d"), seven.get(6));
        assertTrue(seven.get(7).matches("date \\d{1,2} calendar \\d{1,2} gregorian \\d{1,2} instant \\d{1,2} clock \\d{1,2} zoned \\d{1,2}"
                + " utc-date 2024-03-01 utc-time 2024-03-01T09:01:30\\.0\\d\\d"), seven.get(7));
        assertTrue(seven.get(5).matches("uuid [0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"), seven.get(5));
        assertTrue(seven.get(4).matches("thread-local 1\\d\\d -?\\d+"), "bounds must still be honoured: " + seven.get(4));
    }

    /** The cap on stored events is visible in the summary, so a truncated report cannot pass for a full one. */
    @Test
    void droppedEventsAreCountedInTheSummary() throws IOException {
        EmulatorConfig config = Fixtures.config(Fixtures.jar(Files.createDirectory(temp.resolve("flood")), Map.of(), Limits.class));
        EmulatorConfig.EntryPoint entry = new EmulatorConfig.EntryPoint();
        entry.args.add("flood");
        config.entryPoints.add(entry);
        config.limits.maxEventsPerCategory = 50;
        Report report = Fixtures.run(config);

        assertEquals(50, report.events().byCategory("console").size());
        Map<?, ?> summary = (Map<?, ?>) report.toMap().get("summary");
        assertEquals(Map.of("console", 452), summary.get("eventsDropped"), "500 lines, the two framing lines, 50 kept");
        assertEquals(1, Fixtures.events(report, "process", "exec").size(), "other categories are unaffected by a full one");
    }
}
