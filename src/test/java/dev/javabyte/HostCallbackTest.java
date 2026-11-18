package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.guest.HostCallbacks;
import dev.javabyte.host.CallContext;
import dev.javabyte.report.Artifacts;
import dev.javabyte.report.Event;
import dev.javabyte.report.EventLog;
import dev.javabyte.report.Report;
import dev.javabyte.stubs.BuiltinStubs;
import dev.javabyte.vm.VirtualMachine;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the {@link HostCallbacks} fixture, which hands emulator-built objects to JDK code: URLs
 * to collections, providers to engine classes, loaders to methods that take one. It checks
 * what the guest saw, what the report records and, from outside, that nothing reached the real
 * machine: a local listener for connections, the JVM's own providers, and its live threads.
 */
class HostCallbackTest {

    @TempDir
    Path temp;

    /** One run of the fixture: what it printed (label to value), its report, and connections seen. */
    private record Run(Map<String, String> seen, Report report, int connections) {

        String diagnostics() {
            return Fixtures.outcomes(report) + "\n" + seen;
        }
    }

    /** Runs the fixture against a local listener, with extra system properties and policy rules. */
    private Run run(Map<String, String> properties, List<String> allow) throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(200);
            Thread acceptor = new Thread(() -> {
                while (!listener.isClosed()) {
                    try {
                        listener.accept().close();
                        connections.incrementAndGet();
                    } catch (SocketTimeoutException waiting) {
                        // keep listening until the test closes the socket
                    } catch (Exception closed) {
                        return;
                    }
                }
            }, "host-callback-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();

            EmulatorConfig config = Fixtures.config(Fixtures.jar(temp, Map.of(), HostCallbacks.class));
            config.environment.properties.putAll(properties);
            config.policy.allow.addAll(allow);
            EmulatorConfig.EntryPoint entry = new EmulatorConfig.EntryPoint();
            entry.args = List.of(String.valueOf(listener.getLocalPort()));
            config.entryPoints.add(entry);
            Report report = Fixtures.run(config);
            // Give any request that did escape a moment to arrive before checking.
            Thread.sleep(300);
            Map<String, String> seen = new LinkedHashMap<>();
            for (String line : Fixtures.stdout(report)) {
                int equals = line.indexOf('=');
                if (equals > 0) {
                    seen.put(line.substring(0, equals), line.substring(equals + 1));
                }
            }
            return new Run(seen, report, connections.get());
        }
    }

    private static boolean blocked(Report report, String memberPrefix) {
        return Fixtures.events(report, "policy", "blocked").stream()
                .anyMatch(event -> String.valueOf(event.data().get("member")).startsWith(memberPrefix));
    }

    @Test
    void urlsInCollectionsBehaveAsOnARealJvm() throws Exception {
        Run run = run(Map.of(), List.of());
        Map<String, String> seen = run.seen();
        String diagnostics = run.diagnostics();
        assertFalse(run.report().hasOutcome("internal-error"), diagnostics);
        assertEquals("1", seen.get("url.map"), "host names compare without case, as the JDK does: " + diagnostics);
        assertEquals("1", seen.get("url.set"), diagnostics);
        assertEquals("false", seen.get("url.contains"), diagnostics);
        assertEquals("true", seen.get("url.sameFile"), "sameFile ignores the fragment: " + diagnostics);
        assertEquals("http://jbprobe-uri.invalid/p?q#r", seen.get("url.uri"), diagnostics);
        assertEquals("443", seen.get("url.defaultPort"), diagnostics);
        assertEquals("user|jbprobe-parts.invalid|8080|/dir/file.txt|q=1|frag", seen.get("url.parts"), diagnostics);
        assertEquals("http://jbprobe-rel.invalid/c.html", seen.get("url.relative"), diagnostics);
        assertEquals("file:/C:/x.jar!/a/b.class", seen.get("url.jar"), diagnostics);
        assertEquals("MalformedURLException", seen.get("url.unknown"), "unknown protocols are still refused: " + diagnostics);
    }

    @Test
    void reverseLookupsAreAnsweredWithoutDns() throws Exception {
        Run run = run(Map.of(), List.of());
        assertEquals("203.0.113.10", run.seen().get("dns.canonical.address"), run.diagnostics());
        assertEquals("jbprobe-canon.test", run.seen().get("dns.canonical.name"), run.diagnostics());
        List<Event> reverse = Fixtures.events(run.report(), "net", "dns-reverse");
        assertTrue(reverse.stream().anyMatch(event -> "203.0.113.10".equals(event.data().get("address"))),
                "the stub, not the JDK, answered: " + reverse);
    }

    @Test
    void guestChangesNeverReachTheJvmsProviders() throws Exception {
        Run run = run(Map.of(), List.of());
        Map<String, String> seen = run.seen();
        String diagnostics = run.diagnostics();
        assertEquals("true", seen.get("provider.same"), "one copy per provider, so identity holds: " + diagnostics);
        assertEquals("sun.security.provider.Sun", seen.get("provider.class"), diagnostics);
        assertEquals("SUN", seen.get("provider.name"), diagnostics);
        assertEquals("java.util.Timer", seen.get("provider.put"), "the guest sees its own change: " + diagnostics);
        assertEquals("NoSuchAlgorithmException", seen.get("provider.use"), "the JDK ignores it: " + diagnostics);
        assertEquals("NoSuchAlgorithmException", seen.get("provider.mapPut"), diagnostics);
        assertEquals("true", seen.get("provider.engine"), "an engine's provider is the same copy: " + diagnostics);
        assertEquals("32", seen.get("provider.digest"), "a copy still works as a provider: " + diagnostics);
        assertEquals("16", seen.get("provider.cipher"), diagnostics);
        assertEquals("true", seen.get("provider.list"), diagnostics);
        assertEquals("SHA-256", seen.get("provider.service"), diagnostics);

        List<Event> changes = Fixtures.events(run.report(), "crypto", "provider-change");
        assertTrue(changes.stream().anyMatch(event -> "MessageDigest.JBPROBE1".equals(event.data().get("key"))), diagnostics);
        assertTrue(changes.stream().anyMatch(event -> "MessageDigest.JBPROBE2".equals(event.data().get("key"))),
                "a change through the Map interface is reported too: " + diagnostics);

        for (java.security.Provider provider : java.security.Security.getProviders()) {
            assertNull(provider.get("MessageDigest.JBPROBE1"), "the guest changed the JVM's " + provider.getName());
            assertNull(provider.get("MessageDigest.JBPROBE2"), "the guest changed the JVM's " + provider.getName());
        }
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            assertFalse(thread.getName().startsWith("Timer-") && thread.isAlive(),
                    "a provider entry made the JDK start a real thread: " + thread.getName());
        }
    }

    @Test
    void loadersHandedToJdkCodeFetchNothing() throws Exception {
        // java.beans.Beans is allowed here only to show that the router refuses the call anyway.
        Run run = run(Map.of(), List.of("java/beans/Beans"));
        Map<String, String> seen = run.seen();
        String diagnostics = run.diagnostics();
        assertEquals(0, run.connections(), "a class loader fetched from its URL: " + diagnostics);
        assertEquals("null", seen.get("loader.newBundle"), diagnostics);
        assertTrue(blocked(run.report(), "java/util/ResourceBundle$Control.newBundle"), diagnostics);
        assertTrue(blocked(run.report(), "java/beans/Beans.instantiate"),
                "a JDK method taking a ClassLoader is refused even when policy allows its class: " + diagnostics);
        assertEquals("java.net.URLClassLoader", seen.get("loader.class"), diagnostics);
        assertTrue(seen.get("loader.urls").startsWith("http://127.0.0.1:"), diagnostics);
        assertEquals("true", seen.get("loader.parent"), diagnostics);
        assertEquals("null", seen.get("loader.nullParent"), diagnostics);
        assertEquals("null", seen.get("loader.resource"), diagnostics);
        assertEquals("java.net.URLClassLoader@", seen.get("loader.toString"), diagnostics);
        assertFalse(Fixtures.events(run.report(), "class", "url-loader").isEmpty(), "the loader's URLs are reported");
    }

    @Test
    void builtInLoadersMatchJava8ByDefault() throws Exception {
        Map<String, String> seen = run(Map.of(), List.of()).seen();
        assertEquals("sun.misc.Launcher$ExtClassLoader@7852e922", seen.get("app.parent"), seen.toString());
        assertEquals("null", seen.get("app.parent.parent"), seen.toString());
        assertEquals("null", seen.get("app.name"), "Java 8 loaders have no names: " + seen);
        assertEquals("true", seen.get("platform.isParent"), seen.toString());
        assertEquals("false", seen.get("platform.isApp"), seen.toString());
    }

    @Test
    void builtInLoadersMatchTheAdvertisedModernJava() throws Exception {
        Map<String, String> seen = run(Map.of("java.version", "17.0.9", "java.specification.version", "17"), List.of()).seen();
        assertEquals("jdk.internal.loader.ClassLoaders$PlatformClassLoader@4e25154f", seen.get("app.parent"), seen.toString());
        assertEquals("null", seen.get("app.parent.parent"), seen.toString());
        assertEquals("app", seen.get("app.name"), seen.toString());
        assertEquals("platform", seen.get("app.parent.name"), seen.toString());
        assertEquals("true", seen.get("platform.isParent"), seen.toString());
        assertEquals("false", seen.get("platform.isApp"), seen.toString());
    }

    @Test
    void urlsBuiltByTheStubsCannotConnectOrResolve() throws Exception {
        EmulatorConfig config = new EmulatorConfig();
        EventLog events = new EventLog(1000, null);
        VirtualMachine vm = new VirtualMachine(config, events, new Artifacts(null));
        BuiltinStubs.install(vm);
        String desc = "(Ljava/lang/String;)V";
        URL url = (URL) vm.stubs.find("java/net/URL", "<init>", desc, vm.host::findClass)
                .call(new CallContext(vm, "java/net/URL", "<init>", desc, null, new Object[] {"http://jbprobe-unit.invalid/x"}, false));
        // A JDK handler would hand back an unconnected HttpURLConnection; the inert one refuses.
        assertThrows(ConnectException.class, url::openConnection);
        assertTrue(events.events().stream().anyMatch(event -> event.category().equals("policy")
                && String.valueOf(event.data().get("member")).equals("http://jbprobe-unit.invalid/x")),
                "the refused open is reported");
        // Hashing and equality depend on the text only, so they are repeatable and resolve nothing.
        URL again = (URL) vm.stubs.find("java/net/URL", "<init>", desc, vm.host::findClass)
                .call(new CallContext(vm, "java/net/URL", "<init>", desc, null, new Object[] {"HTTP://JBPROBE-UNIT.invalid/x"}, false));
        assertEquals(url, again);
        assertEquals(url.hashCode(), again.hashCode());
        assertEquals(80, url.getDefaultPort());
    }

    @Test
    void everyAllowedRouteToAProviderIsStubbed() throws Exception {
        VirtualMachine vm = new VirtualMachine(new EmulatorConfig(), new EventLog(1000, null), new Artifacts(null));
        BuiltinStubs.install(vm);
        List<String> unstubbed = new ArrayList<>();
        java.nio.file.FileSystem jrt = java.nio.file.FileSystems.getFileSystem(java.net.URI.create("jrt:/"));
        for (String root : List.of("/modules/java.base/java/security", "/modules/java.base/javax/crypto",
                "/modules/java.base/javax/net/ssl", "/modules/java.base/java/net", "/modules/java.base/java/util")) {
            try (java.util.stream.Stream<Path> files = java.nio.file.Files.walk(jrt.getPath(root))) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    String name = file.toString();
                    if (!name.endsWith(".class") || name.endsWith("module-info.class")) {
                        continue;
                    }
                    String internalName = name.substring("/modules/java.base/".length(), name.length() - ".class".length());
                    Class<?> cls = vm.host.findClass(internalName);
                    if (cls == null || !java.lang.reflect.Modifier.isPublic(cls.getModifiers())) {
                        continue;
                    }
                    for (java.lang.reflect.Method method : cls.getMethods()) {
                        Class<?> returned = method.getReturnType();
                        boolean givesProvider = java.security.Provider.class.isAssignableFrom(returned)
                                || (returned.isArray() && java.security.Provider.class.isAssignableFrom(returned.getComponentType()));
                        String methodDesc = org.objectweb.asm.Type.getMethodDescriptor(method);
                        if (givesProvider && vm.policy.allows(internalName, method.getName(), methodDesc)
                                && vm.stubs.find(internalName, method.getName(), methodDesc, vm.host::findClass) == null) {
                            unstubbed.add(internalName + '.' + method.getName() + methodDesc);
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), unstubbed, "these would hand the guest one of the JVM's shared providers");
    }
}
