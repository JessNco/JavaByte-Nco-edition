package dev.javabyte.guest.sim;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Simulated reconnaissance: everything a sample commonly asks about its machine, printed as
 * {@code key=value} lines so that a test can check the answers agree with each other and look
 * like a real desktop. Covers the management beans, the screen, network interfaces, process
 * ids, recon commands, environment variables, system properties, the disk, package metadata
 * from the manifest, and the class names of objects reached through interfaces.
 *
 * <p>This class is only ever interpreted. Do not run it on a real JVM.
 */
public final class Recon {

    public static void main(String[] args) throws Exception {
        if (System.getProperty("os.name").equals("Linux")) {
            linux();
            return;
        }
        // ---- classes seen through interface types (invokeinterface ...getClass)
        java.nio.file.Path path = Paths.get("x");
        ExecutorService executor = Executors.newFixedThreadPool(1);
        Future<Integer> future = executor.submit(() -> 1);
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        ProcessHandle self = ProcessHandle.current();
        print("class.path", path.getClass().getName());
        print("class.executor", executor.getClass().getName());
        print("class.future", future.getClass().getName());
        print("class.runtimeBean", runtime.getClass().getName());
        print("class.osBean", ManagementFactory.getOperatingSystemMXBean().getClass().getName());
        print("class.processHandle", self.getClass().getName());
        print("class.process", Runtime.getRuntime().exec("ver").getClass().getName());
        print("class.package", Recon.class.getPackage().getClass().getName());

        // ---- runtime bean: a fixed start time, growing uptime, the JVM's real properties
        long start = runtime.getStartTime();
        long uptime = runtime.getUptime();
        Thread.sleep(5000);
        print("runtime.startDelta", runtime.getStartTime() - start);
        print("runtime.uptimeDelta", runtime.getUptime() - uptime);
        print("runtime.vmVersion", runtime.getVmVersion());
        print("runtime.specVersion", runtime.getSpecVersion());
        print("runtime.vmVendor", runtime.getVmVendor());
        print("runtime.sameProperties", runtime.getSystemProperties().size() == System.getProperties().size());
        print("runtime.classPath", runtime.getClassPath().equals(System.getProperty("java.class.path")));
        print("runtime.name", runtime.getName());
        print("runtime.version.feature", Runtime.version().feature());

        // ---- hardware: one processor count, plenty of memory, a real screen
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        print("cpu.runtime", Runtime.getRuntime().availableProcessors());
        print("cpu.bean", os.getAvailableProcessors());
        print("cpu.env", System.getenv("NUMBER_OF_PROCESSORS"));
        print("memory.total", os.getTotalMemorySize());
        print("memory.physical", os.getTotalPhysicalMemorySize());
        print("memory.free", os.getFreeMemorySize());
        print("memory.heapMax", Runtime.getRuntime().maxMemory());
        print("memory.heapBeanMax", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax());
        print("os.name", os.getName());
        print("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        print("screen", screen.width + "x" + screen.height);
        print("screen.perPixel", 1_000_000 / (screen.width * screen.height));
        print("screen.dpi", java.awt.Toolkit.getDefaultToolkit().getScreenResolution());
        print("screen.headless", java.awt.GraphicsEnvironment.isHeadless());
        java.awt.GraphicsDevice device = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        print("screen.mode", device.getDisplayMode().getWidth() + "x" + device.getDisplayMode().getHeight());
        print("screen.bounds", device.getDefaultConfiguration().getBounds().width);

        // ---- network interfaces
        print("nic.count", Collections.list(NetworkInterface.getNetworkInterfaces()).size());
        NetworkInterface loopback = NetworkInterface.getByName("lo");
        print("nic.lo", loopback != null && loopback.isLoopback());
        print("nic.index1", NetworkInterface.getByIndex(1).getName());
        print("nic.byLoopbackAddress", NetworkInterface.getByInetAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1})).getName());
        print("nic.unknown", NetworkInterface.getByName("nope"));
        print("ip", InetAddress.getLocalHost().getHostAddress());

        // ---- process ids
        Process first = Runtime.getRuntime().exec("hostname");
        Process second = Runtime.getRuntime().exec("whoami");
        print("pid.self", self.pid());
        print("pid.children", first.pid() + "," + second.pid());

        // ---- recon commands
        print("cmd.hostname", output(first).trim());
        print("cmd.whoami", output(second).trim());
        print("cmd.whoamiViaCmd", output(Runtime.getRuntime().exec(new String[] {"cmd.exe", "/c", "whoami"})).trim());
        String tasks = output(Runtime.getRuntime().exec("tasklist /fo csv /nh"));
        print("cmd.tasklist.count", tasks.lines().filter(l -> !l.isBlank()).count());
        print("cmd.tasklist.self", tasks.contains("\"javaw.exe\",\"" + self.pid() + "\""));
        print("cmd.tasklist.vm", tasks.toLowerCase().matches("(?s).*(vbox|vmware|vmtools|qemu|xenservice|sandboxie|procmon|wireshark).*"));
        print("cmd.tasklist.filtered", output(Runtime.getRuntime().exec("cmd /c tasklist | findstr /i explorer")).trim().split("\\s+")[0]);
        print("cmd.wmic.model", lines(output(Runtime.getRuntime().exec("wmic computersystem get model,manufacturer"))));
        print("cmd.wmic.serial", lines(output(Runtime.getRuntime().exec("wmic bios get serialnumber"))));
        print("cmd.wmic.disk", lines(output(Runtime.getRuntime().exec("wmic diskdrive get size"))));
        print("cmd.wmic.os", lines(output(Runtime.getRuntime().exec("wmic /namespace:\\\\root\\cimv2 path win32_operatingsystem get caption /value"))));
        print("cmd.wmic.bad", lines(error(Runtime.getRuntime().exec("wmic bios get nosuchthing"))));
        String systeminfo = output(Runtime.getRuntime().exec("systeminfo"));
        print("cmd.systeminfo.host", systeminfo.contains("Host Name:                 " + System.getenv("COMPUTERNAME")));
        print("cmd.systeminfo.hypervisor", systeminfo.contains("hypervisor has been detected"));
        print("cmd.ipconfig", output(Runtime.getRuntime().exec("ipconfig /all")).contains(InetAddress.getLocalHost().getHostAddress()));
        byte[] mac = NetworkInterface.getByInetAddress(InetAddress.getLocalHost()).getHardwareAddress();
        print("cmd.getmac", output(Runtime.getRuntime().exec("getmac")).contains(String.format("%02X-%02X-%02X", mac[0], mac[1], mac[2])));
        print("cmd.echo", output(Runtime.getRuntime().exec("cmd /c echo %USERNAME%@%computername%")).trim());
        print("cmd.unknown", output(Runtime.getRuntime().exec("somethingelse.exe")).length());

        // ---- environment variables and properties a real machine always has
        for (String name : new String[] {"PROCESSOR_IDENTIFIER", "PROCESSOR_LEVEL", "PROCESSOR_REVISION", "PATHEXT", "ALLUSERSPROFILE",
                "CommonProgramFiles", "ProgramW6432", "LOGONSERVER", "SESSIONNAME", "PSModulePath"}) {
            print("env." + name, System.getenv(name));
        }
        for (String key : new String[] {"java.runtime.version", "java.vm.version", "java.vm.vendor", "java.vm.specification.version",
                "java.specification.vendor", "java.ext.dirs", "sun.boot.class.path", "sun.desktop", "sun.jnu.encoding",
                "sun.cpu.endian", "user.timezone", "sun.os.patch.level"}) {
            print("prop." + key, System.getProperty(key));
        }

        // ---- the disk
        print("disk.comspec", new File(System.getenv("ComSpec")).exists());
        print("disk.explorer", new File(System.getenv("windir"), "explorer.exe").exists());
        print("disk.x86", new File(System.getenv("ProgramFiles(x86)")).isDirectory());
        File[] desktop = new File(System.getProperty("user.home"), "Desktop").listFiles();
        print("disk.desktop", desktop == null ? 0 : desktop.length);
        print("disk.recent", new File(System.getenv("APPDATA"), "Microsoft\\Windows\\Recent").listFiles().length);
        print("disk.systemAgeDays", (System.currentTimeMillis() - new File(System.getenv("ComSpec")).lastModified()) / 86_400_000L);
        print("disk.hosts", new String(java.nio.file.Files.readAllBytes(Paths.get("C:\\Windows\\System32\\drivers\\etc\\hosts")),
                StandardCharsets.US_ASCII).contains("localhost name resolution"));

        // ---- package metadata from the manifest
        Package own = Recon.class.getPackage();
        print("package.version", own.getImplementationVersion());
        print("package.title", own.getImplementationTitle());
        print("package.vendor", own.getSpecificationVendor());
        executor.shutdown();
    }

    /** The Linux equivalents: the files and commands through which a program learns its machine there. */
    private static void linux() throws Exception {
        print("linux.vendor", System.getProperty("java.vendor"));
        print("linux.vm", System.getProperty("java.vm.name"));
        print("linux.desktop", System.getProperty("sun.desktop"));
        print("linux.dmi", read(new java.io.FileInputStream("/sys/class/dmi/id/sys_vendor")).trim() + " "
                + read(new java.io.FileInputStream("/sys/class/dmi/id/product_name")).trim());
        String cpuinfo = read(new java.io.FileInputStream("/proc/cpuinfo"));
        print("linux.cpus", cpuinfo.lines().filter(l -> l.startsWith("processor")).count());
        print("linux.hypervisor", cpuinfo.contains("hypervisor"));
        print("linux.hostname", read(new java.io.FileInputStream("/etc/hostname")).trim());
        print("linux.uname", output(Runtime.getRuntime().exec("uname -a")).trim());
        print("linux.loopback", NetworkInterface.getByName("lo").getDisplayName());
    }

    private static void print(String key, Object value) {
        System.out.println(key + "=" + value);
    }

    private static String output(Process process) throws Exception {
        return read(process.getInputStream());
    }

    private static String error(Process process) throws Exception {
        return read(process.getErrorStream());
    }

    private static String read(java.io.InputStream in) throws Exception {
        StringBuilder text = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            text.append(line).append('\n');
        }
        return text.toString();
    }

    /** The non-blank lines of a command's output, trimmed and joined with a bar. */
    private static String lines(String text) {
        return String.join("|", text.lines().map(String::trim).filter(l -> !l.isEmpty()).toList());
    }
}
