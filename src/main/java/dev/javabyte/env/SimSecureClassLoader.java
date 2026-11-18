package dev.javabyte.env;

import dev.javabyte.vm.VirtualMachine;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.stream.Stream;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.SecureClassLoader;

/**
 * The real class a sample's own subclass of {@code SecureClassLoader} extends.
 *
 * <p>A sample that extends {@code SecureClassLoader} directly would otherwise get the real class as the
 * base of its shell (see {@link dev.javabyte.shell.ShellFactory}), and with it a real parent:
 * the emulator's own application loader. JDK code that calls such a loader directly, such as
 * {@code Class.forName(name, true, loader)}, {@code ResourceBundle} or {@code ServiceLoader},
 * could then reach the analysis JVM's class path and real resources.
 *
 * <p>So the real superclass is given the bootstrap loader as its parent, and every public or
 * protected method that could look something up is overridden. What JDK code can get from it
 * comes only from the guest's virtual world: JDK classes, as from any loader; classes the
 * sample's own {@code findClass} produces, through the shell's ordinary guest dispatch; and
 * resources from the sample's archive, whose URLs cannot be opened. A class the guest defines
 * exists only in the interpreter, so JDK code asking for one is told it is not found. Guest
 * calls never reach these methods unless the sample overrides them: the class-loader stubs
 * answer them, including {@code defineClass}.
 *
 * <p>The parent the sample passes is only remembered, and the stubs report it from
 * {@code getParent()}: the application loader when the sample names none, as on a real JVM,
 * or null for the bootstrap loader.
 *
 * <p>The class is not final, so that shells can extend it, and its public constructors have
 * the shapes of {@code SecureClassLoader}'s, so that the sample's {@code super(...)} call finds a match.
 */
public class SimSecureClassLoader extends SecureClassLoader implements SimulatedLoader {

    /** The run the loader belongs to; null only if it was made outside an emulator thread. */
    private final VirtualMachine vm = VirtualMachine.current();
    private final ClassLoader guestParent;
    private final boolean defaultParent;

    /** {@code super()}: the parent is the application class loader, as with the real constructor. */
    public SimSecureClassLoader() {
        this(null, null, true);
    }

    /** {@code super(parent)}; null means the bootstrap loader. */
    public SimSecureClassLoader(ClassLoader guestParent) {
        this(null, guestParent, false);
    }

    /** {@code super(name, parent)}; the name is checked as the real constructor checks it. */
    public SimSecureClassLoader(String name, ClassLoader guestParent) {
        this(name, guestParent, false);
    }

    /** Remembers the parent; the real superclass gets the bootstrap loader as parent. */
    private SimSecureClassLoader(String name, ClassLoader guestParent, boolean defaultParent) {
        super(name, null);
        this.guestParent = guestParent;
        this.defaultParent = defaultParent;
    }

    /** The parent the guest asked for; the real parent is always the bootstrap loader. */
    @Override
    public final ClassLoader guestParent() {
        return guestParent;
    }

    /** True if the guest named no parent, so that {@code getParent} reports the application loader. */
    @Override
    public final boolean hasDefaultParent() {
        return defaultParent;
    }

    /** No permissions; nothing is ever defined for real, so nothing ever asks for them. */
    @Override
    protected PermissionCollection getPermissions(CodeSource codesource) {
        return new Permissions();
    }

    /** JDK classes from the bootstrap loader, then the sample's own {@code findClass}, if any. */
    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        return loadClass(name, false);
    }

    /**
     * The real delegation, which with the bootstrap loader as parent reaches only JDK classes
     * and then {@code findClass}. A guest {@code findClass} returns the interpreter's class
     * object, which is not a host class; JDK code is told the class was not found.
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        try {
            return super.loadClass(name, resolve);
        } catch (ClassCastException interpretedOnly) {
            throw new ClassNotFoundException(name);
        }
    }

    /** Nothing of its own: the sample's override, if any, is reached through its shell. */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        throw new ClassNotFoundException(name);
    }

    /** As the real method: only the unnamed module, through {@link #findClass(String)}. */
    @Override
    protected Class<?> findClass(String moduleName, String name) {
        if (moduleName != null) {
            return null;
        }
        try {
            return findClass(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** The sample's resource, on a URL that cannot be opened; never a real resource. */
    @Override
    public URL getResource(String name) {
        return SimLoaderSupport.resource(vm, name);
    }

    /** The sample's resource, if it has one; never a real resource. */
    @Override
    public Enumeration<URL> getResources(String name) {
        return SimLoaderSupport.resources(vm, name);
    }

    /** The sample's resource, if it has one; never a real resource. */
    @Override
    public Stream<URL> resources(String name) {
        URL url = getResource(name);
        return url == null ? Stream.empty() : Stream.of(url);
    }

    /** The bytes of the sample's resource, from memory; never a real file. */
    @Override
    public InputStream getResourceAsStream(String name) {
        return SimLoaderSupport.stream(vm, name);
    }

    /** The sample's resource, if it has one; never a real resource. */
    @Override
    protected URL findResource(String name) {
        return SimLoaderSupport.resource(vm, name);
    }

    /** Only the unnamed module, as the real method, which delegates to {@link #findResource(String)}. */
    @Override
    protected URL findResource(String moduleName, String name) {
        return moduleName == null ? findResource(name) : null;
    }

    /** The sample's resource, if it has one; never a real resource. */
    @Override
    protected Enumeration<URL> findResources(String name) {
        return SimLoaderSupport.resources(vm, name);
    }

    /** No native library is ever found: native code is never loaded. */
    @Override
    protected String findLibrary(String libname) {
        return null;
    }

    /**
     * What {@code Object.toString} prints for the sample's subclass, with its own class name.
     * Guest calls that name {@code Object} reach this method directly, and would otherwise
     * print the shell's generated name.
     */
    @Override
    public String toString() {
        return SimLoaderSupport.describe(this, "java.security.SecureClassLoader");
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
