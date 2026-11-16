package dev.javabyte.env;

/**
 * The object guest code receives as "the application class loader".
 *
 * <p>It is a real {@code ClassLoader} only so that it has the right type; it never loads
 * anything. All loading requests are answered by the class-loader stubs from the guest class
 * repository.
 */
public final class SimClassLoader extends ClassLoader {

    public SimClassLoader() {
        super("app", null);
    }

    @Override
    public String toString() {
        return "jdk.internal.loader.ClassLoaders$AppClassLoader@1b6d3586";
    }
}
