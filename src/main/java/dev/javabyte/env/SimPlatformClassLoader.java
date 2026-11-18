package dev.javabyte.env;

/**
 * The object guest code receives as the parent of the application class loader: the platform
 * class loader on Java 9 and later, the extension class loader on Java 8.
 *
 * <p>A real JVM has three levels (bootstrap, platform, application), and samples that probe
 * for an emulator walk {@code getParent()} or compare {@code getPlatformClassLoader()} with
 * {@code getSystemClassLoader()}. Like {@link SimClassLoader}, it is a real {@code ClassLoader}
 * only so that it has the right type; it never loads anything, and the class-loader stubs
 * answer every call the guest makes on it.
 */
public final class SimPlatformClassLoader extends ClassLoader {

    private final String description;

    /**
     * @param description what {@code toString()} returns, matching the advertised Java
     *                    version, for example {@code jdk.internal.loader.ClassLoaders$PlatformClassLoader@4e25154f}
     */
    public SimPlatformClassLoader(String description) {
        super("platform", null);
        this.description = description;
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
