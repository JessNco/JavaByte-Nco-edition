package dev.javabyte.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The JVM-wide backstop in {@link Main#blockHostNameLookups()}: once it is in place, no host name
 * reaches the system resolver, while localhost and address literals still work. The check runs
 * in a separate JVM, because the JDK reads the setting only once, when {@code InetAddress} is
 * first used, and this test JVM has used it long before.
 */
class HostLookupBackstopTest {

    /**
     * Run in the forked JVM: installs the backstop exactly as {@link Main#main} does, then
     * prints one line per lookup.
     */
    public static void main(String[] args) {
        Main.blockHostNameLookups();
        for (String host : List.of("example.com", "localhost", "127.0.0.1", "10.1.2.3")) {
            try {
                System.out.println(host + " ok " + InetAddress.getByName(host).getHostAddress());
            } catch (Exception e) {
                System.out.println(host + " failed " + e.getMessage());
            }
        }
        System.out.println("loopback ok " + InetAddress.getLoopbackAddress().getHostAddress());
    }

    @Test
    void namesFailWithoutDnsWhileLocalhostAndLiteralsWork() throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                HostLookupBackstopTest.class.getName()).redirectErrorStream(true).start();
        child.getOutputStream().close();
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the probe JVM did not finish");
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, child.exitValue(), output);

        // The failure names the hosts file: the lookup went to the file, not to DNS, and the
        // missing file did not make the JDK fall back to the system resolver.
        assertTrue(output.contains("example.com failed") && output.contains("hosts file"), output);
        assertTrue(output.contains(Main.NO_HOSTS_FILE), output);
        assertTrue(output.contains("localhost ok 127.0.0.1"), output);
        assertTrue(output.contains("127.0.0.1 ok 127.0.0.1"), output);
        assertTrue(output.contains("10.1.2.3 ok 10.1.2.3"), output);
        assertTrue(output.contains("loopback ok 127.0.0.1"), output);
    }

    @Test
    void theHostsFileCannotExist() {
        File file = new File(Main.NO_HOSTS_FILE);
        assertFalse(file.exists());
        // Its parent is a device, not a directory, so nothing can create the file.
        assertFalse(file.getParentFile().isDirectory());
        assertFalse(Files.isDirectory(file.toPath().getParent()));
    }
}
