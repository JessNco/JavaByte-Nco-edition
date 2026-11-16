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
        List<String> expected = Fixtures.realOutput(() -> Basics.main(new String[0]));
        Report report = Fixtures.run(Fixtures.config(Fixtures.jar(temp, Map.of(), Basics.class)));
        assertTrue(report.hasOutcome("completed"), Fixtures.outcomes(report));
        assertEquals(String.join("\n", expected), String.join("\n", Fixtures.stdout(report)), Fixtures.outcomes(report));
        assertEquals(List.of(), report.events().byCategory("gap"), "no emulation gaps expected");
    }

    @Test
    void jdkBoundaryFeaturesMatchRealJvm() throws Exception {
        java.util.List<String> expected = Fixtures.realOutput(() -> {
            try {
                dev.javabyte.guest.Advanced.main(new String[0]);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        });
        Report report = Fixtures.run(Fixtures.config(Fixtures.jar(temp, Map.of(), dev.javabyte.guest.Advanced.class)));
        assertEquals(String.join("\n", expected), String.join("\n", Fixtures.stdout(report)), Fixtures.outcomes(report));
        assertEquals(List.of(), report.events().byCategory("gap"), "no emulation gaps expected");
    }

    @Test
    void multiThreadedProgramMatchesRealJvm() throws Exception {
        java.util.List<String> expected = Fixtures.realOutput(() -> {
            try {
                dev.javabyte.guest.Concurrency.main(new String[0]);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Report report = Fixtures.run(Fixtures.config(Fixtures.jar(temp, Map.of(), dev.javabyte.guest.Concurrency.class)));
        assertEquals(String.join("\n", expected), String.join("\n", Fixtures.stdout(report)), Fixtures.outcomes(report));
        assertEquals(0, Fixtures.events(report, "thread", "stopped").size(), "no thread should have hung: " + Fixtures.outcomes(report));
        assertEquals(List.of(), report.events().byCategory("gap"), "no emulation gaps expected");
    }

    @Test
    void stackTraceKeyedDecryptionMatchesRealJvm() throws Exception {
        List<String> expected = Fixtures.realOutput(() -> StackKeyed.main(new String[0]));
        Report report = Fixtures.run(Fixtures.config(Fixtures.jar(temp, Map.of(), StackKeyed.class)));
        assertEquals(expected, Fixtures.stdout(report), Fixtures.outcomes(report));
        assertEquals("alpha payload", expected.get(0), "fixture self-check");
    }
}
