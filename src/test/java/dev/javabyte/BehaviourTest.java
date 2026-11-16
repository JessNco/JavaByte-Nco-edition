package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.guest.Dropper;
import dev.javabyte.guest.Reflective;
import dev.javabyte.guest.Stage2;
import dev.javabyte.report.Event;
import dev.javabyte.report.Report;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end checks that malware-like behaviour is contained and reported. The fixtures attempt
 * real side effects (launching processes, writing files, opening connections); the assertions
 * are that each attempt shows up as an event and, implicitly, that the test machine is untouched.
 */
class BehaviourTest {

    @TempDir
    Path temp;

    private Path dropperJar;

    @BeforeEach
    void buildDropper() throws Exception {
        // The second stage ships only as an encrypted resource, as a packed sample would carry it.
        byte[] payload = Fixtures.classBytes(Stage2.class);
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= 0x5A;
        }
        dropperJar = Fixtures.jar(temp, Map.of("payload.bin", payload), Dropper.class);
    }

    private EmulatorConfig dropperConfig(String c2Reply) {
        EmulatorConfig config = Fixtures.config(dropperJar);
        EmulatorConfig.ProcessRule whoami = new EmulatorConfig.ProcessRule();
        whoami.command = "whoami";
        whoami.stdout = "desktop\\admin\n";
        config.processes.add(whoami);
        EmulatorConfig.HttpRule gate = new EmulatorConfig.HttpRule();
        gate.url = "c2\\.example\\.test/gate";
        gate.body = c2Reply;
        config.network.http.add(gate);
        config.limits.maxSleeps = 3;
        return config;
    }

    private static Object field(List<Event> events, int index, String key) {
        return events.get(index).data().get(key);
    }

    @Test
    void dropperIsFollowedThroughDefineClassAndItsBehaviourReported() throws Exception {
        Report report = Fixtures.run(dropperConfig("sleep"));
        String diagnostics = Fixtures.outcomes(report);

        List<Event> defined = Fixtures.events(report, "class", "define");
        assertEquals(1, defined.size(), diagnostics);
        assertEquals("dev/javabyte/guest/Stage2", field(defined, 0, "name"));
        assertEquals("ClassLoader.defineClass", field(defined, 0, "via"));

        assertEquals("cmd.exe /c whoami", field(Fixtures.events(report, "process", "exec"), 0, "command"), diagnostics);

        List<Event> written = Fixtures.events(report, "file", "write");
        assertEquals("C:\\Users\\admin\\AppData\\Roaming\\updater\\state.bin", field(written, 0, "path"), diagnostics);
        // "admin@Windows 10" is exactly one AES block, so PKCS5 padding adds a second.
        assertEquals(32, field(written, 0, "size"));

        List<Event> http = Fixtures.events(report, "net", "http");
        assertEquals("http://c2.example.test/gate?id=from-dropper", field(http, 0, "url"), diagnostics);
        assertEquals("POST", field(http, 0, "method"));
        assertEquals("identity=desktop\\admin", field(http, 0, "requestBodyPreview"));
        assertTrue(http.stream().anyMatch(event -> event.data().get("url").equals("http://c2.example.test/beacon")),
                "the beacon thread should have run: " + diagnostics);

        assertEquals("198.51.100.7:4444", field(Fixtures.events(report, "net", "connect"), 0, "endpoint"), diagnostics);
        assertEquals("HELLO\n", field(Fixtures.events(report, "net", "send"), 0, "preview"));

        assertEquals("30313233343536373839616263646566", field(Fixtures.events(report, "crypto", "cipher-init"), 0, "key"));
        assertEquals(List.of("stage returned Windows 10/C:\\Users\\admin\\AppData\\Roaming\\updater\\state.bin/sleep"),
                Fixtures.stdout(report), diagnostics);
        // The beacon loop never ends by itself; the sleep budget must have stopped it.
        assertEquals(1, Fixtures.events(report, "thread", "stopped").size(), diagnostics);
        assertFalse(report.hasOutcome("internal-error"), diagnostics);
    }

    @Test
    void systemExitStopsTheRunAndIsReported() throws Exception {
        Report report = Fixtures.run(dropperConfig("exit"));
        assertTrue(report.hasOutcome("exit"), Fixtures.outcomes(report));
        assertEquals(7, field(Fixtures.events(report, "system", "exit"), 0, "status"));
    }

    @Test
    void hookOverridesAMethodInARuntimeDefinedClass() throws Exception {
        EmulatorConfig config = dropperConfig("sleep");
        EmulatorConfig.Hook hook = new EmulatorConfig.Hook();
        hook.method = "dev/javabyte/guest/Stage2.isSandbox*";
        hook.action = "return";
        hook.value = true;
        config.hooks.add(hook);
        Report report = Fixtures.run(config);
        assertEquals(List.of("stage returned sandbox detected"), Fixtures.stdout(report), Fixtures.outcomes(report));
        assertTrue(Fixtures.events(report, "process", "exec").isEmpty());
    }

    @Test
    void fileFixtureChangesWhatTheSampleSees() throws Exception {
        EmulatorConfig config = dropperConfig("sleep");
        EmulatorConfig.FileFixture marker = new EmulatorConfig.FileFixture();
        marker.path = "C:\\sandbox\\marker.txt";
        marker.text = "present";
        config.environment.files.add(marker);
        Report report = Fixtures.run(config);
        assertEquals(List.of("stage returned sandbox detected"), Fixtures.stdout(report), Fixtures.outcomes(report));
    }

    @Test
    void offlineModeMakesConnectionsFail() throws Exception {
        EmulatorConfig config = dropperConfig("sleep");
        config.network.mode = "offline";
        Report report = Fixtures.run(config);
        assertTrue(report.hasOutcome("uncaught-exception"), Fixtures.outcomes(report));
        assertTrue(report.runs().get(0).detail().contains("ConnectException"), Fixtures.outcomes(report));
    }

    @Test
    void classesReferencedByALoadedClassAreRequestedFromItsLoader() throws Exception {
        java.util.Map<String, byte[]> encrypted = new java.util.HashMap<>();
        for (Class<?> hidden : List.of(dev.javabyte.guest.LazyFirst.class, dev.javabyte.guest.LazySecond.class)) {
            byte[] bytes = Fixtures.classBytes(hidden);
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] ^= 0x33;
            }
            encrypted.put("enc/" + hidden.getName() + ".bin", bytes);
        }
        Report report = Fixtures.run(Fixtures.config(Fixtures.jar(temp, encrypted, dev.javabyte.guest.LazyLoader.class)));
        assertEquals(List.of("first+second"), Fixtures.stdout(report), Fixtures.outcomes(report));
        assertEquals(2, Fixtures.events(report, "class", "define").size(), Fixtures.outcomes(report));
        assertEquals("dev/javabyte/guest/LazySecond",
                field(Fixtures.events(report, "class", "implicit-load"), 0, "name"), Fixtures.outcomes(report));
    }

    @Test
    void indirectProcessLaunchesAreInterceptedLikeDirectOnes() throws Exception {
        Report report = Fixtures.run(Fixtures.config(Fixtures.jar(temp, Map.of(), Reflective.class)));
        List<Object> commands = Fixtures.events(report, "process", "exec").stream()
                .map(event -> event.data().get("command")).toList();
        assertEquals(List.of("via-reflection.exe", "via-method-handle.exe", "via-builder.exe --flag"), commands,
                Fixtures.outcomes(report));
        assertEquals(List.of("exit codes 00", "agent absent"), Fixtures.stdout(report), Fixtures.outcomes(report));
    }
}
