package dev.javabyte.env;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLStreamHandlerFactory;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.security.Permissions;
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
 *
 * <p>The class is not final because a sample's own subclass of {@code URLClassLoader} needs a
 * real class to extend: {@link dev.javabyte.shell.ShellFactory} builds that subclass's shell on
 * this class instead of the real one. Its public constructors therefore have the shapes of
 * {@code URLClassLoader}'s, so the sample's {@code super(...)} call finds a match. A stream
 * handler factory the sample passes is ignored, as nothing here ever opens a URL.
 *
 * <p>The built-in loaders extend it too ({@link SimClassLoader} and
 * {@link SimPlatformClassLoader}), because on Java 8 the application and extension loaders
 * are {@code URLClassLoader}s. On a newer advertised Java they are not, and the type system
 * answers casts and {@code instanceof} from the class they imitate instead.
 */
public class SimURLClassLoader extends URLClassLoader implements SimulatedLoader {

    private final List<URL> urls;
    private final ClassLoader guestParent;
    private final boolean defaultParent;

    /**
     * @param name        the name the guest gave, or null
     * @param urls        the URLs the guest gave; only remembered
     * @param guestParent the parent the guest asked for, which {@code getParent} reports
     */
    public SimURLClassLoader(String name, URL[] urls, ClassLoader guestParent) {
        this(name, urls, guestParent, false);
    }

    /** {@code super(urls)}: the parent is the application class loader, as with the real constructor. */
    public SimURLClassLoader(URL[] urls) {
        this(null, urls, null, true);
    }

    /** {@code super(urls, parent)}. */
    public SimURLClassLoader(URL[] urls, ClassLoader guestParent) {
        this(null, urls, guestParent, false);
    }

    /** {@code super(urls, parent, factory)}; the factory is never used. */
    public SimURLClassLoader(URL[] urls, ClassLoader guestParent, URLStreamHandlerFactory factory) {
        this(null, urls, guestParent, false);
    }

    /** {@code super(name, urls, parent, factory)}; the factory is never used. */
    public SimURLClassLoader(String name, URL[] urls, ClassLoader guestParent, URLStreamHandlerFactory factory) {
        this(name, urls, guestParent, false);
    }

    /** Remembers everything; the real superclass gets no URLs and the bootstrap loader as parent. */
    private SimURLClassLoader(String name, URL[] urls, ClassLoader guestParent, boolean defaultParent) {
        super(name, new URL[0], null);
        this.urls = new ArrayList<>(urls == null ? List.of() : java.util.Arrays.asList(urls));
        this.guestParent = guestParent;
        this.defaultParent = defaultParent;
    }

    /** The parent the guest asked for; the real parent is always the bootstrap loader. */
    @Override
    public final ClassLoader guestParent() {
        return guestParent;
    }

    /**
     * True if the guest named no parent, so that {@code getParent} should report the
     * application class loader, which the stubs hold.
     */
    @Override
    public final boolean hasDefaultParent() {
        return defaultParent;
    }

    /** No permissions: the real method opens a connection to each code source's URL. */
    @Override
    protected PermissionCollection getPermissions(CodeSource codesource) {
        return new Permissions();
    }

    /** The URLs the guest gave, plus any it added later. */
    @Override
    public URL[] getURLs() {
        return guestURLs();
    }

    /**
     * Only the URLs the guest gave or added. These are the archives the class-loader stubs
     * read, from the virtual disk or the network simulator, when the guest asks this loader for
     * a class; a real {@code URLClassLoader} likewise loads from its own list, whatever a
     * subclass's {@code getURLs} says. The built-in loaders list more in {@code getURLs}, but
     * those are the sample itself or exist only as names. Final, so a sample's subclass cannot
     * have the stubs run its code here.
     */
    public final URL[] guestURLs() {
        synchronized (urls) {
            return urls.toArray(new URL[0]);
        }
    }

    /**
     * Adds a URL on the guest's behalf. The stubs call this when the guest reaches the
     * protected {@link #addURL} by reflection, which samples do to extend a loader's path.
     * Final, like the other methods only the stubs call, so that a shell never routes it to
     * guest code.
     */
    public final void addGuestURL(URL url) {
        remember(url);
    }

    /** Remembers the URL, as a real loader would; nothing is fetched. */
    @Override
    protected void addURL(URL url) {
        remember(url);
    }

    /**
     * Adds to the list without calling {@link #addURL}, which a sample's subclass may override:
     * its override calling {@code super.addURL} reaches the stubs, and from there
     * {@link #addGuestURL}, which must not call back into the override.
     */
    private void remember(URL url) {
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

    /**
     * The real delegation, which reaches only JDK classes and then {@code findClass}. A
     * sample's own {@code findClass} returns the interpreter's class object, which is not a
     * host class, so JDK code is told the class was not found.
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        try {
            return super.loadClass(name, resolve);
        } catch (ClassCastException interpretedOnly) {
            throw new ClassNotFoundException(name);
        }
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
     * What {@code Object.toString} prints for a real {@code URLClassLoader}, or for the sample's
     * own subclass when this object is that subclass's shell. Guest calls that name
     * {@code Object} reach this method directly, and would otherwise print this class's name.
     */
    @Override
    public String toString() {
        return SimLoaderSupport.describe(this, "java.net.URLClassLoader");
    }

    /** A per-run fixed stand-in for the identity hash; see {@link SimLoaderSupport#hash}. */
    @Override
    public int hashCode() {
        return SimLoaderSupport.hash(this);
    }

    /** Identity, as for every class loader. */
    @Override
    public boolean equals(Object other) {
        return this == other;
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
