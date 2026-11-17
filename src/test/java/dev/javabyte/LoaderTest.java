package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.guest.Basics;
import dev.javabyte.guest.sim.Loaders;
import dev.javabyte.guest.sim.Payload;
import dev.javabyte.report.Event;
import dev.javabyte.report.Report;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Staged payload delivery and misleading archives.
 *
 * <p>The first group checks that a second stage is followed wherever it comes from: the
 * payload class is absent from the sample, so its process launch can only be observed if the
 * emulator defined and interpreted the delivered bytes. The second group feeds the loader
 * archives built to confuse analysis tools.
 */
class LoaderTest {

    @TempDir
    Path temp;

    /** A jar holding only the second stage, as a server would deliver it. */
    private byte[] stageJar() throws Exception {
        Path jar = Fixtures.rawJar(temp.resolve("stage-source.jar"), null,
                Map.of(Fixtures.entryName(Payload.class), Fixtures.classBytes(Payload.class)));
        return Files.readAllBytes(jar);
    }

    private EmulatorConfig loaderConfig(String mode, Map<String, byte[]> resources) throws Exception {
        EmulatorConfig config = Fixtures.config(Fixtures.jar(temp, resources, Loaders.class));
        EmulatorConfig.EntryPoint entry = new EmulatorConfig.EntryPoint();
        entry.args.add(mode);
        config.entryPoints.add(entry);
        EmulatorConfig.HttpRule rule = new EmulatorConfig.HttpRule();
        rule.url = "cdn\\.loader\\.test/stage\\.jar";
        rule.bodyBase64 = Base64.getEncoder().encodeToString(stageJar());
        config.network.http.add(rule);
        return config;
    }

    /** Runs with an output directory, so that artifacts are really written. */
    private Report run(EmulatorConfig config) throws Exception {
        return Emulator.run(config, Files.createTempDirectory(temp, "out"));
    }

    /**
     * @param artifactGroup where the second stage must have been saved: {@code defined-classes}
     *     for bytes handed to a define call, {@code loaded-archives} for a jar a loader fetched
     */
    private static void assertPayloadRan(Report report, String mode, String artifactGroup) {
        assertEquals(List.of("payload ran via " + mode), Fixtures.stdout(report), Fixtures.outcomes(report));
        assertEquals("stage2-" + mode + ".exe", Fixtures.only(report, "process", "exec").data().get("command"));
        if (artifactGroup.equals("defined-classes")) {
            Event defined = Fixtures.only(report, "class", "define");
            assertEquals("dev.javabyte.guest.sim.Payload", String.valueOf(defined.data().get("name")).replace('/', '.'));
        } else {
            assertEquals(Boolean.TRUE, Fixtures.only(report, "class", "load-archive").data().get("found"));
        }
        List<?> artifacts = (List<?>) report.toMap().get("artifacts");
        assertTrue(artifacts.stream().anyMatch(a -> a.toString().contains(artifactGroup)),
                "the second stage must be saved for the analyst: " + artifacts);
    }

    @Test
    void urlClassLoaderOverHttpIsFollowed() throws Exception {
        Report report = run(loaderConfig("url-http", Map.of()));
        assertPayloadRan(report, "url-http", "loaded-archives");
        assertFalse(Fixtures.events(report, "net", "http").isEmpty(), "the download must be reported");
    }

    @Test
    void downloadedAndDroppedJarIsFollowed() throws Exception {
        Report report = run(loaderConfig("url-file", Map.of()));
        assertPayloadRan(report, "url-file", "loaded-archives");
        assertTrue(Fixtures.events(report, "file", "write").stream()
                .anyMatch(e -> String.valueOf(e.data().get("path")).endsWith("stage.jar")), Fixtures.outcomes(report));
    }

    @Test
    void lookupDefineClassOfDecryptedResourceIsFollowed() throws Exception {
        byte[] encrypted = Fixtures.classBytes(Payload.class);
        for (int i = 0; i < encrypted.length; i++) {
            encrypted[i] ^= 0x41;
        }
        Report report = run(loaderConfig("lookup", Map.of("payload.enc", encrypted)));
        assertPayloadRan(report, "lookup", "defined-classes");
    }

    /** {@code Unsafe.defineClass} was removed in Java 11, yet samples written for Java 8 still call it. */
    @Test
    void unsafeDefineClassIsFollowed() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("UnsafeLoader.class", GeneratedClasses.unsafeLoader("UnsafeLoader"));
        entries.put("payload.bin", Fixtures.classBytes(Payload.class));
        Report report = run(Fixtures.config(Fixtures.rawJar(temp.resolve("unsafe.jar"), "UnsafeLoader", entries)));
        assertPayloadRan(report, "unsafe", "defined-classes");
    }

    // ------------------------------------------------------------------ misleading archives

    /**
     * Entries that look like classes but are not: a text file named {@code .class}, a folder
     * named {@code X.class/} (which makes some unpackers overwrite or skip the real class), and
     * a file that starts like a class and then stops.
     */
    @Test
    void decoyEntriesDoNotStopTheRealClasses() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(Fixtures.entryName(Basics.class) + "/", new byte[0]);
        entries.put(Fixtures.entryName(Basics.class) + "/decoy.txt", "not a class".getBytes());
        entries.put("Decoy.class", "this is not bytecode".getBytes());
        entries.put("Truncated.class", new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 52, 0});
        addWithNested(entries, Basics.class);
        Path jar = Fixtures.rawJar(temp.resolve("decoys.jar"), Basics.class.getName(), entries);

        List<String> expected = Fixtures.realOutput(Basics.class);
        Report report = Fixtures.run(Fixtures.config(jar));
        assertEquals(expected, Fixtures.stdout(report), Fixtures.outcomes(report));
        // Classes are parsed when first needed, so a broken class nobody uses costs nothing.
        assertEquals(List.of(), report.events().byCategory("gap"));

        // Once something asks for every class, the broken one is reported, exactly once, and
        // the decoys without class magic are still not mistaken for classes.
        EmulatorConfig sweeping = Fixtures.config(jar);
        EmulatorConfig.EntryPoint sweep = new EmulatorConfig.EntryPoint();
        sweep.type = "clinit-sweep";
        sweeping.entryPoints.add(sweep);
        List<Event> gaps = Fixtures.run(sweeping).events().byCategory("gap");
        assertEquals(1, gaps.size(), gaps.toString());
        assertEquals("unparseable-class", gaps.get(0).action());
        assertTrue(String.valueOf(gaps.get(0).data().get("detail")).contains("Truncated"), gaps.toString());
    }

    @Test
    void archiveWithoutManifestNeedsAnExplicitEntryPoint() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        addWithNested(entries, Basics.class);
        Path jar = Fixtures.rawJar(temp.resolve("nomanifest.jar"), null, entries);

        Report first = Fixtures.run(Fixtures.config(jar));
        assertTrue(first.hasOutcome("no-entry-point"), Fixtures.outcomes(first));
        assertTrue(first.runs().get(0).detail().contains(Basics.class.getName().replace('.', '/'))
                        || first.runs().get(0).detail().contains(Basics.class.getName()),
                "the message should offer the classes that have a main method: " + first.runs().get(0).detail());

        EmulatorConfig config = Fixtures.config(jar);
        EmulatorConfig.EntryPoint entry = new EmulatorConfig.EntryPoint();
        entry.className = Basics.class.getName();
        config.entryPoints.add(entry);
        Report second = Fixtures.run(config);
        assertTrue(second.hasOutcome("completed"), Fixtures.outcomes(second));
    }

    @Test
    void bareClassFileAndDirectoryAreAcceptedAsSamples() throws Exception {
        byte[] bytes = GeneratedClasses.manyLocals("Solo");
        Path classFile = Files.write(temp.resolve("Solo.class"), bytes);
        EmulatorConfig single = Fixtures.config(classFile);
        EmulatorConfig.EntryPoint entry = new EmulatorConfig.EntryPoint();
        entry.className = "Solo";
        single.entryPoints.add(entry);
        assertEquals(List.of("1599"), Fixtures.stdout(Fixtures.run(single)));

        Path directory = Files.createDirectory(temp.resolve("exploded"));
        Files.write(directory.resolve("Solo.class"), bytes);
        EmulatorConfig exploded = Fixtures.config(directory);
        exploded.entryPoints.add(entry);
        assertEquals(List.of("1599"), Fixtures.stdout(Fixtures.run(exploded)));
    }

    /** Adds a class and its nested classes under their real entry names. */
    private static void addWithNested(Map<String, byte[]> entries, Class<?> cls) throws Exception {
        Path directory = Path.of(cls.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve(cls.getPackageName().replace('.', '/'));
        try (var files = Files.list(directory)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (name.equals(cls.getSimpleName() + ".class") || name.startsWith(cls.getSimpleName() + "$")) {
                    entries.put(cls.getPackageName().replace('.', '/') + '/' + name, Files.readAllBytes(file));
                }
            }
        }
    }
}
