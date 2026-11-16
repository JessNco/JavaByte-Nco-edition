package dev.javabyte.stubs;

import dev.javabyte.env.GuestEnvironment;
import dev.javabyte.host.HostBridge;
import dev.javabyte.host.StandIn;
import dev.javabyte.host.StubRegistry;
import dev.javabyte.model.GuestClass;
import dev.javabyte.model.GuestLambda;
import dev.javabyte.model.Mirrors;
import dev.javabyte.model.PhantomObject;
import dev.javabyte.vm.EmulationAbort;
import dev.javabyte.vm.VirtualMachine;
import java.io.ByteArrayInputStream;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Proxy;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * {@code System}, {@code Runtime} and a few JVM-introspection APIs.
 *
 * <p>These are the calls through which a program learns about its machine or affects the JVM
 * as a whole. Every answer comes from {@link GuestEnvironment}; every effect (exit, library
 * load, shutdown hook) is recorded instead of performed.
 */
final class SystemStubs {

    private SystemStubs() {}

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
        stubs.on(system, "load", ctx -> loadLibrary(vm, ctx.stringArg(0)));
        stubs.on(system, "loadLibrary", ctx -> loadLibrary(vm, ctx.stringArg(0)));
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
        installManagement(vm);

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

    private static Object exit(VirtualMachine vm, int status) {
        vm.events.add("system", "exit", "status", status);
        throw new EmulationAbort(EmulationAbort.Reason.EXIT, "System.exit(" + status + ")");
    }

    /**
     * Records a library load. Native code cannot be interpreted, but the library is usually a
     * payload the sample has just dropped, so it is saved and its JNI surface is described:
     * that tells the analyst which native methods it implements and how it binds them.
     */
    private static Object loadLibrary(VirtualMachine vm, String library) {
        byte[] data = vm.env.files.isFile(library) ? vm.env.files.node(library).data : null;
        dev.javabyte.report.NativeLibraryInfo info = data == null ? null : dev.javabyte.report.NativeLibraryInfo.parse(data);
        if (info != null) {
            vm.natives.libraryLoaded(library, info);
        }
        vm.events.add("native", "load-library", "library", library,
                "presentOnVirtualDisk", data != null,
                "sha256", data == null ? null : VirtualMachine.sha256(data),
                "size", data == null ? null : data.length,
                "format", info == null ? null : info.format + ' ' + info.architecture,
                "binding", info == null ? null : info.binding(),
                "file", data == null ? null : vm.artifacts.save("native", vm.env.files.name(library), data));
        return null;
    }

    private static void installRuntime(VirtualMachine vm) {
        vm.stubs.onClass("java/lang/Runtime", ctx -> {
            switch (ctx.name) {
                case "getRuntime":
                    // The real object is only a token: every method on it is answered here.
                    return Runtime.getRuntime();
                case "availableProcessors":
                    return 4;
                case "totalMemory":
                    return 512L << 20;
                case "freeMemory":
                    return 300L << 20;
                case "maxMemory":
                    return 4096L << 20;
                case "exit", "halt":
                    return exit(vm, ctx.intArg(0));
                case "load", "loadLibrary":
                    return loadLibrary(vm, ctx.stringArg(0));
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

    /** Runtime bean used by anti-debugging checks, which look for agent or debugger arguments. */
    private static void installManagement(VirtualMachine vm) {
        vm.stubs.on("java/lang/management/ManagementFactory", "getRuntimeMXBean", ctx ->
                Proxy.newProxyInstance(SystemStubs.class.getClassLoader(), new Class<?>[] {RuntimeMXBean.class},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "getInputArguments":
                                    return List.of();
                                case "getName":
                                    return "4242@" + vm.config.environment.hostName;
                                case "getVmName":
                                    return vm.env.properties.getProperty("java.vm.name");
                                case "getVmVendor", "getSpecVendor":
                                    return vm.env.properties.getProperty("java.vendor");
                                case "getVmVersion", "getSpecVersion":
                                    return vm.env.properties.getProperty("java.version");
                                case "getClassPath":
                                    return vm.env.samplePath();
                                case "getStartTime":
                                    return vm.env.clock.currentTimeMillis() - 60_000L;
                                case "getUptime":
                                    return 60_000L;
                                case "getPid":
                                    return 4242L;
                                case "getSystemProperties":
                                    return java.util.Map.of();
                                case "isBootClassPathSupported":
                                    return false;
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                                case "equals":
                                    return proxy == args[0];
                                default:
                                    return method.getReturnType() == String.class ? "" : null;
                            }
                        }));
    }

    /**
     * {@code getClass()} for values that are not instances of interpreted classes. Emulator
     * objects report the JDK class they stand for, never their own class.
     */
    private static Object classOf(VirtualMachine vm, Object value) {
        if (value instanceof GuestClass) {
            return Class.class;
        }
        if (value instanceof Mirrors.MethodMirror) {
            return java.lang.reflect.Method.class;
        }
        if (value instanceof Mirrors.FieldMirror) {
            return java.lang.reflect.Field.class;
        }
        if (value instanceof Mirrors.ConstructorMirror) {
            return java.lang.reflect.Constructor.class;
        }
        if (value instanceof GuestLambda || value instanceof Mirrors.LookupMirror) {
            return Object.class;
        }
        if (value instanceof PhantomObject phantom) {
            Object mirror = vm.host.findClass(phantom.type);
            return mirror != null ? mirror : Object.class;
        }
        if (value instanceof StandIn standIn) {
            Class<?> imitated = vm.host.findClass(standIn.standsFor());
            return imitated != null ? imitated : Object.class;
        }
        Class<?> cls = value.getClass();
        if (HostBridge.isSimulated(value)) {
            while (cls.getName().startsWith("dev.javabyte.")) {
                cls = cls.getSuperclass();
            }
        }
        return cls;
    }
}
