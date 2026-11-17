package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.javabyte.corpus.Scenario;
import dev.javabyte.corpus.ScenarioRunner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the simulated malware corpus: every scenario under
 * {@code src/test/resources/corpus/simulated} becomes one test.
 *
 * <p>To add a technique, write a fixture in {@code dev.javabyte.guest.sim} and a scenario file
 * that states what the report must show. No Java test code is needed unless the sample's jar
 * needs generated content, in which case add a builder below.
 */
class SimulatedCorpusTest {

    @TempDir
    Path temp;

    /** Steps that add generated resources to a scenario's jar, selected by {@code builder:} in the scenario. */
    private static final Map<String, Callable<Map<String, byte[]>>> BUILDERS = Map.of(
            "serialized-config", SimulatedCorpusTest::serializedConfig);

    /** A harmless map, and a URL: a class whose deserialization can trigger a DNS lookup. */
    private static Map<String, byte[]> serializedConfig() throws IOException {
        HashMap<String, Object> config = new HashMap<>();
        config.put("server", "c2.config.test");
        config.put("port", 4443);
        return Map.of("config.ser", serialize(config), "gadget.ser", serialize(new URL("http://gadget.config.test/")));
    }

    private static byte[] serialize(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    @TestFactory
    Stream<DynamicTest> scenarios() {
        return ScenarioRunner.load("corpus/simulated").stream()
                .map(scenario -> DynamicTest.dynamicTest(scenario.name, () -> run(scenario)));
    }

    private void run(Scenario scenario) throws Exception {
        Map<String, byte[]> resources = new LinkedHashMap<>();
        scenario.resources.forEach((path, text) -> resources.put(path, text.getBytes(StandardCharsets.UTF_8)));
        if (scenario.builder != null) {
            resources.putAll(BUILDERS.get(scenario.builder).call());
        }
        List<Class<?>> classes = new ArrayList<>();
        classes.add(Class.forName(scenario.fixture, false, getClass().getClassLoader()));
        for (String extra : scenario.classes) {
            classes.add(Class.forName(extra, false, getClass().getClassLoader()));
        }
        Path work = Files.createDirectories(temp.resolve(scenario.name));
        Path jar = Fixtures.jar(work, resources, classes.toArray(Class<?>[]::new));
        List<String> problems = ScenarioRunner.run(scenario, jar, work, null);
        if (!problems.isEmpty()) {
            fail(scenario.name + " (" + scenario.description + ")\n" + String.join("\n", problems));
        }
    }

    @Test
    void corpusIsNotEmpty() {
        // A wrong resource path would otherwise make the factory above pass with zero tests.
        assertTrue(ScenarioRunner.load("corpus/simulated").size() >= 15);
    }
}
