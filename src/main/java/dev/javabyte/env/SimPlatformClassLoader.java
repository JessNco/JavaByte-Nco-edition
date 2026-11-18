package dev.javabyte.env;

import java.net.URL;
import java.util.Arrays;
import java.util.function.Supplier;

/**
 * The object guest code receives as the parent of the application class loader: the platform
 * class loader on Java 9 and later, the extension class loader on Java 8.
 *
 * <p>A real JVM has three levels (bootstrap, platform, application), and samples that probe
 * for an emulator walk {@code getParent()} or compare {@code getPlatformClassLoader()} with
 * {@code getSystemClassLoader()}. Like {@link SimClassLoader}, it never loads anything, and the
 * class-loader stubs answer every call the guest makes on it.
 *
 * <p>Java 8's extension loader ({@code sun.misc.Launcher$ExtClassLoader}) is a
 * {@code URLClassLoader} whose URLs are the jars in the extension directory, so this one is a
 * {@link SimURLClassLoader} that lists the usual ones. On a newer advertised Java the type
 * system answers type checks from the platform loader's class instead, which is not a
 * {@code URLClassLoader}.
 */
public final class SimPlatformClassLoader extends SimURLClassLoader {

    private final String description;
    private final Supplier<URL[]> extensionJars;

    /**
     * @param description   what {@code toString()} returns, matching the advertised Java
     *                      version, for example {@code jdk.internal.loader.ClassLoaders$PlatformClassLoader@4e25154f}
     * @param extensionJars the extension jars that {@code getURLs()} lists, before any URLs the
     *                      guest added
     */
    public SimPlatformClassLoader(String description, Supplier<URL[]> extensionJars) {
        super("platform", new URL[0], null);
        this.description = description;
        this.extensionJars = extensionJars;
    }

    /** The extension jars, then any URLs the guest added. */
    @Override
    public URL[] getURLs() {
        URL[] jars = extensionJars.get();
        URL[] added = guestURLs();
        URL[] all = Arrays.copyOf(jars, jars.length + added.length);
        System.arraycopy(added, 0, all, jars.length, added.length);
        return all;
    }

    /** The text given to the constructor, as a real platform loader would print itself. */
    @Override
    public String toString() {
        return description;
    }

    /** The hash shown after the {@code @} in {@link #toString()}, so the two agree as on a real JVM. */
    @Override
    public int hashCode() {
        return Integer.parseUnsignedInt(description.substring(description.indexOf('@') + 1), 16);
    }

    /** Identity, as for every class loader. */
    @Override
    public boolean equals(Object other) {
        return this == other;
    }
}
