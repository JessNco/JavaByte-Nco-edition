package dev.javabyte.env;

import dev.javabyte.shell.ShellInstance;
import dev.javabyte.vm.VirtualMachine;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * What the simulated class loaders share: the answers they give JDK code that calls them
 * directly, and the text they show.
 *
 * <p>Guest calls on a loader are answered by the class-loader stubs. JDK code is another
 * matter: an allow-listed method that is handed a loader calls the loader's own methods, from
 * inside host code where no stub sees the call. Whatever such code asks, the answer must come
 * from the guest's virtual world, never from the analysis JVM's class path or disk. Resources
 * are therefore read from the sample's own archive, and the URLs handed out cannot be opened:
 * they are built on {@link SimURLStreamHandler}, which refuses every connection.
 *
 * <p>These methods may run on a thread that is not an emulator thread (a JDK worker, say), so
 * they only read the class repository and never write to the event log, which only emulator
 * threads may touch. A refused attempt to open a URL is reported only when it happens on the
 * emulator thread of the same run.
 */
public final class SimLoaderSupport {

    private SimLoaderSupport() {}

    /** A resource name as a loader takes it: never with a leading slash. */
    private static String strip(String name) {
        return name != null && name.startsWith("/") ? name.substring(1) : name;
    }

    /**
     * The URL of a resource in the sample's archive, or null if the archive has no such
     * resource. The URL has the form a real loader would give ({@code jar:file:...!/name}), but
     * opening it is refused.
     *
     * @param vm the run the loader belongs to; null answers nothing
     */
    public static URL resource(VirtualMachine vm, String name) {
        String path = strip(name);
        if (vm == null || path == null || vm.classes.resource(path) == null) {
            return null;
        }
        try {
            URL sample = fileUrl(vm, vm.env.samplePath());
            return new URL(null, "jar:" + sample.toExternalForm() + "!/" + path, new SimURLStreamHandler("jar", refused -> {
                if (VirtualMachine.current() == vm) {
                    vm.events.addOnce("url-open:" + refused.toExternalForm(), "policy", "blocked",
                            "kind", "url-open-by-jdk", "member", refused.toExternalForm());
                }
            }));
        } catch (MalformedURLException e) {
            return null;
        }
    }

    /** Every URL for a resource: the one from {@link #resource}, or none. */
    public static Enumeration<URL> resources(VirtualMachine vm, String name) {
        URL url = resource(vm, name);
        return Collections.enumeration(url == null ? List.of() : List.of(url));
    }

    /** The bytes of a resource in the sample's archive, as a stream; null if there is none. */
    public static InputStream stream(VirtualMachine vm, String name) {
        String path = strip(name);
        byte[] data = vm == null || path == null ? null : vm.classes.resource(path);
        return data == null ? null : new ByteArrayInputStream(data);
    }

    /**
     * The {@code file:} URL of a path on the virtual disk, built on a handler that refuses to
     * open it, so that JDK code given the URL cannot read the real file at the same path.
     */
    public static URL fileUrl(VirtualMachine vm, String path) throws MalformedURLException {
        URL plain = new SimFile(vm.env.files, path).toURI().toURL();
        return new URL(null, plain.toExternalForm(), new SimURLStreamHandler("file", refused -> {
            if (VirtualMachine.current() == vm) {
                vm.events.addOnce("url-open:" + refused.toExternalForm(), "policy", "blocked",
                        "kind", "url-open-by-jdk", "member", refused.toExternalForm());
            }
        }));
    }

    /**
     * What {@code Object.toString} prints for a loader: the sample's own class name when the
     * loader is the shell of a guest subclass, otherwise the JDK class it stands for, then
     * {@code @} and the hash in hexadecimal.
     *
     * @param jdkName the dotted name of the JDK class the loader stands for
     */
    public static String describe(Object loader, String jdkName) {
        String className = loader instanceof ShellInstance shell && shell.javabyte$state() != null
                ? shell.javabyte$state().type.dottedName() : jdkName;
        return className + '@' + Integer.toHexString(loader.hashCode());
    }

    /**
     * A per-run fixed stand-in for a loader's identity hash (see
     * {@link dev.javabyte.vm.TypeSystem#identityHash}), so the text above is the same in every
     * run. Outside an emulator thread there is nothing to ask, and the real identity hash is used.
     */
    public static int hash(Object loader) {
        VirtualMachine vm = VirtualMachine.current();
        return vm != null ? vm.types.identityHash(loader) : System.identityHashCode(loader);
    }
}
