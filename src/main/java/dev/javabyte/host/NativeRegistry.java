package dev.javabyte.host;

import dev.javabyte.model.GuestMethod;
import dev.javabyte.plugin.NativeHandler;
import dev.javabyte.report.NativeLibraryInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What the emulator knows about native code: handlers that plugins registered for native
 * methods, and the libraries the sample has loaded.
 */
public final class NativeRegistry {

    private record Registration(Pattern pattern, NativeHandler handler) {}

    private final List<Registration> handlers = new ArrayList<>();
    private final Map<String, NativeLibraryInfo> loaded = new LinkedHashMap<>();

    /**
     * Registers a handler for native methods matching a glob over {@code owner.name} or
     * {@code owner.name(descriptor)}, in the same form as config hooks.
     */
    public void register(String methodGlob, NativeHandler handler) {
        StringBuilder regex = new StringBuilder();
        for (char c : methodGlob.toCharArray()) {
            regex.append(c == '*' ? ".*" : Pattern.quote(String.valueOf(c)));
        }
        handlers.add(new Registration(Pattern.compile(regex.toString()), handler));
    }

    public NativeHandler find(GuestMethod method) {
        String shortForm = method.owner.name + '.' + method.name;
        for (Registration registration : handlers) {
            if (registration.pattern().matcher(shortForm).matches()
                    || registration.pattern().matcher(shortForm + method.desc).matches()) {
                return registration.handler();
            }
        }
        return null;
    }

    private final List<java.util.function.BiConsumer<String, byte[]>> loadListeners = new ArrayList<>();

    /** Registers a callback for each library the sample loads; plugins use it to emulate the library. */
    public void onLibraryLoaded(java.util.function.BiConsumer<String, byte[]> listener) {
        loadListeners.add(listener);
    }

    /**
     * @param info description of the library, or null if its format was not recognised
     * @param data the library's bytes
     */
    public void libraryLoaded(String name, NativeLibraryInfo info, byte[] data) {
        if (info != null) {
            loaded.put(name, info);
        }
        loadListeners.forEach(listener -> listener.accept(name, data));
    }

    /** Name of a loaded library that exports the JNI symbol for this method, or null. */
    public String implementedBy(GuestMethod method) {
        String symbol = NativeLibraryInfo.jniName(method.owner.name, method.name);
        for (Map.Entry<String, NativeLibraryInfo> library : loaded.entrySet()) {
            for (String export : library.getValue().jniExports) {
                // An overloaded method's symbol carries a "__signature" suffix.
                if (export.equals(symbol) || export.startsWith(symbol + "__")) {
                    return library.getKey();
                }
            }
        }
        return null;
    }
}
