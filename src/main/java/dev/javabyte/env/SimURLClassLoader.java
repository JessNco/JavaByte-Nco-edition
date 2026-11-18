package dev.javabyte.env;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;

/**
 * What the guest receives for {@code new URLClassLoader(...)} and {@code URLClassLoader.newInstance}.
 *
 * <p>A real {@code URLClassLoader} fetches from its URLs whenever anyone asks it for a class or
 * resource. Guest calls are answered by the class-loader stubs, which read archives from the
 * virtual disk or the network simulator. JDK code is another matter: an allow-listed method
 * that takes a loader, such as {@code ResourceBundle.Control.newBundle}, would call the real
 * loader directly, which means a real HTTP request and possibly a remote class loaded into the
 * analysis JVM.
 *
 * <p>So this loader only remembers its URLs, for {@code getURLs} and the report. The real
 * superclass is given no URLs and no parent, and every public method that could look
 * something up is overridden: none of them touches a URL, the disk or the network.
 */
public final class SimURLClassLoader extends URLClassLoader {

    private final List<URL> urls;
    private final ClassLoader guestParent;

    /**
     * @param name        the name the guest gave, or null
     * @param urls        the URLs the guest gave; only remembered
     * @param guestParent the parent the guest asked for, which {@code getParent} reports
     */
    public SimURLClassLoader(String name, URL[] urls, ClassLoader guestParent) {
        super(name, new URL[0], null);
        this.urls = new ArrayList<>(urls == null ? List.of() : List.of(urls));
        this.guestParent = guestParent;
    }

    /** The parent the guest asked for; the real parent is always the bootstrap loader. */
    public ClassLoader guestParent() {
        return guestParent;
    }

    /** The URLs the guest gave, plus any it added later. */
    @Override
    public URL[] getURLs() {
        synchronized (urls) {
            return urls.toArray(new URL[0]);
        }
    }

    /**
     * Adds a URL on the guest's behalf. The stubs call this when the guest reaches the
     * protected {@link #addURL} by reflection, which samples do to extend a loader's path.
     */
    public void addGuestURL(URL url) {
        addURL(url);
    }

    /** Remembers the URL, as a real loader would; nothing is fetched. */
    @Override
    protected void addURL(URL url) {
        if (url != null) {
            synchronized (urls) {
                urls.add(url);
            }
        }
    }

    /**
     * Finds only JDK classes, from the bootstrap loader. The superclass has no URLs and no
     * parent, so nothing else can be reached.
     */
    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        return super.loadClass(name);
    }

    /** Never looks at the URLs. */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        throw new ClassNotFoundException(name);
    }

    /** Nothing: resources are served to the guest by the stubs, never by this object. */
    @Override
    public URL getResource(String name) {
        return null;
    }

    /** Nothing: resources are served to the guest by the stubs, never by this object. */
    @Override
    public Enumeration<URL> getResources(String name) {
        return Collections.emptyEnumeration();
    }

    /** Nothing: resources are served to the guest by the stubs, never by this object. */
    @Override
    public Stream<URL> resources(String name) {
        return Stream.empty();
    }

    /** Nothing: resources are served to the guest by the stubs, never by this object. */
    @Override
    public InputStream getResourceAsStream(String name) {
        return null;
    }

    /** Nothing: resources are served to the guest by the stubs, never by this object. */
    @Override
    public URL findResource(String name) {
        return null;
    }

    /** Nothing: resources are served to the guest by the stubs, never by this object. */
    @Override
    public Enumeration<URL> findResources(String name) {
        return Collections.emptyEnumeration();
    }

    /** Nothing is open, so nothing needs closing. */
    @Override
    public void close() {
        // no files or connections are ever opened
    }

    /**
     * What {@code Object.toString} prints for a real {@code URLClassLoader}. Guest calls that
     * name {@code Object} reach this method directly, and would otherwise print this class's name.
     */
    @Override
    public String toString() {
        return "java.net.URLClassLoader@" + Integer.toHexString(hashCode());
    }

    /** The name the guest gave, or null. */
    @Override
    public String getName() {
        return super.getName();
    }

    /** Bookkeeping on this object only. */
    @Override
    public void setDefaultAssertionStatus(boolean enabled) {
        super.setDefaultAssertionStatus(enabled);
    }

    /** Bookkeeping on this object only. */
    @Override
    public void setPackageAssertionStatus(String packageName, boolean enabled) {
        super.setPackageAssertionStatus(packageName, enabled);
    }

    /** Bookkeeping on this object only. */
    @Override
    public void setClassAssertionStatus(String className, boolean enabled) {
        super.setClassAssertionStatus(className, enabled);
    }

    /** Bookkeeping on this object only. */
    @Override
    public void clearAssertionStatus() {
        super.clearAssertionStatus();
    }
}
