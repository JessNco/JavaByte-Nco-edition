package dev.javabyte;

import dev.javabyte.config.ConfigLoader.ConfigException;
import dev.javabyte.config.EmulatorConfig;
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
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        EventLog events = new EventLog(config.limits.maxEventsPerCategory, trace);
        VirtualMachine vm = new VirtualMachine(config, events, new Artifacts(outputDirectory));
        BuiltinStubs.install(vm);
        for (EmulatorPlugin plugin : ServiceLoader.load(EmulatorPlugin.class)) {
            if (config.plugins.containsKey(plugin.id())) {
                plugin.install(vm, config.plugins.get(plugin.id()));
                events.add("lifecycle", "plugin-installed", "id", plugin.id());
            }
        }

        byte[] sampleBytes = Files.isDirectory(samplePath) ? new byte[0] : Files.readAllBytes(samplePath);
        Manifest manifest = load(vm, samplePath, sampleBytes, "sample");
        for (String extra : config.classpath) {
            Path extraPath = Path.of(extra);
            load(vm, extraPath, Files.isDirectory(extraPath) ? new byte[0] : Files.readAllBytes(extraPath), "classpath");
        }
        vm.env.initialise(samplePath.getFileName().toString(), sampleBytes);

        String mainClass = manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class");
        Report report = new Report(config, samplePath, sampleBytes, mainClass, vm);
        List<EmulatorConfig.EntryPoint> entries = config.entryPoints;
        if (entries.isEmpty()) {
            entries = List.of(new EmulatorConfig.EntryPoint());
        }

        List<EmulatorConfig.EntryPoint> plan = entries;
        Thread emulatorThread = new Thread(null, () -> runAll(vm, plan, mainClass, report),
                "javabyte-emulator", EMULATOR_STACK_BYTES);
        // A daemon thread cannot keep the tool alive if guest-driven JDK code blocks forever.
        emulatorThread.setDaemon(true);
        emulatorThread.start();
        try {
            emulatorThread.join(config.limits.timeoutSeconds * 1000L);
            if (emulatorThread.isAlive()) {
                vm.cancel();
                emulatorThread.join(CANCEL_GRACE_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (emulatorThread.isAlive()) {
            // The thread is not at an instruction boundary, so it must be inside a real JDK call.
            report.addRun("(run)", "timeout", "emulator thread blocked in host call " + vm.hostCallInProgress(), 0);
        } else {
            finish(vm, config);
        }
        report.complete();
        if (outputDirectory != null) {
            report.writeJson(outputDirectory.resolve("report.json"));
        }
        events.flush();
        if (trace != null) {
            trace.close();
        }
        return report;
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

    /** Adds a jar, a class file or a directory of classes to the guest class repository. */
    private static Manifest load(VirtualMachine vm, Path path, byte[] bytes, String origin) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> files = Files.walk(path)) {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    String relative = path.relativize(file).toString().replace('\\', '/');
                    byte[] data = Files.readAllBytes(file);
                    if (relative.endsWith(".class")) {
                        vm.classes.addClassBytes(relative.substring(0, relative.length() - 6), data, origin);
                    }
                    vm.classes.addResource(relative, data);
                }
            }
            return null;
        }
        boolean isArchive = bytes.length > 3 && bytes[0] == 'P' && bytes[1] == 'K';
        if (isArchive) {
            return vm.classes.addArchive(bytes, origin);
        }
        boolean isClass = bytes.length > 3 && (bytes[0] & 0xFF) == 0xCA && (bytes[1] & 0xFF) == 0xFE;
        if (!isClass) {
            throw new ConfigException(path + " is neither a jar/zip archive nor a class file");
        }
        vm.classes.addClassFile(bytes, origin);
        return null;
    }

    // ------------------------------------------------------------------ execution

    /** Body of the emulator thread: runs each entry point, then everything it left queued. */
    private static void runAll(VirtualMachine vm, List<EmulatorConfig.EntryPoint> entries, String mainClass, Report report) {
        vm.bindToCurrentThread();
        try {
            boolean stopped = false;
            for (EmulatorConfig.EntryPoint entry : entries) {
                if (stopped) {
                    break;
                }
                stopped = runEntry(vm, entry, mainClass, report);
            }
            if (!stopped && vm.config.threads.runShutdownHooks) {
                guarded(vm, report, "(shutdown hooks)", vm.scheduler::runShutdownHooks);
            }
        } finally {
            vm.unbind();
        }
    }

    /** @return true if the whole run must stop (exit, limit or internal error) */
    private static boolean runEntry(VirtualMachine vm, EmulatorConfig.EntryPoint entry, String mainClass, Report report) {
        vm.scheduler.beginEntry();
        if ("clinit-sweep".equals(entry.type)) {
            return guarded(vm, report, "clinit-sweep", () -> sweepInitialisers(vm, entry));
        }
        String className = entry.className != null ? entry.className : mainClass;
        if (className == null) {
            report.addRun("main", "no-entry-point",
                    "The sample has no Main-Class. Set entryPoints[].class; candidates: " + mainCandidates(vm), 0);
            return false;
        }
        String label = className + '.' + ("main".equals(entry.type) ? "main" : entry.method);
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
        List<String> candidates = new ArrayList<>();
        for (String name : new ArrayList<>(vm.classes.classNames())) {
            GuestClass cls = vm.classes.find(name);
            if (cls != null && cls.declared("main", "([Ljava/lang/String;)V") != null) {
                candidates.add(name);
            }
        }
        return candidates;
    }
}
