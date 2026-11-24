package dev.javabyte.stubs;

import dev.javabyte.env.GuestEnvironment;
import dev.javabyte.env.SimClassLoader;
import dev.javabyte.env.SimDataConnection;
import dev.javabyte.env.SimFileChannel;
import dev.javabyte.env.SimFuture;
import dev.javabyte.env.SimHttpConnection;
import dev.javabyte.env.SimPackage;
import dev.javabyte.env.SimPath;
import dev.javabyte.env.SimPlatformClassLoader;
import dev.javabyte.env.SimProcess;
import dev.javabyte.env.SimSslSocket;
import dev.javabyte.env.SimSslSocketFactory;
import dev.javabyte.host.StandIn;
import dev.javabyte.host.StubRegistry;
import dev.javabyte.vm.EmulationAbort;
import dev.javabyte.vm.VirtualMachine;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * {@code System}, {@code Runtime} and a few JVM-introspection APIs.
 *
 * <p>These are the calls through which a program learns about its machine or affects the JVM
 * as a whole. Every answer comes from {@link GuestEnvironment} and its machine profile; every
 * effect (exit, library load, shutdown hook) is recorded instead of performed. The management
 * beans and the screen have their own stub classes, {@link ManagementStubs} and
 * {@link ScreenStubs}, installed from here.
 */
final class SystemStubs {

    private SystemStubs() {}

    /** Installs this class's stubs on the machine; called once per run from {@code BuiltinStubs}. */
    static void install(VirtualMachine vm) {
        StubRegistry stubs = vm.stubs;
        GuestEnvironment env = vm.env;
        String system = "java/lang/System";

        stubs.on(system, "getProperty", ctx -> {
            String key = ctx.stringArg(0);
            String value = env.properties.getProperty(key);
            // Which properties a sample reads is a good fingerprint of its environment checks.
            vm.events.addOnce("prop:" + key, "system", "property", "key", key, "value", String.valueOf(value));
            return value != null ? value : ctx.argCount() > 1 ? ctx.arg(1) : null;
        });
        stubs.on(system, "setProperty", ctx -> env.properties.setProperty(ctx.stringArg(0), ctx.stringArg(1)));
        stubs.on(system, "clearProperty", ctx -> env.properties.remove(ctx.stringArg(0)));
        stubs.on(system, "getProperties", ctx -> env.properties);
        // Integer.getInteger, Long.getLong and Boolean.getBoolean read system properties too.
        // Run for real they would read the analysis JVM's, not the guest's.
        stubs.on("java/lang/Boolean", "getBoolean", ctx ->
                ctx.arg(0) != null && Boolean.parseBoolean(env.properties.getProperty(ctx.stringArg(0))) ? 1 : 0);
        stubs.on("java/lang/Integer", "getInteger", ctx -> numberProperty(env, ctx, false));
        stubs.on("java/lang/Long", "getLong", ctx -> numberProperty(env, ctx, true));
        stubs.on(system, "getenv", ctx -> {
            if (ctx.argCount() == 0) {
                return Collections.unmodifiableMap(new LinkedHashMap<>(env.variables));
            }
            String value = env.variables.get(ctx.stringArg(0));
            vm.events.addOnce("env:" + ctx.stringArg(0), "system", "getenv", "name", ctx.stringArg(0), "value", String.valueOf(value));
            return value;
        });
        stubs.on(system, "currentTimeMillis", ctx -> env.clock.currentTimeMillis());
        stubs.on(system, "nanoTime", ctx -> env.clock.nanoTime());
        stubs.on(system, "lineSeparator", ctx -> env.lineSeparator());
        stubs.on(system, "exit", ctx -> exit(vm, ctx.intArg(0)));
        stubs.on(system, "load", ctx -> loadLibrary(vm, ctx.stringArg(0), false));
        stubs.on(system, "loadLibrary", ctx -> loadLibrary(vm, ctx.stringArg(0), true));
        stubs.on(system, "mapLibraryName", ctx -> env.windows ? ctx.stringArg(0) + ".dll" : "lib" + ctx.stringArg(0) + ".so");
        stubs.on(system, "getSecurityManager", ctx -> null);
        stubs.on(system, "setSecurityManager", ctx -> null);
        stubs.on(system, "console", ctx -> null);
        stubs.on(system, "gc", ctx -> null);
        stubs.on(system, "runFinalization", ctx -> null);
        // Redirecting the standard streams would only affect the emulator's own console.
        stubs.on(system, "setOut", ctx -> null);
        stubs.on(system, "setErr", ctx -> null);
        stubs.on(system, "setIn", ctx -> null);
        stubs.staticField(system, "out", () -> env.stdout);
        stubs.staticField(system, "err", () -> env.stderr);
        stubs.staticField(system, "in", () -> new ByteArrayInputStream(new byte[0]));

        installRuntime(vm);
        installLegacyCodecs(stubs);
        ManagementStubs.install(vm);
        ScreenStubs.install(vm);
        installPackages(vm);
        installImitations(vm);

        stubs.on("java/lang/Object", "getClass", ctx -> classOf(vm, ctx.receiver));
        // The no-argument form writes to the real System.err; send it to the guest's instead,
        // where it is captured. The overloads that take a stream need no help.
        stubs.on("java/lang/Throwable", "printStackTrace", "()V", ctx -> {
            if (ctx.receiver instanceof Throwable throwable) {
                throwable.printStackTrace(env.stderr);
            }
            return null;
        });
    }

    /**
     * {@code Integer.getInteger} and {@code Long.getLong} in all their overloads: the guest's
     * property decoded as a number, else the default given (a boxed number or a primitive),
     * else null.
     */
    private static Object numberProperty(GuestEnvironment env, dev.javabyte.host.CallContext ctx, boolean asLong) {
        String key = ctx.stringArg(0);
        String value = key == null || key.isEmpty() ? null : env.properties.getProperty(key);
        if (value != null) {
            try {
                return asLong ? (Object) Long.decode(value) : (Object) Integer.decode(value);
            } catch (NumberFormatException notANumber) {
                // falls through to the default, as the JDK does
            }
        }
        if (ctx.argCount() < 2) {
            return null;
        }
        // A primitive default arrives in interpreter form and the method returns a boxed value.
        Object fallback = ctx.arg(1);
        boolean primitive = ctx.desc.contains(asLong ? ";J)" : ";I)");
        return primitive && fallback instanceof Number number
                ? (asLong ? (Object) Long.valueOf(number.longValue()) : (Object) Integer.valueOf(number.intValue())) : fallback;
    }

    private static Object exit(VirtualMachine vm, int status) {
        vm.events.add("system", "exit", "status", status);
        throw new EmulationAbort(EmulationAbort.Reason.EXIT, "System.exit(" + status + ")");
    }

    /**
     * Records a library load. Native code cannot be interpreted, but the library is usually a
     * payload the sample has just dropped, so it is saved and its JNI surface is described:
     * that tells the analyst which native methods it implements and how it binds them.
     */
    private static Object loadLibrary(VirtualMachine vm, String requested, boolean byName) {
        String library = byName ? findOnLibraryPath(vm, requested) : requested;
        byte[] data = library != null && vm.env.files.isFile(library) ? vm.env.files.node(library).data : null;
        if (data == null) {
            // Failing as a JVM would matters: libraries that bundle their own native code try
            // the system first and only unpack their copy once that attempt has failed.
            vm.events.add("native", "load-library", "library", requested, "presentOnVirtualDisk", false,
                    "result", "UnsatisfiedLinkError, as on a machine without the library");
            throw vm.raise(new UnsatisfiedLinkError(byName
                    ? "no " + requested + " in java.library.path" : "Can't load library: " + requested));
        }
        dev.javabyte.report.NativeLibraryInfo info = dev.javabyte.report.NativeLibraryInfo.parse(data);
        vm.events.add("native", "load-library", "library", library,
                "presentOnVirtualDisk", true,
                "sha256", VirtualMachine.sha256(data),
                "size", data.length,
                "format", info == null ? null : info.format + ' ' + info.architecture,
                "binding", info == null ? null : info.binding(),
                "file", vm.artifacts.save("native", vm.env.files.name(library), data));
        // After the event, so that anything a plugin reports while emulating the library's
        // start-up code appears below the load it belongs to.
        vm.natives.libraryLoaded(library, info, data);
        return null;
    }

    /** Resolves a bare library name the way {@code System.loadLibrary} does; null if not found. */
    private static String findOnLibraryPath(VirtualMachine vm, String name) {
        String fileName = vm.env.windows ? name + ".dll" : "lib" + name + ".so";
        String separator = vm.env.properties.getProperty("path.separator");
        for (String directory : vm.env.properties.getProperty("java.library.path", "").split(java.util.regex.Pattern.quote(separator))) {
            String candidate = vm.env.files.join(directory, fileName);
            if (!directory.isEmpty() && vm.env.files.isFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static void installRuntime(VirtualMachine vm) {
        vm.stubs.onClass("java/lang/Runtime", ctx -> {
            switch (ctx.name) {
                case "getRuntime":
                    // The real object is only a token: every method on it is answered here.
                    return Runtime.getRuntime();
                // Processors and heap sizes follow the machine, as a real JVM's defaults do.
                case "availableProcessors":
                    return vm.env.machine.processors;
                case "totalMemory":
                    return ManagementStubs.initialHeap(vm.env.machine);
                case "freeMemory":
                    return ManagementStubs.freeHeap(vm.env.machine);
                case "maxMemory":
                    return ManagementStubs.maxHeap(vm.env.machine);
                case "version":
                    return runtimeVersion(vm.env);
                case "exit", "halt":
                    return exit(vm, ctx.intArg(0));
                case "load", "loadLibrary":
                    return loadLibrary(vm, ctx.stringArg(0), ctx.name.equals("loadLibrary"));
                case "exec":
                    return ProcessStubs.exec(vm, ctx.args);
                case "addShutdownHook": {
                    Object hook = ctx.arg(0);
                    vm.events.add("thread", "shutdown-hook-registered", "class", vm.types.nameOf(hook));
                    vm.scheduler.addShutdownHook("shutdown-hook:" + vm.types.nameOf(hook), hook,
                            () -> vm.router.invokeVirtual("java/lang/Thread", "run", "()V", hook, new Object[0]));
                    return null;
                }
                case "removeShutdownHook":
                    return 1;
                case "gc", "runFinalization", "traceInstructions", "traceMethodCalls":
                    return null;
                case "hashCode":
                    return 0x5ca1ab1e;
                case "toString":
                    return "java.lang.Runtime@5ca1ab1e";
                case "equals":
                    return ctx.receiver == ctx.arg(0) ? 1 : 0;
                default:
                    return vm.router.blocked(ctx.owner, ctx.name, ctx.desc, "method");
            }
        });
    }

    /**
     * Base64 and hex helpers that existed in Java 8 and were later removed. Malware written for
     * Java 8 uses them constantly, so they are provided on top of {@code java.util.Base64}.
     */
    private static void installLegacyCodecs(StubRegistry stubs) {
        for (String codec : List.of("sun/misc/BASE64Decoder", "sun/misc/BASE64Encoder")) {
            stubs.on(codec, "<init>", ctx -> new LegacyBase64(codec));
        }
        stubs.onClass("javax/xml/bind/DatatypeConverter", ctx -> {
            switch (ctx.name) {
                case "parseBase64Binary":
                    return Base64.getMimeDecoder().decode(ctx.stringArg(0));
                case "printBase64Binary":
                    return Base64.getEncoder().encodeToString((byte[]) ctx.arg(0));
                case "parseHexBinary":
                    return HexFormat.of().parseHex(ctx.stringArg(0));
                case "printHexBinary":
                    return HexFormat.of().withUpperCase().formatHex((byte[]) ctx.arg(0));
                default:
                    return ctx.vm.router.gap("DatatypeConverter." + ctx.name, ctx.desc);
            }
        });
    }

    /** Look-alike for the removed {@code sun.misc.BASE64Decoder}/{@code BASE64Encoder}. */
    public static final class LegacyBase64 implements StandIn {
        private final String type;

        LegacyBase64(String type) {
            this.type = type;
        }

        @Override
        public String standsFor() {
            return type;
        }

        public byte[] decodeBuffer(String text) {
            return Base64.getMimeDecoder().decode(text);
        }

        public String encode(byte[] data) {
            return Base64.getMimeEncoder().encodeToString(data);
        }

        public String encodeBuffer(byte[] data) {
            return Base64.getMimeEncoder().encodeToString(data) + "\n";
        }
    }

    /**
     * {@code Runtime.version()}, which arrived in Java 9. The advertised Java 8 runtime has no
     * such method, but a sample that calls it was built for a newer Java and should still see
     * the advertised version rather than a phantom whose every number is 0. So 1.8.0_361 build
     * 9 becomes 8.0.361+9. A {@code java.version} set in the config in the modern form, such as
     * 11.0.2, is used as it is.
     */
    static Runtime.Version runtimeVersion(GuestEnvironment env) {
        String version = env.properties.getProperty("java.version", "");
        java.util.regex.Matcher legacy = java.util.regex.Pattern.compile("1\\.(\\d+)\\.0_(\\d+)").matcher(version);
        if (legacy.matches()) {
            java.util.regex.Matcher build = java.util.regex.Pattern.compile("-b(\\d+)$")
                    .matcher(env.properties.getProperty("java.runtime.version", ""));
            return Runtime.Version.parse(legacy.group(1) + ".0." + legacy.group(2)
                    + (build.find() ? "+" + Integer.parseInt(build.group(1)) : ""));
        }
        try {
            return Runtime.Version.parse(version);
        } catch (IllegalArgumentException unparseable) {
            return Runtime.Version.parse("8");
        }
    }

    /**
     * Package metadata from the sample's manifest. A real JVM fills {@code Package} from the
     * jar's {@code META-INF/MANIFEST.MF}: first from the package's own section (named after the
     * package with slashes and a trailing slash), then from the main section. Samples read their
     * own version or campaign id this way, so the answer must match what they would see.
     *
     * <p>Guest classes get a {@link SimPackage}. Its attribute methods are answered here because
     * the manifest belongs to the run, not to the package object.
     */
    private static void installPackages(VirtualMachine vm) {
        Map<String, String> methods = Map.of(
                "getImplementationTitle", "Implementation-Title",
                "getImplementationVersion", "Implementation-Version",
                "getImplementationVendor", "Implementation-Vendor",
                "getSpecificationTitle", "Specification-Title",
                "getSpecificationVersion", "Specification-Version",
                "getSpecificationVendor", "Specification-Vendor");
        Manifest[] manifest = new Manifest[1];
        java.util.function.Supplier<Manifest> sampleManifest = () -> {
            if (manifest[0] == null) {
                manifest[0] = SimPackage.readManifest(vm.classes.resource("META-INF/MANIFEST.MF"));
            }
            return manifest[0];
        };
        methods.forEach((method, attribute) -> vm.stubs.on("java/lang/Package", method, ctx ->
                ctx.receiver instanceof SimPackage pkg ? pkg.attribute(sampleManifest.get(), attribute) : ctx.proceed()));
        vm.stubs.on("java/lang/Package", "isSealed", "()Z", ctx -> ctx.receiver instanceof SimPackage pkg
                ? ("true".equalsIgnoreCase(pkg.attribute(sampleManifest.get(), Attributes.Name.SEALED.toString())) ? 1 : 0)
                : ctx.proceed());
    }

    /**
     * {@code getClass()} for values that are not instances of interpreted classes. Emulator
     * objects report the JDK class they stand for, never their own class or a generated proxy
     * class. Used for every {@code getClass()} call, whichever class or interface the call names.
     * The answer is {@link dev.javabyte.vm.TypeSystem#reportedMirror}, which follows the same
     * imitation rules that name classes in exception text, so the two always agree; for a
     * class the analysis JDK lacks, such as Java 8's {@code sun.misc.Launcher$AppClassLoader},
     * it is a modelled class object with the real name and superclass.
     */
    static Object classOf(VirtualMachine vm, Object value) {
        return vm.types.reportedMirror(value);
    }

    /**
     * True when the guest environment advertises Java 8 or older. Read on each call: the
     * properties are filled in after stubs are installed, and config can change them.
     */
    static boolean legacyJava(GuestEnvironment env) {
        return env.properties.getProperty("java.specification.version", "1.8").startsWith("1.");
    }

    /**
     * Tells the type system which concrete JDK class each simulated object replaces, for
     * simulations whose nearest JDK superclass is abstract or {@code Object} and so would give
     * the emulator away. A real JVM returns the platform's implementation class for the Java
     * version the guest environment advertises. Where the analysis JDK lacks that class (a
     * Windows class on a Linux host, or a Java 8 class on a newer JDK), the type system falls
     * back to the next candidate, then to the first JDK superclass; exception text still names
     * the advertised class.
     */
    private static void installImitations(VirtualMachine vm) {
        vm.types.addImitation(value -> {
            String bean = ManagementStubs.imitatedClass(value);
            return bean == null ? null : new String[] {bean};
        });
        // The built-in loaders are URLClassLoaders, as Java 8's are; on a newer advertised Java
        // the class they imitate is not one, and casts and instanceof must say so.
        vm.types.addImitation(value -> imitatedLoader(vm, value), true);
        vm.types.addImitation(value -> imitatedBySimulation(vm, value));
        // Java 8's loader classes do not exist on the analysis JDK. Modelled, getClass() on the
        // loaders shows their real names and superclass. Both are static nested classes.
        vm.types.modelAbsentClass("sun/misc/Launcher$AppClassLoader", "java/net/URLClassLoader",
                "sun/misc/Launcher", org.objectweb.asm.Opcodes.ACC_STATIC);
        vm.types.modelAbsentClass("sun/misc/Launcher$ExtClassLoader", "java/net/URLClassLoader",
                "sun/misc/Launcher", org.objectweb.asm.Opcodes.ACC_STATIC);
    }

    /**
     * The class a built-in loader imitates, best first; null for any other value. Java 8 has
     * {@code sun.misc.Launcher}'s loaders, both {@code URLClassLoader}s; Java 9 and later have
     * {@code jdk.internal.loader}'s, which are not. These candidates also decide type checks
     * on the loaders (see {@link dev.javabyte.vm.TypeSystem#addImitation(java.util.function.Function, boolean)}).
     */
    private static String[] imitatedLoader(VirtualMachine vm, Object value) {
        if (value instanceof SimClassLoader) {
            return legacyJava(vm.env) ? new String[] {"sun/misc/Launcher$AppClassLoader", "java/net/URLClassLoader"}
                    : new String[] {"jdk/internal/loader/ClassLoaders$AppClassLoader", "java/lang/ClassLoader"};
        }
        if (value instanceof SimPlatformClassLoader) {
            return legacyJava(vm.env) ? new String[] {"sun/misc/Launcher$ExtClassLoader", "java/net/URLClassLoader"}
                    : new String[] {"jdk/internal/loader/ClassLoaders$PlatformClassLoader", "java/lang/ClassLoader"};
        }
        return null;
    }

    /**
     * The candidate JDK classes, best first, that a simulated object replaces; null for
     * anything else. See {@link #installImitations}.
     */
    private static String[] imitatedBySimulation(VirtualMachine vm, Object value) {
        GuestEnvironment env = vm.env;
        if (value instanceof SimPath) {
            return env.windows ? new String[] {"sun/nio/fs/WindowsPath", "sun/nio/fs/UnixPath"}
                    : new String[] {"sun/nio/fs/UnixPath", "sun/nio/fs/WindowsPath"};
        }
        if (value instanceof SimFuture<?>) {
            return new String[] {"java/util/concurrent/ScheduledThreadPoolExecutor$ScheduledFutureTask",
                "java/util/concurrent/FutureTask"};
        }
        if (value instanceof SimProcess) {
            // Java 8 on Linux names its process class UNIXProcess; Windows and Java 9+ use ProcessImpl.
            return !env.windows && legacyJava(env) ? new String[] {"java/lang/UNIXProcess", "java/lang/ProcessImpl"}
                    : new String[] {"java/lang/ProcessImpl"};
        }
        if (value instanceof SimFileChannel) {
            return new String[] {"sun/nio/ch/FileChannelImpl"};
        }
        if (value instanceof SimSslSocket) {
            return new String[] {"sun/security/ssl/SSLSocketImpl"};
        }
        if (value instanceof SimSslSocketFactory) {
            return new String[] {"sun/security/ssl/SSLSocketFactoryImpl"};
        }
        if (value instanceof SimHttpConnection connection) {
            return "https".equals(connection.getURL().getProtocol())
                    ? new String[] {"sun/net/www/protocol/https/HttpsURLConnectionImpl"}
                    : new String[] {"sun/net/www/protocol/http/HttpURLConnection"};
        }
        if (value instanceof SimHttpConnection.Plain) {
            return new String[] {"sun/net/www/protocol/http/HttpURLConnection"};
        }
        if (value instanceof SimDataConnection connection) {
            return "jar".equals(connection.getURL().getProtocol())
                    ? new String[] {"sun/net/www/protocol/jar/JarURLConnection"}
                    : new String[] {"sun/net/www/protocol/file/FileURLConnection"};
        }
        return null;
    }
}
