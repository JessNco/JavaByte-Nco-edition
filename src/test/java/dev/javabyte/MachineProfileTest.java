package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.guest.sim.Recon;
import dev.javabyte.report.Event;
import dev.javabyte.report.Report;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The simulated machine as a sample sees it: one coherent desktop whichever API is asked.
 *
 * <p>The {@link Recon} fixture asks the questions anti-analysis code asks and prints the
 * answers; each test checks that a group of answers looks like a real Windows desktop and that
 * answers to the same question through different APIs agree.
 */
class MachineProfileTest {

    @TempDir
    static Path temp;

    private static Report report;
    private static Map<String, String> seen;

    @BeforeAll
    static void runRecon() throws IOException {
        EmulatorConfig config = Fixtures.config(jarWithManifest());
        config.environment.clockStart = "2024-03-01T09:00:00Z";
        report = Fixtures.run(config);
        seen = new LinkedHashMap<>();
        for (String line : Fixtures.stdout(report)) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                seen.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        assertTrue(report.hasOutcome("completed"), Fixtures.outcomes(report) + "\n" + seen);
    }

    /** The fixture in a jar whose manifest has a per-package section and main attributes. */
    private static Path jarWithManifest() throws IOException {
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.put(Attributes.Name.MAIN_CLASS, Recon.class.getName());
        main.put(Attributes.Name.IMPLEMENTATION_TITLE, "Recon tool");
        main.put(Attributes.Name.IMPLEMENTATION_VERSION, "1.0");
        main.put(Attributes.Name.SPECIFICATION_VENDOR, "Example Vendor");
        Attributes section = new Attributes();
        section.put(Attributes.Name.IMPLEMENTATION_VERSION, "9.9");
        manifest.getEntries().put("dev/javabyte/guest/sim/", section);
        Path jar = temp.resolve("recon.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (String name : List.of(Recon.class.getName())) {
                String entry = name.replace('.', '/') + ".class";
                out.putNextEntry(new JarEntry(entry));
                out.write(Fixtures.classBytes(Recon.class));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static String get(String key) {
        String value = seen.get(key);
        assertNotNull(value, "the fixture did not print " + key + ": " + seen + "\n" + Fixtures.outcomes(report));
        return value;
    }

    @Test
    void classesReachedThroughInterfacesAreJdkClasses() {
        for (String key : List.of("class.path", "class.executor", "class.future", "class.runtimeBean", "class.osBean",
                "class.processHandle", "class.process", "class.package")) {
            String name = get(key);
            assertFalse(name.startsWith("dev.javabyte") || name.contains("Proxy") || name.equals("java.lang.Object"),
                    key + " exposed " + name);
        }
        assertEquals("java.lang.Package", get("class.package"));
    }

    @Test
    void runtimeBeanStartTimeIsFixedAndUptimeGrows() {
        assertEquals("0", get("runtime.startDelta"));
        assertTrue(Long.parseLong(get("runtime.uptimeDelta")) >= 5000, get("runtime.uptimeDelta"));
        assertEquals("25.361-b09", get("runtime.vmVersion"));
        assertEquals("1.8", get("runtime.specVersion"));
        assertEquals("Oracle Corporation", get("runtime.vmVendor"));
        assertEquals("true", get("runtime.sameProperties"));
        assertEquals("true", get("runtime.classPath"));
        assertEquals("8", get("runtime.version.feature"));
    }

    @Test
    void hardwareIsADesktopAndEveryApiAgrees() {
        assertEquals("4", get("cpu.runtime"));
        assertEquals(get("cpu.runtime"), get("cpu.bean"));
        assertEquals(get("cpu.runtime"), get("cpu.env"));
        long memory = Long.parseLong(get("memory.total"));
        assertTrue(memory > 15L << 30 && memory <= 16L << 30, "16 GB installed, a little reserved: " + memory);
        assertEquals(get("memory.total"), get("memory.physical"));
        assertTrue(Long.parseLong(get("memory.free")) < memory);
        assertEquals(4L << 30, Long.parseLong(get("memory.heapMax")), "Java 8 defaults to a quarter of memory");
        assertEquals(get("memory.heapMax"), get("memory.heapBeanMax"));
        assertEquals("Windows 10", get("os.name"));
        assertTrue(Integer.parseInt(get("threads")) > 1);
        assertEquals("1920x1080", get("screen"));
        assertEquals("0", get("screen.perPixel"), "division by the screen size must work");
        assertEquals("96", get("screen.dpi"));
        assertEquals("false", get("screen.headless"));
        assertEquals("1920x1080", get("screen.mode"));
        assertEquals("1920", get("screen.bounds"));
        // Reads of the machine's facts are recorded, as property reads are.
        assertTrue(Fixtures.events(report, "system", "management").stream()
                .anyMatch(e -> "getTotalMemorySize".equals(e.data().get("method"))), "memory read not recorded");
        assertTrue(Fixtures.events(report, "system", "screen-size").size() == 1);
    }

    @Test
    void networkHasALoopbackAndAnOrdinaryLanAddress() {
        assertEquals("2", get("nic.count"));
        assertEquals("true", get("nic.lo"));
        assertEquals("lo", get("nic.index1"));
        assertEquals("lo", get("nic.byLoopbackAddress"));
        assertEquals("null", get("nic.unknown"));
        assertEquals("192.168.1.23", get("ip"));
    }

    @Test
    void processIdsAreConsistentAndDistinct() {
        String self = get("pid.self");
        assertTrue(get("runtime.name").startsWith(self + "@"), get("runtime.name"));
        String[] children = get("pid.children").split(",");
        assertNotEquals(children[0], children[1], "two children must not share an id");
        assertNotEquals(self, children[0]);
        assertTrue(Long.parseLong(children[0]) > Long.parseLong(self));
        assertEquals("true", get("cmd.tasklist.self"));
        // The exec events carry the same ids the guest saw.
        List<Object> launched = Fixtures.events(report, "process", "exec").stream().map(e -> e.data().get("pid")).toList();
        assertTrue(launched.stream().map(String::valueOf).toList().containsAll(List.of(children)), launched.toString());
    }

    @Test
    void reconCommandsDescribeTheSameMachine() {
        assertEquals("DESKTOP-4QK7B2M", get("cmd.hostname"));
        assertEquals("desktop-4qk7b2m\\admin", get("cmd.whoami"));
        assertEquals(get("cmd.whoami"), get("cmd.whoamiViaCmd"));
        assertTrue(Integer.parseInt(get("cmd.tasklist.count")) >= 60, get("cmd.tasklist.count"));
        assertEquals("false", get("cmd.tasklist.vm"));
        assertEquals("explorer.exe", get("cmd.tasklist.filtered"));
        assertEquals("Manufacturer  Model|Dell Inc.     OptiPlex 7050", get("cmd.wmic.model"));
        String serial = get("cmd.wmic.serial");
        assertTrue(serial.matches("SerialNumber\\|[0-9A-Z]{7}"), serial);
        long disk = Long.parseLong(get("cmd.wmic.disk").replace("Size|", ""));
        assertTrue(disk > 200_000_000_000L, "disk " + disk);
        assertEquals("Caption=Microsoft Windows 10 Pro", get("cmd.wmic.os"));
        assertTrue(get("cmd.wmic.bad").contains("Invalid query"), get("cmd.wmic.bad"));
        assertEquals("true", get("cmd.systeminfo.host"));
        assertEquals("false", get("cmd.systeminfo.hypervisor"));
        assertEquals("true", get("cmd.ipconfig"));
        assertEquals("true", get("cmd.getmac"));
        assertEquals("admin@DESKTOP-4QK7B2M", get("cmd.echo"));
        assertEquals("0", get("cmd.unknown"));
        List<Event> execs = Fixtures.events(report, "process", "exec");
        assertTrue(execs.stream().anyMatch(e -> "built-in:wmic".equals(e.data().get("matchedRule"))), execs.toString());
        assertTrue(execs.stream().anyMatch(e -> String.valueOf(e.data().get("command")).equals("somethingelse.exe")
                && String.valueOf(e.data().get("matchedRule")).startsWith("none")), execs.toString());
    }

    @Test
    void standardVariablesAndPropertiesArePresent() {
        assertEquals("Intel64 Family 6 Model 158 Stepping 9, GenuineIntel", get("env.PROCESSOR_IDENTIFIER"));
        assertEquals("\\\\DESKTOP-4QK7B2M", get("env.LOGONSERVER"));
        for (String key : seen.keySet()) {
            if (key.startsWith("env.") || key.startsWith("prop.")) {
                assertNotEquals("null", seen.get(key), key + " is missing");
            }
        }
        assertEquals("1.8.0_361-b09", get("prop.java.runtime.version"));
        assertEquals("windows", get("prop.sun.desktop"));
        assertEquals("", get("prop.user.timezone"));
    }

    @Test
    void diskLooksUsed() {
        assertEquals("true", get("disk.comspec"));
        assertEquals("true", get("disk.explorer"));
        assertEquals("true", get("disk.x86"));
        assertTrue(Integer.parseInt(get("disk.desktop")) >= 3, get("disk.desktop"));
        assertTrue(Integer.parseInt(get("disk.recent")) >= 3, get("disk.recent"));
        assertTrue(Long.parseLong(get("disk.systemAgeDays")) > 300, "system files are as old as the installation");
        assertEquals("true", get("disk.hosts"));
    }

    @Test
    void packageMetadataComesFromTheManifest() {
        assertEquals("9.9", get("package.version"), "the package's own section wins");
        assertEquals("Recon tool", get("package.title"), "the main section fills in the rest");
        assertEquals("Example Vendor", get("package.vendor"));
    }

    @Test
    void twoRunsSeeTheSameMachine() throws IOException {
        EmulatorConfig config = Fixtures.config(jarWithManifest());
        config.environment.clockStart = "2024-03-01T09:00:00Z";
        List<String> again = Fixtures.stdout(Fixtures.run(config));
        assertEquals(Fixtures.stdout(report), again);
    }

    @Test
    void linuxMachineIsTheSameComputer() throws IOException {
        EmulatorConfig config = Fixtures.config(jarWithManifest());
        config.environment.os = "linux";
        config.environment.processors = 8;
        Report linux = Fixtures.run(config);
        List<String> lines = Fixtures.stdout(linux);
        assertEquals(List.of("linux.vendor=Private Build", "linux.vm=OpenJDK 64-Bit Server VM", "linux.desktop=gnome",
                "linux.dmi=Dell Inc. OptiPlex 7050", "linux.cpus=8", "linux.hypervisor=false", "linux.hostname=DESKTOP-4QK7B2M",
                "linux.uname=Linux DESKTOP-4QK7B2M 5.15.0-91-generic #101-Ubuntu SMP Tue Nov 14 13:30:08 UTC 2023 x86_64 x86_64 x86_64 GNU/Linux",
                "linux.loopback=lo"), lines, Fixtures.outcomes(linux));
    }

    @Test
    void builtInCommandsCanBeSwitchedOff() throws IOException {
        EmulatorConfig config = Fixtures.config(jarWithManifest());
        config.environment.builtInCommands = false;
        Report quiet = Fixtures.run(config);
        assertTrue(Fixtures.stdout(quiet).contains("cmd.hostname="), Fixtures.stdout(quiet).toString());
        assertTrue(Fixtures.events(quiet, "process", "exec").stream()
                .allMatch(e -> String.valueOf(e.data().get("matchedRule")).startsWith("none")));
    }

    @Test
    void defaultAddressesAvoidHypervisorDefaults() {
        EmulatorConfig.Network network = new EmulatorConfig().network;
        for (String subnet : List.of("192.168.56.", "10.0.2.", "192.168.122.", "172.16.")) {
            assertFalse(network.localAddress.startsWith(subnet), network.localAddress + " is in a hypervisor's default subnet");
        }
        int last = Integer.parseInt(network.localAddress.substring(network.localAddress.lastIndexOf('.') + 1));
        assertTrue(last < 128, "VMware's DHCP hands out .128 to .254");
        String mac = network.macAddress.toUpperCase();
        for (String prefix : List.of("08:00:27", "00:05:69", "00:0C:29", "00:1C:14", "00:50:56", "52:54:00", "00:16:3E", "00:15:5D")) {
            assertFalse(mac.startsWith(prefix), mac + " has a hypervisor's vendor prefix");
        }
    }
}
