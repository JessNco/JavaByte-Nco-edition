package dev.javabyte.env;

import java.net.URL;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The object guest code receives as "the application class loader".
 *
 * <p>It never loads anything. All loading requests from the guest are answered by the
 * class-loader stubs from the guest class repository, and JDK code that calls it directly gets
 * the answers of {@link SimURLClassLoader}, which looks nothing up.
 *
 * <p>It is a {@code URLClassLoader} because on Java 8, the default profile, the real
 * application loader ({@code sun.misc.Launcher$AppClassLoader}) is one, and samples rely on
 * that: {@code ((URLClassLoader) ClassLoader.getSystemClassLoader()).getURLs()} lists the class
 * path, and calling the protected {@code addURL} by reflection extends it. Here
 * {@code getURLs()} lists the sample's virtual class path, and a URL the guest adds is only
 * remembered and reported. On Java 9 and later the real loader is not a
 * {@code URLClassLoader}, so the type system answers casts, {@code instanceof} and
 * {@code Class.isInstance} from the class this loader imitates for the advertised version (see
 * the imitation rules in the system stubs), and the same cast fails as it would there.
 *
 * <p>Its {@code toString()} and {@code hashCode()} are those of the application loader of the
 * Java version the guest environment advertises, with a fixed, typical hash so that every run
 * of a sample sees the same text. Guest calls compiled against {@code Object} reach these
 * methods directly, without passing through the stubs, so the answer has to live here.
 */
public final class SimClassLoader extends SimURLClassLoader {

    /** What a Java 8 application loader prints. */
    public static final String JAVA_8_TEXT = "sun.misc.Launcher$AppClassLoader@18b4aac2";

    /** What a Java 9 or later application loader prints. */
    public static final String MODERN_TEXT = "jdk.internal.loader.ClassLoaders$AppClassLoader@1b6d3586";

    /** Says whether the advertised Java is 8 or older; Java 8, the default profile, until told otherwise. */
    private volatile BooleanSupplier legacyJava = () -> true;

    /** The sample's class path as URLs; empty until the stubs say where the sample is. */
    private volatile Supplier<URL[]> classPath = () -> new URL[0];

    public SimClassLoader() {
        super("app", new URL[0], null);
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

    /**
     * Makes {@link #getURLs()} list the sample's class path. The supplier is asked on every
     * call, for the same reason as in {@link #followJavaVersion}.
     *
     * @param classPath the entries of the guest's {@code java.class.path}, as {@code file:} URLs
     */
    public void followClassPath(Supplier<URL[]> classPath) {
        this.classPath = classPath;
    }

    /**
     * The class path, then any URLs the guest added, as a Java 8 application loader reports
     * them. A real loader does not list the {@code Class-Path} entries of a jar's manifest
     * here either, so they are not added.
     */
    @Override
    public URL[] getURLs() {
        URL[] path = classPath.get();
        URL[] added = guestURLs();
        URL[] all = Arrays.copyOf(path, path.length + added.length);
        System.arraycopy(added, 0, all, path.length, added.length);
        return all;
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
