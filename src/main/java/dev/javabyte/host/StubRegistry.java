package dev.javabyte.host;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Lookup table of {@link Stub}s, keyed by JDK class and method.
 *
 * <p>A stub registered on a class also applies when the call names a subclass or implementor.
 * Bytecode routinely does that ({@code myLoader.defineClass(...)} names the sample's own loader
 * class), and security depends on the stub being found regardless of which name was used.
 */
public final class StubRegistry {

    private final Map<String, Stub> bySignature = new HashMap<>();
    private final Map<String, Stub> byName = new HashMap<>();
    private final Map<String, Stub> byClass = new HashMap<>();
    private final Map<String, Supplier<Object>> staticFields = new HashMap<>();
    private final Map<String, Optional<Stub>> cache = new HashMap<>();

    /** Replaces one exact overload. */
    public void on(String owner, String name, String desc, Stub stub) {
        bySignature.put(owner + '.' + name + desc, stub);
        cache.clear();
    }

    /** Replaces every overload of a method. */
    public void on(String owner, String name, Stub stub) {
        byName.put(owner + '.' + name, stub);
        cache.clear();
    }

    /** Replaces every method and constructor of a class; the stub dispatches on {@code ctx.name}. */
    public void onClass(String owner, Stub stub) {
        byClass.put(owner, stub);
        cache.clear();
    }

    /** Supplies the value of a static field such as {@code System.out}. */
    public void staticField(String owner, String name, Supplier<Object> value) {
        staticFields.put(owner + '.' + name, value);
    }

    public Supplier<Object> staticField(String owner, String name) {
        return staticFields.get(owner + '.' + name);
    }

    /**
     * Finds the stub for a call, or null.
     *
     * @param classFinder resolves an internal name to a host class (null if unavailable); needed
     *                    to walk supertypes
     */
    public Stub find(String owner, String name, String desc, Function<String, Class<?>> classFinder) {
        String key = owner + '.' + name + desc;
        Optional<Stub> cached = cache.get(key);
        if (cached == null) {
            cached = Optional.ofNullable(search(owner, name, desc, classFinder));
            cache.put(key, cached);
        }
        return cached.orElse(null);
    }

    private Stub search(String owner, String name, String desc, Function<String, Class<?>> classFinder) {
        Stub direct = direct(owner, name, desc);
        // Constructors are not inherited, so only the exact class is considered for them.
        if (direct != null || "<init>".equals(name)) {
            return direct;
        }
        Class<?> start = classFinder.apply(owner);
        if (start == null) {
            return null;
        }
        Deque<Class<?>> work = new ArrayDeque<>();
        Set<Class<?>> visited = new HashSet<>();
        work.add(start);
        while (!work.isEmpty()) {
            Class<?> current = work.poll();
            if (!visited.add(current)) {
                continue;
            }
            Stub inherited = direct(current.getName().replace('.', '/'), name, desc);
            if (inherited != null) {
                return inherited;
            }
            if (current.getSuperclass() != null) {
                work.add(current.getSuperclass());
            }
            work.addAll(java.util.List.of(current.getInterfaces()));
        }
        return null;
    }

    /** Most specific registration first: exact overload, then any overload, then whole class. */
    private Stub direct(String owner, String name, String desc) {
        Stub stub = bySignature.get(owner + '.' + name + desc);
        if (stub == null) {
            stub = byName.get(owner + '.' + name);
        }
        return stub != null ? stub : byClass.get(owner);
    }
}
