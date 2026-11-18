package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.env.SimBaseClassLoader;
import dev.javabyte.env.SimURLClassLoader;
import dev.javabyte.guest.DefinedPayload;
import dev.javabyte.guest.LoaderSubclass;
import dev.javabyte.guest.OwnLoader;
import dev.javabyte.guest.SystemLoaderPath;
import dev.javabyte.report.Event;
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
 * Runs the loader fixtures. {@link LoaderSubclass} is a sample's own subclass of
 * {@code URLClassLoader}, which must be built on the simulated loader so that neither the
 * guest nor JDK code calling the loader directly can make it fetch from its URL; it also
 * prints the text the guest reads from built-in loaders, concatenated dates and threads, and
 * reflective stacks. {@link OwnLoader} subclasses {@code ClassLoader} and
 * {@code SecureClassLoader} directly, which must be built on simulated bases that JDK code
 * cannot use to reach the emulator's own class path. {@link SystemLoaderPath} treats the
 * application loader as a {@code URLClassLoader}, which only Java 8 allows.
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
            // The analysis JDK has no sun.misc.Launcher; getClass() gives a modelled class object.
            assertEquals("sun.misc.Launcher$AppClassLoader", java8.get("app.class"), java8.toString());
            assertEquals("sun.misc.Launcher$ExtClassLoader", java8.get("app.parent.class"), java8.toString());

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

    /** Runs a fixture with no arguments and extra system properties; returns what it printed, by label. */
    private Map<String, String> runPlain(Path jar, Map<String, String> properties, Report[] report) throws Exception {
        EmulatorConfig config = Fixtures.config(jar);
        config.environment.properties.putAll(properties);
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

    /** The {@link OwnLoader} sample: its classes, and the payload as an encoded resource. */
    private Path ownLoaderJar() throws Exception {
        byte[] payload = Fixtures.classBytes(DefinedPayload.class);
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= 0x2C;
        }
        return Fixtures.jar(temp, Map.of("payload.bin", payload), OwnLoader.class);
    }

    @Test
    void guestSubclassOfClassLoaderStillDefinesAndLoadsFromBytes() throws Exception {
        Report[] report = new Report[1];
        Map<String, String> seen = runPlain(ownLoaderJar(), Map.of(), report);
        String diagnostics = Fixtures.outcomes(report[0]) + "\n" + seen;
        assertTrue(report[0].hasOutcome("completed"), diagnostics);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("defined", "dev.javabyte.guest.DefinedPayload");
        expected.put("result", "payload ran");
        expected.put("again", "true");
        expected.put("byName", "true");
        expected.put("class", "dev.javabyte.guest.OwnLoader$Memory");
        expected.put("super", "java.lang.ClassLoader");
        expected.put("parent", "true");
        expected.put("nullParent", "null");
        expected.put("toString", "dev.javabyte.guest.OwnLoader$Memory@");
        expected.put("hashText", "true");
        expected.put("emulatorClass", "ClassNotFoundException");
        expected.put("jdkClass", "java.lang.String");
        expected.put("emulatorResource", "null");
        expected.put("ownResource", "true");
        expected.put("secure.class", "dev.javabyte.guest.OwnLoader$Secure");
        expected.put("secure.super", "java.security.SecureClassLoader");
        expected.put("secure.parent", "true");
        expected.put("secure.isLoader", "true");
        expected.put("secure.toString", "dev.javabyte.guest.OwnLoader$Secure@");
        assertEquals(expected, seen, diagnostics);
        assertTrue(Fixtures.events(report[0], "class", "define").stream()
                .anyMatch(event -> "dev/javabyte/guest/DefinedPayload".equals(event.data().get("name"))
                        || "dev.javabyte.guest.DefinedPayload".equals(event.data().get("name"))),
                "the class came from the sample's own defineClass: " + Fixtures.events(report[0], "class", "define"));
        assertFalse(Fixtures.events(report[0], "gap", "phantom-superclass").stream()
                .anyMatch(event -> String.valueOf(event.data().get("superclass")).contains("ClassLoader")),
                "both super(...) calls found their constructors: " + diagnostics);
    }

    @Test
    void jdkCodeCallingAClassLoaderSubclassSeesOnlyTheVirtualWorld() throws Exception {
        // Both a class and a resource that the analysis JVM's own class path really has.
        String emulatorClass = "dev.javabyte.Emulator";
        String testResource = "corpus/simulated/emulator-tells.yaml";
        assertTrue(getClass().getClassLoader().getResource(testResource) != null, "the probe resource exists for real");
        VirtualMachine vm = new VirtualMachine(new EmulatorConfig(), new EventLog(1000, null), new Artifacts(null));
        BuiltinStubs.install(vm);
        for (Class<?> cls : List.of(OwnLoader.class, OwnLoader.Memory.class, OwnLoader.Secure.class)) {
            vm.classes.addClassBytes(cls.getName().replace('.', '/'), Fixtures.classBytes(cls), "test");
        }
        vm.classes.addResource("stage/config.txt", "virtual".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        vm.bindToCurrentThread();
        Object made;
        try {
            vm.env.initialise("ownloader.jar", new byte[0]);
            made = vm.router.invokeStatic("dev/javabyte/guest/OwnLoader", "make", "()Ljava/lang/ClassLoader;", new Object[0]);
        } finally {
            vm.unbind();
        }
        ClassLoader loader = (ClassLoader) made;

        // What JDK code would do with a loader it was handed, first from a plain host thread,
        // then from the emulator thread, where the sample's own findClass is reached.
        for (boolean onEmulatorThread : new boolean[] {false, true}) {
            if (onEmulatorThread) {
                vm.bindToCurrentThread();
            }
            try {
                String where = onEmulatorThread ? " (emulator thread)" : " (host thread)";
                assertNull(loader.getResource(testResource), where);
                assertFalse(loader.getResources(testResource).hasMoreElements(), where);
                assertEquals(0, loader.resources(testResource).count(), where);
                assertNull(loader.getResourceAsStream(testResource), where);
                assertNull(loader.getResource(emulatorClass.replace('.', '/') + ".class"), where);
                assertThrows(ClassNotFoundException.class, () -> loader.loadClass(emulatorClass), where);
                assertThrows(ClassNotFoundException.class, () -> Class.forName(emulatorClass, true, loader), where);
                assertThrows(ClassNotFoundException.class, () -> Class.forName(OwnLoader.class.getName(), false, loader),
                        "a guest class exists only in the interpreter" + where);
                assertEquals(String.class, loader.loadClass("java.lang.String"), "JDK classes, as from any loader" + where);
                // The sample's own resources, from memory; their URLs cannot be opened.
                try (InputStream in = loader.getResourceAsStream("stage/config.txt")) {
                    assertEquals("virtual", new String(in.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII), where);
                }
                URL own = loader.getResource("/stage/config.txt");
                assertTrue(own.toExternalForm().endsWith("ownloader.jar!/stage/config.txt"), own + where);
                assertThrows(java.io.IOException.class, () -> own.openStream().close(), "opening is refused" + where);
                assertTrue(loader.toString().startsWith("dev.javabyte.guest.OwnLoader$Memory@"), loader + where);
            } finally {
                vm.unbind();
            }
        }
        assertNull(loader.getParent(), "the real parent is the bootstrap loader, not the emulator's own");
        assertTrue(made instanceof SimBaseClassLoader, "the shell extends the simulated base: " + made.getClass());
    }

    /** Lines {@link SystemLoaderPath} prints, by label, on the profile the properties select. */
    private Map<String, String> systemLoaderPath(Map<String, String> properties, Report[] report) throws Exception {
        return runPlain(Fixtures.jar(temp, Map.of(), SystemLoaderPath.class), properties, report);
    }

    @Test
    void systemLoaderIsAUrlClassLoaderOnJava8() throws Exception {
        Report[] report = new Report[1];
        Map<String, String> seen = systemLoaderPath(Map.of(), report);
        String diagnostics = Fixtures.outcomes(report[0]) + "\n" + seen;
        assertTrue(report[0].hasOutcome("completed"), diagnostics);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("app.class", "sun.misc.Launcher$AppClassLoader");
        expected.put("app.chain", "java.net.URLClassLoader > java.security.SecureClassLoader > java.lang.ClassLoader > java.lang.Object");
        expected.put("app.instanceof", "true");
        expected.put("app.isInstance", "true");
        expected.put("app.assignable", "true");
        expected.put("app.ownClass", "true");
        expected.put("parent.class", "sun.misc.Launcher$ExtClassLoader");
        expected.put("parent.chain", "java.net.URLClassLoader > java.security.SecureClassLoader > java.lang.ClassLoader > java.lang.Object");
        expected.put("parent.instanceof", "true");
        expected.put("cast", "ok");
        expected.put("urls", "[file:/C:/Users/admin/Downloads/systemloaderpath.jar]");
        expected.put("urls.added", "[file:/C:/Users/admin/Downloads/systemloaderpath.jar, file:/opt/plugins/extra.jar]");
        expected.put("parent.urls", "12 file:/C:/Program%20Files/Java/jre1.8.0_361/lib/ext/access-bridge-64.jar");
        assertEquals(expected, seen, diagnostics);
        List<Event> added = Fixtures.events(report[0], "class", "url-loader-add");
        assertEquals(1, added.size(), diagnostics);
        assertEquals("file:/opt/plugins/extra.jar", added.get(0).data().get("url"), diagnostics);
        assertTrue(Fixtures.events(report[0], "class", "load-archive").isEmpty(), "an added URL is never read unasked");
    }

    @Test
    void systemLoaderIsNotAUrlClassLoaderOnJava17() throws Exception {
        Report[] report = new Report[1];
        Map<String, String> seen = systemLoaderPath(Map.of("java.version", "17.0.9", "java.specification.version", "17"), report);
        String diagnostics = Fixtures.outcomes(report[0]) + "\n" + seen;
        assertTrue(report[0].hasOutcome("completed"), diagnostics);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("app.class", "jdk.internal.loader.ClassLoaders$AppClassLoader");
        expected.put("app.chain", "jdk.internal.loader.BuiltinClassLoader > java.security.SecureClassLoader > java.lang.ClassLoader > java.lang.Object");
        expected.put("app.instanceof", "false");
        expected.put("app.isInstance", "false");
        expected.put("app.assignable", "false");
        expected.put("app.ownClass", "true");
        expected.put("parent.class", "jdk.internal.loader.ClassLoaders$PlatformClassLoader");
        expected.put("parent.chain", "jdk.internal.loader.BuiltinClassLoader > java.security.SecureClassLoader > java.lang.ClassLoader > java.lang.Object");
        expected.put("parent.instanceof", "false");
        // The text a real JDK 17 gives for this cast.
        expected.put("cast", "class jdk.internal.loader.ClassLoaders$AppClassLoader cannot be cast to class java.net.URLClassLoader"
                + " (jdk.internal.loader.ClassLoaders$AppClassLoader and java.net.URLClassLoader are in module java.base of loader 'bootstrap')");
        assertEquals(expected, seen, diagnostics);
        assertTrue(Fixtures.events(report[0], "class", "url-loader-add").isEmpty(), diagnostics);
    }
}
