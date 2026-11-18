package dev.javabyte.env;

import dev.javabyte.shell.ShellInstance;
import dev.javabyte.vm.VirtualMachine;
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
 */
public class SimURLClassLoader extends URLClassLoader {

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
    public ClassLoader guestParent() {
        return guestParent;
    }

    /**
     * True if the guest named no parent, so that {@code getParent} should report the
     * application class loader, which the stubs hold.
     */
    public boolean hasDefaultParent() {
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
     * What {@code Object.toString} prints for a real {@code URLClassLoader}, or for the sample's
     * own subclass when this object is that subclass's shell. Guest calls that name
     * {@code Object} reach this method directly, and would otherwise print this class's name.
     */
    @Override
    public String toString() {
        String className = this instanceof ShellInstance shell && shell.javabyte$state() != null
                ? shell.javabyte$state().type.dottedName() : "java.net.URLClassLoader";
        return className + '@' + Integer.toHexString(hashCode());
    }

    /**
     * A per-run fixed stand-in for the identity hash (see
     * {@link dev.javabyte.vm.TypeSystem#identityHash}), so the text above is the same in every
     * run. Outside an emulator thread there is nothing to ask, and the real identity hash is used.
     */
    @Override
    public int hashCode() {
        VirtualMachine vm = VirtualMachine.current();
        return vm != null ? vm.types.identityHash(this) : super.hashCode();
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
