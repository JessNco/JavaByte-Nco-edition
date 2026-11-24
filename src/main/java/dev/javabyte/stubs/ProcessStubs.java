package dev.javabyte.stubs;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.env.SimProcess;
import dev.javabyte.vm.VirtualMachine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Process creation. Nothing is ever launched: the command line is recorded and a
 * {@link SimProcess} with configured output is returned, so the sample continues as if the
 * command had run.
 *
 * <p>The output comes from the first matching {@code processes} rule in the config. Failing
 * that, common system commands ({@code whoami}, {@code tasklist}, {@code wmic} and others) are
 * answered by {@link BuiltInCommands} from the simulated machine, because a real Windows
 * machine never answers them with nothing. Anything else prints nothing and exits with 0. The
 * {@code process.exec} event's {@code matchedRule} says which of the three applied.
 */
final class ProcessStubs {

    private ProcessStubs() {}

    /**
     * The process id given to each simulated launch, per run. {@code SimProcess} itself does not
     * know its id, so {@code Process.pid()} is answered from here. The table is keyed weakly by
     * the run, so it goes away with the run.
     */
    private static final Map<VirtualMachine, Map<SimProcess, Long>> PIDS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Installs this class's stubs on the machine; called once per run from {@code BuiltinStubs}. */
    static void install(VirtualMachine vm) {
        vm.stubs.on("java/lang/ProcessBuilder", "start", ctx ->
                launch(vm, ((ProcessBuilder) ctx.receiver).command(), ((ProcessBuilder) ctx.receiver).directory()));
        // The real method would expose the analysis machine's environment variables.
        vm.stubs.on("java/lang/ProcessBuilder", "environment", ctx -> new HashMap<>(vm.env.variables));
        vm.stubs.on("java/lang/ProcessBuilder", "startPipeline", ctx -> {
            List<Process> processes = new ArrayList<>();
            for (Object builder : (List<?>) ctx.arg(0)) {
                processes.add(launch(vm, ((ProcessBuilder) builder).command(), null));
            }
            return processes;
        });
        // Each launched program has its own id, distinct from the sample's and from each other's.
        vm.stubs.on("java/lang/Process", "pid", ctx -> ctx.receiver instanceof SimProcess process
                ? pidOf(vm, process) : ctx.proceed());
        vm.stubs.on("java/lang/Process", "toHandle", ctx -> ctx.receiver instanceof SimProcess process
                ? ManagementStubs.bean(vm, ManagementStubs.Bean.PROCESS_HANDLE, pidOf(vm, process)) : ctx.proceed());
    }

    /** Handles all {@code Runtime.exec} overloads: (command[, envp[, dir]]) with a String or String[] command. */
    static Object exec(VirtualMachine vm, Object[] args) {
        List<String> command = new ArrayList<>();
        if (args[0] instanceof String line) {
            command.add(line);
        } else if (args[0] instanceof Object[] parts) {
            for (Object part : parts) {
                command.add(String.valueOf(part));
            }
        }
        return launch(vm, command, args.length > 2 ? args[2] : null);
    }

    /**
     * Records one launch and returns the simulated process. The output comes from the first
     * matching rule, then from the built-in commands, then is empty. The process is registered
     * with the guest environment so that its standard input is reported at the end of the run.
     */
    private static SimProcess launch(VirtualMachine vm, List<String> command, Object directory) {
        String line = String.join(" ", command);
        EmulatorConfig.ProcessRule matched = null;
        for (EmulatorConfig.ProcessRule rule : vm.config.processes) {
            if (rule.command != null && Pattern.compile(rule.command).matcher(line).find()) {
                matched = rule;
                break;
            }
        }
        long pid = vm.env.machine.nextChildPid();
        BuiltInCommands.Answer builtIn = matched == null && vm.config.environment.builtInCommands
                ? BuiltInCommands.answer(vm, line, pid) : null;
        String source = matched != null ? matched.command
                : builtIn != null ? "built-in:" + builtIn.command() : "none (empty output, exit 0)";
        vm.events.add("process", "exec", "command", line, "argv", command.size() > 1 ? new ArrayList<>(command) : null,
                "directory", directory == null ? null : directory.toString(), "pid", pid, "matchedRule", source);
        // What the sample writes to the process's standard input is reported as process.stdin
        // events; input it never flushed is reported when the run ends (Emulator.finish).
        SimProcess process = matched != null
                ? new SimProcess(matched.stdout, matched.stderr, matched.exitCode, line, vm.events, vm.artifacts)
                : builtIn != null
                ? new SimProcess(builtIn.stdout(), builtIn.stderr(), builtIn.exitCode(), line, vm.events, vm.artifacts)
                : new SimProcess("", "", 0, line, vm.events, vm.artifacts);
        vm.env.addProcess(process);
        PIDS.computeIfAbsent(vm, key -> new IdentityHashMap<>()).put(process, pid);
        return process;
    }

    /** The id assigned at launch; a process this run did not launch gets the next free one. */
    private static long pidOf(VirtualMachine vm, SimProcess process) {
        return PIDS.computeIfAbsent(vm, key -> new IdentityHashMap<>())
                .computeIfAbsent(process, key -> vm.env.machine.nextChildPid());
    }
}
