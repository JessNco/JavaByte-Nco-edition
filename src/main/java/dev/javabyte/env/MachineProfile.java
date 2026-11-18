package dev.javabyte.env;

import dev.javabyte.config.EmulatorConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The simulated computer as a whole: its maker and model, processor, memory, disk, screen,
 * network card, Windows build and running programs.
 *
 * <p>Samples rarely ask one question about their machine. They compare answers: the processor
 * count from {@code Runtime} with {@code NUMBER_OF_PROCESSORS}, the MAC prefix with the maker,
 * the memory in {@code wmic} with the management bean. So every stub that describes hardware
 * reads it from this one place, and the answers agree with each other and with the user, host
 * name and Java version that {@link GuestEnvironment} advertises.
 *
 * <p>The default machine is a Dell OptiPlex 7050 office desktop with an Intel Core i5-7500,
 * 16 GB of memory, a 512 GB NVMe disk and a 1920x1080 screen, running Windows 10 Pro 22H2 on a
 * home-router LAN. The parts an analyst may want to change (maker, model, processors, memory,
 * screen, process id, addresses) come from {@link EmulatorConfig}; the rest are fixed here.
 * Values that a real machine makes unique, such as the serial number or the volume serial, are
 * derived from the host name, so the same config always gives the same machine and the same
 * report.
 */
public final class MachineProfile {

    // ---------------------------------------------------------------- fixed facts

    /** The processor's marketing name, as in the registry and {@code wmic cpu get name}. */
    public static final String CPU_NAME = "Intel(R) Core(TM) i5-7500 CPU @ 3.40GHz";
    /** {@code PROCESSOR_IDENTIFIER}; the family, model and stepping of a Kaby Lake i5-7500. */
    public static final String CPU_IDENTIFIER = "Intel64 Family 6 Model 158 Stepping 9, GenuineIntel";
    /** {@code PROCESSOR_LEVEL}: the processor family. */
    public static final String CPU_LEVEL = "6";
    /** {@code PROCESSOR_REVISION}: model 0x9e, stepping 9. */
    public static final String CPU_REVISION = "9e09";
    /** {@code wmic cpu get processorid}: the CPUID feature flags and signature. */
    public static final String CPU_ID = "BFEBFBFF000906E9";
    /** The rated clock speed in MHz, as Windows reports it. */
    public static final int CPU_MHZ = 3401;

    /** The firmware version string of the default model. */
    public static final String BIOS_VERSION = "1.24.0";
    /** The firmware release date, in WMI's date format. */
    public static final String BIOS_DATE = "20230411000000.000000+000";

    /** Windows edition, as {@code systeminfo} and {@code Win32_OperatingSystem.Caption} give it. */
    public static final String OS_CAPTION = "Microsoft Windows 10 Pro";
    /** Windows 10 22H2. */
    public static final String OS_BUILD = "19045";
    /** The Windows version with build, as WMI reports it. */
    public static final String OS_VERSION = "10.0." + OS_BUILD;
    /** The full version shown by {@code ver}, including the update revision. */
    public static final String OS_FULL_VERSION = OS_VERSION + ".3693";

    /** The system disk, an NVMe drive common in office desktops of the period. */
    public static final String DISK_MODEL = "KXG50ZNV512G NVMe TOSHIBA 512GB";
    /** The disk's size in bytes, as {@code wmic diskdrive get size} gives it. */
    public static final long DISK_BYTES = 512_105_932_800L;
    /**
     * The size of drive C:. It equals what {@code SimFile.getTotalSpace} returns, so every way
     * of asking about the drive gets the same answer.
     */
    public static final long VOLUME_BYTES = 256L << 30;
    /** The free space on drive C:, equal to what {@code SimFile.getFreeSpace} returns. */
    public static final long VOLUME_FREE_BYTES = 120L << 30;

    /** The on-board network card of the default model. */
    public static final String NIC_NAME = "Intel(R) Ethernet Connection (5) I219-LM";
    /** The on-board graphics of the i5-7500. */
    public static final String GPU_NAME = "Intel(R) HD Graphics 630";
    /** Screen density in dots per inch: Windows' default 100% scaling. */
    public static final int SCREEN_DPI = 96;
    /** Bits per pixel of the display mode. */
    public static final int SCREEN_BIT_DEPTH = 32;
    /** Refresh rate of the display mode in hertz. */
    public static final int SCREEN_REFRESH_HZ = 60;

    /**
     * The time zone of the simulated Windows installation, used where a command prints local
     * time. It matches the advertised {@code user.country} of US.
     */
    public static final ZoneId TIME_ZONE = ZoneId.of("America/New_York");
    /** How {@code systeminfo} names {@link #TIME_ZONE}. */
    public static final String TIME_ZONE_DISPLAY = "(UTC-05:00) Eastern Time (US & Canada)";

    /** How long before the virtual clock starts Windows was installed: about thirteen months. */
    private static final long INSTALL_AGE_MILLIS = 412L * 24 * 3600 * 1000 + 37_284_000L;
    /** How long before the virtual clock starts the machine booted: two hours and some minutes. */
    private static final long BOOT_AGE_MILLIS = 2L * 3600 * 1000 + 13 * 60_000 + 27_000;
    /** How long before the virtual clock starts the sample's Java process started. */
    private static final long JVM_AGE_MILLIS = 420L;

    // ---------------------------------------------------------------- per-run values

    /** True when the guest believes it runs on Windows. */
    public final boolean windows;
    /** The computer maker, from {@code environment.manufacturer}. */
    public final String manufacturer;
    /** The computer model, from {@code environment.model}. */
    public final String model;
    /** The computer name, from {@code environment.hostName}. */
    public final String hostName;
    /** The user name, from {@code environment.userName}. */
    public final String userName;
    /** Logical processors, from {@code environment.processors}. */
    public final int processors;
    /** Installed memory in bytes, from {@code environment.memoryMegabytes}. */
    public final long installedMemoryBytes;
    /**
     * Memory Windows can use: what is installed less what the firmware and integrated graphics
     * reserve. This is what the management bean, {@code wmic} and {@code systeminfo} report.
     */
    public final long visibleMemoryBytes;
    /** Memory currently free, a steady 61% of the visible memory. */
    public final long freeMemoryBytes;
    /** Screen width in pixels, from {@code environment.screenWidth}. */
    public final int screenWidth;
    /** Screen height in pixels, from {@code environment.screenHeight}. */
    public final int screenHeight;
    /** The sample's own process id, from {@code environment.processId}. */
    public final long processId;
    /** The JVM options the sample's process appears to have, from {@code environment.jvmArguments}. */
    public final List<String> jvmArguments;
    /** The machine's IPv4 address, from {@code network.localAddress}. */
    public final String localAddress;
    /** The router: address .1 of the local /24 network. It also serves DHCP and DNS. */
    public final String gateway;
    /** The network card's MAC address as {@code AA-BB-CC-DD-EE-FF}, the way Windows prints it. */
    public final String macAddress;
    /** The card's IPv6 link-local address, as {@code ipconfig} lists it. */
    public final String linkLocalAddress;
    /** The BIOS serial number. For Dell this is the seven-character service tag. */
    public final String serialNumber;
    /** The SMBIOS system UUID. */
    public final String systemUuid;
    /** The machine's security identifier prefix; the user's SID is this plus {@code -1001}. */
    public final String machineSid;
    /** The serial number of volume C: as eight hex digits, as {@code wmic} prints it. */
    public final String volumeSerial;
    /** The Windows product id shown by {@code systeminfo}. */
    public final String productId;
    /** When Windows was installed, in epoch milliseconds. Older than anything the sample writes. */
    public final long installTime;
    /** When the machine last booted, in epoch milliseconds. */
    public final long bootTime;
    /** When the sample's Java process started, in epoch milliseconds. Never changes during a run. */
    public final long jvmStartTime;

    private final List<ProcessEntry> systemProcesses;
    private long lastChildPid;

    /**
     * Builds the machine for one run.
     *
     * @param config     the run's configuration
     * @param clockStart when the virtual clock starts, in epoch milliseconds; boot and install
     *                   times are placed before it
     */
    public MachineProfile(EmulatorConfig config, long clockStart) {
        EmulatorConfig.Environment environment = config.environment;
        this.windows = "windows".equals(environment.os);
        this.manufacturer = environment.manufacturer;
        this.model = environment.model;
        this.hostName = environment.hostName;
        this.userName = environment.userName;
        this.processors = environment.processors;
        this.installedMemoryBytes = (long) environment.memoryMegabytes << 20;
        // Integrated graphics and firmware typically take a little under 1% of a desktop's memory.
        // Both are whole 4 KB pages, as Windows counts memory.
        this.visibleMemoryBytes = (installedMemoryBytes - installedMemoryBytes / 136) & ~0xFFFL;
        this.freeMemoryBytes = (visibleMemoryBytes / 100 * 61) & ~0xFFFL;
        this.screenWidth = environment.screenWidth;
        this.screenHeight = environment.screenHeight;
        this.processId = environment.processId;
        this.jvmArguments = List.copyOf(environment.jvmArguments);
        this.localAddress = config.network.localAddress;
        this.gateway = localAddress.substring(0, localAddress.lastIndexOf('.') + 1) + "1";
        this.macAddress = config.network.macAddress.toUpperCase(java.util.Locale.ROOT).replace(':', '-');

        byte[] seed = digest("machine:" + hostName);
        this.serialNumber = serviceTag(seed);
        this.systemUuid = String.format("4C4C4544-00%02X-%02X10-80%02X-%02X%02X%02X%02X%02X%02X", seed[0] & 0xFF, seed[1] & 0xFF,
                seed[2] & 0xFF, seed[3] & 0xFF, seed[4] & 0xFF, seed[5] & 0xFF, seed[6] & 0xFF, seed[7] & 0xFF, seed[8] & 0xFF);
        this.machineSid = "S-1-5-21-" + unsigned(seed, 9) + "-" + unsigned(seed, 13) + "-" + unsigned(seed, 17);
        this.volumeSerial = String.format("%02X%02X%02X%02X", seed[21] & 0xFF, seed[22] & 0xFF, seed[23] & 0xFF, seed[24] & 0xFF);
        this.productId = String.format("00330-80000-00000-AA%03d", ((seed[25] & 0xFF) << 2 | seed[26] & 0x3) % 1000);
        this.linkLocalAddress = String.format("fe80::%x:%x:%x:%x%%12", ((seed[27] & 0xFF) << 8 | seed[28] & 0xFF) | 0x1000,
                (seed[29] & 0xFF) << 8 | seed[30] & 0xFF, (seed[31] & 0xFF) << 8 | seed[0] & 0xFF, (seed[1] & 0xFF) << 8 | seed[2] & 0xFF);
        this.installTime = clockStart - INSTALL_AGE_MILLIS;
        this.bootTime = clockStart - BOOT_AGE_MILLIS;
        this.jvmStartTime = clockStart - JVM_AGE_MILLIS;
        this.systemProcesses = windows ? windowsProcesses() : linuxProcesses();
        this.lastChildPid = processId;
    }

    /** A running program, as {@code tasklist} and {@code ProcessHandle.allProcesses} list it. */
    public record ProcessEntry(String image, long pid, String sessionName, int session, int memoryKb) {}

    /**
     * Every program running on the machine, ordered by start as {@code tasklist} prints them,
     * ending with the sample's own Java process.
     */
    public List<ProcessEntry> processes() {
        List<ProcessEntry> all = new ArrayList<>(systemProcesses);
        all.add(new ProcessEntry(windows ? "javaw.exe" : "java", processId, windows ? "Console" : "", 1, 58_212));
        return Collections.unmodifiableList(all);
    }

    /**
     * The process id for the next program the sample starts. Each launch gets its own id,
     * higher than the last and, as on Windows, a multiple of four apart from it, so no two
     * children and no child and the sample share an id.
     */
    public synchronized long nextChildPid() {
        lastChildPid += 4L * (3 + (lastChildPid / 4) % 7);
        return lastChildPid;
    }

    /** The Java executable the sample's process runs, as a command line names it. */
    public String javaExecutable(String javaHome) {
        return windows ? javaHome + "\\bin\\javaw.exe" : javaHome + "/bin/java";
    }

    /** {@code DOMAIN\\user} as {@code whoami} prints it: the host name in lower case on Windows. */
    public String qualifiedUser() {
        return windows ? hostName.toLowerCase(java.util.Locale.ROOT) + "\\" + userName.toLowerCase(java.util.Locale.ROOT) : userName;
    }

    /** The user's security identifier: the first local account created at install, number 1001. */
    public String userSid() {
        return machineSid + "-1001";
    }

    // ---------------------------------------------------------------- the process table

    /**
     * A Windows 10 desktop after logon, with Defender, the Dell and Intel helpers its maker
     * installs, and a browser open. No hypervisor tools and no analysis tools. Ids are multiples
     * of four and all below the sample's own, which started last.
     */
    private List<ProcessEntry> windowsProcesses() {
        String[][] table = {
            {"System Idle Process", "0", "S", "8"},
            {"System", "4", "S", "152"},
            {"Registry", "124", "S", "71,288"},
            {"smss.exe", "412", "S", "1,192"},
            {"csrss.exe", "596", "S", "5,412"},
            {"wininit.exe", "680", "S", "6,940"},
            {"csrss.exe", "688", "C", "5,876"},
            {"services.exe", "752", "S", "10,208"},
            {"lsass.exe", "772", "S", "19,644"},
            {"winlogon.exe", "840", "C", "11,812"},
            {"svchost.exe", "904", "S", "3,920"},
            {"fontdrvhost.exe", "928", "S", "3,144"},
            {"fontdrvhost.exe", "936", "C", "6,872"},
            {"svchost.exe", "964", "S", "30,412"},
            {"WUDFHost.exe", "1012", "S", "7,036"},
            {"svchost.exe", "1068", "S", "17,228"},
            {"svchost.exe", "1116", "S", "8,108"},
            {"dwm.exe", "1196", "C", "78,400"},
            {"svchost.exe", "1272", "S", "9,844"},
            {"svchost.exe", "1300", "S", "12,520"},
            {"svchost.exe", "1388", "S", "14,956"},
            {"svchost.exe", "1428", "S", "7,612"},
            {"svchost.exe", "1520", "S", "10,148"},
            {"svchost.exe", "1572", "S", "6,224"},
            {"IntelCpHDCPSvc.exe", "1604", "S", "5,632"},
            {"svchost.exe", "1652", "S", "21,340"},
            {"svchost.exe", "1696", "S", "8,900"},
            {"svchost.exe", "1768", "S", "6,404"},
            {"Memory Compression", "1832", "S", "142,312"},
            {"svchost.exe", "1888", "S", "11,264"},
            {"svchost.exe", "1960", "S", "7,880"},
            {"svchost.exe", "2036", "S", "9,212"},
            {"IntelCpHeciSvc.exe", "2088", "S", "6,148"},
            {"svchost.exe", "2148", "S", "16,084"},
            {"spoolsv.exe", "2340", "S", "15,532"},
            {"svchost.exe", "2416", "S", "28,764"},
            {"svchost.exe", "2464", "S", "12,044"},
            {"RtkAudUService64.exe", "2520", "S", "8,616"},
            {"svchost.exe", "2588", "S", "18,976"},
            {"MsMpEng.exe", "2672", "S", "203,448"},
            {"svchost.exe", "2716", "S", "6,788"},
            {"SupportAssistAgent.exe", "2804", "S", "64,236"},
            {"svchost.exe", "2868", "S", "10,532"},
            {"SecurityHealthService.exe", "2936", "S", "12,368"},
            {"svchost.exe", "3012", "S", "8,452"},
            {"NisSrv.exe", "3236", "S", "9,876"},
            {"SearchIndexer.exe", "3488", "S", "39,820"},
            {"svchost.exe", "3612", "S", "13,640"},
            {"WmiPrvSE.exe", "3756", "S", "14,288"},
            {"sihost.exe", "3964", "C", "27,316"},
            {"svchost.exe", "3992", "C", "22,904"},
            {"svchost.exe", "4028", "C", "31,072"},
            {"taskhostw.exe", "4104", "C", "18,404"},
            {"igfxEM.exe", "4180", "C", "16,772"},
            {"ctfmon.exe", "4256", "C", "19,388"},
            {"explorer.exe", "4352", "C", "142,920"},
            {"svchost.exe", "4464", "C", "15,316"},
            {"StartMenuExperienceHost.exe", "4648", "C", "71,104"},
            {"RuntimeBroker.exe", "4772", "C", "24,980"},
            {"SearchApp.exe", "4896", "C", "186,232"},
            {"RuntimeBroker.exe", "5008", "C", "33,604"},
            {"TextInputHost.exe", "5124", "C", "47,212"},
            {"RuntimeBroker.exe", "5236", "C", "19,064"},
            {"SecurityHealthSystray.exe", "5352", "C", "9,708"},
            {"OneDrive.exe", "5448", "C", "96,516"},
            {"ShellExperienceHost.exe", "5580", "C", "52,840"},
            {"chrome.exe", "5712", "C", "168,344"},
            {"chrome.exe", "5740", "C", "8,212"},
            {"chrome.exe", "5868", "C", "112,604"},
            {"chrome.exe", "5896", "C", "38,972"},
            {"chrome.exe", "6024", "C", "74,188"},
            {"chrome.exe", "6152", "C", "52,436"},
            {"dllhost.exe", "6284", "C", "12,112"},
            {"ApplicationFrameHost.exe", "6372", "C", "30,276"},
            {"smartscreen.exe", "6496", "C", "23,184"},
            {"audiodg.exe", "6568", "S", "13,452"},
            {"svchost.exe", "6624", "S", "9,060"},
            {"WmiPrvSE.exe", "6688", "S", "10,940"},
        };
        List<ProcessEntry> processes = new ArrayList<>();
        for (String[] row : table) {
            boolean console = row[2].equals("C");
            processes.add(new ProcessEntry(row[0], Long.parseLong(row[1]), console ? "Console" : "Services", console ? 1 : 0,
                    Integer.parseInt(row[3].replace(",", ""))));
        }
        return processes;
    }

    /** A Ubuntu desktop session, for samples told they run on Linux. */
    private List<ProcessEntry> linuxProcesses() {
        String[][] table = {
            {"systemd", "1"}, {"kthreadd", "2"}, {"systemd-journal", "312"}, {"systemd-udevd", "356"},
            {"systemd-resolve", "612"}, {"systemd-timesyn", "613"}, {"accounts-daemon", "702"}, {"cron", "706"},
            {"dbus-daemon", "708"}, {"NetworkManager", "711"}, {"rsyslogd", "742"}, {"snapd", "748"},
            {"systemd-logind", "752"}, {"udisksd", "760"}, {"wpa_supplicant", "764"}, {"cupsd", "803"},
            {"gdm3", "1012"}, {"systemd", "1544"}, {"pulseaudio", "1561"}, {"gnome-shell", "1788"},
            {"gnome-terminal-", "2410"}, {"bash", "2431"}, {"firefox", "2655"},
        };
        List<ProcessEntry> processes = new ArrayList<>();
        for (String[] row : table) {
            processes.add(new ProcessEntry(row[0], Long.parseLong(row[1]), "", 0, 0));
        }
        return processes;
    }

    // ---------------------------------------------------------------- derivation helpers

    private static byte[] digest(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Four bytes as an unsigned decimal number, as each part of a Windows SID is written. */
    private static long unsigned(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 4; i++) {
            value = value << 8 | bytes[offset + i] & 0xFF;
        }
        return value;
    }

    /** Seven characters in the style of a Dell service tag: digits and consonants, ending in 2. */
    private static String serviceTag(byte[] seed) {
        String alphabet = "0123456789BCDFGHJKLMNPQRSTVWXYZ";
        StringBuilder tag = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            tag.append(alphabet.charAt((seed[i + 10] & 0xFF) % alphabet.length()));
        }
        return tag.append('2').toString();
    }
}
