package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.config.ConfigLoader;
import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.env.GuestRegion;
import dev.javabyte.env.MachineProfile;
import dev.javabyte.env.SimFile;
import dev.javabyte.env.VirtualFileSystem;
import dev.javabyte.guest.ShellAndWeb;
import dev.javabyte.report.Event;
import dev.javabyte.report.EventLog;
import dev.javabyte.report.Report;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pieces that were built separately and are joined up here: process input reporting, the
 * connection type for each URL scheme, config checks shared with the network simulator, and
 * values the simulated machine gives in more than one place.
 */
class WiringTest {

    @TempDir
    Path temp;

    /** Runs the {@link ShellAndWeb} fixture under the emulator. */
    private Report runShellAndWeb() throws Exception {
        EmulatorConfig config = Fixtures.config(Fixtures.jar(temp, Map.of(), ShellAndWeb.class));
        Report report = Fixtures.run(config);
        assertTrue(Fixtures.outcomes(report).contains("completed"), Fixtures.outcomes(report));
        return report;
    }

    @Test
    void inputWrittenToALaunchedShellIsReportedWhenTheRunEnds() throws Exception {
        Report report = runShellAndWeb();
        List<Event> stdin = Fixtures.events(report, "process", "stdin");
        assertEquals(1, stdin.size(), stdin.toString());
        assertEquals("cmd.exe", stdin.get(0).data().get("command"));
        assertTrue(String.valueOf(stdin.get(0).data().get("preview")).contains("whoami /all"), stdin.toString());
        assertEquals(13, stdin.get(0).data().get("size"));
    }

    @Test
    void httpUrlsGiveAPlainConnectionAndHttpsUrlsATlsOne() throws Exception {
        List<String> printed = Fixtures.stdout(runShellAndWeb());
        assertEquals(List.of("http-is-https=false", "http-is-http=true", "https-is-https=true"), printed);
    }

    /** The keys of the problems found in a config. */
    private static Set<String> problemKeys(EmulatorConfig config) {
        return ConfigLoader.problems(config).stream().map(ConfigLoader.Problem::key).collect(Collectors.toSet());
    }

    @Test
    void theDefaultConfigHasNoProblems() {
        assertEquals(Set.of(), problemKeys(new EmulatorConfig()));
    }

    @Test
    void hardwareNumbersMustBePositive() {
        EmulatorConfig config = new EmulatorConfig();
        config.environment.processors = 0;
        config.environment.memoryMegabytes = -1;
        config.environment.screenWidth = 0;
        config.environment.screenHeight = 0;
        config.environment.processId = 0;
        assertEquals(Set.of("environment.processors", "environment.memoryMegabytes", "environment.screenWidth",
                "environment.screenHeight", "environment.processId"), problemKeys(config));
    }

    @Test
    void malformedNetworkAddressesAreListedByValidate() {
        EmulatorConfig config = new EmulatorConfig();
        config.network.defaultAddress = "203.0.113";
        config.network.localAddress = "192.168.1.300";
        config.network.dns.put("c2.example", "not-an-address");
        config.network.macAddress = "08:00:27";
        assertEquals(Set.of("network.defaultAddress", "network.localAddress", "network.dns.c2.example",
                "network.macAddress"), problemKeys(config));
    }

    @Test
    void aBadLocalAddressInAFileIsAConfigError() throws Exception {
        Path file = java.nio.file.Files.writeString(temp.resolve("bad.yaml"), "network:\n  localAddress: '::1'\n");
        ConfigLoader.ConfigException error = org.junit.jupiter.api.Assertions.assertThrows(
                ConfigLoader.ConfigException.class, () -> ConfigLoader.load(file, List.of()));
        assertTrue(error.getMessage().contains("network.localAddress"), error.getMessage());
    }

    @Test
    void theDefaultTimeZoneIsTheOneTheMachineReports() {
        EmulatorConfig config = new EmulatorConfig();
        assertEquals(GuestRegion.DEFAULT_TIME_ZONE, config.environment.timeZone);
        assertEquals(GuestRegion.DEFAULT_LOCALE, config.environment.locale);
        assertEquals(MachineProfile.TIME_ZONE, ZoneId.of(GuestRegion.DEFAULT_TIME_ZONE),
                "systeminfo and TimeZone.getDefault must name the same zone");
        assertEquals(MachineProfile.TIME_ZONE, GuestRegion.of(config.environment).zone);
    }

    @Test
    void fileSpaceMatchesTheMachineProfile() {
        VirtualFileSystem files = new VirtualFileSystem(true, new EventLog(100, null), 1 << 20, 0);
        SimFile drive = new SimFile(files, "C:\\");
        assertEquals(MachineProfile.VOLUME_BYTES, drive.getTotalSpace());
        assertEquals(MachineProfile.VOLUME_FREE_BYTES, drive.getFreeSpace());
        assertEquals(MachineProfile.VOLUME_FREE_BYTES, drive.getUsableSpace());
    }
}
