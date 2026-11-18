package dev.javabyte.guest;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;

/**
 * Fixture for the classic Java 8 idiom of treating the application class loader as a
 * {@code URLClassLoader}: reading its class path with {@code getURLs()} and extending it by
 * calling the protected {@code addURL} through reflection. On Java 8 the cast succeeds; on
 * Java 9 and later it fails, and the message says why. Each line it prints is
 * {@code label=value}.
 *
 * <p>This class is only ever interpreted. Do not run it on a real JVM.
 */
public final class SystemLoaderPath {

    private SystemLoaderPath() {}

    /** The superclasses of a class, nearest first, separated by " > ". */
    private static String chain(Class<?> cls) {
        StringBuilder text = new StringBuilder();
        for (Class<?> current = cls.getSuperclass(); current != null; current = current.getSuperclass()) {
            text.append(text.length() == 0 ? "" : " > ").append(current.getName());
        }
        return text.toString();
    }

    public static void main(String[] args) throws Exception {
        ClassLoader app = ClassLoader.getSystemClassLoader();
        System.out.println("app.class=" + app.getClass().getName());
        System.out.println("app.chain=" + chain(app.getClass()));
        System.out.println("app.instanceof=" + (app instanceof URLClassLoader));
        System.out.println("app.isInstance=" + URLClassLoader.class.isInstance(app));
        System.out.println("app.assignable=" + URLClassLoader.class.isAssignableFrom(app.getClass()));
        System.out.println("app.ownClass=" + app.getClass().isInstance(app));
        ClassLoader parent = app.getParent();
        System.out.println("parent.class=" + parent.getClass().getName());
        System.out.println("parent.chain=" + chain(parent.getClass()));
        System.out.println("parent.instanceof=" + (parent instanceof URLClassLoader));
        try {
            URLClassLoader path = (URLClassLoader) app;
            System.out.println("cast=ok");
            System.out.println("urls=" + Arrays.toString(path.getURLs()));
            Method addUrl = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            addUrl.setAccessible(true);
            addUrl.invoke(path, new URL("file:/opt/plugins/extra.jar"));
            System.out.println("urls.added=" + Arrays.toString(path.getURLs()));
            URL[] extensions = ((URLClassLoader) parent).getURLs();
            System.out.println("parent.urls=" + extensions.length + " " + extensions[0]);
        } catch (ClassCastException e) {
            System.out.println("cast=" + e.getMessage());
        }
    }
}
