package dev.javabyte.cli;

import dev.javabyte.Emulator;
import dev.javabyte.RunControl;
import dev.javabyte.config.ConfigLoader;
import dev.javabyte.config.ConfigLoader.ConfigException;
import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.report.Report;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Command-line front end.
 *
 * <p>The tool is meant to be driven equally by a person at a terminal and by an automated
 * agent. To that end every command has a {@code --json} form with stable field names, results
 * go to stdout while diagnostics go to stderr, and exit codes distinguish "the tool failed"
 * from "the sample misbehaved":
 *
 * <pre>
 *   0  the command ran; for 'run', the report describes what the sample did
 *   1  the emulator itself failed (a defect; see the report's internal-error entry)
 *   2  bad usage or invalid config
 *   3  the run was cut short by the instruction or time limit
 * </pre>
 */
@Command(name = "javabyte", mixinStandardHelpOptions = true, versionProvider = Main.Version.class,
        description = "Emulates JVM bytecode to analyse untrusted Java code without running it on a real JVM.",
        subcommands = {Main.Run.class, Main.Inspect.class, Main.Init.class, Main.Validate.class, Main.Schema.class,
                Main.Defaults.class, Main.Policy.class, Main.Disasm.class})
public final class Main {

    static final int EXIT_OK = 0;
    static final int EXIT_INTERNAL = 1;
    static final int EXIT_USAGE = 2;
    static final int EXIT_LIMIT = 3;

    /** Supplies {@code --version} from the build, since annotations cannot hold a computed value. */
    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {"javabyte " + Report.TOOL_VERSION};
        }
    }

    /**
     * The command-line entry point. Before anything else it turns off host name lookups for the
     * whole JVM (see {@link #blockHostNameLookups()}), then runs the tool and ends the JVM with
     * its exit code.
     */
    public static void main(String[] args) {
        blockHostNameLookups();
        System.exit(execute(args));
    }

    /**
     * A hosts file that cannot exist on any computer: a path below a device, which is not a
     * directory. Nothing can create it, so it cannot be used to plant names.
     */
    static final String NO_HOSTS_FILE = System.getProperty("os.name", "").startsWith("Windows")
            ? "NUL\\javabyte-no-hosts" : "/dev/null/javabyte-no-hosts";

    /**
     * Makes every host name lookup in this JVM fail, as a backstop behind the network simulator.
     *
     * <p>The emulator itself never needs to resolve a name: the guest's lookups are answered by
     * {@code NetworkSimulator}, and the native bridge talks to its helper over pipes. If a defect
     * ever let a guest lookup reach the real {@code InetAddress}, a DNS query would tell the
     * attacker's name server that the sample was being analysed. Setting
     * {@code jdk.net.hosts.file} makes the JDK answer names from that file instead of the
     * system resolver, and when the file is missing every lookup fails with
     * {@code UnknownHostException} rather than falling back to DNS (checked on JDK 17 and 21).
     * {@code localhost}, the loopback address and address literals such as {@code 127.0.0.1}
     * still work, since the JDK answers them without a lookup.
     *
     * <p>The JDK reads the property once, when {@code InetAddress} is first used, so this must
     * run before anything touches the network classes; {@link #main} calls it first. It
     * replaces any value given on the command line, so the backstop cannot be turned off by
     * accident.
     */
    static void blockHostNameLookups() {
        System.setProperty("jdk.net.hosts.file", NO_HOSTS_FILE);
    }

    /**
     * Runs the tool and returns its exit code without ending the JVM, so it can be called from
     * tests and from other programs.
     */
    public static int execute(String... args) {
        CommandLine commandLine = new CommandLine(new Main());
        // No "@file" arguments. A sample is named on the command line, by people and by tools
        // that pass on whatever name it has; a sample called "@x" must be read as a sample, not
        // as a file of further options (-s policy.allow=..., say) for this run.
        commandLine.setExpandAtFiles(false);
        commandLine.setExecutionExceptionHandler((exception, cmd, parseResult) -> {
            if (exception instanceof ConfigException) {
                cmd.getErr().println("error: " + exception.getMessage());
                return EXIT_USAGE;
            }
            exception.printStackTrace(cmd.getErr());
            return EXIT_INTERNAL;
        });
        return args.length == 0 ? usage(commandLine) : commandLine.execute(args);
    }

    private static int usage(CommandLine commandLine) {
        commandLine.usage(System.out);
        return EXIT_USAGE;
    }

    /**
     * Prints one JSON document to standard output as UTF-8, whatever the platform's charset.
     * Printed as text it would lose every character that charset lacks (all but ASCII where no
     * locale is set, as under {@code run-isolated.sh}), and a report is full of text the sample
     * chose. The bytes pass through {@code System.out} unchanged.
     */
    static void printJson(String json) {
        byte[] bytes = (json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        System.out.write(bytes, 0, bytes.length);
        System.out.flush();
    }

    /** Runs a sample and reports its behaviour. */
    @Command(name = "run", mixinStandardHelpOptions = true,
            description = "Emulate a sample and report what it did.")
    static final class Run implements Callable<Integer> {

        @Parameters(arity = "0..1", paramLabel = "SAMPLE",
                description = "Jar, class file or class directory. Overrides 'sample' in the config.")
        String sample;

        @Option(names = {"-c", "--config"}, paramLabel = "FILE", description = "YAML or JSON config file.")
        Path config;

        @Option(names = {"-s", "--set"}, paramLabel = "KEY=VALUE",
                description = "Override one config value, e.g. -s limits.timeoutSeconds=30. Repeatable.")
        List<String> overrides = new ArrayList<>();

        @Option(names = {"-o", "--out"}, paramLabel = "DIR", description = "Output directory. Overrides output.directory.")
        String out;

        @Option(names = "--json", description = "Print the full JSON report to stdout instead of the text summary.")
        boolean json;

        @Option(names = "--events", paramLabel = "FORMAT",
                description = "Stream the run to stdout as it happens. FORMAT is 'jsonl': one JSON object per line. "
                        + "The text summary then goes to stderr.")
        String events;

        @Option(names = "--progress", description = "Report progress about every half second: in the event stream if "
                + "--events is given, otherwise on stderr.")
        boolean progress;

        @Option(names = "--control", paramLabel = "SOURCE",
                description = "Accept commands while running. SOURCE is 'stdin': the line 'stop', or the end of input, "
                        + "stops the run and still writes the report.")
        String control;

        @Override
        public Integer call() throws Exception {
            if (events != null && !events.equals("jsonl")) {
                throw new ConfigException("--events must be 'jsonl', found '" + events + "'");
            }
            if (control != null && !control.equals("stdin")) {
                throw new ConfigException("--control must be 'stdin', found '" + control + "'");
            }
            EmulatorConfig loaded = ConfigLoader.load(config, overrides);
            if (sample != null) {
                loaded.sample = sample;
            }
            if (out != null) {
                loaded.output.directory = out;
            }
            if (json) {
                loaded.output.console = "json";
            }
            RunControl runControl = new RunControl();
            EventStream stream = null;
            if (events != null) {
                stream = new EventStream(System.out, loaded.output.directory, progress);
                runControl.listen(stream);
            } else if (progress) {
                runControl.listen(new ProgressPrinter(System.err));
            }
            if (control != null) {
                listenForStop(runControl);
            }
            // Ctrl+C stops the run like the Stop button: the report is written before the
            // process ends. The hook waits for that, but not for ever.
            Thread interrupt = new Thread(() -> {
                runControl.requestStop();
                try {
                    runControl.awaitFinished(30, java.util.concurrent.TimeUnit.SECONDS);
                    finishedOutput.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "javabyte-stop-on-interrupt");
            Runtime.getRuntime().addShutdownHook(interrupt);
            try {
                Path reportPath = Path.of(loaded.output.directory, "report.json");
                Report report = Emulator.run(loaded, Path.of(loaded.output.directory), runControl);
                int exitCode = exitCode(report);
                // With an event stream, standard output belongs to it; people read stderr.
                java.io.PrintStream human = stream != null ? System.err : System.out;
                switch (loaded.output.console) {
                    case "json":
                        if (stream == null) {
                            printJson(report.toJson());
                        }
                        break;
                    case "text":
                        human.print(report.toText());
                        human.println("\nFull report: " + reportPath);
                        break;
                    default:
                        break;
                }
                if (stream != null) {
                    stream.finish(report, exitCode, reportPath.toString());
                }
                return exitCode;
            } finally {
                finishedOutput.countDown();
                try {
                    Runtime.getRuntime().removeShutdownHook(interrupt);
                } catch (IllegalStateException alreadyShuttingDown) {
                    // The hook is running; it is waiting for the latch counted down above.
                }
            }
        }

        private final java.util.concurrent.CountDownLatch finishedOutput = new java.util.concurrent.CountDownLatch(1);

        /**
         * Reads commands from standard input on a background thread. The end of input also
         * stops the run: the program that started this one has gone, and nobody would read the
         * rest of the stream.
         */
        private static void listenForStop(RunControl runControl) {
            Thread reader = new Thread(() -> {
                try (java.io.BufferedReader in = new java.io.BufferedReader(
                        new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8))) {
                    for (String line = in.readLine(); line != null; line = in.readLine()) {
                        if (line.trim().equalsIgnoreCase("stop")) {
                            break;
                        }
                    }
                } catch (java.io.IOException e) {
                    // Treated like the end of input.
                }
                runControl.requestStop();
            }, "javabyte-control");
            reader.setDaemon(true);
            reader.start();
        }

        /** Exit codes are documented in README.md and AGENTS.md; a stopped run is a normal end. */
        private static int exitCode(Report report) {
            if (report.hasOutcome("internal-error")) {
                return EXIT_INTERNAL;
            }
            if (report.hasOutcome("config-error")) {
                return EXIT_USAGE;
            }
            return report.stoppedByLimit() ? EXIT_LIMIT : EXIT_OK;
        }
    }

    /** Static look at a sample, to decide how to configure a run. */
    @Command(name = "inspect", mixinStandardHelpOptions = true,
            description = "Describe a sample without executing anything: entry points, loaders, natives, resources.")
    static final class Inspect implements Callable<Integer> {

        @Parameters(paramLabel = "SAMPLE") String sample;

        @Option(names = "--json", description = "Print JSON instead of text.") boolean json;

        @Override
        public Integer call() throws Exception {
            Inspection inspection = Inspection.of(Path.of(sample));
            if (json) {
                printJson(ConfigLoader.json().writeValueAsString(inspection.toMap()));
            } else {
                System.out.println(inspection.toText());
            }
            return EXIT_OK;
        }
    }

    /** A class file as readable instructions. Parses only: nothing in it runs. */
    @Command(name = "disasm", mixinStandardHelpOptions = true,
            description = "Print a class file as readable instructions, without running anything. Exit code 0, or 2 if it cannot be read.")
    static final class Disasm implements Callable<Integer> {

        @Parameters(paramLabel = "FILE", description = "A .class file, or a jar or zip together with --class.") Path file;

        @Option(names = "--class", paramLabel = "NAME", description = "For a jar or zip: the class to print, with dots or slashes.")
        String className;

        @Override
        public Integer call() throws Exception {
            System.out.print(Disassembly.of(file, className));
            return EXIT_OK;
        }
    }

    /** Writes a commented starter config for a sample. */
    @Command(name = "init", mixinStandardHelpOptions = true,
            description = "Print a starter config for a sample, ready to edit.")
    static final class Init implements Callable<Integer> {

        @Parameters(paramLabel = "SAMPLE") String sample;

        @Override
        public Integer call() throws Exception {
            Inspection inspection = Inspection.of(Path.of(sample));
            System.out.print(inspection.starterConfig());
            return EXIT_OK;
        }
    }

    /** Checks a config without running anything, and lists every problem at once. */
    @Command(name = "validate", mixinStandardHelpOptions = true,
            description = "Check a config and list every problem, each with the key it concerns. Exit code 0 if valid, 2 if not.")
    static final class Validate implements Callable<Integer> {

        @Option(names = {"-c", "--config"}, paramLabel = "FILE", description = "YAML or JSON config file.")
        Path config;

        @Option(names = {"-s", "--set"}, paramLabel = "KEY=VALUE", description = "Override one config value. Repeatable.")
        List<String> overrides = new ArrayList<>();

        @Option(names = "--json", description = "Print JSON: {valid, problems: [{key, message}]}.") boolean json;

        @Override
        public Integer call() throws Exception {
            List<ConfigLoader.Problem> problems;
            try {
                problems = ConfigLoader.problems(ConfigLoader.readTree(config, overrides));
            } catch (ConfigException unreadable) {
                // A file that cannot be parsed at all is one problem with the whole file.
                problems = unreadable.problems();
            }
            if (json) {
                java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
                result.put("valid", problems.isEmpty());
                result.put("problems", problems);
                printJson(ConfigLoader.json().writeValueAsString(result));
            } else if (problems.isEmpty()) {
                System.out.println("The config is valid.");
            } else {
                System.out.println(problems.size() == 1 ? "1 problem:" : problems.size() + " problems:");
                problems.forEach(p -> System.out.println("  " + (p.key().isEmpty() ? "(file)" : p.key()) + ": " + p.message()));
            }
            return problems.isEmpty() ? EXIT_OK : EXIT_USAGE;
        }
    }

    /** Describes every config option, for forms, editors and agents. */
    @Command(name = "schema", mixinStandardHelpOptions = true,
            description = "Describe every config option: key, type, default, allowed values and meaning.")
    static final class Schema implements Callable<Integer> {

        @Option(names = "--json", description = "Print JSON instead of a table.") boolean json;

        @Override
        public Integer call() throws Exception {
            if (json) {
                printJson(ConfigLoader.json().writeValueAsString(dev.javabyte.config.ConfigSchema.toMap()));
                return EXIT_OK;
            }
            for (dev.javabyte.config.ConfigSchema.Option option : dev.javabyte.config.ConfigSchema.options()) {
                String type = option.element() == null ? option.type() : option.type() + " of " + option.element();
                System.out.println(option.path() + "  (" + type + ")"
                        + (option.defaultValue() == null ? "" : "  default: " + option.defaultValue())
                        + (option.allowed().isEmpty() ? "" : "  one of: " + String.join(", ", option.allowed())));
                System.out.println("    " + option.description());
            }
            return EXIT_OK;
        }
    }

    /** Prints every option with its default, which doubles as the config reference. */
    @Command(name = "defaults", mixinStandardHelpOptions = true,
            description = "Print every config option with its default value.")
    static final class Defaults implements Callable<Integer> {

        @Option(names = "--json", description = "Print JSON instead of YAML.") boolean json;

        @Override
        public Integer call() throws Exception {
            EmulatorConfig defaults = new EmulatorConfig();
            if (json) {
                printJson(ConfigLoader.json().writeValueAsString(defaults));
            } else {
                System.out.println(ConfigLoader.toYaml(defaults));
            }
            return EXIT_OK;
        }
    }

    /** Prints the built-in host allow-list. */
    @Command(name = "policy", mixinStandardHelpOptions = true,
            description = "Print the built-in list of JDK members guest code may call for real.")
    static final class Policy implements Callable<Integer> {

        @Option(names = "--json", description = "Print each rule with its effect, section and explanation, as JSON.")
        boolean json;

        @Override
        public Integer call() throws Exception {
            String text;
            try (InputStream in = Main.class.getResourceAsStream("/dev/javabyte/default-policy.txt")) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (json) {
                java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
                out.put("rules", dev.javabyte.host.PolicyListing.read(text));
                printJson(ConfigLoader.json().writeValueAsString(out));
            } else {
                System.out.print(text);
            }
            return EXIT_OK;
        }
    }
}
