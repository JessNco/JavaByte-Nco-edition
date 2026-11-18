package dev.javabyte.env;

import java.util.function.BooleanSupplier;

/**
 * The object guest code receives as "the application class loader".
 *
 * <p>It is a real {@code ClassLoader} only so that it has the right type; it never loads
 * anything. All loading requests are answered by the class-loader stubs from the guest class
 * repository.
 *
 * <p>Its {@code toString()} and {@code hashCode()} are those of the application loader of the
 * Java version the guest environment advertises, with a fixed, typical hash so that every run
 * of a sample sees the same text. Guest calls compiled against {@code Object} reach these
 * methods directly, without passing through the stubs, so the answer has to live here.
 */
public final class SimClassLoader extends ClassLoader {

    /** What a Java 8 application loader prints. */
    public static final String JAVA_8_TEXT = "sun.misc.Launcher$AppClassLoader@18b4aac2";

    /** What a Java 9 or later application loader prints. */
    public static final String MODERN_TEXT = "jdk.internal.loader.ClassLoaders$AppClassLoader@1b6d3586";

    /** Says whether the advertised Java is 8 or older; Java 8, the default profile, until told otherwise. */
    private volatile BooleanSupplier legacyJava = () -> true;

    public SimClassLoader() {
        super("app", null);
    }

    /**
     * Makes the text and hash follow the advertised Java version. The supplier is asked on
     * every call, because the guest's system properties are filled in, and may be changed by
     * config, after the loader is created.
     *
     * @param legacyJava true while the guest environment advertises Java 8 or older
     */
    public void followJavaVersion(BooleanSupplier legacyJava) {
        this.legacyJava = legacyJava;
    }

    /** The application loader's text for the advertised Java version. */
    @Override
    public String toString() {
        return legacyJava.getAsBoolean() ? JAVA_8_TEXT : MODERN_TEXT;
    }

    /** The hash shown after the {@code @} in {@link #toString()}, so the two agree as on a real JVM. */
    @Override
    public int hashCode() {
        String text = toString();
        return Integer.parseUnsignedInt(text.substring(text.indexOf('@') + 1), 16);
    }

    /** Identity, as for every class loader. */
    @Override
    public boolean equals(Object other) {
        return this == other;
    }
}
