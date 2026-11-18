package dev.javabyte.env;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.vm.VirtualMachine;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The fake machine: operating system identity, properties, environment variables, clock, disk
 * and network, and the hardware in {@link MachineProfile}. Everything the guest can learn about
 * "where it is running" comes from here, and all of it is derived from configuration, so
 * nothing about the real analysis host leaks in.
 */
public final class GuestEnvironment {

    public final boolean windows;
    public final VirtualClock clock;
    public final VirtualFileSystem files;
    public final NetworkSimulator network;
    /** The simulated hardware and Windows identity that every hardware question is answered from. */
    public final MachineProfile machine;
    public final Properties properties = new Properties();
    public final Map<String, String> variables = new LinkedHashMap<>();
    public final SimClassLoader appLoader = new SimClassLoader();
    public final PrintStream stdout;
    public final PrintStream stderr;

    private final VirtualMachine vm;
    private String samplePath;

    public GuestEnvironment(VirtualMachine vm) {
        this.vm = vm;
        EmulatorConfig.Environment config = vm.config.environment;
        this.windows = "windows".equals(config.os);
        long start = config.clockStart == null ? System.currentTimeMillis() : Instant.parse(config.clockStart).toEpochMilli();
        this.clock = new VirtualClock(start);
        this.files = new VirtualFileSystem(windows, vm.events, vm.config.limits.maxWrittenBytes, start);
        this.network = new NetworkSimulator(vm.config.network, vm.events, vm.artifacts);
        this.machine = new MachineProfile(vm.config, start);
        this.stdout = consoleStream("stdout");
        this.stderr = consoleStream("stderr");
        this.samplePath = config.samplePath;
    }

    /**
     * Finishes setup once the sample's file name is known: derives default paths, fills in
     * properties and variables, and populates the virtual disk.
     */
    public void initialise(String sampleFileName, byte[] sampleBytes) {
        EmulatorConfig.Environment config = vm.config.environment;
        String user = config.userName;
        String home = windows ? "C:\\Users\\" + user : "/home/" + user;
        if (samplePath == null) {
            samplePath = windows ? home + "\\Downloads\\" + sampleFileName : home + "/Downloads/" + sampleFileName;
        }
        String javaHome = windows ? "C:\\Program Files\\Java\\jre1.8.0_361" : "/usr/lib/jvm/java-8-openjdk-amd64/jre";
        String temp = windows ? home + "\\AppData\\Local\\Temp\\" : "/tmp";

        // A Java 8 runtime is advertised because most Java malware was written for it and some
        // samples refuse to run on anything newer. On Windows it is Oracle's build, which is what
        // java.com installs; on Linux it is the OpenJDK 8 that Ubuntu 22.04 packages. Every
        // property a real Java 8 JVM of that build defines at start-up is present, because a
        // missing one reads as null and is itself a sign of an unusual runtime.
        String version = windows ? "1.8.0_361" : "1.8.0_392";
        String vmVendor = windows ? "Oracle Corporation" : "Private Build";
        put("java.version", version);
        put("java.runtime.version", windows ? "1.8.0_361-b09" : "1.8.0_392-8u392-ga-1~22.04-b08");
        put("java.specification.version", "1.8");
        put("java.specification.name", "Java Platform API Specification");
        put("java.specification.vendor", "Oracle Corporation");
        put("java.class.version", "52.0");
        put("java.vendor", vmVendor);
        put("java.vendor.url", "http://java.oracle.com/");
        put("java.vendor.url.bug", "http://bugreport.sun.com/bugreport/");
        put("java.vm.name", windows ? "Java HotSpot(TM) 64-Bit Server VM" : "OpenJDK 64-Bit Server VM");
        put("java.vm.vendor", vmVendor);
        put("java.vm.version", windows ? "25.361-b09" : "25.392-b08");
        put("java.vm.info", "mixed mode");
        put("java.vm.specification.version", "1.8");
        put("java.vm.specification.name", "Java Virtual Machine Specification");
        put("java.vm.specification.vendor", "Oracle Corporation");
        put("java.runtime.name", windows ? "Java(TM) SE Runtime Environment" : "OpenJDK Runtime Environment");
        put("java.home", javaHome);
        put("java.class.path", samplePath);
        put("java.io.tmpdir", temp);
        put("java.library.path", windows ? "C:\\Windows\\System32" : "/usr/lib");
        put("java.ext.dirs", windows ? javaHome + "\\lib\\ext;C:\\WINDOWS\\Sun\\Java\\lib\\ext"
                : javaHome + "/lib/ext:/usr/java/packages/lib/ext");
        put("java.endorsed.dirs", windows ? javaHome + "\\lib\\endorsed" : javaHome + "/lib/endorsed");
        put("java.awt.graphicsenv", windows ? "sun.awt.Win32GraphicsEnvironment" : "sun.awt.X11GraphicsEnvironment");
        put("java.awt.printerjob", windows ? "sun.awt.windows.WPrinterJob" : "sun.print.PSPrinterJob");
        put("awt.toolkit", windows ? "sun.awt.windows.WToolkit" : "sun.awt.X11.XToolkit");
        put("sun.java.command", sampleFileName);
        put("sun.java.launcher", "SUN_STANDARD");
        put("sun.arch.data.model", "64");
        put("sun.boot.library.path", windows ? javaHome + "\\bin" : javaHome + "/lib/amd64");
        put("sun.boot.class.path", bootClassPath(javaHome));
        put("sun.cpu.endian", "little");
        put("sun.cpu.isalist", windows ? "amd64" : "");
        put("sun.io.unicode.encoding", "UnicodeLittle");
        put("sun.jnu.encoding", windows ? "Cp1252" : "UTF-8");
        put("sun.os.patch.level", windows ? "" : "unknown");
        put("sun.management.compiler", "HotSpot 64-Bit Tiered Compilers");
        put("sun.desktop", windows ? "windows" : "gnome");
        put("os.name", windows ? "Windows 10" : "Linux");
        put("os.version", windows ? "10.0" : "5.15.0-91-generic");
        put("os.arch", "amd64");
        put("user.name", user);
        put("user.home", home);
        put("user.dir", files.parent(samplePath));
        put("user.language", "en");
        put("user.country", "US");
        put("user.script", "");
        put("user.variant", "");
        // Java 8 leaves this empty until something asks for the default time zone.
        put("user.timezone", "");
        put("file.separator", String.valueOf(files.separator()));
        put("path.separator", windows ? ";" : ":");
        put("line.separator", windows ? "\r\n" : "\n");
        put("file.encoding", windows ? "Cp1252" : "UTF-8");
        put("file.encoding.pkg", "sun.io");
        config.properties.forEach(this::put);

        if (windows) {
            variables.put("USERNAME", user);
            variables.put("USERPROFILE", home);
            variables.put("HOMEDRIVE", "C:");
            variables.put("HOMEPATH", "\\Users\\" + user);
            variables.put("APPDATA", home + "\\AppData\\Roaming");
            variables.put("LOCALAPPDATA", home + "\\AppData\\Local");
            variables.put("TEMP", home + "\\AppData\\Local\\Temp");
            variables.put("TMP", home + "\\AppData\\Local\\Temp");
            variables.put("COMPUTERNAME", config.hostName);
            variables.put("USERDOMAIN", config.hostName);
            variables.put("OS", "Windows_NT");
            variables.put("SystemRoot", "C:\\Windows");
            variables.put("windir", "C:\\Windows");
            variables.put("SystemDrive", "C:");
            variables.put("ProgramFiles", "C:\\Program Files");
            variables.put("ProgramFiles(x86)", "C:\\Program Files (x86)");
            variables.put("ProgramData", "C:\\ProgramData");
            variables.put("PUBLIC", "C:\\Users\\Public");
            variables.put("ComSpec", "C:\\Windows\\System32\\cmd.exe");
            variables.put("ProgramW6432", "C:\\Program Files");
            variables.put("CommonProgramFiles", "C:\\Program Files\\Common Files");
            variables.put("CommonProgramFiles(x86)", "C:\\Program Files (x86)\\Common Files");
            variables.put("CommonProgramW6432", "C:\\Program Files\\Common Files");
            variables.put("ALLUSERSPROFILE", "C:\\ProgramData");
            variables.put("DriverData", "C:\\Windows\\System32\\Drivers\\DriverData");
            variables.put("LOGONSERVER", "\\\\" + config.hostName);
            variables.put("SESSIONNAME", "Console");
            variables.put("USERDOMAIN_ROAMINGPROFILE", config.hostName);
            variables.put("OneDrive", home + "\\OneDrive");
            variables.put("PATHEXT", ".COM;.EXE;.BAT;.CMD;.VBS;.VBE;.JS;.JSE;.WSF;.WSH;.MSC");
            variables.put("PSModulePath", "C:\\Program Files\\WindowsPowerShell\\Modules;"
                    + "C:\\Windows\\system32\\WindowsPowerShell\\v1.0\\Modules");
            // The processor variables describe the same processor as wmic and the management beans.
            variables.put("PROCESSOR_ARCHITECTURE", "AMD64");
            variables.put("PROCESSOR_IDENTIFIER", MachineProfile.CPU_IDENTIFIER);
            variables.put("PROCESSOR_LEVEL", MachineProfile.CPU_LEVEL);
            variables.put("PROCESSOR_REVISION", MachineProfile.CPU_REVISION);
            variables.put("NUMBER_OF_PROCESSORS", String.valueOf(machine.processors));
            variables.put("PATH", "C:\\Windows\\system32;C:\\Windows;C:\\Windows\\System32\\Wbem;"
                    + "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\;C:\\Windows\\System32\\OpenSSH\\;"
                    + javaHome + "\\bin;" + home + "\\AppData\\Local\\Microsoft\\WindowsApps;");
        } else {
            variables.put("USER", user);
            variables.put("HOME", home);
            variables.put("SHELL", "/bin/bash");
            variables.put("HOSTNAME", config.hostName);
            variables.put("PATH", "/usr/local/bin:/usr/bin:/bin");
            variables.put("LANG", "en_US.UTF-8");
        }
        variables.putAll(config.env);

        files.setWorkingDirectory(files.parent(samplePath));
        if (windows) {
            for (String directory : new String[] {home + "\\Desktop", home + "\\Documents", home + "\\Downloads",
                    home + "\\AppData\\Roaming\\Microsoft\\Windows\\Start Menu\\Programs\\Startup",
                    home + "\\AppData\\Local\\Temp", "C:\\Windows\\System32", "C:\\ProgramData",
                    "C:\\Program Files", "C:\\Users\\Public"}) {
                files.addDirectory(directory);
            }
            // Samples relaunch themselves with the JVM they find under java.home.
            files.addFile(javaHome + "\\bin\\java.exe", new byte[] {'M', 'Z'});
            files.addFile(javaHome + "\\bin\\javaw.exe", new byte[] {'M', 'Z'});
            seedWindowsDisk(home);
        } else {
            for (String directory : new String[] {home + "/Desktop", home + "/Downloads", home + "/.config", "/tmp", "/etc"}) {
                files.addDirectory(directory);
            }
            files.addFile(javaHome + "/bin/java", new byte[] {0x7F, 'E', 'L', 'F'});
            seedLinuxDisk();
        }
        config.directories.forEach(files::addDirectory);
        for (EmulatorConfig.FileFixture fixture : config.files) {
            try {
                byte[] data = fixture.hostFile != null ? Files.readAllBytes(Path.of(fixture.hostFile))
                        : NetworkSimulator.content(fixture.text, fixture.base64, null);
                files.addFile(fixture.path, data);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read fixture for " + fixture.path, e);
            }
        }
        // The sample can find and read itself at its virtual location, as droppers do.
        files.addFile(samplePath, sampleBytes);
    }

    private void put(String key, String value) {
        properties.setProperty(key, value);
    }

    /** The boot class path a Java 8 JVM reports: its core jars under {@code lib}, then {@code classes}. */
    private String bootClassPath(String javaHome) {
        String lib = windows ? javaHome + "\\lib\\" : javaHome + "/lib/";
        StringBuilder path = new StringBuilder();
        for (String jar : new String[] {"resources.jar", "rt.jar", "sunrsasign.jar", "jsse.jar", "jce.jar", "charsets.jar", "jfr.jar"}) {
            path.append(lib).append(jar).append(windows ? ';' : ':');
        }
        return path.append(windows ? javaHome + "\\classes" : javaHome + "/classes").toString();
    }

    /**
     * Fills the Windows disk with what a used desktop has: the system binaries that the
     * environment variables point at, the maker's and common third-party software, and a few
     * files in the user's folders. Without them the disk looks freshly imaged, which is what
     * sandboxes look like. Files are small placeholders: an executable is an {@code MZ} header.
     * System files carry the install date and user files recent dates, rather than the moment
     * the run started. Config files and directories are added afterwards, so they can replace
     * any of these.
     */
    private void seedWindowsDisk(String home) {
        long installed = machine.installTime;
        long day = 24L * 3600 * 1000;
        String[] system = {
            "C:\\Windows\\explorer.exe", "C:\\Windows\\notepad.exe", "C:\\Windows\\regedit.exe",
            "C:\\Windows\\System32\\cmd.exe", "C:\\Windows\\System32\\conhost.exe", "C:\\Windows\\System32\\notepad.exe",
            "C:\\Windows\\System32\\kernel32.dll", "C:\\Windows\\System32\\ntdll.dll", "C:\\Windows\\System32\\user32.dll",
            "C:\\Windows\\System32\\advapi32.dll", "C:\\Windows\\System32\\shell32.dll", "C:\\Windows\\System32\\svchost.exe",
            "C:\\Windows\\System32\\lsass.exe", "C:\\Windows\\System32\\csrss.exe", "C:\\Windows\\System32\\winlogon.exe",
            "C:\\Windows\\System32\\services.exe", "C:\\Windows\\System32\\taskmgr.exe", "C:\\Windows\\System32\\tasklist.exe",
            "C:\\Windows\\System32\\schtasks.exe", "C:\\Windows\\System32\\reg.exe", "C:\\Windows\\System32\\systeminfo.exe",
            "C:\\Windows\\System32\\whoami.exe", "C:\\Windows\\System32\\HOSTNAME.EXE", "C:\\Windows\\System32\\ipconfig.exe",
            "C:\\Windows\\System32\\getmac.exe", "C:\\Windows\\System32\\wbem\\WMIC.exe",
            "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
            "C:\\Windows\\System32\\drivers\\disk.sys", "C:\\Windows\\System32\\drivers\\ndis.sys",
            "C:\\Windows\\System32\\drivers\\tcpip.sys", "C:\\Windows\\System32\\drivers\\stornvme.sys",
            "C:\\Windows\\System32\\drivers\\e1d68x64.sys", "C:\\Windows\\System32\\drivers\\WdFilter.sys",
            "C:\\Windows\\SysWOW64\\kernel32.dll", "C:\\Windows\\SysWOW64\\cmd.exe",
            "C:\\Windows\\Fonts\\arial.ttf", "C:\\Windows\\Fonts\\segoeui.ttf", "C:\\Windows\\Fonts\\consola.ttf",
            "C:\\Program Files\\Windows Defender\\MpCmdRun.exe", "C:\\Program Files\\Windows Defender\\MSASCuiL.exe",
            "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
        };
        for (String path : system) {
            seedFile(path, placeholder(path), installed);
        }
        for (String directory : new String[] {"C:\\Windows\\System32\\config", "C:\\Windows\\System32\\drivers\\etc",
                "C:\\Windows\\System32\\Drivers\\DriverData", "C:\\Program Files\\Common Files",
                "C:\\Program Files (x86)\\Common Files", "C:\\Program Files\\WindowsPowerShell\\Modules",
                "C:\\ProgramData\\Microsoft\\Windows Defender", "C:\\Users\\Public\\Desktop", "C:\\Users\\Public\\Documents"}) {
            seedDirectory(directory, installed);
        }
        seedFile("C:\\Windows\\System32\\drivers\\etc\\hosts", HOSTS.getBytes(StandardCharsets.US_ASCII), installed);
        // Software the maker preinstalled and the user added later.
        seedFile("C:\\Program Files\\Dell\\SupportAssistAgent\\bin\\SupportAssistAgent.exe",
                placeholder("SupportAssistAgent.exe"), installed + day);
        seedFile("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe", placeholder("chrome.exe"), installed + 3 * day);
        seedFile(home + "\\AppData\\Local\\Microsoft\\OneDrive\\OneDrive.exe", placeholder("OneDrive.exe"), installed + day);
        // The user's own folders: shortcuts, a few documents, and a recent-files list.
        long recent = machine.jvmStartTime;
        for (String directory : new String[] {home + "\\Pictures", home + "\\Music", home + "\\Videos", home + "\\Favorites",
                home + "\\OneDrive", home + "\\Documents\\My Music", home + "\\Documents\\My Pictures",
                home + "\\Documents\\My Videos"}) {
            seedDirectory(directory, installed);
        }
        String recentFolder = home + "\\AppData\\Roaming\\Microsoft\\Windows\\Recent\\";
        String[][] userFiles = {
            {home + "\\Desktop\\desktop.ini", "200"},
            {home + "\\Desktop\\Google Chrome.lnk", "120"},
            {home + "\\Desktop\\Microsoft Edge.lnk", "200"},
            {home + "\\Desktop\\Invoice 2023-114.pdf", "9"},
            {home + "\\Desktop\\meeting notes.txt", "2"},
            {recentFolder + "Invoice 2023-114.lnk", "9"},
            {recentFolder + "meeting notes.lnk", "2"},
            {recentFolder + "Downloads.lnk", "1"},
        };
        for (String[] entry : userFiles) {
            seedFile(entry[0], placeholder(entry[0]), recent - Long.parseLong(entry[1]) * day);
        }
    }

    /**
     * The Linux equivalents of the Windows answers: the files through which programs learn the
     * machine's name, maker, processor and memory. They describe the same machine.
     */
    private void seedLinuxDisk() {
        long installed = machine.installTime;
        seedFile("/etc/hostname", (machine.hostName + "\n").getBytes(StandardCharsets.US_ASCII), installed);
        seedFile("/etc/os-release", ("PRETTY_NAME=\"Ubuntu 22.04.3 LTS\"\nNAME=\"Ubuntu\"\nVERSION_ID=\"22.04\"\n"
                + "VERSION=\"22.04.3 LTS (Jammy Jellyfish)\"\nID=ubuntu\nID_LIKE=debian\n").getBytes(StandardCharsets.US_ASCII), installed);
        String dmi = "/sys/class/dmi/id/";
        seedFile(dmi + "sys_vendor", (machine.manufacturer + "\n").getBytes(StandardCharsets.US_ASCII), installed);
        seedFile(dmi + "product_name", (machine.model + "\n").getBytes(StandardCharsets.US_ASCII), installed);
        seedFile(dmi + "board_vendor", (machine.manufacturer + "\n").getBytes(StandardCharsets.US_ASCII), installed);
        seedFile(dmi + "bios_vendor", (machine.manufacturer + "\n").getBytes(StandardCharsets.US_ASCII), installed);
        seedFile(dmi + "bios_version", (MachineProfile.BIOS_VERSION + "\n").getBytes(StandardCharsets.US_ASCII), installed);
        StringBuilder cpuinfo = new StringBuilder();
        for (int i = 0; i < machine.processors; i++) {
            // No "hypervisor" flag: that is what virtual machines add.
            cpuinfo.append("processor\t: ").append(i).append("\nvendor_id\t: GenuineIntel\ncpu family\t: 6\nmodel\t\t: 158\n")
                    .append("model name\t: ").append(MachineProfile.CPU_NAME).append("\nstepping\t: 9\ncpu MHz\t\t: 3400.000\n")
                    .append("cpu cores\t: ").append(machine.processors).append("\nflags\t\t: fpu vme de pse tsc msr pae mce cx8 apic ")
                    .append("sep mtrr pge mca cmov pat pse36 clflush dts acpi mmx fxsr sse sse2 ss ht tm pbe syscall nx pdpe1gb ")
                    .append("rdtscp lm constant_tsc art arch_perfmon pebs bts rep_good nopl xtopology nonstop_tsc cpuid aperfmperf ")
                    .append("pni pclmulqdq dtes64 monitor ds_cpl vmx smx est tm2 ssse3 sdbg fma cx16 xtpr pdcm pcid sse4_1 sse4_2 ")
                    .append("x2apic movbe popcnt tsc_deadline_timer aes xsave avx f16c rdrand lahf_lm abm 3dnowprefetch ")
                    .append("cpuid_fault epb invpcid_single pti ssbd ibrs ibpb stibp tpr_shadow vnmi flexpriority ept vpid ")
                    .append("ept_ad fsgsbase tsc_adjust bmi1 avx2 smep bmi2 erms invpcid mpx rdseed adx smap clflushopt ")
                    .append("intel_pt xsaveopt xsavec xgetbv1 xsaves dtherm ida arat pln pts hwp hwp_notify hwp_act_window ")
                    .append("hwp_epp md_clear flush_l1d arch_capabilities\n\n");
        }
        seedFile("/proc/cpuinfo", cpuinfo.toString().getBytes(StandardCharsets.US_ASCII), machine.bootTime);
        long kilobytes = machine.visibleMemoryBytes >> 10;
        seedFile("/proc/meminfo", ("MemTotal:       " + kilobytes + " kB\nMemFree:        " + (machine.freeMemoryBytes >> 10)
                + " kB\nMemAvailable:   " + (machine.freeMemoryBytes >> 10) + " kB\n").getBytes(StandardCharsets.US_ASCII), machine.bootTime);
    }

    /** Adds a file and dates it, so it does not carry the run's start time. */
    private void seedFile(String path, byte[] data, long modified) {
        files.addFile(path, data);
        files.node(path).modified = modified;
    }

    /** Adds a directory and dates it. */
    private void seedDirectory(String path, long modified) {
        files.addDirectory(path);
        files.node(path).modified = modified;
    }

    /**
     * Placeholder content for a seeded file: a minimal DOS header for executables and
     * libraries, and a short line of text for anything else.
     */
    private static byte[] placeholder(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".exe") || lower.endsWith(".dll") || lower.endsWith(".sys")) {
            byte[] image = new byte[512];
            image[0] = 'M';
            image[1] = 'Z';
            byte[] stub = "This program cannot be run in DOS mode.".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(stub, 0, image, 78, stub.length);
            return image;
        }
        if (lower.endsWith(".pdf")) {
            return "%PDF-1.7\n%\u00e2\u00e3\u00cf\u00d3\n".getBytes(StandardCharsets.ISO_8859_1);
        }
        if (lower.endsWith(".lnk")) {
            return new byte[] {0x4C, 0, 0, 0, 0x01, 0x14, 0x02, 0, 0, 0, 0, 0, (byte) 0xC0, 0, 0, 0, 0, 0, 0, 0x46};
        }
        if (lower.endsWith("desktop.ini")) {
            return "\r\n[.ShellClassInfo]\r\nLocalizedResourceName=@%SystemRoot%\\system32\\shell32.dll,-21769\r\n"
                    .getBytes(StandardCharsets.US_ASCII);
        }
        return "Call with the supplier on Thursday about the delivery dates.\r\n".getBytes(StandardCharsets.US_ASCII);
    }

    /** The hosts file Windows installs: comments only. */
    private static final String HOSTS = "# Copyright (c) 1993-2009 Microsoft Corp.\r\n#\r\n"
            + "# This is a sample HOSTS file used by Microsoft TCP/IP for Windows.\r\n#\r\n"
            + "# This file contains the mappings of IP addresses to host names. Each\r\n"
            + "# entry should be kept on an individual line. The IP address should\r\n"
            + "# be placed in the first column followed by the corresponding host name.\r\n"
            + "# The IP address and the host name should be separated by at least one\r\n# space.\r\n#\r\n"
            + "# localhost name resolution is handled within DNS itself.\r\n"
            + "#\t127.0.0.1       localhost\r\n#\t::1             localhost\r\n";

    public String samplePath() {
        return samplePath;
    }

    public String lineSeparator() {
        return windows ? "\r\n" : "\n";
    }

    /** A print stream whose completed lines become {@code console} events. */
    private PrintStream consoleStream(String name) {
        OutputStream sink = new OutputStream() {
            private final StringBuilder line = new StringBuilder();

            @Override
            public void write(int b) {
                if (b == '\n') {
                    flushLine();
                } else if (b != '\r') {
                    line.append((char) (b & 0xFF));
                }
            }

            @Override
            public void flush() {
                // Lines are emitted on newline; an explicit flush mid-line is left to accumulate.
            }

            private void flushLine() {
                vm.events.add("console", name, "text", dev.javabyte.vm.Values.truncate(line.toString(), 2000));
                line.setLength(0);
            }
        };
        return new PrintStream(sink, true, StandardCharsets.ISO_8859_1);
    }
}
