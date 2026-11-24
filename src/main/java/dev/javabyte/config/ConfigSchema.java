package dev.javabyte.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shape of a config, read from {@link EmulatorConfig}'s fields and their {@link ConfigDoc}.
 *
 * <p>Deriving it from the classes, rather than writing it out separately, means a new option
 * cannot be forgotten: it appears in {@code javabyte schema}, in the desktop app's forms and in
 * validation the moment the field exists, and a test fails until it has a description.
 */
public final class ConfigSchema {

    private ConfigSchema() {}

    /**
     * One option. {@code path} uses dots for nesting and {@code []} for list items, as in
     * {@code network.http[].url}. {@code type} is one of {@code string}, {@code integer},
     * {@code boolean}, {@code object}, {@code list}, {@code map} or {@code any}; for lists and
     * maps, {@code element} is the type of their items.
     */
    public record Option(String path, String type, String element, Object defaultValue, List<String> allowed, String description) {}

    /** Every option, parents before their children, in declaration order. */
    public static List<Option> options() {
        List<Option> options = new ArrayList<>();
        collect(EmulatorConfig.class, "", options);
        return options;
    }

    private static void collect(Class<?> type, String prefix, List<Option> out) {
        Object defaults = newInstance(type);
        for (Field field : fields(type)) {
            String path = prefix + jsonName(field);
            ConfigDoc doc = field.getAnnotation(ConfigDoc.class);
            Type generic = field.getGenericType();
            Object value = read(field, defaults);
            String kind = kind(generic);
            String element = null;
            if (kind.equals("list") || kind.equals("map")) {
                Type item = typeArgument(generic, kind.equals("list") ? 0 : 1);
                element = kind(item);
                out.add(new Option(path, kind, element, value, List.of(), doc == null ? null : doc.value()));
                if (item instanceof Class<?> itemClass && isConfigClass(itemClass)) {
                    collect(itemClass, path + (kind.equals("list") ? "[]." : ".*."), out);
                }
                continue;
            }
            out.add(new Option(path, kind, null, isConfigClass(field.getType()) ? null : value,
                    doc == null ? List.of() : List.of(doc.allowed()), doc == null ? null : doc.value()));
            if (isConfigClass(field.getType())) {
                collect(field.getType(), path + ".", out);
            }
        }
    }

    /** The schema as plain data, for {@code javabyte schema --json}. */
    /** The plugins on the class path, each with its description and settings. */
    public static List<Map<String, Object>> plugins() {
        List<Map<String, Object>> plugins = new ArrayList<>();
        for (dev.javabyte.plugin.EmulatorPlugin plugin : java.util.ServiceLoader.load(dev.javabyte.plugin.EmulatorPlugin.class)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", plugin.id());
            entry.put("description", plugin.description());
            List<Map<String, Object>> settings = new ArrayList<>();
            for (Option option : plugin.settings()) {
                Map<String, Object> setting = new LinkedHashMap<>();
                setting.put("path", option.path());
                setting.put("type", option.type());
                if (option.defaultValue() != null) {
                    setting.put("default", option.defaultValue());
                }
                if (!option.allowed().isEmpty()) {
                    setting.put("allowed", option.allowed());
                }
                setting.put("description", option.description());
                settings.add(setting);
            }
            entry.put("settings", settings);
            plugins.add(entry);
        }
        plugins.sort(java.util.Comparator.comparing(p -> (String) p.get("id")));
        return plugins;
    }

    /** The whole schema as the JSON document {@code schema --json} prints: every option, then every plugin. */
    public static Map<String, Object> toMap() {
        List<Map<String, Object>> options = new ArrayList<>();
        for (Option option : options()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", option.path());
            entry.put("type", option.type());
            if (option.element() != null) {
                entry.put("element", option.element());
            }
            if (option.defaultValue() != null) {
                entry.put("default", option.defaultValue());
            }
            if (!option.allowed().isEmpty()) {
                entry.put("allowed", option.allowed());
            }
            entry.put("description", option.description());
            options.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("options", options);
        root.put("plugins", plugins());
        return root;
    }

    // ------------------------------------------------------------------ structural checks

    /**
     * Checks a parsed config against the shape, collecting every problem instead of stopping
     * at the first: unknown keys (with a suggestion when one is close), wrong value types and
     * values outside an option's allowed list. Keys in problems name list items by index, such
     * as {@code network.http[2].url}, so a person or the desktop app can find the exact place.
     */
    static void check(JsonNode root, List<ConfigLoader.Problem> problems) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return;
        }
        if (!root.isObject()) {
            problems.add(new ConfigLoader.Problem("", "The config must be a mapping of options, found " + describe(root)));
            return;
        }
        checkObject(root, EmulatorConfig.class, "", problems);
    }

    private static void checkObject(JsonNode node, Class<?> type, String prefix, List<ConfigLoader.Problem> problems) {
        Map<String, Field> byName = new LinkedHashMap<>();
        for (Field field : fields(type)) {
            byName.put(jsonName(field), field);
        }
        node.fieldNames().forEachRemaining(name -> {
            String path = prefix + name;
            Field field = byName.get(name);
            if (field == null) {
                String hint = closest(name, byName.keySet());
                problems.add(new ConfigLoader.Problem(path, "Unknown option '" + path + "'"
                        + (hint == null ? "" : "; did you mean '" + prefix + hint + "'?")));
            } else {
                checkValue(node.get(name), field.getGenericType(), field.getAnnotation(ConfigDoc.class), path, problems);
            }
        });
    }

    private static void checkValue(JsonNode value, Type type, ConfigDoc doc, String path, List<ConfigLoader.Problem> problems) {
        if (value == null || value.isNull()) {
            return; // null means "use the default"
        }
        switch (kind(type)) {
            case "string" -> {
                if (!value.isValueNode()) {
                    problems.add(new ConfigLoader.Problem(path, path + " must be text, found " + describe(value)));
                } else if (doc != null && doc.allowed().length > 0 && !Arrays.asList(doc.allowed()).contains(value.asText())) {
                    problems.add(new ConfigLoader.Problem(path,
                            path + " must be one of " + Arrays.toString(doc.allowed()) + ", found '" + value.asText() + "'"));
                }
            }
            case "integer" -> {
                boolean whole = value.isIntegralNumber() || (value.isTextual() && value.asText().trim().matches("-?\\d+"));
                if (!whole) {
                    problems.add(new ConfigLoader.Problem(path, path + " must be a whole number, found " + describe(value)));
                } else if (type == int.class || type == Integer.class) {
                    try {
                        Integer.parseInt(value.asText().trim());
                    } catch (NumberFormatException e) {
                        problems.add(new ConfigLoader.Problem(path, path + " is too large, found " + value.asText()));
                    }
                }
            }
            case "boolean" -> {
                boolean ok = value.isBoolean() || (value.isTextual() && value.asText().matches("(?i)true|false"));
                if (!ok) {
                    problems.add(new ConfigLoader.Problem(path, path + " must be true or false, found " + describe(value)));
                }
            }
            case "list" -> {
                if (!value.isArray()) {
                    problems.add(new ConfigLoader.Problem(path, path + " must be a list, found " + describe(value)));
                    return;
                }
                Type item = typeArgument(type, 0);
                for (int i = 0; i < value.size(); i++) {
                    checkValue(value.get(i), item, doc, path + "[" + i + "]", problems);
                }
            }
            case "map" -> {
                if (!value.isObject()) {
                    problems.add(new ConfigLoader.Problem(path, path + " must be a mapping of names to values, found " + describe(value)));
                    return;
                }
                Type item = typeArgument(type, 1);
                value.fields().forEachRemaining(e -> checkValue(e.getValue(), item, null, path + "." + e.getKey(), problems));
            }
            case "object" -> {
                if (!value.isObject()) {
                    problems.add(new ConfigLoader.Problem(path, path + " must be a mapping of options, found " + describe(value)));
                } else {
                    checkObject(value, (Class<?>) type, path + ".", problems);
                }
            }
            default -> {
                // "any": free-form values such as hook results and method arguments.
            }
        }
    }

    // ------------------------------------------------------------------ reflection helpers

    /** The option fields of a config class, in declaration order: public, not static. */
    static List<Field> fields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers()) && !Modifier.isStatic(field.getModifiers())) {
                fields.add(field);
            }
        }
        return fields;
    }

    static String jsonName(Field field) {
        JsonProperty renamed = field.getAnnotation(JsonProperty.class);
        return renamed != null && !renamed.value().isEmpty() ? renamed.value() : field.getName();
    }

    static boolean isConfigClass(Class<?> type) {
        return type.getEnclosingClass() == EmulatorConfig.class || type == EmulatorConfig.class;
    }

    private static String kind(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            if (List.class.isAssignableFrom(raw)) {
                return "list";
            }
            if (Map.class.isAssignableFrom(raw)) {
                return "map";
            }
        }
        if (type == String.class) {
            return "string";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return "integer";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type instanceof Class<?> cls && isConfigClass(cls)) {
            return "object";
        }
        return "any";
    }

    private static Type typeArgument(Type type, int index) {
        return type instanceof ParameterizedType parameterized ? parameterized.getActualTypeArguments()[index] : Object.class;
    }

    private static Object newInstance(Class<?> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("config class without a public no-argument constructor: " + type, e);
        }
    }

    private static Object read(Field field, Object instance) {
        try {
            return field.get(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String describe(JsonNode value) {
        if (value.isTextual()) {
            return "'" + value.asText() + "'";
        }
        if (value.isArray()) {
            return "a list";
        }
        if (value.isObject()) {
            return "a mapping";
        }
        return value.toString();
    }

    /** The known name nearest to a misspelt one, if it is close enough to be a typo. */
    private static String closest(String name, Iterable<String> known) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : known) {
            int distance = distance(name.toLowerCase(), candidate.toLowerCase());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return bestDistance <= Math.max(2, name.length() / 4) ? best : null;
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
