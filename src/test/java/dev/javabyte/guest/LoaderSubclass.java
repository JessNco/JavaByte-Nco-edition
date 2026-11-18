package dev.javabyte.guest;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Date;

/**
 * Fixture for a sample's own subclass of {@code URLClassLoader}, the usual way a stager loads
 * its next stage, plus a few other things whose text the guest reads: the built-in loaders'
 * classes, {@code "x" + date}, and the stack inside a method called by reflection. Each line
 * it prints is {@code label=value}.
 *
 * <p>Argument: a real TCP port on localhost that the test is listening on.
 *
 * <p>This class is only ever interpreted. Do not run it on a real JVM.
 */
public final class LoaderSubclass {

    /** A stager's loader: no overrides beyond the constructors, as is common. */
    public static final class Stager extends URLClassLoader {
        /** {@code super(urls)}: the parent is the application loader. */
        public Stager(URL[] urls) {
            super(urls);
        }

        /** {@code super(urls, parent)}. */
        public Stager(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }
    }

    private LoaderSubclass() {}

    /** Builds a stager for the given URL; the test calls this to hold the loader itself. */
    public static ClassLoader make(String url) throws Exception {
        return new Stager(new URL[] {new URL(url)});
    }

    /** A method that reports how deep its caller is, for the reflection-frames check. */
    public static String caller() {
        StackTraceElement[] trace = new Throwable().getStackTrace();
        return trace[1].getClassName() + '.' + trace[1].getMethodName() + '|' + trace[trace.length > 5 ? 5 : 0].getMethodName();
    }

    public static void main(String[] args) throws Exception {
        String remote = "http://127.0.0.1:" + args[0] + "/stage2.jar";
        Stager stager = new Stager(new URL[] {new URL(remote)});
        System.out.println("sub.class=" + stager.getClass().getName());
        System.out.println("sub.super=" + stager.getClass().getSuperclass().getName());
        System.out.println("sub.isUrlLoader=" + (stager instanceof URLClassLoader));
        System.out.println("sub.urls=" + stager.getURLs()[0]);
        System.out.println("sub.parent=" + (stager.getParent() == ClassLoader.getSystemClassLoader()));
        System.out.println("sub.explicitParent=" + (new Stager(new URL[0], null).getParent() == null));
        System.out.println("sub.toString=" + stager.toString().replaceAll("@[0-9a-f]+$", "@"));
        System.out.println("sub.hashText=" + stager.toString().endsWith('@' + Integer.toHexString(stager.hashCode())));
        try {
            stager.loadClass("stage2.Payload");
            System.out.println("sub.load=found");
        } catch (ClassNotFoundException e) {
            System.out.println("sub.load=ClassNotFoundException");
        }
        System.out.println("sub.resource=" + stager.getResource("stage2/config.txt"));

        ClassLoader app = ClassLoader.getSystemClassLoader();
        System.out.println("app.class=" + app.getClass().getName());
        System.out.println("app.toString=" + app);
        System.out.println("app.hash=" + Integer.toHexString(app.hashCode()));
        System.out.println("app.parent.class=" + app.getParent().getClass().getName());

        System.out.println("concat.date=" + new Date(0L));
        System.out.println("concat.thread=" + Thread.currentThread());

        System.out.println("reflect.caller=" + LoaderSubclass.class.getMethod("caller").invoke(null));
        System.out.println("package.same=" + (LoaderSubclass.class.getPackage() == Stager.class.getPackage()));
    }
}
