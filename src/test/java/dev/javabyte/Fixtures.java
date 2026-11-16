package dev.javabyte;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.report.Event;
import dev.javabyte.report.Report;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/** Helpers that package compiled test fixtures as sample jars and run them in the emulator. */
final class Fixtures {

    private Fixtures() {}

    /** Bytes of a compiled test class, read as data from the test output directory. */
    static byte[] classBytes(Class<?> cls) throws IOException {
        try (InputStream in = cls.getResourceAsStream('/' + cls.getName().replace('.', '/') + ".class")) {
            return in.readAllBytes();
        }
    }

    /**
     * Builds a jar holding the given classes with their nested classes, plus extra resources.
     * The first class becomes the Main-Class.
     */
    static Path jar(Path directory, Map<String, byte[]> resources, Class<?>... classes) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, classes[0].getName());
        Path jar = directory.resolve(classes[0].getSimpleName().toLowerCase() + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (Class<?> cls : classes) {
                Path classFile = Path.of(cls.getProtectionDomain().getCodeSource().getLocation().getPath())
                        .resolve(cls.getName().replace('.', '/') + ".class");
                String prefix = cls.getSimpleName();
                try (Stream<Path> siblings = Files.list(classFile.getParent())) {
                    for (Path file : (Iterable<Path>) siblings::iterator) {
                        String name = file.getFileName().toString();
                        if (name.equals(prefix + ".class") || name.startsWith(prefix + "$")) {
                            out.putNextEntry(new JarEntry(cls.getPackageName().replace('.', '/') + '/' + name));
                            out.write(Files.readAllBytes(file));
                            out.closeEntry();
                        }
                    }
                }
            }
            for (Map.Entry<String, byte[]> resource : resources.entrySet()) {
                out.putNextEntry(new JarEntry(resource.getKey()));
                out.write(resource.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

    static EmulatorConfig config(Path sample) {
        EmulatorConfig config = new EmulatorConfig();
        config.sample = sample.toString();
        config.limits.timeoutSeconds = 60;
        return config;
    }

    static Report run(EmulatorConfig config) throws IOException {
        return Emulator.run(config, null);
    }

    /** Lines the guest printed to standard output. */
    static List<String> stdout(Report report) {
        return report.events().events().stream()
                .filter(event -> event.category().equals("console") && event.action().equals("stdout"))
                .map(event -> (String) event.data().get("text"))
                .toList();
    }

    static List<Event> events(Report report, String category, String action) {
        return report.events().events().stream()
                .filter(event -> event.category().equals(category) && event.action().equals(action))
                .toList();
    }

    /** Runs a harmless fixture's main method on the real JVM and returns what it printed. */
    static List<String> realOutput(Runnable main) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.ISO_8859_1));
        try {
            main.run();
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.ISO_8859_1).lines().toList();
    }

    static String outcomes(Report report) {
        StringBuilder text = new StringBuilder();
        report.runs().forEach(run -> text.append(run.entry()).append(" -> ").append(run.outcome())
                .append(run.detail() == null ? "" : ": " + run.detail()).append('\n'));
        report.events().byCategory("gap").forEach(event -> text.append(event.toLine()).append('\n'));
        return text.toString();
    }
}
