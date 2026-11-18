package dev.javabyte;

import dev.javabyte.config.ConfigLoader.ConfigException;
import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.model.ClassRepository;
import dev.javabyte.model.GuestClass;
import dev.javabyte.model.GuestMethod;
import dev.javabyte.plugin.EmulatorPlugin;
import dev.javabyte.report.Artifacts;
import dev.javabyte.report.EventLog;
import dev.javabyte.report.Report;
import dev.javabyte.stubs.BuiltinStubs;
import dev.javabyte.vm.EmulationAbort;
import dev.javabyte.vm.GuestThrow;
import dev.javabyte.vm.InvocationRouter;
import dev.javabyte.vm.Scheduler;
import dev.javabyte.vm.Values;
import dev.javabyte.vm.VirtualMachine;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.objectweb.asm.Type;

/**
 * Runs one emulation from a config and produces a {@link Report}. This is the programmatic
 * entry point; the command-line tool is a thin wrapper around it.
 */
public final class Emulator {

    /**
     * Host stack for the emulator thread. The interpreter recurses once per guest call, and
     * obfuscated code nests deeply, so the default thread stack would overflow long before the
     * guest's own call-depth limit is reached.
     */
    private static final long EMULATOR_STACK_BYTES = 1L << 30;

    /** Extra time the emulator thread is given to notice cancellation before it is abandoned. */
    private static final long CANCEL_GRACE_MILLIS = 5_000;

    private Emulator() {}

    /**
     * @param outputDirectory where the trace, report and artifacts are written; null keeps
     *                        everything in memory (used by tests)
     */
    public static Report run(EmulatorConfig config, Path outputDirectory) throws IOException {
        return run(config, outputDirectory, new RunControl());
    }

    /**
     * Runs with a control, through which the caller watches the run live and can stop it.
     *
     * @param control used for this run only
     */
    public static Report run(EmulatorConfig config, Path outputDirectory, RunControl control) throws IOException {
        try {
            return runControlled(config, outputDirectory, control);
        } finally {
            control.markFinished();
        }
    }

    private static Report runControlled(EmulatorConfig config, Path outputDirectory, RunControl control) throws IOException {
        if (config.sample == null) {
            throw new ConfigException("No sample given: set 'sample' in the config or pass it on the command line");
        }
        Path samplePath = Path.of(config.sample);
        if (!Files.exists(samplePath)) {
            throw new ConfigException("Sample not found: " + samplePath);
        }
        Writer trace = null;
        if (outputDirectory != null) {
            Files.createDirectories(outputDirectory);
            trace = Files.newBufferedWriter(outputDirectory.resolve("trace.log"), StandardCharsets.UTF_8);
        }
        // Declared before the try so that a failure part way through installing plugins, or
        // while loading the sample, still closes the plugins installed so far.
        List<EmulatorPlugin> installed = new ArrayList<>();
        EventLog events = null;
        try {
            events = new EventLog(config.limits.maxEventsPerCategory, trace);
            VirtualMachine vm = new VirtualMachine(config, events, new Artifacts(outputDirectory));
            control.attach(vm);
            BuiltinStubs.install(vm);
            for (EmulatorPlugin plugin : ServiceLoader.load(EmulatorPlugin.class)) {
                if (config.plugins.containsKey(plugin.id())) {
                    Map<String, Object> settings = config.plugins.get(plugin.id());
                    plugin.install(vm, settings == null ? Map.of() : settings);
                    installed.add(plugin);
                    events.add("lifecycle", "plugin-installed", "id", plugin.id());
                }
            }

            byte[] sampleBytes = Files.isDirectory(samplePath) ? new byte[0] : Files.readAllBytes(samplePath);
            Manifest manifest = loadSample(vm.classes, samplePath, sampleBytes, "sample");
            for (String extra : config.classpath) {
                Path extraPath = Path.of(extra);
                loadSample(vm.classes, extraPath, Files.isDirectory(extraPath) ? new byte[0] : Files.readAllBytes(extraPath),
                        "classpath");
            }
            vm.env.initialise(samplePath.getFileName().toString(), sampleBytes);

            String mainClass = manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class");
            Report report = new Report(config, samplePath, sampleBytes, mainClass, vm);
            report.setRunListener(control::entryFinished);
            control.started(report);
            List<EmulatorConfig.EntryPoint> entries = config.entryPoints;
            if (entries.isEmpty()) {
                entries = List.of(new EmulatorConfig.EntryPoint());
            }

            List<EmulatorConfig.EntryPoint> plan = entries;
            Thread emulatorThread = new Thread(null, () -> runAll(vm, plan, mainClass, report, control),
                    "javabyte-emulator", EMULATOR_STACK_BYTES);
            // A daemon thread cannot keep the tool alive if guest-driven JDK code blocks forever.
            emulatorThread.setDaemon(true);
            emulatorThread.start();
            boolean stopped = false;
            try {
                // Waits in short steps so that a stop request is acted on even while the sample is
                // stuck inside a JDK call, where the emulator thread cannot notice it.
                long deadline = System.nanoTime() + config.limits.timeoutSeconds * 1_000_000_000L;
                while (emulatorThread.isAlive() && System.nanoTime() < deadline && !control.stopRequested()) {
                    emulatorThread.join(100);
                }
                if (emulatorThread.isAlive()) {
                    stopped = control.stopRequested();
                    vm.cancel(stopped ? EmulationAbort.Reason.STOPPED : EmulationAbort.Reason.TIMEOUT);
                    emulatorThread.join(CANCEL_GRACE_MILLIS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (emulatorThread.isAlive()) {
                // The thread is not at an instruction boundary, so it must be inside a real JDK call.
                report.addRun("(run)", stopped ? "stopped" : "timeout",
                        (stopped ? "stopped while blocked in host call " : "emulator thread blocked in host call ") + vm.hostCallInProgress(), 0);
            }
            control.finalProgress();
            try {
                // Also done after a hard timeout: the stuck thread is parked inside the JDK, and
                // losing every dropped file would throw away the evidence gathered so far.
                finish(vm, config);
            } catch (RuntimeException e) {
                vm.events.add("gap", "finish-failed", "error", e.toString());
            }
            // Closed before the report is completed, so that a failure to close is in the report.
            closePlugins(installed, events);
            report.complete();
            if (outputDirectory != null) {
                report.writeJson(outputDirectory.resolve("report.json"));
            }
            events.flush();
            if (trace != null) {
                Writer finished = trace;
                trace = null;
                finished.close();
            }
            return report;
        } finally {
            // Only reached with work left to do when the run failed before its report was
            // written, for example on a classpath entry that is not a jar.
            closePlugins(installed, events);
            if (trace != null) {
                try {
                    trace.close();
                } catch (IOException ignored) {
                    // The run has already failed; that failure is the one worth reporting.
                }
            }
        }
    }

    /**
     * Closes every installed plugin and empties the list, so a second call does nothing. A plugin
     * that fails to close is recorded as a gap and does not stop the others from closing.
     *
     * @param events where failures are recorded; null if the event log was never created
     */
    private static void closePlugins(List<EmulatorPlugin> installed, EventLog events) {
        for (EmulatorPlugin plugin : installed) {
            try {
                plugin.close();
            } catch (RuntimeException e) {
                if (events != null) {
                    events.add("gap", "plugin-close-failed", "id", plugin.id(), "error", e.toString());
                }
            }
        }
        installed.clear();
    }

    /** Collects what is only known once execution has stopped: unflushed output and dropped files. */
    private static void finish(VirtualMachine vm, EmulatorConfig config) {
        vm.env.files.commitOpenStreams();
        vm.env.network.reportOpenSockets();
        if (config.output.dumpDroppedFiles) {
            vm.env.files.guestFiles().forEach(node -> vm.artifacts.save("dropped", node.path, node.data));
        }
        for (String failure : vm.classes.unparseable()) {
            vm.events.add("gap", "unparseable-class", "detail", failure);
        }
    }

    // ------------------------------------------------------------------ loading

    /**
     * Adds a jar, a class file or a directory of classes to a class repository. This is the one
     * definition of what counts as a sample, so that {@code run} and {@code inspect} agree on it.
     *
     * <p>A directory is read as an unpacked jar: every {@code .class} file under it becomes a
     * class, every file becomes a resource, and {@code META-INF/MANIFEST.MF} (in any letter case,
     * as Windows would find it) supplies the manifest, so that its Main-Class is honoured.
     *
     * @param bytes  the file's content; ignored for a directory
     * @param origin where the classes came from, as the report names it ({@code sample}, {@code classpath})
     * @return the manifest, or null if there is none
     * @throws ConfigException if the path is neither an archive, a class file nor a directory
     */
    public static Manifest loadSample(ClassRepository repository, Path path, byte[] bytes, String origin) throws IOException {
        if (Files.isDirectory(path)) {
            Manifest manifest = null;
            try (Stream<Path> files = Files.walk(path)) {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile).sorted()::iterator) {
                    String relative = path.relativize(file).toString().replace('\\', '/');
                    byte[] data = Files.readAllBytes(file);
                    if (relative.endsWith(".class")) {
                        repository.addClassBytes(relative.substring(0, relative.length() - ".class".length()), data, origin);
                    }
                    repository.addResource(relative, data);
                    if (relative.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                        manifest = new Manifest(new ByteArrayInputStream(data));
                    }
                }
            }
            return manifest;
        }
        if (isArchive(bytes)) {
            return repository.addArchive(bytes, origin);
        }
        if (!isClassFile(bytes)) {
            throw new ConfigException(path + " is neither a jar/zip archive nor a class file");
        }
        repository.addClassFile(bytes, origin);
        return null;
    }

    /** True if the bytes start like a zip archive (a jar is one), with room for more than the signature. */
    public static boolean isArchive(byte[] bytes) {
        return bytes.length > 3 && bytes[0] == 'P' && bytes[1] == 'K';
    }

    /** True if the bytes start with the class file signature {@code 0xCAFE}. */
    public static boolean isClassFile(byte[] bytes) {
        return bytes.length > 3 && (bytes[0] & 0xFF) == 0xCA && (bytes[1] & 0xFF) == 0xFE;
    }

    // ------------------------------------------------------------------ execution

    /** Body of the emulator thread: runs each entry point, then everything it left queued. */
    private static void runAll(VirtualMachine vm, List<EmulatorConfig.EntryPoint> entries, String mainClass, Report report,
            RunControl control) {
        vm.bindToCurrentThread();
        try {
            boolean stopped = false;
            for (EmulatorConfig.EntryPoint entry : entries) {
                if (stopped) {
                    break;
                }
                control.entryStarted(entryLabel(entry, mainClass));
                stopped = runEntry(vm, entry, mainClass, report);
            }
            if (!stopped && vm.config.threads.runShutdownHooks) {
                guarded(vm, report, "(shutdown hooks)", vm.scheduler::runShutdownHooks);
            }
        } finally {
            vm.scheduler.shutdown();
            vm.unbind();
        }
    }

    /**
     * The name of an entry point, as both progress records and the report's run labels use it.
     * It is defined only here, so that the two always match and the GUI can pair them.
     */
    private static String entryLabel(EmulatorConfig.EntryPoint entry, String mainClass) {
        if ("clinit-sweep".equals(entry.type)) {
            return "clinit-sweep";
        }
        String className = entry.className != null ? entry.className : mainClass;
        return className == null ? "main" : className + '.' + ("main".equals(entry.type) ? "main" : entry.method);
    }

    /** @return true if the whole run must stop (exit, limit or internal error) */
    private static boolean runEntry(VirtualMachine vm, EmulatorConfig.EntryPoint entry, String mainClass, Report report) {
        vm.scheduler.beginEntry();
        if ("clinit-sweep".equals(entry.type)) {
            return guarded(vm, report, entryLabel(entry, mainClass), () -> sweepInitialisers(vm, entry));
        }
        String className = entry.className != null ? entry.className : mainClass;
        if (className == null) {
            report.addRun("main", "no-entry-point",
                    "The sample has no Main-Class. Set entryPoints[].class; candidates: " + mainCandidates(vm), 0);
            return false;
        }
        String label = entryLabel(entry, mainClass);
        boolean stopped = guarded(vm, report, label, () -> invokeEntry(vm, entry, className.replace('.', '/')));
        if (!stopped) {
            // Threads and timers the entry point started run once it has returned, like a JVM
            // that stays alive while non-daemon threads exist.
            stopped = guarded(vm, report, label + " (queued tasks)", vm.scheduler::drain);
        }
        return stopped;
    }

    private static void invokeEntry(VirtualMachine vm, EmulatorConfig.EntryPoint entry, String className) {
        GuestClass cls = vm.classes.find(className);
        if (cls == null) {
            throw new ConfigException("Entry class not found in sample: " + className);
        }
        String methodName = "main".equals(entry.type) ? "main" : entry.method;
        String desc = "main".equals(entry.type) ? "([Ljava/lang/String;)V" : entry.descriptor;
        GuestMethod method = null;
        for (GuestMethod candidate : cls.declaredMethods()) {
            if (candidate.name.equals(methodName) && (desc == null || candidate.desc.equals(desc))) {
                if (method != null) {
                    throw new ConfigException(className + '.' + methodName + " is overloaded; set 'descriptor'");
                }
                method = candidate;
            }
        }
        if (method == null) {
            throw new ConfigException("Entry method not found: " + className + '.' + methodName + (desc == null ? "" : desc));
        }
        Object[] args = new Object[method.argTypes.length];
        if ("main".equals(entry.type)) {
            args[0] = entry.args.stream().map(String::valueOf).toArray(String[]::new);
        } else {
            for (int i = 0; i < args.length; i++) {
                args[i] = InvocationRouter.configValue(i < entry.args.size() ? entry.args.get(i) : null, method.argTypes[i]);
            }
        }
        vm.events.add("lifecycle", "entry", "method", method.id);
        Object receiver = null;
        if (method.isStatic) {
            vm.ensureInitialised(cls);
        } else {
            receiver = vm.instantiate(className, "()V", new Object[0]);
        }
        Object result = vm.router.callGuest(method, receiver, args);
        if (method.returnType.getSort() != Type.VOID) {
            vm.events.add("lifecycle", "entry-result", "method", method.id, "value", Values.describe(result));
        }
    }

    /**
     * Runs every static initialiser. Each class is isolated: one failing initialiser is
     * reported and the sweep moves on, because the point is to harvest as many decrypted
     * strings as possible.
     */
    private static void sweepInitialisers(VirtualMachine vm, EmulatorConfig.EntryPoint entry) {
        List<Pattern> include = entry.include.stream().map(glob -> Pattern.compile(
                Pattern.quote(glob.replace('.', '/')).replace("*", "\\E.*\\Q"))).toList();
        for (String name : new ArrayList<>(vm.classes.classNames())) {
            if (!include.isEmpty() && include.stream().noneMatch(pattern -> pattern.matcher(name).matches())) {
                continue;
            }
            GuestClass cls = vm.classes.find(name);
            if (cls == null) {
                continue;
            }
            vm.scheduler.beginEntry();
            try {
                vm.ensureInitialised(cls);
            } catch (GuestThrow thrown) {
                vm.events.add("lifecycle", "initialiser-failed", "class", name, "exception", vm.types.nameOf(thrown.value),
                        "message", Scheduler.messageOf(thrown));
            } catch (EmulationAbort abort) {
                if (!abort.reason.taskScoped) {
                    throw abort;
                }
                vm.events.add("lifecycle", "initialiser-stopped", "class", name, "reason", abort.getMessage());
            }
        }
    }

    /**
     * Runs one unit of guest work and classifies how it ended.
     *
     * @return true if the whole run must stop
     */
    private static boolean guarded(VirtualMachine vm, Report report, String label, Runnable work) {
        long before = vm.instructionCount();
        try {
            work.run();
            report.addRun(label, "completed", null, vm.instructionCount() - before);
            return false;
        } catch (GuestThrow thrown) {
            // An uncaught exception ends that thread of execution on a real JVM too.
            report.addRun(label, "uncaught-exception",
                    vm.types.nameOf(thrown.value) + ": " + Scheduler.messageOf(thrown) + traceOf(thrown), vm.instructionCount() - before);
            return false;
        } catch (EmulationAbort abort) {
            report.addRun(label, abort.reason.name().toLowerCase().replace('_', '-'), abort.detail, vm.instructionCount() - before);
            return !abort.reason.taskScoped;
        } catch (ConfigException e) {
            report.addRun(label, "config-error", e.getMessage(), 0);
            return true;
        } catch (RuntimeException | Error defect) {
            // A defect in the emulator, not behaviour of the sample. Keep everything gathered
            // so far and say so plainly, with the stack needed to fix it.
            StringWriter stack = new StringWriter();
            defect.printStackTrace(new PrintWriter(stack));
            report.addRun(label, "internal-error", "at guest " + vm.callerLocation() + "\n" + stack, vm.instructionCount() - before);
            return true;
        }
    }

    /** The top guest frames and the cause chain; reflection usually hides the real failure in a cause. */
    private static String traceOf(GuestThrow thrown) {
        if (!(thrown.value instanceof Throwable throwable)) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        StackTraceElement[] trace = throwable.getStackTrace();
        for (int i = 0; i < Math.min(trace.length, 8); i++) {
            text.append("\n    at ").append(trace[i]);
        }
        Throwable cause = throwable.getCause();
        for (int depth = 0; cause != null && depth < 5; depth++, cause = cause.getCause()) {
            // getClass().getName() and getMessage() run guest code only for guest exception
            // classes, which is acceptable here: execution has already ended.
            text.append("\n  caused by ").append(cause.getClass().getName()).append(": ").append(cause.getMessage());
        }
        return text.toString();
    }

    /** Names of classes declaring {@code public static void main(String[])}, to help pick an entry point. */
    public static List<String> mainCandidates(VirtualMachine vm) {
        return mainCandidates(vm.classes);
    }

    /**
     * Names of classes in a repository that declare {@code public static void main(String[])}.
     * Shared by the {@code no-entry-point} message and {@code inspect}, so both list the same classes.
     */
    public static List<String> mainCandidates(ClassRepository repository) {
        List<String> candidates = new ArrayList<>();
        for (String name : new ArrayList<>(repository.classNames())) {
            GuestClass cls = repository.find(name);
            if (cls != null && cls.declared("main", "([Ljava/lang/String;)V") != null) {
                candidates.add(name);
            }
        }
        return candidates;
    }
}
