package dev.javabyte.corpus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.javabyte.Emulator;
import dev.javabyte.config.ConfigLoader;
import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.report.Event;
import dev.javabyte.report.Report;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Loads {@link Scenario} files, runs their stages and checks the expectations. */
public final class ScenarioRunner {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private ScenarioRunner() {}

    /** Every scenario in a test-resource directory such as {@code corpus/simulated}, sorted by file name. */
    public static List<Scenario> load(String resourceDirectory) {
        URL url = ScenarioRunner.class.getClassLoader().getResource(resourceDirectory);
        if (url == null) {
            return List.of();
        }
        List<Scenario> scenarios = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of(url.toURI()))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
                Scenario scenario = YAML.readValue(file.toFile(), Scenario.class);
                if (scenario.name == null) {
                    scenario.name = file.getFileName().toString().replace(".yaml", "");
                }
                scenarios.add(scenario);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        return scenarios;
    }

    /**
     * Runs every stage and returns the problems found; an empty list means the scenario passed.
     *
     * @param sample        the sample for the first stage
     * @param workDirectory where each stage's output directory is created
     * @param bridgePython  interpreter for the native bridge, or null if it is unavailable
     */
    public static List<String> run(Scenario scenario, Path sample, Path workDirectory, String bridgePython) throws IOException {
        List<String> problems = new ArrayList<>();
        Path currentSample = sample;
        Report previous = null;
        Path previousOutput = null;
        for (Scenario.Stage stage : scenario.stages) {
            if (stage.sampleFromArtifact != null) {
                currentSample = findArtifact(previous, previousOutput, stage.sampleFromArtifact);
                if (currentSample == null) {
                    problems.add(stage.name + ": no artifact of the previous stage matches " + stage.sampleFromArtifact);
                    return problems;
                }
            }
            if (stage.nativeBridge && bridgePython == null) {
                continue; // the caller reports this as a skipped part, not a failure
            }
            Map<String, Object> tree = new LinkedHashMap<>(stage.config);
            if (stage.nativeBridge) {
                tree.put("plugins", Map.of("native-bridge", Map.of("python", bridgePython)));
            }
            EmulatorConfig config = ConfigLoader.fromMap(tree);
            config.sample = currentSample.toString();
            Path output = Files.createDirectories(workDirectory.resolve(scenario.name + "-" + stage.name));
            Report report = Emulator.run(config, output);
            for (String problem : check(stage.expect, report)) {
                problems.add(stage.name + ": " + problem);
            }
            if (!problems.isEmpty()) {
                problems.add(stage.name + " diagnostics:\n" + diagnostics(report));
                return problems; // later stages depend on this one
            }
            previous = report;
            previousOutput = output;
        }
        return problems;
    }

    private static Path findArtifact(Report report, Path output, String regex) {
        if (report == null) {
            return null;
        }
        Pattern pattern = Pattern.compile(regex);
        for (Object artifact : (List<?>) report.toMap().get("artifacts")) {
            if (pattern.matcher(artifact.toString().replace('\\', '/')).find()) {
                return output.resolve(artifact.toString());
            }
        }
        return null;
    }

    /** Compares a report with expectations and describes each mismatch in plain words. */
    public static List<String> check(Scenario.Expect expect, Report report) {
        List<String> problems = new ArrayList<>();
        List<String> outcomes = report.runs().stream().map(Report.Run::outcome).toList();
        for (String wanted : expect.outcomes) {
            if (!outcomes.contains(wanted)) {
                problems.add("expected outcome '" + wanted + "', found " + outcomes);
            }
        }
        List<String> forbidden = new ArrayList<>(expect.forbidOutcomes);
        forbidden.add("internal-error");
        for (String outcome : forbidden) {
            if (outcomes.contains(outcome)) {
                problems.add("forbidden outcome '" + outcome + "' occurred");
            }
        }
        List<String> printed = report.events().events().stream()
                .filter(event -> event.category().equals("console") && event.action().equals("stdout"))
                .map(event -> String.valueOf(event.data().get("text"))).toList();
        for (String regex : expect.stdout) {
            Pattern pattern = Pattern.compile(regex);
            if (printed.stream().noneMatch(line -> pattern.matcher(line).find())) {
                problems.add("no printed line matches /" + regex + "/; printed: " + printed);
            }
        }
        for (Scenario.EventMatch match : expect.events) {
            long count = count(match, report);
            if (count < match.min || (match.max != null && count > match.max)) {
                problems.add("expected " + describe(match) + " between " + match.min + " and "
                        + (match.max == null ? "any number of" : match.max) + " times, found " + count
                        + (match.because == null ? "" : " (" + match.because + ")"));
            }
        }
        for (Scenario.EventMatch match : expect.absent) {
            long count = count(match, report);
            if (count > 0) {
                problems.add("expected no " + describe(match) + ", found " + count
                        + (match.because == null ? "" : " (" + match.because + ")"));
            }
        }
        List<?> artifacts = (List<?>) report.toMap().get("artifacts");
        for (String regex : expect.artifacts) {
            Pattern pattern = Pattern.compile(regex);
            if (artifacts.stream().noneMatch(a -> pattern.matcher(a.toString().replace('\\', '/')).find())) {
                problems.add("no saved artifact matches /" + regex + "/; saved: " + artifacts);
            }
        }
        int gaps = report.events().byCategory("gap").size();
        if (expect.maxGaps != null && gaps > expect.maxGaps) {
            problems.add("expected at most " + expect.maxGaps + " gap events, found " + gaps);
        }
        return problems;
    }

    private static long count(Scenario.EventMatch match, Report report) {
        return report.events().events().stream().filter(event -> matches(match, event)).count();
    }

    private static boolean matches(Scenario.EventMatch match, Event event) {
        if (!event.category().equals(match.category) || (match.action != null && !event.action().equals(match.action))) {
            return false;
        }
        for (Map.Entry<String, String> field : match.where.entrySet()) {
            Object value = event.data().get(field.getKey());
            if (value == null || !Pattern.compile(field.getValue()).matcher(String.valueOf(value)).find()) {
                return false;
            }
        }
        return true;
    }

    private static String describe(Scenario.EventMatch match) {
        return match.category + (match.action == null ? "" : "." + match.action) + (match.where.isEmpty() ? "" : " " + match.where);
    }

    /** A compact view of a report for failure messages: outcomes, then every event on one line each. */
    public static String diagnostics(Report report) {
        StringBuilder text = new StringBuilder();
        report.runs().forEach(run -> text.append("  ").append(run.entry()).append(" -> ").append(run.outcome())
                .append(run.detail() == null ? "" : ": " + run.detail().lines().limit(6).reduce("", (a, b) -> a + "\n      " + b))
                .append('\n'));
        report.events().events().stream()
                .filter(event -> !event.category().equals("string") && !event.category().equals("system"))
                .limit(120)
                .forEach(event -> {
                    String line = event.toLine();
                    text.append("  ").append(line.length() > 260 ? line.substring(0, 260) + "..." : line).append('\n');
                });
        return text.toString();
    }
}
