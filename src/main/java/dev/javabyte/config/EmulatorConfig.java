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
 * from YAML or JSON and the defaults sit next to the fields they belong to. Unknown keys are
 * rejected by {@link ConfigLoader}, so a misspelt option fails loudly instead of being ignored.
 */
public final class EmulatorConfig {

    /** Path to the code under analysis: a {@code .jar}/{@code .zip}, a single {@code .class}, or a directory. */
    public String sample;

    /** Extra jars or directories whose classes are interpreted alongside the sample (bundled libraries). */
    public List<String> classpath = new ArrayList<>();

    /** What to execute, in order. Empty means "the manifest's Main-Class". */
    public List<EntryPoint> entryPoints = new ArrayList<>();

    public Limits limits = new Limits();
    public Environment environment = new Environment();
    public Network network = new Network();

    /** Canned answers for process launches. Launches are never performed, only recorded. */
    public List<ProcessRule> processes = new ArrayList<>();

    public Policy policy = new Policy();

    /** Per-method overrides applied before normal dispatch. */
    public List<Hook> hooks = new ArrayList<>();

    public Trace trace = new Trace();
    public Threads threads = new Threads();
    public Output output = new Output();

    /** Free-form settings handed to plugins, keyed by plugin id. */
    public Map<String, Map<String, Object>> plugins = new LinkedHashMap<>();

    /** One thing to execute. */
    public static final class EntryPoint {
        /**
         * {@code main}: run {@code public static void main(String[])}.
         * {@code method}: run one named method.
         * {@code clinit-sweep}: run every class's static initialiser, which makes most obfuscators
         * decrypt their string tables without running any program logic.
         */
        public String type = "main";

        /** Class name, dotted or slashed. For {@code main} it defaults to the manifest Main-Class. */
        @com.fasterxml.jackson.annotation.JsonProperty("class")
        public String className;

        public String method;

        /** JVM descriptor, needed only when {@link #method} is overloaded. */
        public String descriptor;

        /** Arguments. For {@code main} these are the program arguments. */
        public List<Object> args = new ArrayList<>();

        /** For {@code clinit-sweep}: class-name globs to include. Empty means all classes. */
        public List<String> include = new ArrayList<>();
    }

    /** Budgets. Each one ends the run with a named reason rather than hanging the analysis. */
    public static final class Limits {
        public long maxInstructions = 2_000_000_000L;
        public int timeoutSeconds = 120;
        /** Guest call depth before a guest StackOverflowError is raised. */
        public int maxCallDepth = 1500;
        /** Sleeps one task may perform. Beacon loops sleep forever, so this is what ends them. */
        public int maxSleeps = 40;
        /** Tasks (threads, timers, executor jobs) one run may queue. */
        public int maxTasks = 512;
        /** Events kept per category; further events are counted but not stored. */
        public int maxEventsPerCategory = 20_000;
        /** Total bytes the sample may write to the virtual file system. */
        public long maxWrittenBytes = 256L * 1024 * 1024;
    }

    /** The fake machine the sample believes it is running on. */
    public static final class Environment {
        /** {@code windows} or {@code linux}. Controls path syntax and default properties. */
        public String os = "windows";
        public String userName = "admin";
        public String hostName = "DESKTOP-4QK7B2M";
        /** Virtual location of the sample. Droppers copy or relaunch themselves from this path. */
        public String samplePath;
        /** Overrides and additions for {@code System.getProperty}. */
        public Map<String, String> properties = new LinkedHashMap<>();
        /** Overrides and additions for {@code System.getenv}. */
        public Map<String, String> env = new LinkedHashMap<>();
        /** Files that exist before the run starts. */
        public List<FileFixture> files = new ArrayList<>();
        /** Directories that exist before the run starts. */
        public List<String> directories = new ArrayList<>();
        /** Start of the virtual clock, ISO-8601. Null means the real current time. */
        public String clockStart;
    }

    /** One pre-existing virtual file. Exactly one content source should be set. */
    public static final class FileFixture {
        public String path;
        public String text;
        public String base64;
        /** Real file on the analysis machine whose bytes back the virtual file. */
        public String hostFile;
    }

    /** Simulated network. No packet ever leaves the emulator. */
    public static final class Network {
        /**
         * {@code respond}: connections succeed and get a configured or empty answer.
         * {@code offline}: connections fail the way an unplugged machine would.
         */
        public String mode = "respond";
        public List<HttpRule> http = new ArrayList<>();
        public List<SocketRule> sockets = new ArrayList<>();
        /** Host name to IPv4 address. Unlisted names resolve to {@link #defaultAddress}. */
        public Map<String, String> dns = new LinkedHashMap<>();
        public String defaultAddress = "203.0.113.10";
        public String localAddress = "192.168.56.101";
        public String macAddress = "00:1B:44:11:3A:B7";
    }

    /** Answer for HTTP(S) requests whose URL matches. First matching rule wins. */
    public static final class HttpRule {
        /** Regular expression searched for in the full URL. */
        public String url;
        public int status = 200;
        public Map<String, String> headers = new LinkedHashMap<>();
        public String body;
        public String bodyBase64;
        public String bodyFile;
    }

    /** Bytes a raw socket connection receives after connecting. */
    public static final class SocketRule {
        /** Regular expression matched against {@code host:port}. */
        public String endpoint;
        public String text;
        public String base64;
        public String file;
    }

    /** Answer for a process launch whose command line matches. */
    public static final class ProcessRule {
        /** Regular expression searched for in the joined command line. */
        public String command;
        public String stdout = "";
        public String stderr = "";
        public int exitCode;
    }

    /** Adjustments to the built-in host allow-list. See {@code default-policy.txt} for the syntax. */
    public static final class Policy {
        public List<String> allow = new ArrayList<>();
        public List<String> deny = new ArrayList<>();
        /**
         * What a blocked or unknown host call does.
         * {@code phantom}: record it and return a neutral value so the run continues.
         * {@code throw}: raise a guest SecurityException.
         * {@code abort}: stop the run.
         */
        public String onBlocked = "phantom";
    }

    /** Override for methods matching a glob such as {@code com/evil/AntiVm.check()Z}. */
    public static final class Hook {
        /** Glob over {@code owner.name} or {@code owner.namedescriptor}; {@code *} matches anything. */
        public String method;
        /** {@code return}, {@code skip}, {@code throw} or {@code trace}. */
        public String action = "return";
        /** For {@code return}: the value. For {@code throw}: the exception class name. */
        public Object value;
    }

    /** What to record beyond behavioural events. */
    public static final class Trace {
        /**
         * Which guest methods have their String results recorded.
         * {@code static} (default) catches string-decryption helpers without flooding the log;
         * {@code all} also records instance methods; {@code none} turns it off.
         */
        public String strings = "static";
        /** Globs of guest methods whose calls, arguments and results are logged. */
        public List<String> methods = new ArrayList<>();
        /** Globs of guest methods that are single-stepped into the trace log. */
        public List<String> instructions = new ArrayList<>();
        /** Record every call into the real JDK. Verbose; useful when extending the allow-list. */
        public boolean hostCalls;
    }

    /** How concurrency is flattened onto the single emulator thread. */
    public static final class Threads {
        /**
         * {@code deferred}: started threads are queued and run when the starter blocks or returns.
         * {@code inline}: {@code start()} runs the thread body immediately.
         */
        public String mode = "deferred";
        /** How many times a repeating timer or scheduled task body is run. */
        public int periodicRuns = 1;
        /** Whether registered shutdown hooks are executed at the end of the run. */
        public boolean runShutdownHooks = true;
    }

    /** Where results go. */
    public static final class Output {
        public String directory = "javabyte-out";
        /** Write every class the sample defines at run time to {@code defined-classes/}. */
        public boolean dumpDefinedClasses = true;
        /** Write every file the sample creates to {@code dropped/}. */
        public boolean dumpDroppedFiles = true;
        /** {@code text}: readable summary. {@code json}: the report on stdout. {@code quiet}: nothing. */
        public String console = "text";
    }
}
