package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.guest.Basics;
import dev.javabyte.guest.StackKeyed;
import dev.javabyte.report.Report;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs harmless programs on the real JVM and in the emulator and requires identical output.
 * This is the main guard on interpreter correctness: it needs no hand-written expectations, so
 * extending a fixture automatically extends the check.
 */
class DifferentialTest {

    @TempDir
    Path temp;

    @Test
    void languageFeaturesMatchRealJvm() throws Exception {
        List<String> expected = Fixtures.realOutput(Basics.class);
        Report report = Fixtures.run(Fixtures.config(Fixtures.jar(temp, Map.of(), Basics.class)));
        assertTrue(report.hasOutcome("completed"), Fixtures.outcomes(report));
        assertEquals(String.join("\n", expected), String.join("\n", Fixtures.stdout(report)), Fixtures.outcomes(report));
        assertEquals(List.of(), report.events().byCategory("gap"), "no emulation gaps expected");
    }

    @Test
    void jdkBoundaryFeaturesMatchRealJvm() throws Exception {
        java.util.List<String> expected = Fixtures.realOutput(dev.javabyte.guest.Advanced.class);
        Report report = Fixtures.run(Fixtures.config(Fixtures.jar(temp, Map.of(), dev.javabyte.guest.Advanced.class)));
        assertEquals(String.join("\n", expected), String.join("\n", Fixtures.stdout(report)), Fixtures.outcomes(report));
        assertEquals(List.of(), report.events().byCategory("gap"), "no emulation gaps expected");
    }

    @Test
    void multiThreadedProgramMatchesRealJvm() throws Exception {
        java.util.List<String> expected = Fixtures.realOutput(dev.javabyte.guest.Concurrency.class);
        Report report = Fixtures.run(Fixtures.config(Fixtures.jar(temp, Map.of(), dev.javabyte.guest.Concurrency.class)));
        assertEquals(String.join("\n", expected), String.join("\n", Fixtures.stdout(report)), Fixtures.outcomes(report));
        assertEquals(0, Fixtures.events(report, "thread", "stopped").size(), "no thread should have hung: " + Fixtures.outcomes(report));
        assertEquals(List.of(), report.events().byCategory("gap"), "no emulation gaps expected");
    }

    @Test
    void stackTraceKeyedDecryptionMatchesRealJvm() throws Exception {
        List<String> expected = Fixtures.realOutput(StackKeyed.class);
        Report report = Fixtures.run(Fixtures.config(Fixtures.jar(temp, Map.of(), StackKeyed.class)));
        assertEquals(expected, Fixtures.stdout(report), Fixtures.outcomes(report));
        assertEquals("alpha payload", expected.get(0), "fixture self-check");
    }

    /** Reflection, method handles, dynamic proxies and executors: the tools obfuscators hide calls behind. */
    @Test
    void reflectionHandlesProxiesAndExecutorsMatchRealJvm() throws Exception {
        List<String> expected = Fixtures.realOutput(dev.javabyte.guest.Introspection.class);
        Report report = Fixtures.run(Fixtures.config(Fixtures.jar(temp, Map.of(), dev.javabyte.guest.Introspection.class)));
        assertEquals(String.join("\n", expected), String.join("\n", Fixtures.stdout(report)), Fixtures.outcomes(report));
        assertEquals(List.of(), report.events().byCategory("gap"), "no emulation gaps expected");
        assertTrue(expected.size() >= 14, "fixture self-check: " + expected);
    }

    /**
     * {@code Object.clone()} on plain guest objects, on guest classes that extend JDK classes and
     * through reflection, and the three atomic field updaters with their error cases.
     */
    @Test
    void cloneAndFieldUpdatersMatchRealJvm() throws Exception {
        List<String> expected = Fixtures.realOutput(dev.javabyte.guest.CloneAndUpdaters.class);
        Report report = Fixtures.run(Fixtures.config(Fixtures.jar(temp, Map.of(),
                dev.javabyte.guest.CloneAndUpdaters.class, Class.forName("dev.javabyte.guest.UpdaterOutsider"))));
        assertEquals(String.join("\n", expected), String.join("\n", Fixtures.stdout(report)), Fixtures.outcomes(report));
        assertEquals(List.of(), report.events().byCategory("gap"), "no emulation gaps expected");
        // Fixture self-check: the lines that show each case really ran on the real JVM.
        for (String line : List.of("sealed CloneNotSupportedException dev.javabyte.guest.CloneAndUpdaters$Sealed",
                "outsider RuntimeException caused by IllegalAccessException", "nestmate 1",
                "class java.util.concurrent.atomic.AtomicReferenceFieldUpdater$AtomicReferenceFieldUpdaterImpl true false",
                "class java.util.concurrent.atomic.AtomicIntegerFieldUpdater$AtomicIntegerFieldUpdaterImpl true true")) {
            assertTrue(expected.contains(line), "fixture self-check, missing \"" + line + "\": " + expected);
        }
    }
}
