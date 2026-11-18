package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.env.SimURLClassLoader;
import dev.javabyte.guest.LoaderSubclass;
import dev.javabyte.report.Artifacts;
import dev.javabyte.report.EventLog;
import dev.javabyte.report.Report;
import dev.javabyte.stubs.BuiltinStubs;
import dev.javabyte.vm.VirtualMachine;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the {@link LoaderSubclass} fixture: a sample's own subclass of {@code URLClassLoader},
 * which must be built on the simulated loader so that neither the guest nor JDK code calling
 * the loader directly can make it fetch from its URL, and the text the guest reads from
 * built-in loaders, concatenated dates and threads, and reflective stacks.
 */
class LoaderShellTest {

    @TempDir
    Path temp;

    /** A localhost listener that counts the connections it accepts. */
    private static final class Listener implements AutoCloseable {
        final ServerSocket socket;
        final AtomicInteger connections = new AtomicInteger();

        /** Opens the listener and starts accepting in a daemon thread. */
        Listener() throws Exception {
            socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            socket.setSoTimeout(200);
            Thread acceptor = new Thread(() -> {
                while (!socket.isClosed()) {
                    try {
                        socket.accept().close();
                        connections.incrementAndGet();
                    } catch (SocketTimeoutException waiting) {
                        // keep listening until the test closes the socket
                    } catch (Exception closed) {
                        return;
                    }
                }
            }, "loader-shell-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        /** The connections seen, after giving any that escaped a moment to arrive. */
        int connections() throws InterruptedException {
            Thread.sleep(300);
            return connections.get();
        }

        /** Stops listening. */
        @Override
        public void close() throws Exception {
            socket.close();
        }
    }

    /** Runs the fixture with extra system properties; returns what it printed, by label. */
    private Map<String, String> run(Listener listener, Map<String, String> properties, Report[] report) throws Exception {
        EmulatorConfig config = Fixtures.config(Fixtures.jar(temp, Map.of(), LoaderSubclass.class, LoaderSubclass.Stager.class));
        config.environment.properties.putAll(properties);
        EmulatorConfig.EntryPoint entry = new EmulatorConfig.EntryPoint();
        entry.args = List.of(String.valueOf(listener.socket.getLocalPort()));
        config.entryPoints.add(entry);
        report[0] = Fixtures.run(config);
        Map<String, String> seen = new LinkedHashMap<>();
        for (String line : Fixtures.stdout(report[0])) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                seen.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        return seen;
    }

    @Test
    void guestSubclassOfUrlClassLoaderIsSimulatedAndFetchesNothing() throws Exception {
        try (Listener listener = new Listener()) {
            Report[] report = new Report[1];
            Map<String, String> seen = run(listener, Map.of(), report);
            String diagnostics = Fixtures.outcomes(report[0]) + "\n" + seen;
            assertFalse(report[0].hasOutcome("internal-error"), diagnostics);
            assertEquals("dev.javabyte.guest.LoaderSubclass$Stager", seen.get("sub.class"), diagnostics);
            assertEquals("java.net.URLClassLoader", seen.get("sub.super"), diagnostics);
            assertEquals("true", seen.get("sub.isUrlLoader"), diagnostics);
            assertTrue(seen.get("sub.urls").startsWith("http://127.0.0.1:"), diagnostics);
            assertEquals("true", seen.get("sub.parent"), "super(urls) gives the application loader as parent: " + diagnostics);
            assertEquals("true", seen.get("sub.explicitParent"), diagnostics);
            assertEquals("dev.javabyte.guest.LoaderSubclass$Stager@", seen.get("sub.toString"), diagnostics);
            assertEquals("true", seen.get("sub.hashText"), diagnostics);
            assertEquals("ClassNotFoundException", seen.get("sub.load"), diagnostics);
            assertEquals("null", seen.get("sub.resource"), diagnostics);
            assertFalse(Fixtures.events(report[0], "gap", "phantom-superclass").stream()
                    .anyMatch(event -> "java/net/URLClassLoader".equals(event.data().get("superclass"))),
                    "the super(...) call found its constructor: " + diagnostics);
            assertTrue(Fixtures.events(report[0], "class", "load-archive").stream()
                    .anyMatch(event -> String.valueOf(event.data().get("url")).endsWith("/stage2.jar")),
                    "the archive was asked of the network simulator: " + diagnostics);
            assertEquals(0, listener.connections(), "a guest loader fetched from its URL: " + diagnostics);
        }
    }

    @Test
    void jdkCodeCallingTheSubclassDirectlyReachesNothing() throws Exception {
        try (Listener listener = new Listener()) {
            String url = "http://127.0.0.1:" + listener.socket.getLocalPort() + "/stage2.jar";
            VirtualMachine vm = new VirtualMachine(new EmulatorConfig(), new EventLog(1000, null), new Artifacts(null));
            BuiltinStubs.install(vm);
            for (Class<?> cls : List.of(LoaderSubclass.class, LoaderSubclass.Stager.class)) {
                vm.classes.addClassBytes(cls.getName().replace('.', '/'), Fixtures.classBytes(cls), "test");
            }
            vm.bindToCurrentThread();
            Object made;
            try {
                made = vm.router.invokeStatic("dev/javabyte/guest/LoaderSubclass", "make",
                        "(Ljava/lang/String;)Ljava/lang/ClassLoader;", new Object[] {url});
            } finally {
                vm.unbind();
            }
            assertTrue(made instanceof SimURLClassLoader, "the shell must extend the simulated loader: " + made.getClass());
            // What JDK code would do with a loader it was handed, called here from a plain host
            // thread as such code would be.
            ClassLoader loader = (ClassLoader) made;
            assertNull(loader.getResource("stage2/config.txt"));
            assertFalse(loader.getResources("stage2/config.txt").hasMoreElements());
            try (InputStream in = loader.getResourceAsStream("stage2/config.txt")) {
                assertNull(in);
            }
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("stage2.Payload"));
            assertThrows(ClassNotFoundException.class, () -> Class.forName("stage2.Payload", true, loader));
            assertEquals(new URL(url), ((SimURLClassLoader) loader).getURLs()[0], "the URL is only remembered");
            assertEquals(0, listener.connections(), "a guest loader fetched from its URL");
        }
    }

    @Test
    void builtInLoadersReportTheirRealClassesAndFixedText() throws Exception {
        try (Listener listener = new Listener()) {
            Report[] report = new Report[1];
            Map<String, String> java8 = run(listener, Map.of(), report);
            assertEquals("sun.misc.Launcher$AppClassLoader@18b4aac2", java8.get("app.toString"), java8.toString());
            assertEquals("18b4aac2", java8.get("app.hash"), java8.toString());
            // The analysis JDK has no sun.misc.Launcher, so getClass() falls back to ClassLoader.
            assertEquals("java.lang.ClassLoader", java8.get("app.class"), java8.toString());

            Map<String, String> modern = run(listener, Map.of("java.version", "17.0.9", "java.specification.version", "17"), report);
            assertEquals("jdk.internal.loader.ClassLoaders$AppClassLoader@1b6d3586", modern.get("app.toString"), modern.toString());
            assertEquals("1b6d3586", modern.get("app.hash"), modern.toString());
            assertEquals("jdk.internal.loader.ClassLoaders$AppClassLoader", modern.get("app.class"), modern.toString());
            assertEquals("jdk.internal.loader.ClassLoaders$PlatformClassLoader", modern.get("app.parent.class"), modern.toString());
        }
    }

    @Test
    void textTheGuestReadsIsTheGuestsAndRepeatable() throws Exception {
        try (Listener listener = new Listener()) {
            Report[] report = new Report[1];
            Map<String, String> first = run(listener, Map.of(), report);
            Map<String, String> second = run(listener, Map.of(), report);
            assertEquals("Wed Dec 31 19:00:00 EST 1969", first.get("concat.date"),
                    "concatenation uses the guest's time zone, not the host's: " + first);
            assertEquals("Thread[main,5,main]", first.get("concat.thread"), first.toString());
            assertEquals("sun.reflect.NativeMethodAccessorImpl.invoke0|main", first.get("reflect.caller"),
                    "Method.invoke shows Java 8's accessor frames: " + first);
            assertEquals("true", first.get("package.same"), "one Package per package: " + first);
            assertEquals(first.get("sub.toString"), second.get("sub.toString"));
            assertEquals(first, second, "every run of a sample sees the same text");
        }
    }
}
