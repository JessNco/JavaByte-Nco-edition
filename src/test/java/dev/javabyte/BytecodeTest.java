package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.guest.sim.IndyTargets;
import dev.javabyte.report.Report;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Differential tests for bytecode that Java source cannot produce: every stack-shuffling
 * form, JSR/RET subroutines, wide instructions and obfuscator-style {@code invokedynamic}.
 *
 * <p>The classes come from {@link GeneratedClasses}. Each is run on the real JVM and in the
 * emulator, and the two outputs must be identical. The real JVM is the oracle, so a mistake in
 * a generator cannot hide an emulator bug: at worst both sides print something unexpected,
 * which the self-check lines below catch.
 */
class BytecodeTest {

    @TempDir
    Path temp;

    private List<String> compare(String name, Function<String, byte[]> generator, Class<?>... helpers) throws Exception {
        byte[] bytes = generator.apply(name);
        List<String> expected = Fixtures.realOutput(name, bytes);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(name + ".class", bytes);
        for (Class<?> helper : helpers) {
            entries.put(Fixtures.entryName(helper), Fixtures.classBytes(helper));
        }
        Report report = Fixtures.run(Fixtures.config(Fixtures.rawJar(temp.resolve(name + ".jar"), name, entries)));
        assertTrue(report.hasOutcome("completed"), Fixtures.outcomes(report));
        assertEquals(expected, Fixtures.stdout(report), Fixtures.outcomes(report));
        assertEquals(List.of(), report.events().byCategory("gap"), "no emulation gaps expected");
        return expected;
    }

    @Test
    void everyStackShufflingFormMatchesRealJvm() throws Exception {
        List<String> output = compare("StackOps", GeneratedClasses::stackOps);
        // Self-check of the generator: the comments in it state these values.
        assertEquals(List.of("-10", "-25", "1018", "34", "441", "-4", "-8", "-28", "-57", "36", "105", "-40", "3111"), output);
    }

    @Test
    void jsrRetSubroutinesMatchRealJvm() throws Exception {
        assertEquals(List.of("21"), compare("Subroutine", GeneratedClasses::subroutine));
    }

    @Test
    void wideInstructionsMatchRealJvm() throws Exception {
        assertEquals(List.of("1599"), compare("ManyLocals", GeneratedClasses::manyLocals));
    }

    /**
     * The sample's own bootstrap methods are interpreted like any other guest code, so the
     * emulator resolves hidden call targets the same way the JVM would.
     */
    @Test
    void customInvokeDynamicBootstrapsMatchRealJvm() throws Exception {
        List<String> output = compare("HiddenCalls", GeneratedClasses::hiddenCalls, IndyTargets.class);
        assertEquals(List.of("42", "43", "HEY!HEY!", "19", "second", "42", "2"), output);
    }

    /**
     * The emulator has no verifier, so invalid code is discovered when it runs. It must end as
     * the sample's own failure, flagged as a gap, and not as a defect of the emulator.
     */
    @Test
    void unverifiableCodeBecomesAVerifyErrorAndAGap() throws Exception {
        Map<String, byte[]> entries = Map.of("Underflow.class", GeneratedClasses.stackUnderflow("Underflow"));
        Report report = Fixtures.run(Fixtures.config(Fixtures.rawJar(temp.resolve("underflow.jar"), "Underflow", entries)));
        assertEquals(List.of("before the bad instruction"), Fixtures.stdout(report));
        assertTrue(report.hasOutcome("uncaught-exception"), Fixtures.outcomes(report));
        assertTrue(report.runs().get(0).detail().startsWith("java.lang.VerifyError"), report.runs().get(0).detail());
        assertEquals("Underflow.main([Ljava/lang/String;)V", Fixtures.only(report, "gap", "invalid-bytecode").data().get("method"));
    }
}
