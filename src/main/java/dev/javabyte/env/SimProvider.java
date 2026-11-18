package dev.javabyte.env;

import dev.javabyte.host.StandIn;
import java.io.IOException;
import java.io.InputStream;
import java.security.Provider;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A private copy of one of the JVM's security providers, which is what the guest receives
 * wherever the JDK would hand out the real {@code Provider}.
 *
 * <p>The real providers are shared by the whole analysis JVM, and each one is a map from
 * algorithm names to class names. If the guest could add an entry such as
 * {@code "MessageDigest.X" -> "java.util.Timer"}, a later {@code MessageDigest.getInstance("X")}
 * would make the JDK load and construct that class itself: a real thread, socket or log file,
 * created inside host code where no stub or policy rule can see it. The change would also
 * outlive the run.
 *
 * <p>This copy keeps its own table. Reads see the real provider's entries plus whatever the
 * guest has changed, so the guest's view stays consistent, and each change is reported. But
 * services, which are what the JDK actually instantiates, always come from the real provider,
 * so nothing the guest writes can name a class for the JDK to load.
 */
public final class SimProvider extends Provider implements StandIn {

    private static final long serialVersionUID = 1L;

    private final transient Provider real;
    private final transient BiConsumer<String, Object> changed;

    /**
     * @param real    the JVM's provider this object stands for; it is only ever read
     * @param changed told about each change the guest makes (the operation and the key, or
     *                null when there is no single key), so it can be reported
     */
    public SimProvider(Provider real, BiConsumer<String, Object> changed) {
        super(real.getName(), real.getVersionStr(), real.getInfo());
        this.real = real;
        this.changed = changed;
        copyEntries();
    }

    /** Fills this table with the real provider's entries; not reported, as the guest did nothing. */
    private void copyEntries() {
        for (Map.Entry<Object, Object> entry : real.entrySet()) {
            super.put(entry.getKey(), entry.getValue());
        }
    }

    /** The real provider's class, so {@code getClass()} in the guest names it and not this one. */
    @Override
    public String standsFor() {
        return real.getClass().getName().replace('.', '/');
    }

    private void report(String operation, Object key) {
        if (changed != null) {
            changed.accept(operation, key);
        }
    }

    // ------------------------------------------------------------------ what the JDK instantiates

    /** Always the real provider's service, whatever the guest has written into this table. */
    @Override
    public Service getService(String type, String algorithm) {
        return real.getService(type, algorithm);
    }

    /** Always the real provider's services, whatever the guest has written into this table. */
    @Override
    public Set<Service> getServices() {
        return real.getServices();
    }

    /** Refused, as the JDK refuses it for every provider that has no configuration step. */
    @Override
    public Provider configure(String configArg) {
        throw new UnsupportedOperationException("configure is not supported");
    }

    /** Reports the real provider's state; configuring is refused, so it never changes. */
    @Override
    public boolean isConfigured() {
        return real.isConfigured();
    }

    // ------------------------------------------------------------------ identity

    /** The real provider's name. */
    @Override
    public String getName() {
        return real.getName();
    }

    /** The real provider's version. */
    @Override
    @SuppressWarnings("deprecation")
    public double getVersion() {
        return real.getVersion();
    }

    /** The real provider's version text. */
    @Override
    public String getVersionStr() {
        return real.getVersionStr();
    }

    /** The real provider's description. */
    @Override
    public String getInfo() {
        return real.getInfo();
    }

    /** Same text as the real provider, for example "SUN version 17". */
    @Override
    public String toString() {
        return real.toString();
    }

    // ------------------------------------------------------------------ reads of this table

    /** Reads this copy. */
    @Override
    public synchronized Set<Map.Entry<Object, Object>> entrySet() {
        return super.entrySet();
    }

    /** Reads this copy. */
    @Override
    public Set<Object> keySet() {
        return super.keySet();
    }

    /** Reads this copy. */
    @Override
    public Collection<Object> values() {
        return super.values();
    }

    /** Reads this copy. */
    @Override
    public Object get(Object key) {
        return super.get(key);
    }

    /** Reads this copy. */
    @Override
    public synchronized Object getOrDefault(Object key, Object defaultValue) {
        return super.getOrDefault(key, defaultValue);
    }

    /** Reads this copy. */
    @Override
    public synchronized void forEach(BiConsumer<? super Object, ? super Object> action) {
        super.forEach(action);
    }

    /** Reads this copy. */
    @Override
    public Enumeration<Object> keys() {
        return super.keys();
    }

    /** Reads this copy. */
    @Override
    public Enumeration<Object> elements() {
        return super.elements();
    }

    /** Reads this copy. */
    @Override
    public String getProperty(String key) {
        return super.getProperty(key);
    }

    // ------------------------------------------------------------------ changes: this copy only, reported

    /** Changes this copy only, and reports it. */
    @Override
    public synchronized Object put(Object key, Object value) {
        report("put", key);
        return super.put(key, value);
    }

    /** Changes this copy only, and reports it. */
    @Override
    public synchronized Object putIfAbsent(Object key, Object value) {
        report("putIfAbsent", key);
        return super.putIfAbsent(key, value);
    }

    /** Changes this copy only, and reports it. */
    @Override
    public synchronized void putAll(Map<?, ?> map) {
        report("putAll", null);
        super.putAll(map);
    }

    /** Changes this copy only, and reports it. */
    @Override
    public synchronized Object remove(Object key) {
        report("remove", key);
        return super.remove(key);
    }

    /** Changes this copy only, and reports it. */
    @Override
    public synchronized boolean remove(Object key, Object value) {
        report("remove", key);
        return super.remove(key, value);
    }

    /** Changes this copy only, and reports it. */
    @Override
    public synchronized boolean replace(Object key, Object oldValue, Object newValue) {
        report("replace", key);
        return super.replace(key, oldValue, newValue);
    }

    /** Changes this copy only, and reports it. */
    @Override
    public synchronized Object replace(Object key, Object value) {
        report("replace", key);
        return super.replace(key, value);
    }

    /** Changes this copy only, and reports it. */
    @Override
    public synchronized void replaceAll(BiFunction<? super Object, ? super Object, ? extends Object> function) {
        report("replaceAll", null);
        super.replaceAll(function);
    }

    /** Changes this copy only, and reports it. */
    @Override
    public synchronized Object compute(Object key, BiFunction<? super Object, ? super Object, ? extends Object> function) {
        report("compute", key);
        return super.compute(key, function);
    }

    /** Changes this copy only, and reports it. */
    @Override
    public synchronized Object computeIfAbsent(Object key, Function<? super Object, ? extends Object> function) {
        report("computeIfAbsent", key);
        return super.computeIfAbsent(key, function);
    }

    /** Changes this copy only, and reports it. */
    @Override
    public synchronized Object computeIfPresent(Object key, BiFunction<? super Object, ? super Object, ? extends Object> function) {
        report("computeIfPresent", key);
        return super.computeIfPresent(key, function);
    }

    /** Changes this copy only, and reports it. */
    @Override
    public synchronized Object merge(Object key, Object value, BiFunction<? super Object, ? super Object, ? extends Object> function) {
        report("merge", key);
        return super.merge(key, value, function);
    }

    /** Changes this copy only, and reports it. */
    @Override
    public synchronized void clear() {
        report("clear", null);
        super.clear();
    }

    /** Changes this copy only, and reports it. */
    @Override
    public synchronized void load(InputStream in) throws IOException {
        report("load", null);
        super.load(in);
    }

    /** Changes this copy only, and reports it; {@link #getService} never consults it. */
    @Override
    protected void putService(Service service) {
        report("putService", service == null ? null : service.getType() + '.' + service.getAlgorithm());
        super.putService(service);
    }

    /** Changes this copy only, and reports it. */
    @Override
    protected void removeService(Service service) {
        report("removeService", service == null ? null : service.getType() + '.' + service.getAlgorithm());
        super.removeService(service);
    }
}
