package dev.javabyte.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete, declarative description of one emulation run.
 *
 * <p>Everything that changes emulator behaviour lives here rather than in command-line flags or
 * system properties. A run is therefore reproducible from a single file, and a person or an
 * automated agent can review exactly what the sample was allowed to see and do.
 *
 * <p>The classes are plain mutable holders with public fields because Jackson binds them directly
 * from YAML or JSON and the defaults sit next to the fields they belong to. Each field's meaning
 * is in its {@link ConfigDoc}, which {@code javabyte schema} prints. Unknown keys are rejected by
 * {@link ConfigLoader}, so a misspelt option fails loudly instead of being ignored.
 */
public final class EmulatorConfig {

    @ConfigDoc("The code to analyse: a .jar or .zip, a single .class file, or a folder of classes.")
    public String sample;

    @ConfigDoc("More jars or folders whose classes are interpreted alongside the sample, such as libraries it expects to find.")
    public List<String> classpath = new ArrayList<>();

    @ConfigDoc("What to run, in order. Empty means the main method named in the sample's manifest.")
    public List<EntryPoint> entryPoints = new ArrayList<>();

    @ConfigDoc("Budgets that end a run with a named reason instead of letting it hang.")
    public Limits limits = new Limits();

    @ConfigDoc("The pretend machine the sample believes it is running on.")
    public Environment environment = new Environment();

    @ConfigDoc("The simulated network. Nothing is ever really sent.")
    public Network network = new Network();

    @ConfigDoc("Made-up results for programs the sample tries to start. Nothing is ever really started.")
    public List<ProcessRule> processes = new ArrayList<>();

    @ConfigDoc("Changes to which real JDK calls the sample may make.")
    public Policy policy = new Policy();

    @ConfigDoc("Overrides for chosen methods in the sample, applied before they run.")
    public List<Hook> hooks = new ArrayList<>();

    @ConfigDoc("What to record beyond the sample's behaviour.")
    public Trace trace = new Trace();

    @ConfigDoc("How the sample's threads are scheduled. They take turns; only one runs at a time.")
    public Threads threads = new Threads();

    @ConfigDoc("Where and how results are written.")
    public Output output = new Output();

    @ConfigDoc("Settings for plugins, by plugin id. A plugin runs only if it has an entry here.")
    public Map<String, Map<String, Object>> plugins = new LinkedHashMap<>();

    /** One thing to execute. */
    public static final class EntryPoint {
        @ConfigDoc(value = "main runs the main method; method runs one named method; clinit-sweep runs every class's "
                + "start-up code, which makes most obfuscators decrypt their hidden text without running the program.",
                allowed = {"main", "method", "clinit-sweep"})
        public String type = "main";

        @ConfigDoc("The class to run, with dots or slashes. For main it defaults to the manifest's Main-Class.")
        @com.fasterxml.jackson.annotation.JsonProperty("class")
        public String className;

        @ConfigDoc("The method to run. Required when type is method.")
        public String method;

        @ConfigDoc("The method's JVM descriptor, such as (Ljava/lang/String;)V. Needed only when the method name is overloaded.")
        public String descriptor;

        @ConfigDoc("For main: the program arguments. For method: one value per parameter.")
        public List<Object> args = new ArrayList<>();

        @ConfigDoc("For clinit-sweep: class-name patterns to include. Empty means every class.")
        public List<String> include = new ArrayList<>();
    }

    /** Budgets. Each one ends the run with a named reason rather than hanging the analysis. */
    public static final class Limits {
        @ConfigDoc("Bytecode instructions the run may execute before it ends with outcome instruction-limit.")
        public long maxInstructions = 2_000_000_000L;

        @ConfigDoc("Instructions a task may run without doing anything the report records before it is ended "
                + "with outcome silent-loop, so a spin loop cannot use up the whole run. 0 never ends one.")
        public long maxSilentInstructions = 500_000_000L;

        @ConfigDoc("Real seconds the run may take before it ends with outcome timeout.")
        public int timeoutSeconds = 120;

        @ConfigDoc("How deep the sample's calls may nest before it gets a StackOverflowError, as on a real JVM.")
        public int maxCallDepth = 1500;

        @ConfigDoc("Sleeps one thread may perform. Beacon loops sleep for ever, so this is what ends them.")
        public int maxSleeps = 40;

        @ConfigDoc("Threads, timers and background jobs one run may start.")
        public int maxTasks = 512;

        @ConfigDoc("Events kept per category. Further events are counted but not stored.")
        public int maxEventsPerCategory = 20_000;

        @ConfigDoc("Total bytes the sample may write to the pretend disk. The files saved while it runs (classes it "
                + "defines, data it sends, libraries it loads) are held to the same total.")
        public long maxWrittenBytes = 256L * 1024 * 1024;
    }

    /** The fake machine the sample believes it is running on. */
    public static final class Environment {
        @ConfigDoc(value = "The operating system to pretend to be. It decides path style and system properties.",
                allowed = {"windows", "linux"})
        public String os = "windows";

        @ConfigDoc("The user name the sample sees.")
        public String userName = "admin";

        @ConfigDoc("The computer name the sample sees.")
        public String hostName = "DESKTOP-4QK7B2M";

        @ConfigDoc("Where the sample believes it is stored. Droppers copy or restart themselves from here.")
        public String samplePath;

        @ConfigDoc("Extra or changed Java system properties.")
        public Map<String, String> properties = new LinkedHashMap<>();

        @ConfigDoc("Extra or changed environment variables.")
        public Map<String, String> env = new LinkedHashMap<>();

        @ConfigDoc("Files that exist on the pretend disk before the run starts.")
        public List<FileFixture> files = new ArrayList<>();

        @ConfigDoc("Folders that exist on the pretend disk before the run starts.")
        public List<String> directories = new ArrayList<>();

        @ConfigDoc("When the pretend clock starts, as an ISO-8601 time such as 2024-03-01T09:00:00Z. Empty means now.")
        public String clockStart;

        /**
         * The guest's time zone. Without it the sample would see the analysis computer's own
         * zone, which tells it where the sandbox is and changes the report from one computer to
         * the next. The default matches the simulated en-US Windows desktop and is taken from
         * {@link dev.javabyte.env.GuestRegion#DEFAULT_TIME_ZONE}, so the two cannot drift apart.
         */
        @ConfigDoc("The pretend computer's time zone, as a region name such as America/New_York or Europe/London.")
        public String timeZone = dev.javabyte.env.GuestRegion.DEFAULT_TIME_ZONE;

        /**
         * The guest's language and region. Like {@link #timeZone}, it keeps the analysis
         * computer's own settings away from the sample, and it decides the user.language and
         * user.country properties. The default is
         * {@link dev.javabyte.env.GuestRegion#DEFAULT_LOCALE}.
         */
        @ConfigDoc("The pretend computer's language and region, as a language tag such as en-US or de-DE.")
        public String locale = dev.javabyte.env.GuestRegion.DEFAULT_LOCALE;

        @ConfigDoc("Seed for random numbers the sample does not seed itself, so that runs are repeatable. Empty means truly random.")
        public Long randomSeed = 0x4A617661L;

        // The machine's hardware. Samples compare these with thresholds typical of sandboxes
        // (one processor, under 4 GB of memory, a tiny screen), so the defaults describe an
        // ordinary office desktop. The fixed parts of the profile are in MachineProfile.

        @ConfigDoc("The computer maker the sample sees, as in wmic and systeminfo. Sandbox checks look for VMware, VirtualBox or QEMU here.")
        public String manufacturer = "Dell Inc.";

        @ConfigDoc("The computer model the sample sees, as in wmic and systeminfo.")
        public String model = "OptiPlex 7050";

        @ConfigDoc("Logical processors, as reported by Runtime.availableProcessors, NUMBER_OF_PROCESSORS and the management beans.")
        public int processors = 4;

        @ConfigDoc("Installed memory in megabytes, as reported by the management beans, wmic and systeminfo.")
        public int memoryMegabytes = 16384;

        @ConfigDoc("Screen width in pixels, as reported by java.awt.Toolkit and GraphicsEnvironment.")
        public int screenWidth = 1920;

        @ConfigDoc("Screen height in pixels, as reported by java.awt.Toolkit and GraphicsEnvironment.")
        public int screenHeight = 1080;

        @ConfigDoc("The process id of the sample's own Java process. Programs it starts get higher ids.")
        public int processId = 6732;

        @ConfigDoc("Options the Java process appears to have been started with, as RuntimeMXBean.getInputArguments reports. "
                + "Anti-debugging checks look for -agentlib:jdwp or -javaagent here.")
        public List<String> jvmArguments = new ArrayList<>();

        @ConfigDoc("Answer common system commands (whoami, hostname, tasklist, wmic, systeminfo, ipconfig and others) from the "
                + "pretend machine when no processes rule matches. Off means such commands print nothing.")
        public boolean builtInCommands = true;
    }

    /** One pre-existing virtual file. Exactly one content source should be set. */
    public static final class FileFixture {
        @ConfigDoc("Where the file is on the pretend disk.")
        public String path;

        @ConfigDoc("The file's content as text.")
        public String text;

        @ConfigDoc("The file's content as base64, for binary files.")
        public String base64;

        @ConfigDoc("A real file on this computer whose content the pretend file has.")
        public String hostFile;
    }

    /** Simulated network. No packet ever leaves the emulator. */
    public static final class Network {
        @ConfigDoc(value = "respond: connections succeed and get the answers set here or an empty one. offline: "
                + "connections fail as on an unplugged computer.", allowed = {"respond", "offline"})
        public String mode = "respond";

        @ConfigDoc("Answers for web requests. The first rule whose pattern matches the address is used.")
        public List<HttpRule> http = new ArrayList<>();

        @ConfigDoc("Data sent back on raw network connections. The first matching rule is used.")
        public List<SocketRule> sockets = new ArrayList<>();

        @ConfigDoc("Host names and the addresses they look up to. Other names get the default address.")
        public Map<String, String> dns = new LinkedHashMap<>();

        @ConfigDoc("The address every host name not listed under dns looks up to.")
        public String defaultAddress = "203.0.113.10";

        // A home-router LAN, chosen to avoid every hypervisor's default subnet: VirtualBox
        // host-only 192.168.56.0/24 and NAT 10.0.2.0/24, libvirt 192.168.122.0/24, and the
        // VMware DHCP ranges. The gateway and DNS server are taken to be .1 of the same /24.
        @ConfigDoc("The pretend computer's own network address, on a /24 network whose router is the .1 address.")
        public String localAddress = "192.168.1.23";

        // D4:BE:D9 is registered to Dell Inc., matching the default manufacturer. Hypervisor
        // prefixes such as 08:00:27 (VirtualBox) or 00:50:56 (VMware) give a sandbox away.
        @ConfigDoc("The pretend computer's network card address. Sandbox checks often look at it.")
        public String macAddress = "D4:BE:D9:5E:3A:9C";
    }

    /** Answer for HTTP(S) requests whose URL matches. First matching rule wins. */
    public static final class HttpRule {
        @ConfigDoc("A regular expression searched for in the full web address. Required.")
        public String url;

        @ConfigDoc("The HTTP status code of the answer.")
        public int status = 200;

        @ConfigDoc("Headers of the answer.")
        public Map<String, String> headers = new LinkedHashMap<>();

        @ConfigDoc("The answer's body as text.")
        public String body;

        @ConfigDoc("The answer's body as base64, for binary data.")
        public String bodyBase64;

        @ConfigDoc("A real file on this computer whose content is the answer's body.")
        public String bodyFile;
    }

    /** Bytes a raw socket connection receives after connecting. */
    public static final class SocketRule {
        @ConfigDoc("A regular expression matched against host:port. Required.")
        public String endpoint;

        @ConfigDoc("Data sent back, as text.")
        public String text;

        @ConfigDoc("Data sent back, as base64.")
        public String base64;

        @ConfigDoc("A real file on this computer whose content is sent back.")
        public String file;
    }

    /** Answer for a process launch whose command line matches. */
    public static final class ProcessRule {
        @ConfigDoc("A regular expression searched for in the command line. Required.")
        public String command;

        @ConfigDoc("What the pretend program prints.")
        public String stdout = "";

        @ConfigDoc("What the pretend program prints as errors.")
        public String stderr = "";

        @ConfigDoc("The pretend program's exit code.")
        public int exitCode;
    }

    /** Adjustments to the built-in host allow-list. See {@code default-policy.txt} for the syntax. */
    public static final class Policy {
        @ConfigDoc("JDK members to allow in addition to the built-in list. Allow only code with no effect outside the process.")
        public List<String> allow = new ArrayList<>();

        @ConfigDoc("JDK members to refuse even though the built-in list allows them.")
        public List<String> deny = new ArrayList<>();

        @ConfigDoc(value = "What a refused JDK call does. phantom: record it and give back a harmless value so the run "
                + "continues. throw: give the sample a SecurityException. abort: end the run.",
                allowed = {"phantom", "throw", "abort"})
        public String onBlocked = "phantom";
    }

    /** Override for methods matching a glob such as {@code com/evil/AntiVm.check()Z}. */
    public static final class Hook {
        @ConfigDoc("Which methods, as a pattern such as com/example/Checks.isSandbox()Z; * matches anything. Required.")
        public String method;

        @ConfigDoc(value = "return: skip the method and give back value. skip: do nothing. throw: raise the exception "
                + "class named in value. trace: run it normally and record its calls.",
                allowed = {"return", "skip", "throw", "trace"})
        public String action = "return";

        @ConfigDoc("For return: the value to give back. For throw: the exception class name.")
        public Object value;
    }

    /** What to record beyond behavioural events. */
    public static final class Trace {
        @ConfigDoc(value = "Which methods have the text they return recorded. static catches decryption helpers without "
                + "flooding the report; all includes instance methods; none turns it off.",
                allowed = {"static", "all", "none"})
        public String strings = "static";

        @ConfigDoc("Patterns of methods whose calls, arguments and results are recorded.")
        public List<String> methods = new ArrayList<>();

        @ConfigDoc("Patterns of methods recorded instruction by instruction in the trace. Very verbose.")
        public List<String> instructions = new ArrayList<>();

        @ConfigDoc("Record every call into the real JDK. Verbose; useful when extending the allow-list.")
        public boolean hostCalls;

        @ConfigDoc("How much of a value an event shows: characters of a recorded string, bytes of cipher output "
                + "and of what was hashed, MACed or fed to a key derivation. Longer values are cut and marked.")
        public int previewLength = 2000;
    }

    /** How guest threads are scheduled. They are interleaved; only one runs at any moment. */
    public static final class Threads {
        @ConfigDoc(value = "deferred: a new thread first runs when the current one waits or uses up its turn. inline: "
                + "a new thread runs at once.", allowed = {"deferred", "inline"})
        public String mode = "deferred";

        @ConfigDoc("How many times a repeating timer or scheduled job is run.")
        public int periodicRuns = 1;

        @ConfigDoc("Whether the sample's shutdown code runs at the end.")
        public boolean runShutdownHooks = true;
    }

    /** Where results go. */
    public static final class Output {
        @ConfigDoc("The folder for the report, trace and saved files.")
        public String directory = "javabyte-out";

        @ConfigDoc("Save every class the sample creates while running, in defined-classes/.")
        public boolean dumpDefinedClasses = true;

        @ConfigDoc("Save every file the sample writes, in dropped/.")
        public boolean dumpDroppedFiles = true;

        @ConfigDoc(value = "What the run command prints. text: a readable summary. json: the report. quiet: nothing.",
                allowed = {"text", "json", "quiet"})
        public String console = "text";
    }
}
