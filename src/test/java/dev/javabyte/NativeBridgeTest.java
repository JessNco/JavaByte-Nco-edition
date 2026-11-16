package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.guest.NativeLib;
import dev.javabyte.report.Event;
import dev.javabyte.report.Report;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test of the native bridge: interpreted Java calls native methods, which run in the
 * CPU emulator and call back into interpreted Java.
 *
 * <p>The bridge needs a Python interpreter with speakeasy-emulator installed. Point the
 * {@code JAVABYTE_BRIDGE_PYTHON} environment variable at one to run this test; without it the
 * test is skipped, so the ordinary build needs nothing beyond Java.
 */
class NativeBridgeTest {

    @TempDir
    Path temp;

    private static String bridgePython() throws Exception {
        String python = System.getenv("JAVABYTE_BRIDGE_PYTHON");
        assumeTrue(python != null && !python.isBlank(), "JAVABYTE_BRIDGE_PYTHON is not set");
        Process probe = new ProcessBuilder(python, "-W", "ignore", "-c", "import speakeasy")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assumeTrue(probe.waitFor(60, TimeUnit.SECONDS) && probe.exitValue() == 0,
                "the interpreter in JAVABYTE_BRIDGE_PYTHON cannot import speakeasy");
        return python;
    }

    private Path sampleJar() throws Exception {
        byte[] library = NativeBridgeTest.class.getResourceAsStream("/native/testlib.dll").readAllBytes();
        return Fixtures.jar(temp, Map.of("native/testlib.dll", library), NativeLib.class);
    }

    @Test
    void nativeMethodsRunInTheCpuEmulator() throws Exception {
        EmulatorConfig config = Fixtures.config(sampleJar());
        config.plugins.put("native-bridge", Map.of("python", bridgePython()));
        Report report = Emulator.run(config, Files.createDirectory(temp.resolve("out")));
        String diagnostics = Fixtures.outcomes(report);

        assertEquals(List.of("add 42", "greet hello, world", "viaJava 21", "xor abc", "triple -42000000000"),
                Fixtures.stdout(report), diagnostics);
        assertEquals(List.of(), report.events().byCategory("gap"), diagnostics);

        // The method bound in JNI_OnLoad has no exported name; only RegisterNatives reveals it.
        List<Event> registered = Fixtures.events(report, "native", "register-natives");
        assertEquals("dev/javabyte/guest/NativeLib", registered.get(0).data().get("class"), diagnostics);
        assertTrue(String.valueOf(registered.get(0).data().get("methods")).contains("triple (J)J"), diagnostics);

        // Windows API calls made by the native code are reported, with their arguments.
        List<String> apis = Fixtures.events(report, "native", "api").stream()
                .map(event -> event.data().get("api") + " " + event.data().get("args")).toList();
        assertTrue(apis.stream().anyMatch(api -> api.startsWith("KERNEL32.CreateFileA")
                && api.contains("native-marker.txt")), apis.toString());
        assertTrue(apis.stream().anyMatch(api -> api.startsWith("KERNEL32.WriteFile")), apis.toString());

        // The call from native code back into Java went through the router.
        assertEquals("dev/javabyte/guest/NativeLib.twice(I)I",
                Fixtures.events(report, "native", "jni-call").get(0).data().get("method"), diagnostics);
    }

    @Test
    void withoutTheBridgeNativeCallsAreReportedNotEmulated() throws Exception {
        Report report = Fixtures.run(Fixtures.config(sampleJar()));
        assertEquals("add 0", Fixtures.stdout(report).get(0), Fixtures.outcomes(report));
        Event load = Fixtures.events(report, "native", "load-library").get(0);
        assertEquals("PE x86-64", load.data().get("format"));
        // The exported name ties the Java method to the library even though it was not run.
        Event call = Fixtures.events(report, "native", "call").get(0);
        assertEquals(load.data().get("library"), call.data().get("implementedBy"));
    }

    @Test
    void aMissingInterpreterIsReportedAndTheRunContinues() throws Exception {
        EmulatorConfig config = Fixtures.config(sampleJar());
        config.plugins.put("native-bridge", Map.of("python", "/nonexistent/python"));
        Report report = Fixtures.run(config);
        assertEquals("add 0", Fixtures.stdout(report).get(0), Fixtures.outcomes(report));
        assertEquals(1, Fixtures.events(report, "gap", "native-bridge-unavailable").size(), Fixtures.outcomes(report));
    }
}
