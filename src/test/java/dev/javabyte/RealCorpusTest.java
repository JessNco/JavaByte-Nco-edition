package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.javabyte.corpus.Scenario;
import dev.javabyte.corpus.ScenarioRunner;
import dev.javabyte.vm.VirtualMachine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the emulator against real malware from MalwareBazaar and checks the behaviour each
 * scenario under {@code src/test/resources/corpus/real} describes.
 *
 * <p><b>The samples are live malware and are never stored in this repository.</b> They are
 * read from the directory named by the environment variable {@value #SAMPLES_VARIABLE},
 * which {@code scripts/fetch-corpus.py} fills. Without it every scenario is reported as
 * skipped, so an ordinary build and the public CI stay free of malware. Set
 * {@value #REQUIRED_VARIABLE} to {@code true} to turn a missing sample into a failure, for a
 * release check that must not silently skip.
 *
 * <p>The emulator only ever reads a sample as data. Nothing here starts one on a real JVM.
 */
class RealCorpusTest {

    static final String SAMPLES_VARIABLE = "JAVABYTE_SAMPLES";
    static final String REQUIRED_VARIABLE = "JAVABYTE_SAMPLES_REQUIRED";

    @TempDir
    Path temp;

    private static List<Scenario> scenarios() {
        return ScenarioRunner.load("corpus/real");
    }

    @TestFactory
    Stream<DynamicTest> scenariosAgainstRealSamples() {
        return scenarios().stream().map(scenario -> DynamicTest.dynamicTest(scenario.name, () -> run(scenario)));
    }

    private void run(Scenario scenario) throws IOException {
        Path sample = locate(scenario.sha256);
        boolean required = Boolean.parseBoolean(System.getenv(REQUIRED_VARIABLE));
        if (sample == null && required) {
            fail("sample " + scenario.sha256 + " is not in " + System.getenv(SAMPLES_VARIABLE)
                    + "; run scripts/fetch-corpus.py");
        }
        assumeTrue(sample != null, "sample not available; set " + SAMPLES_VARIABLE + " and run scripts/fetch-corpus.py");
        // A wrong file under the right name would make every expectation fail confusingly.
        assertEquals(scenario.sha256, VirtualMachine.sha256(Files.readAllBytes(sample)), "file content does not match its name: " + sample);

        String bridgePython = System.getenv("JAVABYTE_BRIDGE_PYTHON");
        List<String> problems = ScenarioRunner.run(scenario, sample, Files.createDirectories(temp.resolve(scenario.name)), bridgePython);
        if (!problems.isEmpty()) {
            fail(scenario.name + " (" + scenario.family + ")\n" + String.join("\n", problems));
        }
    }

    /** Finds a sample in either layout {@code scripts/fetch-sample.py} and people commonly use. */
    private static Path locate(String sha256) throws IOException {
        String root = System.getenv(SAMPLES_VARIABLE);
        if (root == null || root.isBlank()) {
            return null;
        }
        Path directory = Path.of(root);
        for (Path candidate : List.of(directory.resolve(sha256).resolve(sha256 + ".jar"), directory.resolve(sha256 + ".jar"),
                directory.resolve(sha256))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        Path own = directory.resolve(sha256);
        if (Files.isDirectory(own)) {
            try (Stream<Path> files = Files.list(own)) {
                return files.filter(Files::isRegularFile).findFirst().orElse(null);
            }
        }
        return null;
    }

    /**
     * Checks the scenario files themselves. This part needs no samples, so it runs everywhere
     * and keeps the corpus description from rotting while the samples are absent.
     */
    @Test
    void scenarioFilesAreCompleteAndConsistent() {
        List<Scenario> scenarios = scenarios();
        assertTrue(scenarios.size() >= 9, "expected the documented real-sample scenarios, found " + scenarios.size());
        Set<String> hashes = new HashSet<>();
        for (Scenario scenario : scenarios) {
            assertNotNull(scenario.sha256, scenario.name + " needs a sha256");
            assertTrue(scenario.sha256.matches("[0-9a-f]{64}"), scenario.name + ": sha256 must be 64 lower-case hex digits");
            assertTrue(hashes.add(scenario.sha256), scenario.name + ": sample is used by two scenarios");
            assertNotNull(scenario.family, scenario.name + " needs a family");
            assertTrue(scenario.source != null && scenario.source.contains(scenario.sha256), scenario.name + ": source must link to the sample");
            assertTrue(scenario.fixture == null, scenario.name + ": real scenarios take their sample from disk, not from a fixture");
            assertTrue(!scenario.techniques.isEmpty() && !scenario.stages.isEmpty(), scenario.name + " needs techniques and stages");
            for (Scenario.Stage stage : scenario.stages) {
                // Expectations are regular expressions; a typo should fail here, not on the day the samples are fetched.
                stage.expect.events.forEach(match -> match.where.values().forEach(java.util.regex.Pattern::compile));
                stage.expect.absent.forEach(match -> match.where.values().forEach(java.util.regex.Pattern::compile));
                stage.expect.artifacts.forEach(java.util.regex.Pattern::compile);
            }
        }
    }
}
