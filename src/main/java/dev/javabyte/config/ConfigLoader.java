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

    /** Loads a config file, then applies {@code key.path=value} overrides in order. */
    public static EmulatorConfig load(Path file, List<String> overrides) throws IOException {
        if (file != null && !Files.isRegularFile(file)) {
            throw new ConfigException("Config file not found: " + file);
        }
        JsonNode tree = file == null ? YAML.createObjectNode() : YAML.readTree(Files.newBufferedReader(file));
        if (tree == null || tree.isMissingNode() || tree.isNull()) {
            tree = YAML.createObjectNode();
        }
        if (!tree.isObject()) {
            throw new ConfigException("Config root must be a mapping, found " + tree.getNodeType());
        }
        for (String override : overrides) {
            applyOverride((ObjectNode) tree, override);
        }
        try {
            return validate(YAML.treeToValue(tree, EmulatorConfig.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ConfigException("Invalid config: " + e.getOriginalMessage());
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

    /** Checks cross-field rules that the type system cannot express. */
    public static EmulatorConfig validate(EmulatorConfig config) {
        requireOneOf("environment.os", config.environment.os, "windows", "linux");
        requireOneOf("network.mode", config.network.mode, "respond", "offline");
        requireOneOf("policy.onBlocked", config.policy.onBlocked, "phantom", "throw", "abort");
        requireOneOf("trace.strings", config.trace.strings, "static", "all", "none");
        requireOneOf("threads.mode", config.threads.mode, "deferred", "inline");
        requireOneOf("output.console", config.output.console, "text", "json", "quiet");
        for (EmulatorConfig.EntryPoint entry : config.entryPoints) {
            requireOneOf("entryPoints[].type", entry.type, "main", "method", "clinit-sweep");
            if ("method".equals(entry.type) && (entry.className == null || entry.method == null)) {
                throw new ConfigException("An entry point of type 'method' needs both 'class' and 'method'");
            }
        }
        for (EmulatorConfig.Hook hook : config.hooks) {
            if (hook.method == null) {
                throw new ConfigException("Every hook needs a 'method' pattern");
            }
            requireOneOf("hooks[].action", hook.action, "return", "skip", "throw", "trace");
        }
        EmulatorConfig.Limits limits = config.limits;
        if (limits.maxInstructions <= 0 || limits.timeoutSeconds <= 0 || limits.maxCallDepth <= 0
                || limits.maxEventsPerCategory <= 0 || limits.maxWrittenBytes <= 0) {
            throw new ConfigException("limits.maxInstructions, timeoutSeconds, maxCallDepth, maxEventsPerCategory "
                    + "and maxWrittenBytes must be positive");
        }
        if (limits.maxSleeps < 0 || limits.maxTasks < 0 || config.threads.periodicRuns < 1) {
            throw new ConfigException("limits.maxSleeps and maxTasks cannot be negative; threads.periodicRuns must be at least 1");
        }
        for (EmulatorConfig.HttpRule rule : config.network.http) {
            requirePattern("network.http[].url", rule.url);
        }
        for (EmulatorConfig.SocketRule rule : config.network.sockets) {
            requirePattern("network.sockets[].endpoint", rule.endpoint);
        }
        for (EmulatorConfig.ProcessRule rule : config.processes) {
            requirePattern("processes[].command", rule.command);
        }
        return config;
    }

    /** A rule without a pattern would never match; a broken pattern would fail mid-run. */
    private static void requirePattern(String key, String pattern) {
        if (pattern == null) {
            throw new ConfigException(key + " is required");
        }
        try {
            java.util.regex.Pattern.compile(pattern);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new ConfigException(key + " is not a valid regular expression: " + e.getDescription() + " in '" + pattern + "'");
        }
    }

    private static void requireOneOf(String key, String value, String... allowed) {
        if (!List.of(allowed).contains(value)) {
            throw new ConfigException(key + " must be one of " + List.of(allowed) + ", found '" + value + "'");
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
        public ConfigException(String message) {
            super(message);
        }
    }
}
