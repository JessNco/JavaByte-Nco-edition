package dev.javabyte.stubs;

import dev.javabyte.env.GuestEnvironment;
import dev.javabyte.env.MachineProfile;
import dev.javabyte.host.CallContext;
import dev.javabyte.vm.VirtualMachine;
import java.lang.management.MemoryUsage;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The JVM's management beans and {@code ProcessHandle}: the APIs through which a sample reads
 * its machine's memory and processor count, its own process id and start time, and the options
 * its JVM was started with.
 *
 * <p>Anti-analysis code relies on them heavily (less than 4 GB of memory or a single processor
 * means a sandbox; {@code -agentlib:jdwp} means a debugger). Every answer comes from the
 * {@link MachineProfile} and the guest's system properties, so it agrees with
 * {@code Runtime.availableProcessors}, {@code NUMBER_OF_PROCESSORS}, {@code wmic} and the rest.
 *
 * <p>Each bean is a JDK proxy whose handler is emulator code, which {@code HostBridge} treats as
 * a simulated object. The calls are also registered as stubs on the bean interfaces by name, so
 * they are answered even when the analysis JDK lacks an interface (the
 * {@code com.sun.management} extensions live in a module a trimmed runtime may leave out).
 */
final class ManagementStubs {

    private ManagementStubs() {}

    /** Bean kinds, each with the interfaces its proxy implements and the JDK class it imitates. */
    enum Bean {
        RUNTIME("sun/management/RuntimeImpl", "java.lang.management.RuntimeMXBean"),
        OPERATING_SYSTEM("com/sun/management/internal/OperatingSystemImpl", "com.sun.management.OperatingSystemMXBean",
                "java.lang.management.OperatingSystemMXBean"),
        MEMORY("sun/management/MemoryImpl", "java.lang.management.MemoryMXBean"),
        THREAD("sun/management/ThreadImpl", "java.lang.management.ThreadMXBean"),
        PROCESS_HANDLE("java/lang/ProcessHandleImpl", "java.lang.ProcessHandle"),
        PROCESS_INFO("java/lang/ProcessHandleImpl$Info", "java.lang.ProcessHandle$Info");

        /** Internal name of the JDK class a real bean of this kind belongs to, for getClass(). */
        final String implementation;
        /** Interfaces the proxy implements, most specific first; missing ones are skipped. */
        final String[] interfaces;

        Bean(String implementation, String... interfaces) {
            this.implementation = implementation;
            this.interfaces = interfaces;
        }
    }

    /**
     * The handler behind every simulated bean. It names what the bean imitates, so that
     * {@code getClass()} on the proxy can answer with a JDK class instead of the proxy's own.
     */
    record BeanHandler(VirtualMachine vm, Bean kind, long pid) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "hashCode":
                    return kind == Bean.PROCESS_HANDLE ? Long.hashCode(pid) : System.identityHashCode(proxy);
                case "equals":
                    return args[0] == proxy || (kind == Bean.PROCESS_HANDLE && args[0] instanceof ProcessHandle other
                            && answer(vm, kind, pid, "pid").equals(other.pid()));
                case "toString":
                    return kind == Bean.PROCESS_HANDLE ? String.valueOf(pid)
                            : kind.implementation.replace('/', '.') + '@' + Integer.toHexString(System.identityHashCode(proxy));
                default:
                    Object value = answer(vm, kind, pid, method.getName());
                    return value != UNKNOWN ? asType(value, method.getReturnType()) : neutral(method.getReturnType());
            }
        }
    }

    /** Methods every object has. Through a bean interface they still behave as on any object. */
    private static final java.util.Set<String> OBJECT_METHODS = java.util.Set.of("getClass", "hashCode", "equals", "toString");

    /** Marks a method the simulated bean does not describe. */
    private static final Object UNKNOWN = new Object();

    /** Installs this class's stubs on the machine; called once per run from {@code BuiltinStubs}. */
    static void install(VirtualMachine vm) {
        String factory = "java/lang/management/ManagementFactory";
        vm.stubs.on(factory, "getRuntimeMXBean", ctx -> bean(vm, Bean.RUNTIME, vm.env.machine.processId));
        vm.stubs.on(factory, "getOperatingSystemMXBean", ctx -> bean(vm, Bean.OPERATING_SYSTEM, 0));
        vm.stubs.on(factory, "getMemoryMXBean", ctx -> bean(vm, Bean.MEMORY, 0));
        vm.stubs.on(factory, "getThreadMXBean", ctx -> bean(vm, Bean.THREAD, 0));
        vm.stubs.on(factory, "getPlatformMXBean", ctx -> platformBean(vm, ctx));

        // Answer calls through each bean interface directly, whatever the receiver's real class.
        register(vm, Bean.RUNTIME, "java/lang/management/RuntimeMXBean");
        register(vm, Bean.OPERATING_SYSTEM, "java/lang/management/OperatingSystemMXBean");
        register(vm, Bean.OPERATING_SYSTEM, "com/sun/management/OperatingSystemMXBean");
        register(vm, Bean.OPERATING_SYSTEM, "com/sun/management/UnixOperatingSystemMXBean");
        register(vm, Bean.MEMORY, "java/lang/management/MemoryMXBean");
        register(vm, Bean.THREAD, "java/lang/management/ThreadMXBean");
        register(vm, Bean.THREAD, "com/sun/management/ThreadMXBean");

        // ProcessHandle exists from Java 9. A sample that uses it was built for a newer Java, but
        // it should still see the same process id and process list as everywhere else.
        vm.stubs.onClass("java/lang/ProcessHandle", ctx -> {
            if (ctx.receiver != null) {
                return OBJECT_METHODS.contains(ctx.name) ? objectMethod(ctx) : ctx.proceed();
            }
            switch (ctx.name) {
                case "current":
                    return bean(vm, Bean.PROCESS_HANDLE, vm.env.machine.processId);
                case "of": {
                    long wanted = (Long) ctx.arg(0);
                    return vm.env.machine.processes().stream().anyMatch(p -> p.pid() == wanted)
                            ? Optional.of(bean(vm, Bean.PROCESS_HANDLE, wanted)) : Optional.empty();
                }
                case "allProcesses":
                    return vm.env.machine.processes().stream().map(p -> bean(vm, Bean.PROCESS_HANDLE, p.pid())).toList().stream();
                default:
                    return vm.router.blocked(ctx.owner, ctx.name, ctx.desc, "method");
            }
        });
    }

    /** Routes every call through a bean interface to {@link #answer}. */
    private static void register(VirtualMachine vm, Bean kind, String owner) {
        vm.stubs.onClass(owner, ctx -> {
            if (OBJECT_METHODS.contains(ctx.name)) {
                return objectMethod(ctx);
            }
            Object value = answer(vm, kind, pidOf(ctx), ctx.name);
            return value != UNKNOWN ? toGuest(value, ctx.desc) : vm.router.blocked(ctx.owner, ctx.name, ctx.desc, "method");
        });
    }

    /**
     * {@code getClass}, {@code hashCode}, {@code equals} or {@code toString} called through a
     * bean interface. {@code getClass} must go through the same check as anywhere else, which
     * names the JDK class the bean imitates rather than the proxy's generated class.
     */
    private static Object objectMethod(CallContext ctx) {
        if (ctx.name.equals("getClass")) {
            return SystemStubs.classOf(ctx.vm, ctx.receiver);
        }
        return ctx.proceed();
    }

    /** Converts an answer to the interpreter's form for the method's return type, as stubs must. */
    private static Object toGuest(Object value, String desc) {
        return switch (org.objectweb.asm.Type.getReturnType(desc).getSort()) {
            case org.objectweb.asm.Type.BOOLEAN -> value instanceof Boolean b ? (b ? 1 : 0) : value;
            case org.objectweb.asm.Type.INT -> ((Number) value).intValue();
            case org.objectweb.asm.Type.LONG -> ((Number) value).longValue();
            case org.objectweb.asm.Type.DOUBLE -> ((Number) value).doubleValue();
            default -> value;
        };
    }

    /** Converts an answer to the boxed type a proxy method must return. */
    private static Object asType(Object value, Class<?> type) {
        if (value instanceof Number number) {
            if (type == int.class) {
                return number.intValue();
            }
            if (type == long.class) {
                return number.longValue();
            }
            if (type == double.class) {
                return number.doubleValue();
            }
        }
        return value;
    }

    /** The process id a handle-like receiver stands for; the guest's own for the other beans. */
    private static long pidOf(CallContext ctx) {
        if (ctx.receiver instanceof Proxy proxy && Proxy.getInvocationHandler(proxy) instanceof BeanHandler handler) {
            return handler.pid();
        }
        return ctx.vm.env.machine.processId;
    }

    /** {@code getPlatformMXBean(Class)}: the simulated bean for the kinds above, blocked otherwise. */
    private static Object platformBean(VirtualMachine vm, CallContext ctx) {
        Object requested = ctx.arg(ctx.argCount() - 1);
        String name = requested instanceof Class<?> cls ? cls.getName() : String.valueOf(requested);
        for (Bean kind : List.of(Bean.RUNTIME, Bean.OPERATING_SYSTEM, Bean.MEMORY, Bean.THREAD)) {
            for (String type : kind.interfaces) {
                if (type.equals(name)) {
                    return bean(vm, kind, kind == Bean.RUNTIME ? vm.env.machine.processId : 0);
                }
            }
        }
        return vm.router.blocked(ctx.owner, ctx.name, ctx.desc, "method");
    }

    /** Builds a proxy for one bean, implementing whichever of its interfaces the host has. */
    static Object bean(VirtualMachine vm, Bean kind, long pid) {
        List<Class<?>> interfaces = new ArrayList<>();
        for (String name : kind.interfaces) {
            try {
                interfaces.add(Class.forName(name, false, ManagementStubs.class.getClassLoader()));
            } catch (ClassNotFoundException | LinkageError absent) {
                // A trimmed runtime may not ship com.sun.management; the next interface will do.
            }
        }
        return Proxy.newProxyInstance(ManagementStubs.class.getClassLoader(), interfaces.toArray(new Class<?>[0]),
                new BeanHandler(vm, kind, pid));
    }

    /** The JDK class a simulated bean imitates, or null if the object is not one. */
    static String imitatedClass(Object value) {
        if (value instanceof Proxy proxy && Proxy.getInvocationHandler(proxy) instanceof BeanHandler handler) {
            return handler.kind().implementation;
        }
        return null;
    }

    /**
     * The value of one bean method, or {@link #UNKNOWN}. Methods that would describe or change
     * the real JVM (thread dumps, heap dumps, verbose logging switches) are left unknown and so
     * are blocked and reported.
     */
    static Object answer(VirtualMachine vm, Bean kind, long pid, String name) {
        // Which facts a sample reads about its machine is a fingerprint of its checks, as with
        // system properties, so the first read of each is recorded.
        vm.events.addOnce("bean:" + kind + '.' + name, "system", "management", "bean", kind.interfaces[kind.interfaces.length - 1],
                "method", name);
        return switch (kind) {
            case RUNTIME -> runtime(vm, name);
            case OPERATING_SYSTEM -> operatingSystem(vm, name);
            case MEMORY -> memory(vm, name);
            case THREAD -> thread(vm, name);
            case PROCESS_HANDLE -> processHandle(vm, pid, name);
            case PROCESS_INFO -> processInfo(vm, pid, name);
        };
    }

    private static Object runtime(VirtualMachine vm, String name) {
        GuestEnvironment env = vm.env;
        MachineProfile machine = env.machine;
        return switch (name) {
            case "getInputArguments" -> machine.jvmArguments;
            case "getName" -> machine.processId + "@" + machine.hostName;
            case "getPid" -> machine.processId;
            case "getVmName" -> env.properties.getProperty("java.vm.name");
            case "getVmVendor" -> env.properties.getProperty("java.vm.vendor");
            case "getVmVersion" -> env.properties.getProperty("java.vm.version");
            case "getSpecName" -> env.properties.getProperty("java.vm.specification.name");
            case "getSpecVendor" -> env.properties.getProperty("java.vm.specification.vendor");
            case "getSpecVersion" -> env.properties.getProperty("java.vm.specification.version");
            case "getManagementSpecVersion" -> "1.2";
            case "getClassPath" -> env.properties.getProperty("java.class.path");
            case "getLibraryPath" -> env.properties.getProperty("java.library.path");
            // Java 8 still has a boot class path; isBootClassPathSupported only became false in 9.
            case "isBootClassPathSupported" -> true;
            case "getBootClassPath" -> env.properties.getProperty("sun.boot.class.path");
            // The start time is fixed when the process "started"; uptime grows with virtual time.
            case "getStartTime" -> machine.jvmStartTime;
            case "getUptime" -> env.clock.currentTimeMillis() - machine.jvmStartTime;
            case "getSystemProperties" -> {
                Map<String, String> copy = new LinkedHashMap<>();
                env.properties.stringPropertyNames().stream().sorted()
                        .forEach(key -> copy.put(key, env.properties.getProperty(key)));
                yield copy;
            }
            default -> UNKNOWN;
        };
    }

    private static Object operatingSystem(VirtualMachine vm, String name) {
        GuestEnvironment env = vm.env;
        MachineProfile machine = env.machine;
        // Windows adds the page file to the commit limit, which Java reports as swap.
        long swap = machine.windows ? machine.visibleMemoryBytes + (2432L << 20) : 2048L << 20;
        long uptimeNanos = (env.clock.currentTimeMillis() - machine.jvmStartTime) * 1_000_000L;
        return switch (name) {
            case "getName" -> env.properties.getProperty("os.name");
            case "getVersion" -> env.properties.getProperty("os.version");
            case "getArch" -> env.properties.getProperty("os.arch");
            case "getAvailableProcessors" -> machine.processors;
            // Windows has no load average, and Java says so with -1.
            case "getSystemLoadAverage" -> machine.windows ? -1.0 : 0.42;
            case "getTotalPhysicalMemorySize", "getTotalMemorySize" -> machine.visibleMemoryBytes;
            case "getFreePhysicalMemorySize", "getFreeMemorySize" -> machine.freeMemoryBytes;
            case "getTotalSwapSpaceSize" -> swap;
            case "getFreeSwapSpaceSize" -> swap / 100 * 58;
            case "getCommittedVirtualMemorySize" -> 312_590_336L;
            case "getProcessCpuTime" -> uptimeNanos / 3;
            case "getSystemCpuLoad", "getCpuLoad" -> 0.07;
            case "getProcessCpuLoad" -> 0.02;
            case "getOpenFileDescriptorCount" -> 87L;
            case "getMaxFileDescriptorCount" -> 1_048_576L;
            default -> UNKNOWN;
        };
    }

    private static Object memory(VirtualMachine vm, String name) {
        MachineProfile machine = vm.env.machine;
        return switch (name) {
            case "getHeapMemoryUsage" -> new MemoryUsage(initialHeap(machine), initialHeap(machine) - freeHeap(machine),
                    initialHeap(machine), maxHeap(machine));
            case "getNonHeapMemoryUsage" -> new MemoryUsage(2_555_904L, 31_457_280L, 33_947_648L, -1L);
            case "getObjectPendingFinalizationCount" -> 0;
            case "isVerbose" -> false;
            case "gc" -> null;
            default -> UNKNOWN;
        };
    }

    private static Object thread(VirtualMachine vm, String name) {
        // A freshly started Java 8 program: main plus the JVM's own service threads.
        return switch (name) {
            case "getThreadCount" -> 11;
            case "getPeakThreadCount" -> 12;
            case "getDaemonThreadCount" -> 10;
            case "getTotalStartedThreadCount" -> 12L;
            case "isThreadCpuTimeSupported", "isCurrentThreadCpuTimeSupported", "isThreadCpuTimeEnabled",
                    "isThreadContentionMonitoringSupported", "isObjectMonitorUsageSupported",
                    "isSynchronizerUsageSupported" -> true;
            case "isThreadContentionMonitoringEnabled" -> false;
            case "getCurrentThreadCpuTime", "getCurrentThreadUserTime" ->
                    (vm.env.clock.currentTimeMillis() - vm.env.machine.jvmStartTime) * 1_000_000L / 3;
            case "findDeadlockedThreads", "findMonitorDeadlockedThreads" -> null;
            default -> UNKNOWN;
        };
    }

    private static Object processHandle(VirtualMachine vm, long pid, String name) {
        MachineProfile machine = vm.env.machine;
        return switch (name) {
            case "pid" -> pid;
            case "isAlive" -> true;
            case "info" -> bean(vm, Bean.PROCESS_INFO, pid);
            case "parent" -> pid == machine.processId ? Optional.of(bean(vm, Bean.PROCESS_HANDLE, explorerPid(machine))) : Optional.empty();
            case "children", "descendants" -> Stream.empty();
            case "supportsNormalTermination" -> true;
            // A real JVM refuses to destroy itself through its own handle.
            case "destroy", "destroyForcibly" -> {
                if (pid == machine.processId) {
                    throw vm.raise(new IllegalStateException("destroy of current process not allowed"));
                }
                vm.events.add("process", "destroy", "pid", pid);
                yield false;
            }
            default -> UNKNOWN;
        };
    }

    private static Object processInfo(VirtualMachine vm, long pid, String name) {
        MachineProfile machine = vm.env.machine;
        if (pid != machine.processId) {
            // Windows does not let a normal user read other processes' details.
            return name.equals("toString") ? "[]" : Optional.empty();
        }
        String java = machine.javaExecutable(vm.env.properties.getProperty("java.home"));
        List<String> arguments = new ArrayList<>(machine.jvmArguments);
        arguments.add("-jar");
        arguments.add(vm.env.samplePath());
        return switch (name) {
            case "command" -> Optional.of(java);
            case "arguments" -> Optional.of(arguments.toArray(new String[0]));
            case "commandLine" -> Optional.of(java + " " + String.join(" ", arguments));
            case "user" -> Optional.of(machine.windows ? machine.hostName + "\\" + machine.userName : machine.userName);
            case "startInstant" -> Optional.of(Instant.ofEpochMilli(machine.jvmStartTime));
            case "totalCpuDuration" -> Optional.of(Duration.ofMillis((vm.env.clock.currentTimeMillis() - machine.jvmStartTime) / 3));
            default -> UNKNOWN;
        };
    }

    /** The desktop shell, which starts programs the user opens. */
    private static long explorerPid(MachineProfile machine) {
        return machine.processes().stream().filter(p -> p.image().equals("explorer.exe")).mapToLong(MachineProfile.ProcessEntry::pid)
                .findFirst().orElse(1L);
    }

    /** Java 8's default heap size: one sixty-fourth of physical memory at start, at least 16 MB. */
    static long initialHeap(MachineProfile machine) {
        return Math.max(16L << 20, machine.installedMemoryBytes / 64);
    }

    /** Java 8's default maximum heap on a 64-bit machine: a quarter of physical memory. */
    static long maxHeap(MachineProfile machine) {
        return Math.max(64L << 20, machine.installedMemoryBytes / 4);
    }

    /** Free space in the current heap: a program that has only just started has used little of it. */
    static long freeHeap(MachineProfile machine) {
        return initialHeap(machine) / 100 * 83;
    }

    /** Zero or null for a return type, as a bean method the simulation does not describe gives. */
    private static Object neutral(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == double.class) {
            return 0.0;
        }
        return type == Optional.class ? Optional.empty() : null;
    }
}
