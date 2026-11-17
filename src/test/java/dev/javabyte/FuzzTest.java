package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.javabyte.config.ConfigLoader.ConfigException;
import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.guest.Advanced;
import dev.javabyte.guest.Basics;
import dev.javabyte.report.Report;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Robustness against hostile input. Malware authors ship class files that no compiler would
 * produce and that a real JVM's verifier would reject; the emulator has no verifier, so its
 * interpreter meets that bytecode directly.
 *
 * <p>The property checked is the same for every mutated input: the emulator either refuses
 * the sample with a config error or produces a report, within its limits, and never with the
 * outcome {@code internal-error}. What the damaged program then does is not checked.
 *
 * <p>Mutations are driven by a fixed seed so a failure can be reproduced; the failing
 * iteration is named in the message. Raise the iteration count with
 * {@code -Djavabyte.fuzz.iterations=5000} for a longer hunt.
 */
class FuzzTest {

    private static final int ITERATIONS = Integer.getInteger("javabyte.fuzz.iterations", 150);
    private static final long SEED = Long.getLong("javabyte.fuzz.seed", 20_250_601L);

    @TempDir
    Path temp;

    /** The fixture's classes by archive entry name; the mutated copy replaces one of them. */
    private Map<String, byte[]> classes(Class<?> fixture) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        Path directory = Path.of(fixture.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve(fixture.getPackageName().replace('.', '/'));
        try (var files = Files.list(directory)) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                String name = file.getFileName().toString();
                if (name.equals(fixture.getSimpleName() + ".class") || name.startsWith(fixture.getSimpleName() + "$")) {
                    entries.put(fixture.getPackageName().replace('.', '/') + '/' + name, Files.readAllBytes(file));
                }
            }
        }
        return entries;
    }

    /** Runs one mutated sample under tight limits and returns a description of any violation. */
    private String survive(Path sample, String mainClass) {
        EmulatorConfig config = Fixtures.config(sample);
        config.limits.timeoutSeconds = 10;
        config.limits.maxInstructions = 3_000_000;
        config.limits.maxSleeps = 3;
        if (mainClass != null) {
            EmulatorConfig.EntryPoint entry = new EmulatorConfig.EntryPoint();
            entry.className = mainClass;
            config.entryPoints.add(entry);
        }
        long started = System.nanoTime();
        try {
            Report report = Fixtures.run(config);
            if (report.hasOutcome("internal-error")) {
                return Fixtures.outcomes(report);
            }
        } catch (ConfigException refused) {
            // Refusing an unreadable sample is a correct answer.
        } catch (Throwable escaped) {
            return "exception escaped the emulator: " + escaped;
        }
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        return seconds > 20 ? "Error: run took " + seconds + " s despite a 10 s limit" : null;
    }

    private void report(List<String> failures, int total) {
        if (failures.isEmpty()) {
            return;
        }
        // Hundreds of failures usually share a handful of causes; show each cause once.
        Map<String, Integer> causes = new java.util.TreeMap<>();
        for (String failure : failures) {
            String cause = failure.lines().filter(line -> line.contains("Exception") || line.contains("Error")).findFirst().orElse(failure);
            causes.merge(cause.replaceAll("\\d+", "N"), 1, Integer::sum);
        }
        fail(failures.size() + " of " + total + " mutated samples broke the emulator (seed " + SEED + ").\nCauses: " + causes
                + "\nFirst:\n" + failures.get(0));
    }

    /** Random byte damage anywhere in a class file: constant pool, attributes, code. */
    @Test
    void corruptedClassFilesNeverCrashTheEmulator() throws Exception {
        Random random = new Random(SEED);
        List<String> failures = new ArrayList<>();
        for (Class<?> fixture : List.of(Basics.class, Advanced.class)) {
            Map<String, byte[]> original = classes(fixture);
            List<String> names = new ArrayList<>(original.keySet());
            for (int i = 0; i < ITERATIONS; i++) {
                Map<String, byte[]> entries = new LinkedHashMap<>(original);
                String victim = names.get(random.nextInt(names.size()));
                byte[] bytes = original.get(victim).clone();
                switch (random.nextInt(4)) {
                    case 0 -> bytes = java.util.Arrays.copyOf(bytes, 10 + random.nextInt(bytes.length - 10));
                    case 1 -> random.nextBytes(bytes = java.util.Arrays.copyOfRange(bytes, 0, bytes.length)); // all noise
                    default -> {
                        // Skip the first 10 bytes so the file still looks like a class.
                        for (int flips = 1 + random.nextInt(6); flips > 0; flips--) {
                            bytes[10 + random.nextInt(bytes.length - 10)] = (byte) random.nextInt(256);
                        }
                    }
                }
                entries.put(victim, bytes);
                Path jar = Fixtures.rawJar(temp.resolve("bytes-" + fixture.getSimpleName() + '-' + i + ".jar"), fixture.getName(), entries);
                String problem = survive(jar, null);
                if (problem != null) {
                    failures.add(fixture.getSimpleName() + " iteration " + i + ", entry " + victim + ":\n" + problem);
                }
                Files.delete(jar);
            }
        }
        report(failures, ITERATIONS * 2);
    }

    /**
     * Damage that keeps the class file well formed but makes the code invalid: instructions
     * replaced, removed or duplicated. This is what reaches the interpreter loop, which a
     * verifier would normally protect: stack underflow, wrong operand types, falling off the
     * end of a method.
     */
    @Test
    void unverifiableBytecodeNeverCrashesTheEmulator() throws Exception {
        Random random = new Random(SEED + 1);
        List<String> failures = new ArrayList<>();
        Map<String, byte[]> original = classes(Basics.class);
        String mainEntry = Fixtures.entryName(Basics.class);
        for (int i = 0; i < ITERATIONS * 2; i++) {
            ClassNode node = new ClassNode();
            new ClassReader(original.get(mainEntry)).accept(node, ClassReader.SKIP_FRAMES);
            List<MethodNode> withCode = node.methods.stream().filter(m -> m.instructions.size() > 4).toList();
            for (int edits = 1 + random.nextInt(3); edits > 0; edits--) {
                MethodNode method = withCode.get(random.nextInt(withCode.size()));
                AbstractInsnNode target = method.instructions.get(random.nextInt(method.instructions.size()));
                if (target.getOpcode() < 0) {
                    continue; // a label or line number
                }
                switch (random.nextInt(4)) {
                    case 0 -> method.instructions.remove(target);
                    case 1 -> method.instructions.insert(target, new InsnNode(SIMPLE_OPCODES[random.nextInt(SIMPLE_OPCODES.length)]));
                    case 2 -> method.instructions.set(target, new InsnNode(SIMPLE_OPCODES[random.nextInt(SIMPLE_OPCODES.length)]));
                    default -> method.instructions.insertBefore(target, new IntInsnNode(Opcodes.SIPUSH, random.nextInt(65536) - 32768));
                }
            }
            // Version 49 needs no stack map frames, so ASM can write code that makes no sense.
            node.version = Opcodes.V1_5;
            ClassWriter writer = new ClassWriter(0);
            byte[] mutated;
            try {
                node.accept(writer);
                mutated = writer.toByteArray();
            } catch (RuntimeException unwritable) {
                continue; // the mutation cannot be expressed as a class file at all
            }
            Map<String, byte[]> entries = new LinkedHashMap<>(original);
            entries.put(mainEntry, mutated);
            Path jar = Fixtures.rawJar(temp.resolve("code-" + i + ".jar"), Basics.class.getName(), entries);
            String problem = survive(jar, null);
            if (problem != null) {
                failures.add("iteration " + i + ":\n" + problem);
            }
            Files.delete(jar);
        }
        report(failures, ITERATIONS * 2);
    }

    /** Opcodes without operands, covering every stack effect: pushes, pops, shuffles, arithmetic, returns, throws. */
    private static final int[] SIMPLE_OPCODES = {
        Opcodes.NOP, Opcodes.ACONST_NULL, Opcodes.ICONST_0, Opcodes.LCONST_1, Opcodes.FCONST_2, Opcodes.DCONST_1,
        Opcodes.IALOAD, Opcodes.AALOAD, Opcodes.BASTORE, Opcodes.AASTORE, Opcodes.POP, Opcodes.POP2, Opcodes.DUP,
        Opcodes.DUP_X1, Opcodes.DUP_X2, Opcodes.DUP2, Opcodes.DUP2_X1, Opcodes.DUP2_X2, Opcodes.SWAP, Opcodes.IADD,
        Opcodes.LMUL, Opcodes.FDIV, Opcodes.DREM, Opcodes.INEG, Opcodes.ISHL, Opcodes.LUSHR, Opcodes.IAND, Opcodes.LXOR,
        Opcodes.I2L, Opcodes.D2I, Opcodes.I2B, Opcodes.LCMP, Opcodes.FCMPG, Opcodes.IRETURN, Opcodes.ARETURN,
        Opcodes.RETURN, Opcodes.ARRAYLENGTH, Opcodes.ATHROW, Opcodes.MONITORENTER, Opcodes.MONITOREXIT, Opcodes.IDIV,
    };

    /** Damage to the archive itself rather than to a class in it. */
    @Test
    void corruptedArchivesAreRefusedOrReported() throws Exception {
        Random random = new Random(SEED + 2);
        byte[] original = Files.readAllBytes(Fixtures.jar(Files.createDirectory(temp.resolve("src")), Map.of(), Basics.class));
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            byte[] bytes = original.clone();
            if (random.nextBoolean()) {
                bytes = java.util.Arrays.copyOf(bytes, 4 + random.nextInt(bytes.length - 4));
            }
            for (int flips = random.nextInt(12); flips > 0; flips--) {
                bytes[2 + random.nextInt(bytes.length - 2)] = (byte) random.nextInt(256);
            }
            Path jar = Files.write(temp.resolve("archive-" + i + ".jar"), bytes);
            String problem = survive(jar, Basics.class.getName());
            if (problem != null) {
                failures.add("iteration " + i + ":\n" + problem);
            }
            Files.delete(jar);
        }
        report(failures, ITERATIONS);
    }

    /**
     * {@code javabyte disasm} reads class files from samples and from what samples create, so
     * damaged or hostile ones must give a clean error in bounded time, never a crash or a hang.
     */
    @Test
    void corruptedClassFilesNeverCrashTheDisassembler() throws Exception {
        Random random = new Random(SEED);
        List<String> failures = new ArrayList<>();
        List<byte[]> originals = new ArrayList<>(classes(Basics.class).values());
        originals.addAll(classes(Advanced.class).values());
        int total = ITERATIONS * 4;
        for (int i = 0; i < total; i++) {
            byte[] bytes = originals.get(random.nextInt(originals.size())).clone();
            switch (random.nextInt(4)) {
                case 0 -> bytes = java.util.Arrays.copyOf(bytes, 4 + random.nextInt(bytes.length - 4));
                case 1 -> {
                    random.nextBytes(bytes);
                    bytes[0] = (byte) 0xCA; bytes[1] = (byte) 0xFE; bytes[2] = (byte) 0xBA; bytes[3] = (byte) 0xBE;
                }
                default -> {
                    for (int flips = 1 + random.nextInt(8); flips > 0; flips--) {
                        bytes[10 + random.nextInt(bytes.length - 10)] = (byte) random.nextInt(256);
                    }
                }
            }
            long started = System.nanoTime();
            try {
                dev.javabyte.cli.DisassemblyAccess.text(bytes);
            } catch (dev.javabyte.config.ConfigLoader.ConfigException expected) {
                // A clean, explained refusal.
            } catch (Throwable t) {
                failures.add("iteration " + i + ": " + t);
            }
            long millis = (System.nanoTime() - started) / 1_000_000;
            if (millis > 5_000) {
                failures.add("iteration " + i + " took " + millis + " ms");
            }
        }
        report(failures, total);
    }

    @Test
    void seedAndIterationCountAreUsable() {
        assertTrue(ITERATIONS > 0, "javabyte.fuzz.iterations must be positive");
    }
}
