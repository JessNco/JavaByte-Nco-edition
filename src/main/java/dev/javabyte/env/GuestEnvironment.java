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
 * and network. Everything the guest can learn about "where it is running" comes from here, and
 * all of it is derived from configuration, so nothing about the real analysis host leaks in.
 */
public final class GuestEnvironment {

    public final boolean windows;
    public final VirtualClock clock;
    /** The guest's time zone and locale; the host's own are never shown to the sample. */
    public final GuestRegion region;
    public final VirtualFileSystem files;
    public final NetworkSimulator network;
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
        this.region = GuestRegion.of(config);
        this.files = new VirtualFileSystem(windows, vm.events, vm.config.limits.maxWrittenBytes, start);
        this.network = new NetworkSimulator(vm.config.network, vm.events, vm.artifacts);
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
        // samples refuse to run on anything newer.
        put("java.version", "1.8.0_361");
        put("java.specification.version", "1.8");
        put("java.class.version", "52.0");
        put("java.vendor", "Oracle Corporation");
        put("java.vm.name", "Java HotSpot(TM) 64-Bit Server VM");
        put("java.runtime.name", "Java(TM) SE Runtime Environment");
        put("java.home", javaHome);
        put("java.class.path", samplePath);
        put("java.io.tmpdir", temp);
        put("java.library.path", windows ? "C:\\Windows\\System32" : "/usr/lib");
        put("sun.java.command", sampleFileName);
        put("sun.arch.data.model", "64");
        put("os.name", windows ? "Windows 10" : "Linux");
        put("os.version", windows ? "10.0" : "5.15.0-91-generic");
        put("os.arch", "amd64");
        put("user.name", user);
        put("user.home", home);
        put("user.dir", files.parent(samplePath));
        put("user.language", region.locale.getLanguage());
        put("user.country", region.locale.getCountry());
        put("user.timezone", region.zone.getId());
        put("file.separator", String.valueOf(files.separator()));
        put("path.separator", windows ? ";" : ":");
        put("line.separator", windows ? "\r\n" : "\n");
        put("file.encoding", windows ? "Cp1252" : "UTF-8");
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
            variables.put("PROCESSOR_ARCHITECTURE", "AMD64");
            variables.put("NUMBER_OF_PROCESSORS", "4");
            variables.put("PATH", "C:\\Windows\\System32;C:\\Windows;" + javaHome + "\\bin");
        } else {
            variables.put("USER", user);
            variables.put("HOME", home);
            variables.put("SHELL", "/bin/bash");
            variables.put("HOSTNAME", config.hostName);
            variables.put("PATH", "/usr/local/bin:/usr/bin:/bin");
            variables.put("LANG", region.locale + ".UTF-8");
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
        } else {
            for (String directory : new String[] {home + "/Desktop", home + "/Downloads", home + "/.config", "/tmp", "/etc"}) {
                files.addDirectory(directory);
            }
            files.addFile(javaHome + "/bin/java", new byte[] {0x7F, 'E', 'L', 'F'});
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
