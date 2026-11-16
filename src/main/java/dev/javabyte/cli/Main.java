package dev.javabyte.cli;

import dev.javabyte.Emulator;
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
        subcommands = {Main.Run.class, Main.Inspect.class, Main.Init.class, Main.Defaults.class, Main.Policy.class})
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

    public static void main(String[] args) {
        CommandLine commandLine = new CommandLine(new Main());
        commandLine.setExecutionExceptionHandler((exception, cmd, parseResult) -> {
            if (exception instanceof ConfigException) {
                cmd.getErr().println("error: " + exception.getMessage());
                return EXIT_USAGE;
            }
            exception.printStackTrace(cmd.getErr());
            return EXIT_INTERNAL;
        });
        int exit = args.length == 0 ? usage(commandLine) : commandLine.execute(args);
        System.exit(exit);
    }

    private static int usage(CommandLine commandLine) {
        commandLine.usage(System.out);
        return EXIT_USAGE;
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

        @Override
        public Integer call() throws Exception {
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
            Report report = Emulator.run(loaded, Path.of(loaded.output.directory));
            switch (loaded.output.console) {
                case "json":
                    System.out.println(report.toJson());
                    break;
                case "text":
                    System.out.print(report.toText());
                    System.out.println("\nFull report: " + Path.of(loaded.output.directory, "report.json"));
                    break;
                default:
                    break;
            }
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
            System.out.println(json ? ConfigLoader.json().writeValueAsString(inspection.toMap()) : inspection.toText());
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

    /** Prints every option with its default, which doubles as the config reference. */
    @Command(name = "defaults", mixinStandardHelpOptions = true,
            description = "Print every config option with its default value.")
    static final class Defaults implements Callable<Integer> {

        @Option(names = "--json", description = "Print JSON instead of YAML.") boolean json;

        @Override
        public Integer call() throws Exception {
            EmulatorConfig defaults = new EmulatorConfig();
            System.out.println(json ? ConfigLoader.json().writeValueAsString(defaults) : ConfigLoader.toYaml(defaults));
            return EXIT_OK;
        }
    }

    /** Prints the built-in host allow-list. */
    @Command(name = "policy", mixinStandardHelpOptions = true,
            description = "Print the built-in list of JDK members guest code may call for real.")
    static final class Policy implements Callable<Integer> {

        @Override
        public Integer call() throws Exception {
            try (InputStream in = Main.class.getResourceAsStream("/dev/javabyte/default-policy.txt")) {
                System.out.print(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            return EXIT_OK;
        }
    }
}
