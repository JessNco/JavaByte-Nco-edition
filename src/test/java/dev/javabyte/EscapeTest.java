package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.guest.Escape;
import dev.javabyte.report.Report;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the {@link Escape} fixture, which attempts some eighty ways out of the emulator, and
 * checks from the outside that none of them had a real effect.
 *
 * <p>The checks deliberately look at the real machine (a file, a listening socket, JVM state)
 * rather than at what the emulator reports, so they hold even if a stub is wrong about itself.
 */
class EscapeTest {

    @TempDir
    Path temp;

    @Test
    void noEscapeRouteHasARealEffect() throws Exception {
        Path canary = temp.resolve("canary-created-by-guest");
        String token = "SECRET-" + UUID.randomUUID();
        Path secret = Files.writeString(temp.resolve("secret.txt"), token + "\n");
        Locale localeBefore = Locale.getDefault();

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
            }, "escape-test-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();

            EmulatorConfig config = Fixtures.config(Fixtures.jar(temp, Map.of(), Escape.class));
            // Linux paths, so the fixture's real paths are meaningful to the virtual disk too.
            config.environment.os = "linux";
            EmulatorConfig.EntryPoint entry = new EmulatorConfig.EntryPoint();
            entry.args = List.of(canary.toString(), secret.toString(), String.valueOf(listener.getLocalPort()));
            config.entryPoints.add(entry);
            Report report = Fixtures.run(config);
            String diagnostics = Fixtures.outcomes(report);
            String everything = report.toJson();

            // Give any thread that did escape a moment to act before checking.
            Thread.sleep(500);

            assertFalse(report.hasOutcome("internal-error"), diagnostics);
            assertTrue(report.hasOutcome("exit"), "the fixture should run to its final System.exit: " + diagnostics);
            assertFalse(Files.exists(canary), "a guest write reached the real disk");
            assertFalse(everything.contains(token), "the guest read a real file");
            assertEquals(0, connections.get(), "the guest reached a real socket");
            assertNull(System.getProperty("javabyte.escape"), "the guest changed a real system property");
            assertNull(java.security.Security.getProperty("javabyte.escape"), "the guest changed JVM security settings");
            assertEquals(localeBefore, Locale.getDefault(), "the guest changed the JVM default locale");
            // "LEAK" lines are what the guest itself managed to observe. Its own class name is
            // expected there; anything else of the emulator's is not.
            for (String line : Fixtures.stdout(report)) {
                if (line.startsWith("LEAK")) {
                    String seen = line.replace("dev.javabyte.guest.", "");
                    assertFalse(seen.contains("dev.javabyte") || seen.contains("objectweb") || seen.contains("Sim"),
                            "the guest saw emulator internals: " + line);
                }
            }
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                assertFalse(thread.getName().startsWith("escape-") && !thread.getName().equals("escape-test-acceptor")
                        && thread.isAlive(), "a guest thread is really running: " + thread.getName());
                // A Timer constructed by the JDK from a provider entry the guest wrote.
                assertFalse(thread.getName().startsWith("Timer-") && thread.isAlive(),
                        "a provider entry made the JDK start a real thread: " + thread.getName());
            }
            for (String key : List.of("MessageDigest.JBESCAPE1", "MessageDigest.JBESCAPE2", "MessageDigest.JBESCAPE3")) {
                for (java.security.Provider provider : java.security.Security.getProviders()) {
                    assertNull(provider.get(key), "the guest changed the JVM's " + provider.getName() + " provider");
                }
            }
            // The attempts must have been seen, not silently dropped.
            assertTrue(Fixtures.events(report, "process", "exec").size() >= 7, diagnostics);
            assertTrue(Fixtures.stdout(report).stream().noneMatch(line -> line.startsWith("unreachable")), diagnostics);
        }
    }
}
