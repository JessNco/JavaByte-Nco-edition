package dev.javabyte.cli;

import dev.javabyte.Emulator;
import dev.javabyte.model.ClassRepository;
import dev.javabyte.model.GuestClass;
import dev.javabyte.model.GuestMethod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;
import org.objectweb.asm.Opcodes;

/**
 * A static description of a sample: what could be run and what will probably need attention.
 * Nothing is executed. It exists so that the first config for a sample can be written from
 * facts rather than guesses.
 */
final class Inspection {

    private final Path path;
    private final long size;
    private String mainClass;
    private String agentClass;
    private int classCount;
    private final List<String> mainCandidates = new ArrayList<>();
    private final List<String> loaderClasses = new ArrayList<>();
    private final List<String> nativeMethods = new ArrayList<>();
    private final List<String> unparseable;
    private final Map<String, Integer> largestResources = new LinkedHashMap<>();
    private int invokeDynamicBootstraps;
    private final Map<String, dev.javabyte.report.NativeLibraryInfo> nativeLibraries = new LinkedHashMap<>();

    private Inspection(Path path, long size, List<String> unparseable) {
        this.path = path;
        this.size = size;
        this.unparseable = unparseable;
    }

    /**
     * Reads a jar, class file or directory of classes without running anything, and describes
     * it. The sample is loaded by {@link Emulator#loadSample}, the same code {@code run} uses.
     *
     * @throws dev.javabyte.config.ConfigLoader.ConfigException if the path does not exist or is
     *         not a sample
     */
    static Inspection of(Path path) throws IOException {
        if (!Files.exists(path)) {
            // A usage error (exit code 2), not an emulator failure: the caller named a wrong file.
            throw new dev.javabyte.config.ConfigLoader.ConfigException("Sample not found: " + path);
        }
        ClassRepository repository = new ClassRepository();
        // Loaded exactly as 'run' loads it, so that the two agree on what the sample is.
        boolean directory = Files.isDirectory(path);
        byte[] bytes = directory ? new byte[0] : Files.readAllBytes(path);
        Manifest manifest = Emulator.loadSample(repository, path, bytes, "sample");
        long size = directory ? directorySize(path) : bytes.length;
        Inspection inspection = new Inspection(path, size, repository.unparseable());
        if (manifest != null) {
            inspection.mainClass = manifest.getMainAttributes().getValue("Main-Class");
            inspection.agentClass = manifest.getMainAttributes().getValue("Premain-Class");
        }
        // The same list the no-entry-point message of 'run' gives.
        inspection.mainCandidates.addAll(Emulator.mainCandidates(repository));
        for (String name : new ArrayList<>(repository.classNames())) {
            GuestClass cls = repository.find(name);
            if (cls == null) {
                continue;
            }
            inspection.classCount++;
            String superName = cls.node.superName == null ? "" : cls.node.superName;
            if (superName.endsWith("ClassLoader")) {
                inspection.loaderClasses.add(name + " extends " + superName);
            }
            for (GuestMethod method : cls.declaredMethods()) {
                if (method.isNative) {
                    inspection.nativeMethods.add(method.id);
                }
                for (var insn : method.code) {
                    if (insn.getOpcode() == Opcodes.INVOKEDYNAMIC
                            && repository.contains(((org.objectweb.asm.tree.InvokeDynamicInsnNode) insn).bsm.getOwner())) {
                        inspection.invokeDynamicBootstraps++;
                    }
                }
            }
        }
        for (String name : repository.resourceNames()) {
            dev.javabyte.report.NativeLibraryInfo info = dev.javabyte.report.NativeLibraryInfo.parse(repository.resource(name));
            if (info != null) {
                inspection.nativeLibraries.put(name, info);
            }
        }
        repository.resourceNames().stream()
                .filter(name -> !name.endsWith(".class"))
                .sorted(Comparator.comparingInt((String name) -> repository.resource(name).length).reversed())
                .limit(10)
                .forEach(name -> inspection.largestResources.put(name, repository.resource(name).length));
        return inspection;
    }

    /** The total size of every regular file under a directory sample, in bytes. */
    private static long directorySize(Path directory) throws IOException {
        long total = 0;
        try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                total += Files.size(file);
            }
        }
        return total;
    }

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("path", path.toString());
        map.put("size", size);
        map.put("classCount", classCount);
        map.put("mainClass", mainClass);
        map.put("agentClass", agentClass);
        map.put("mainCandidates", mainCandidates);
        map.put("classLoaders", loaderClasses);
        map.put("nativeMethodCount", nativeMethods.size());
        map.put("nativeMethods", nativeMethods.stream().limit(50).toList());
        Map<String, Object> libraries = new LinkedHashMap<>();
        nativeLibraries.forEach((name, info) -> libraries.put(name, info.toMap()));
        map.put("nativeLibraries", libraries);
        map.put("obfuscatedCallSites", invokeDynamicBootstraps);
        map.put("largestResources", largestResources);
        map.put("unparseableClasses", unparseable);
        return map;
    }

    String toText() {
        StringBuilder text = new StringBuilder();
        text.append("Sample            ").append(path).append(" (").append(size).append(" bytes)\n");
        text.append("Classes           ").append(classCount).append('\n');
        text.append("Main-Class        ").append(mainClass == null ? "(none in manifest)" : mainClass).append('\n');
        if (agentClass != null) {
            text.append("Premain-Class     ").append(agentClass).append('\n');
        }
        text.append("Classes with main ").append(mainCandidates.isEmpty() ? "(none)" : String.join(", ", mainCandidates)).append('\n');
        text.append("Custom loaders    ").append(loaderClasses.isEmpty() ? "(none)" : String.join("; ", loaderClasses)).append('\n');
        text.append("Native methods    ").append(nativeMethods.size())
                .append(nativeMethods.isEmpty() ? "" : "  (cannot be emulated; calls are reported)").append('\n');
        nativeLibraries.forEach((name, info) -> text.append("  library ").append(name).append(": ")
                .append(info.format).append(' ').append(info.architecture).append(", ").append(info.binding()).append('\n'));
        text.append("Obfuscated calls  ").append(invokeDynamicBootstraps).append(" invokedynamic sites using the sample's own bootstrap\n");
        if (!largestResources.isEmpty()) {
            text.append("Largest resources\n");
            largestResources.forEach((name, length) -> text.append("  ").append(length).append("  ").append(name).append('\n'));
        }
        if (!unparseable.isEmpty()) {
            text.append("Unparseable classes ").append(unparseable.size()).append('\n');
        }
        return text.toString();
    }

    /** A minimal config with the choices a first run most often needs to revisit, as comments. */
    String starterConfig() {
        String entryClass = mainClass != null ? mainClass : mainCandidates.isEmpty() ? null : mainCandidates.get(0);
        StringBuilder yaml = new StringBuilder();
        yaml.append("# JavaByte config for ").append(path.getFileName()).append('\n');
        yaml.append("# Run with: javabyte run -c <this file>. Every option: javabyte defaults\n");
        yaml.append("sample: ").append(path).append("\n\n");
        yaml.append("entryPoints:\n");
        if (entryClass == null) {
            yaml.append("  # No main method was found. A static-initialiser sweep still decrypts most strings.\n");
            yaml.append("  - type: clinit-sweep\n");
        } else {
            yaml.append("  - type: main\n    class: ").append(entryClass).append("\n    args: []\n");
            yaml.append("  # Uncomment to also run every static initialiser (harvests decrypted strings):\n");
            yaml.append("  # - type: clinit-sweep\n");
        }
        yaml.append("\nlimits:\n  timeoutSeconds: 120\n  maxSleeps: 40        # ends beacon loops\n\n");
        yaml.append("environment:\n  os: windows          # or linux\n  userName: admin\n");
        yaml.append("  # files:             # files the sample should find\n");
        yaml.append("  #   - path: 'C:\\Users\\admin\\AppData\\Roaming\\config.dat'\n  #     text: 'example'\n\n");
        yaml.append("network:\n  mode: respond        # or offline\n");
        yaml.append("  # http:              # canned answers; first matching rule wins\n");
        yaml.append("  #   - url: 'example\\.com/gate'\n  #     body: 'OK'\n\n");
        yaml.append("# hooks:               # force a result, e.g. to pass an environment check\n");
        yaml.append("#   - method: 'com/example/Checks.isSandbox()Z'\n#     action: return\n#     value: false\n\n");
        yaml.append("output:\n  directory: javabyte-out\n");
        return yaml.toString();
    }
}
