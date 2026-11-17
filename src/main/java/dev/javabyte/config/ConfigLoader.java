package dev.javabyte.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads, overrides, validates and prints {@link EmulatorConfig}. */
public final class ConfigLoader {

    private ConfigLoader() {}

    /**
     * YAML is a superset of JSON, so one mapper reads both. That keeps "what is valid config"
     * identical for people writing YAML and tools emitting JSON.
     */
    private static final ObjectMapper YAML = configure(new ObjectMapper(
            new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)));

    private static final ObjectMapper JSON = configure(new ObjectMapper());

    private static ObjectMapper configure(ObjectMapper mapper) {
        // A typo such as "enviroment:" must stop the run. Silently ignoring it would emulate
        // with defaults while the analyst believes their settings applied.
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }

    /** A problem with a config: the key it concerns (empty for the whole file) and what is wrong. */
    public record Problem(String key, String message) {}

    /** Loads a config file, then applies {@code key.path=value} overrides in order. */
    public static EmulatorConfig load(Path file, List<String> overrides) throws IOException {
        return bind(readTree(file, overrides));
    }

    /**
     * Reads a config file and applies overrides, without checking the result. Use
     * {@link #problems(JsonNode)} to check it.
     */
    public static JsonNode readTree(Path file, List<String> overrides) throws IOException {
        if (file != null && !Files.isRegularFile(file)) {
            throw new ConfigException("Config file not found: " + file);
        }
        JsonNode tree;
        try {
            tree = file == null ? YAML.createObjectNode() : YAML.readTree(Files.newBufferedReader(file));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Syntax errors: the parser's message names the line and column.
            throw new ConfigException("The config file could not be read: " + e.getOriginalMessage()
                    + (e.getLocation() == null ? "" : " (line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr() + ")"));
        }
        if (tree == null || tree.isMissingNode() || tree.isNull()) {
            tree = YAML.createObjectNode();
        }
        if (!overrides.isEmpty() && !tree.isObject()) {
            throw new ConfigException("Config root must be a mapping, found " + tree.getNodeType());
        }
        for (String override : overrides) {
            applyOverride((ObjectNode) tree, override);
        }
        return tree;
    }

    /** Builds a validated config from an already parsed tree, such as a section of a larger file. */
    public static EmulatorConfig fromMap(java.util.Map<String, Object> tree) {
        return bind(YAML.valueToTree(tree == null ? java.util.Map.of() : tree));
    }

    /** Checks and binds a tree; every problem found is in the exception. */
    private static EmulatorConfig bind(JsonNode tree) {
        List<Problem> found = problems(tree);
        if (!found.isEmpty()) {
            throw ConfigException.of(found);
        }
        try {
            return YAML.treeToValue(tree, EmulatorConfig.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
            // The checks above should have caught this; report it in the same form anyway.
            throw new ConfigException("Invalid config: " + e.getMessage());
        }
    }

    /**
     * Every problem with a parsed config, not just the first: unknown keys, wrong types,
     * values outside their allowed list, and rules that involve several options.
     */
    public static List<Problem> problems(JsonNode tree) {
        List<Problem> found = new ArrayList<>();
        ConfigSchema.check(tree, found);
        if (tree != null && !tree.isObject() && !tree.isNull() && !tree.isMissingNode()) {
            return found;
        }
        // To report rules involving several options in the same pass, check the rest of the
        // config with the broken parts set aside. If even that cannot be read, the structural
        // problems already found are the useful answer.
        JsonNode rest = tree == null ? YAML.createObjectNode() : tree.deepCopy();
        found.forEach(problem -> remove(rest, problem.key()));
        try {
            for (Problem problem : problems(YAML.treeToValue(rest, EmulatorConfig.class))) {
                if (found.stream().noneMatch(p -> p.key().equals(problem.key()))) {
                    found.add(problem);
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
            if (found.isEmpty()) {
                found.add(new Problem("", "Invalid config: " + e.getMessage()));
            }
        }
        return found;
    }

    /** Removes the value at a problem's key, such as {@code network.http[1].url}, if it can be found. */
    private static void remove(JsonNode root, String key) {
        java.util.regex.Matcher steps = java.util.regex.Pattern.compile("([^.\\[\\]]+)|\\[(\\d+)\\]").matcher(key);
        List<String> path = new ArrayList<>();
        while (steps.find()) {
            path.add(steps.group(1) != null ? steps.group(1) : "#" + steps.group(2));
        }
        JsonNode node = root;
        for (int i = 0; i < path.size() - 1 && node != null; i++) {
            String step = path.get(i);
            node = step.startsWith("#") ? node.get(Integer.parseInt(step.substring(1))) : node.get(step);
        }
        String last = path.isEmpty() ? null : path.get(path.size() - 1);
        if (last == null || node == null) {
            return;
        }
        if (last.startsWith("#") && node instanceof com.fasterxml.jackson.databind.node.ArrayNode array) {
            array.set(Integer.parseInt(last.substring(1)), com.fasterxml.jackson.databind.node.NullNode.getInstance());
        } else if (node instanceof ObjectNode object) {
            object.remove(last);
        }
    }

    /**
     * Applies one {@code a.b.c=value} override. The value is parsed as YAML so that numbers,
     * booleans and inline lists keep their types without a second syntax to learn.
     */
    private static void applyOverride(ObjectNode root, String override) throws IOException {
        int eq = override.indexOf('=');
        if (eq <= 0) {
            throw new ConfigException("Override must look like key.path=value: " + override);
        }
        String[] path = override.substring(0, eq).trim().split("\\.");
        ObjectNode node = root;
        for (int i = 0; i < path.length - 1; i++) {
            JsonNode child = node.get(path[i]);
            node = child instanceof ObjectNode existing ? existing : node.putObject(path[i]);
        }
        node.set(path[path.length - 1], YAML.readTree(override.substring(eq + 1)));
    }

    /** Checks a bound config and throws if anything is wrong; every problem is in the message. */
    public static EmulatorConfig validate(EmulatorConfig config) {
        List<Problem> found = problems(config);
        if (!found.isEmpty()) {
            throw ConfigException.of(found);
        }
        return config;
    }

    /**
     * Problems in a bound config: allowed values (from {@link ConfigDoc}), and rules involving
     * more than one value, which the structural check cannot see.
     */
    public static List<Problem> problems(EmulatorConfig config) {
        List<Problem> found = new ArrayList<>();
        checkAllowed(config, "", found);
        for (int i = 0; i < config.entryPoints.size(); i++) {
            EmulatorConfig.EntryPoint entry = config.entryPoints.get(i);
            if ("method".equals(entry.type)) {
                if (entry.className == null) {
                    found.add(new Problem("entryPoints[" + i + "].class", "entryPoints[" + i + "].class is required when the type is 'method'"));
                }
                if (entry.method == null) {
                    found.add(new Problem("entryPoints[" + i + "].method", "entryPoints[" + i + "].method is required when the type is 'method'"));
                }
            }
        }
        for (int i = 0; i < config.hooks.size(); i++) {
            if (config.hooks.get(i).method == null) {
                found.add(new Problem("hooks[" + i + "].method", "hooks[" + i + "].method is required: every hook needs a method pattern"));
            }
        }
        EmulatorConfig.Limits limits = config.limits;
        positive(found, "limits.maxInstructions", limits.maxInstructions);
        positive(found, "limits.timeoutSeconds", limits.timeoutSeconds);
        positive(found, "limits.maxCallDepth", limits.maxCallDepth);
        positive(found, "limits.maxEventsPerCategory", limits.maxEventsPerCategory);
        positive(found, "limits.maxWrittenBytes", limits.maxWrittenBytes);
        if (limits.maxSleeps < 0) {
            found.add(new Problem("limits.maxSleeps", "limits.maxSleeps cannot be negative, found " + limits.maxSleeps));
        }
        if (limits.maxTasks < 0) {
            found.add(new Problem("limits.maxTasks", "limits.maxTasks cannot be negative, found " + limits.maxTasks));
        }
        if (config.threads.periodicRuns < 1) {
            found.add(new Problem("threads.periodicRuns", "threads.periodicRuns must be at least 1, found " + config.threads.periodicRuns));
        }
        if (config.environment.clockStart != null) {
            try {
                java.time.Instant.parse(config.environment.clockStart);
            } catch (java.time.format.DateTimeParseException e) {
                found.add(new Problem("environment.clockStart", "environment.clockStart must be an ISO-8601 time such as "
                        + "2024-03-01T09:00:00Z, found '" + config.environment.clockStart + "'"));
            }
        }
        for (int i = 0; i < config.network.http.size(); i++) {
            pattern(found, "network.http[" + i + "].url", config.network.http.get(i).url);
        }
        for (int i = 0; i < config.network.sockets.size(); i++) {
            pattern(found, "network.sockets[" + i + "].endpoint", config.network.sockets.get(i).endpoint);
        }
        for (int i = 0; i < config.processes.size(); i++) {
            pattern(found, "processes[" + i + "].command", config.processes.get(i).command);
        }
        return found;
    }

    private static void positive(List<Problem> found, String key, long value) {
        if (value <= 0) {
            found.add(new Problem(key, key + " must be more than 0, found " + value));
        }
    }

    /** A rule without a pattern would never match; a broken pattern would fail mid-run. */
    private static void pattern(List<Problem> found, String key, String pattern) {
        if (pattern == null) {
            found.add(new Problem(key, key + " is required"));
            return;
        }
        try {
            java.util.regex.Pattern.compile(pattern);
        } catch (java.util.regex.PatternSyntaxException e) {
            found.add(new Problem(key, key + " is not a valid regular expression: " + e.getDescription() + " in '" + pattern + "'"));
        }
    }

    /** Values set in code (not read from a file) get the same allowed-value check as files. */
    private static void checkAllowed(Object holder, String prefix, List<Problem> found) {
        for (java.lang.reflect.Field field : ConfigSchema.fields(holder.getClass())) {
            String path = prefix + ConfigSchema.jsonName(field);
            Object value;
            try {
                value = field.get(holder);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            ConfigDoc doc = field.getAnnotation(ConfigDoc.class);
            if (doc != null && doc.allowed().length > 0 && !java.util.Arrays.asList(doc.allowed()).contains(value)) {
                found.add(new Problem(path, path + " must be one of " + java.util.Arrays.toString(doc.allowed()) + ", found '" + value + "'"));
            }
            if (value != null && ConfigSchema.isConfigClass(value.getClass())) {
                checkAllowed(value, path + ".", found);
            } else if (value instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) != null && ConfigSchema.isConfigClass(list.get(i).getClass())) {
                        checkAllowed(list.get(i), path + "[" + i + "].", found);
                    }
                }
            }
        }
    }

    public static String toYaml(EmulatorConfig config) {
        try {
            return YAML.writeValueAsString(config);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ObjectMapper json() {
        return JSON;
    }

    /** A problem the user can fix by editing the config. Reported without a stack trace. */
    public static final class ConfigException extends RuntimeException {
        private final List<Problem> problems;

        public ConfigException(String message) {
            this(message, List.of(new Problem("", message)));
        }

        private ConfigException(String message, List<Problem> problems) {
            super(message);
            this.problems = List.copyOf(problems);
        }

        /** One problem reads as itself; several are listed one per line. */
        static ConfigException of(List<Problem> problems) {
            if (problems.size() == 1) {
                return new ConfigException(problems.get(0).message(), problems);
            }
            StringBuilder message = new StringBuilder(problems.size() + " problems in the config:");
            problems.forEach(p -> message.append("\n  - ").append(p.message()));
            return new ConfigException(message.toString(), problems);
        }

        public List<Problem> problems() {
            return problems;
        }
    }
}
