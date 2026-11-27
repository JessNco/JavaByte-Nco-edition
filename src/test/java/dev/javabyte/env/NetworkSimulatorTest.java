package dev.javabyte.env;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.config.ConfigLoader;
import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.report.Artifacts;
import dev.javabyte.report.EventLog;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Network addresses from the config are checked before the run, not when the guest first uses them. */
class NetworkSimulatorTest {

    private static NetworkSimulator simulator(EmulatorConfig.Network config) {
        return new NetworkSimulator(config, new EventLog(1000, null), new Artifacts(null));
    }

    @Test
    void theDefaultsAreValid() {
        assertEquals(List.of(), NetworkSimulator.configProblems(new EmulatorConfig.Network()));
    }

    @Test
    void everyBadAddressIsReportedWithItsKey() {
        EmulatorConfig.Network config = new EmulatorConfig.Network();
        config.defaultAddress = "203.0.113";
        config.localAddress = "192.168.1.300";
        config.dns.put("c2.example", "::1");
        config.dns.put("ok.example", "198.51.100.7");
        config.macAddress = "00:1B:44:11:3A";

        List<String> keys = NetworkSimulator.configProblems(config).stream().map(ConfigLoader.Problem::key).toList();
        assertEquals(List.of("network.defaultAddress", "network.localAddress", "network.dns.c2.example", "network.macAddress"), keys);

        // A bad value stops the run up front as a config error, never mid-emulation.
        ConfigLoader.ConfigException error = assertThrows(ConfigLoader.ConfigException.class, () -> simulator(config));
        assertTrue(error.getMessage().contains("network.macAddress"), error.getMessage());
    }

    @Test
    void addressesAreParsedStrictly() {
        assertArrayEquals(new byte[] {(byte) 192, (byte) 168, 0, 1}, NetworkSimulator.parseIpv4("192.168.0.1"));
        assertThrows(IllegalArgumentException.class, () -> NetworkSimulator.parseIpv4("1.2.3"));
        assertThrows(IllegalArgumentException.class, () -> NetworkSimulator.parseIpv4("256.1.1.1"));
        assertTrue(NetworkSimulator.isMacAddress("00-1B-44-11-3A-B7"));
    }

    @Test
    void anOutOfRangeLiteralIsLookedUpAsAName() throws Exception {
        EmulatorConfig.Network config = new EmulatorConfig.Network();
        // A real JVM does not accept 999.1.1.1 as an address and looks it up as a host name.
        assertEquals("203.0.113.10", simulator(config).resolve("999.1.1.1").getHostAddress());
        assertEquals("10.0.0.5", simulator(config).resolve("10.0.0.5").getHostAddress());
    }

    /** An address without a name is described by its text; asking the JDK would be a reverse lookup. */
    @Test
    void theNameOfAnAddressIsNeverLookedUp() throws Exception {
        assertEquals("203.0.113.9", NetworkSimulator.nameOf(java.net.InetAddress.getByAddress(new byte[] {(byte) 203, 0, 113, 9})));
        assertEquals("c2.example", NetworkSimulator.nameOf(java.net.InetAddress.getByAddress("c2.example", new byte[] {(byte) 203, 0, 113, 9})));
    }
}
