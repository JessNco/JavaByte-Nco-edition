package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.javabyte.cli.Main;
import dev.javabyte.config.ConfigLoader;
import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.guest.Basics;
import dev.javabyte.guest.sim.Limits;
import dev.javabyte.report.Report;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The live side of a run, which the desktop app and scripts depend on: the JSON Lines event
 * stream, progress reports, stopping a run early, and the trace surviving a killed process.
 * Part of the public interface, like ContractTest.
 */
class StreamingTest {

    @TempDir
    Path temp;

    private record Cli(int exitCode, String out, String err) {
        List<JsonNode> records() throws IOException {
            List<JsonNode> records = new ArrayList<>();
            for (String line : out.split("\n")) {
                if (!line.isBlank()) {
                    records.add(ConfigLoader.json().readTree(line));
                }
            }
            return records;
        }
    }

    private static Cli cli(InputStream stdin, String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        InputStream originalIn = System.in;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        System.setIn(stdin);
        try {
            int code = Main.execute(args);
            return new Cli(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            System.setIn(originalIn);
        }
    }

    private static Cli cli(String... args) {
        return cli(new ByteArrayInputStream(new byte[0]), args);
    }

    private Path limitsJar() throws IOException {
        return Fixtures.jar(Files.createTempDirectory(temp, "limits"), Map.of(), Limits.class);
    }

    @Test
    void eventStreamIsOneRecordPerLineAndMatchesTheReport() throws IOException {
        Path jar = Fixtures.jar(temp, Map.of(), Basics.class);
        Path out = temp.resolve("out");
        Cli result = cli("run", jar.toString(), "-o", out.toString(), "--events", "jsonl");
        assertEquals(0, result.exitCode(), result.err());

        List<JsonNode> records = result.records();
        assertEquals("start", records.get(0).get("type").asText());
        assertEquals(1, records.get(0).get("streamVersion").asInt());
        assertEquals(64, records.get(0).get("sample").get("sha256").asText().length());
        JsonNode finish = records.get(records.size() - 1);
        assertEquals("finish", finish.get("type").asText());
        assertEquals(0, finish.get("exitCode").asInt());
        assertTrue(Files.isRegularFile(Path.of(finish.get("report").asText())));

        JsonNode report = ConfigLoader.json().readTree(out.resolve("report.json").toFile());
        List<JsonNode> streamed = records.stream().filter(r -> r.get("type").asText().equals("event")).toList();
        assertEquals(report.get("events").size(), streamed.size(), "every stored event is streamed, once");
        for (int i = 0; i < streamed.size(); i++) {
            JsonNode inReport = report.get("events").get(i);
            assertEquals(inReport.get("seq"), streamed.get(i).get("seq"));
            assertEquals(inReport.get("category"), streamed.get(i).get("category"));
            assertEquals(inReport.get("data"), streamed.get(i).get("data"));
        }
        long entries = records.stream().filter(r -> r.get("type").asText().equals("entry")).count();
        assertEquals(report.get("runs").size(), entries, "one entry record per run in the report");
        assertEquals(report.get("runs"), finish.get("runs"));

        // With a stream, stdout carries only records; the text summary moves to stderr.
        assertTrue(result.err().contains("report.json"), result.err());
    }

    @Test
    void progressIsReportedInTheStreamAndOnStderr() throws IOException {
        Path jar = limitsJar();
        String[] spin = {"-s", "entryPoints=[{args: [spin]}]", "-s", "limits.maxInstructions=150000000", "-s", "output.console=quiet"};
        Cli streamed = cli(concat(new String[] {"run", jar.toString(), "-o", temp.resolve("p1").toString(), "--events", "jsonl", "--progress"}, spin));
        List<JsonNode> progress = streamed.records().stream().filter(r -> r.get("type").asText().equals("progress")).toList();
        assertFalse(progress.isEmpty(), "a run of over a second must report progress: " + streamed.out());
        JsonNode last = progress.get(progress.size() - 1);
        assertTrue(last.get("instructions").asLong() >= 150_000_000L, "the last progress has the final count: " + last);
        assertTrue(last.get("entry").asText().endsWith("Limits.main"), last.toString());
        assertTrue(last.get("eventCounts").has("process"), last.toString());

        Cli plain = cli(concat(new String[] {"run", jar.toString(), "-o", temp.resolve("p2").toString(), "--progress"}, spin));
        assertTrue(plain.err().contains("instructions"), "progress for people goes to stderr: " + plain.err());
        assertEquals("", plain.out(), "quiet console: nothing on stdout");
    }

    @Test
    void stopOnStdinEndsTheRunAndKeepsTheReport() throws Exception {
        Path jar = limitsJar();
        Path out = temp.resolve("stopped");
        PipedOutputStream feed = new PipedOutputStream();
        PipedInputStream stdin = new PipedInputStream(feed);
        Thread stopper = new Thread(() -> {
            try {
                Thread.sleep(1500);
                feed.write("status\nstop\n".getBytes(StandardCharsets.UTF_8));
                feed.flush();
            } catch (Exception ignored) {
                // The test fails on its own if the stop never arrives.
            }
        });
        stopper.start();
        long started = System.nanoTime();
        Cli result = cli(stdin, "run", jar.toString(), "-o", out.toString(), "--events", "jsonl", "--control", "stdin",
                "-s", "entryPoints=[{args: [spin]}]", "-s", "limits.maxInstructions=100000000000", "-s", "limits.timeoutSeconds=60");
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
        feed.close();

        assertEquals(0, result.exitCode(), "a stopped run is a normal end: " + result.err());
        assertTrue(seconds < 20, "the run should stop soon after the request, took " + seconds + " s");
        JsonNode report = ConfigLoader.json().readTree(out.resolve("report.json").toFile());
        assertEquals("stopped", report.get("runs").get(0).get("outcome").asText());
        assertTrue(report.toString().contains("seen-before-the-limit.exe"), "evidence before the stop is kept");
        JsonNode finish = result.records().get(result.records().size() - 1);
        assertEquals("finish", finish.get("type").asText());
    }

    @Test
    void endOfInputAlsoStops() throws IOException {
        // The program that started the run has gone; nobody would read the rest.
        Cli result = cli(new ByteArrayInputStream(new byte[0]), "run", limitsJar().toString(), "-o", temp.resolve("eof").toString(),
                "--control", "stdin", "-s", "entryPoints=[{args: [spin]}]", "-s", "limits.maxInstructions=100000000000",
                "-s", "output.console=quiet");
        assertEquals(0, result.exitCode());
        JsonNode report = ConfigLoader.json().readTree(temp.resolve("eof").resolve("report.json").toFile());
        assertEquals("stopped", report.get("runs").get(0).get("outcome").asText());
    }

    @Test
    void stopFromAnotherThreadThroughTheApi() throws Exception {
        EmulatorConfig config = Fixtures.config(limitsJar());
        EmulatorConfig.EntryPoint entry = new EmulatorConfig.EntryPoint();
        entry.args.add("spin");
        config.entryPoints.add(entry);
        config.limits.maxInstructions = Long.MAX_VALUE;
        RunControl control = new RunControl();
        List<String> seen = new ArrayList<>();
        control.listen(new RunListener() {
            @Override
            public void event(dev.javabyte.report.Event event) {
                seen.add(event.category() + "." + event.action());
            }
        });
        Thread stopper = new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
                return;
            }
            control.requestStop();
        });
        stopper.start();
        Report report = Emulator.run(config, null, control);
        assertTrue(report.hasOutcome("stopped"), Fixtures.outcomes(report));
        assertFalse(report.stoppedByLimit(), "a stop is not a limit");
        assertTrue(seen.contains("process.exec"), "listeners see events as they happen: " + seen);
        assertTrue(control.awaitFinished(1, TimeUnit.SECONDS));
    }

    @Test
    void unknownStreamOptionsAreUsageErrors() throws IOException {
        Path jar = Fixtures.jar(temp, Map.of(), Basics.class);
        assertEquals(2, cli("run", jar.toString(), "-o", temp.resolve("x").toString(), "--events", "xml").exitCode());
        assertEquals(2, cli("run", jar.toString(), "-o", temp.resolve("y").toString(), "--control", "socket").exitCode());
    }

    /**
     * The trace is written as events happen, so a process killed outright (no chance to clean
     * up) still leaves the evidence up to that point. Runs the tool in a separate JVM so it can
     * be killed for real.
     */
    @Test
    void traceSurvivesAKilledProcess() throws Exception {
        Path out = temp.resolve("killed");
        String java = ProcessHandle.current().info().command().orElse("java");
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "dev.javabyte.cli.Main",
                "run", limitsJar().toString(), "-o", out.toString(), "-s", "entryPoints=[{args: [spin]}]",
                "-s", "limits.maxInstructions=100000000000", "-s", "limits.timeoutSeconds=120")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            Path trace = out.resolve("trace.log");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() < deadline && !(Files.exists(trace) && Files.readString(trace).contains("seen-before-the-limit.exe"))) {
                Thread.sleep(100);
            }
            assertTrue(process.isAlive(), "the run should still be spinning");
        } finally {
            process.destroyForcibly().waitFor(30, TimeUnit.SECONDS);
        }
        String trace = Files.readString(out.resolve("trace.log"));
        assertTrue(trace.contains("[process.exec] command=seen-before-the-limit.exe"), trace);
        assertFalse(Files.exists(out.resolve("report.json")), "a killed run writes no report; the trace is what remains");
    }

    private static String[] concat(String[] a, String[] b) {
        String[] all = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    static {
        assertNotNull(Limits.class);
    }
}
