package dev.javabyte.stubs;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.env.SimProcess;
import dev.javabyte.vm.VirtualMachine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Process creation. Nothing is ever launched: the command line is recorded and a
 * {@link SimProcess} with configured output is returned, so the sample continues as if the
 * command had run.
 */
final class ProcessStubs {

    private ProcessStubs() {}

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

    private static SimProcess launch(VirtualMachine vm, List<String> command, Object directory) {
        String line = String.join(" ", command);
        EmulatorConfig.ProcessRule matched = null;
        for (EmulatorConfig.ProcessRule rule : vm.config.processes) {
            if (rule.command != null && Pattern.compile(rule.command).matcher(line).find()) {
                matched = rule;
                break;
            }
        }
        vm.events.add("process", "exec", "command", line, "argv", command.size() > 1 ? new ArrayList<>(command) : null,
                "directory", directory == null ? null : directory.toString(),
                "matchedRule", matched == null ? "none (empty output, exit 0)" : matched.command);
        return matched == null ? new SimProcess("", "", 0) : new SimProcess(matched.stdout, matched.stderr, matched.exitCode);
    }
}
