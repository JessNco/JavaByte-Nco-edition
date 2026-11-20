package dev.javabyte.vm;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.env.GuestEnvironment;
import dev.javabyte.host.HostBridge;
import dev.javabyte.host.HostPolicy;
import dev.javabyte.host.StubRegistry;
import dev.javabyte.model.ClassRepository;
import dev.javabyte.model.GuestClass;
import dev.javabyte.model.GuestInstance;
import dev.javabyte.model.GuestMethod;
import dev.javabyte.model.InstanceState;
import dev.javabyte.model.PendingInstance;
import dev.javabyte.model.PlainObject;
import dev.javabyte.report.Artifacts;
import dev.javabyte.report.EventLog;
import dev.javabyte.shell.ShellFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

/**
 * One emulated JVM: the classes, the guest call stack, and the components that execute and
 * constrain guest code.
 *
 * <p>Guest code only ever runs on one host thread at a time. The {@link Scheduler} gives each
 * guest thread its own carrier thread and passes a single baton between them, so the fields of
 * this class need no synchronisation: whoever is reading or writing them holds the baton.
 * That also makes runs repeatable and guarantees nothing the sample starts outlives the run.
 */
public final class VirtualMachine {

    /** The VM bound to the current host thread, so code called back from the JDK can find it. */
    private static final ThreadLocal<VirtualMachine> CURRENT = new ThreadLocal<>();

    public final EmulatorConfig config;
    public final EventLog events;
    public final Artifacts artifacts;
    public final ClassRepository classes = new ClassRepository();
    public final HostPolicy policy;
    public final StubRegistry stubs = new StubRegistry();
    /** Native-method handlers from plugins, and the native libraries the sample has loaded. */
    public final dev.javabyte.host.NativeRegistry natives = new dev.javabyte.host.NativeRegistry();
    public final HostBridge host;
    public final ShellFactory shells;
    public final TypeSystem types;
    public final Fields fields;
    public final Hooks hooks;
    public final InvocationRouter router;
    public final IndyLinker indy;
    public final Scheduler scheduler;
    public final GuestEnvironment env;

    final Interpreter interpreter;
    /** Wording of the exceptions the JVM throws by itself, matched to the advertised Java version. */
    final ImplicitExceptions implicitExceptions = new ImplicitExceptions(this);

    private long instructions;
    /** For {@link #checkSilence}: the event count last seen, and when it last changed. */
    private long lastActivity = -1;
    private long silentSince;
    private boolean silenceReported;
    /** Why the run must end at the next checkpoint; null while it may continue. */
    private volatile EmulationAbort.Reason cancelReason;
    /** Called at every checkpoint on the emulator thread; used for progress reports. */
    private Runnable checkpointHook = () -> {};
    /** The JDK call in progress, for diagnosing a run that blocks inside real code. */
    private volatile String hostCallInProgress;
    private Throwable escapingGuestException;

    /** Sample class loaders that have defined classes, newest first. */
    private final java.util.Deque<Object> guestLoaders = new java.util.ArrayDeque<>();
    private final java.util.Set<String> resolving = new java.util.HashSet<>();
    /** Names that could not be resolved, with the number of defined classes at the time. */
    private final java.util.Map<String, Integer> unresolved = new java.util.HashMap<>();
    private int definedClassCount;

    public VirtualMachine(EmulatorConfig config, EventLog events, Artifacts artifacts) {
        this.config = config;
        this.events = events;
        this.artifacts = artifacts;
        dev.javabyte.host.SerializationGuard.install();
        this.policy = HostPolicy.defaults();
        config.policy.allow.forEach(policy::allow);
        config.policy.deny.forEach(policy::deny);
        this.host = new HostBridge(this);
        this.shells = new ShellFactory(this);
        this.types = new TypeSystem(this);
        this.fields = new Fields(this);
        this.hooks = new Hooks(config);
        this.interpreter = new Interpreter(this);
        this.router = new InvocationRouter(this);
        this.indy = new IndyLinker(this);
        this.scheduler = new Scheduler(this);
        this.env = new GuestEnvironment(this);
        classes.setLoadListener((cls, origin) -> hooks.attach(cls));
        classes.setMissingClassResolver(this::loadThroughGuestLoaders);
        events.setCallerSupplier(this::callerLocation);
    }

    // ------------------------------------------------------------------ thread binding

    /** Makes the calling thread the emulator thread for this VM. */
    public void bindToCurrentThread() {
        CURRENT.set(this);
    }

    public void unbind() {
        CURRENT.remove();
    }

    /** The VM running on this thread, or null when called from any thread but an emulator thread. */
    public static VirtualMachine current() {
        return CURRENT.get();
    }

    // ------------------------------------------------------------------ budgets

    void countInstruction() {
        // The limits are checked every 16384 instructions; a per-instruction check of a
        // volatile flag would slow the hot loop for no practical gain in precision.
        if ((++instructions & 0x3FFF) == 0) {
            EmulationAbort.Reason cancel = cancelReason;
            if (cancel != null) {
                throw new EmulationAbort(cancel, (cancel == EmulationAbort.Reason.STOPPED ? "stop requested" : "wall-clock limit reached")
                        + whereNow());
            }
            checkpointHook.run();
            if (instructions > config.limits.maxInstructions) {
                throw new EmulationAbort(EmulationAbort.Reason.INSTRUCTION_LIMIT,
                        "executed " + instructions + " instructions" + whereNow());
            }
            checkSilence();
            scheduler.preempt();
        }
    }

    /**
     * Notices a loop that does nothing the report could show. A spin loop waiting for a flag
     * that no thread will ever set (its producer died at a gap, say) otherwise runs until the
     * time limit, and the report says only that the time ran out.
     *
     * <p>"Silent" means no event of any kind was recorded: no console output, no file, network
     * or reflection activity, nothing. Halfway to {@code limits.maxSilentInstructions} (or
     * after 100 million instructions, whichever comes first) a {@code lifecycle.silent-loop}
     * event names where the guest is, once for each silent stretch. At the limit itself, the
     * current task is ended with outcome {@code silent-loop}; other threads and queued tasks
     * still run. Checked at each budget check, every 16384 instructions.
     */
    private void checkSilence() {
        long activity = events.activity();
        if (activity != lastActivity) {
            lastActivity = activity;
            silentSince = instructions;
            silenceReported = false;
            return;
        }
        long silent = instructions - silentSince;
        long limit = config.limits.maxSilentInstructions;
        long reportAt = limit > 0 ? Math.min(SILENCE_REPORT, limit / 2) : SILENCE_REPORT;
        if (!silenceReported && silent >= reportAt) {
            silenceReported = true;
            events.add("lifecycle", "silent-loop", "instructions", silent, "location", callerLocation(),
                    "stack", shortStack());
            // The report itself is activity; it must not end the stretch it describes.
            lastActivity = events.activity();
        }
        if (limit > 0 && silent >= limit) {
            silentSince = instructions;
            throw new EmulationAbort(EmulationAbort.Reason.SILENT_LOOP,
                    "no events for " + silent + " instructions" + whereNow());
        }
    }

    /** When silence is reported if no limit comes sooner. */
    private static final long SILENCE_REPORT = 100_000_000L;

    /** The top few guest frames, for saying where a loop was when it was noticed or ended. */
    private String shortStack() {
        StackTraceElement[] trace = guestStackTrace();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(5, trace.length); i++) {
            text.append(i == 0 ? "" : " < ").append(trace[i]);
        }
        return text.toString();
    }

    /** ", at <stack>" for the end of a run limit's message, or "" between entry points. */
    private String whereNow() {
        String stack = shortStack();
        return stack.isEmpty() ? "" : ", at " + stack;
    }

    public long instructionCount() {
        return instructions;
    }

    /**
     * Asks the emulator thread to stop at its next budget check for the given reason. Safe to
     * call from any thread. The first
     * reason wins: a stop requested by a person is not relabelled as a timeout afterwards.
     */
    public void cancel(EmulationAbort.Reason reason) {
        if (cancelReason == null) {
            cancelReason = reason;
        }
    }

    public void setCheckpointHook(Runnable hook) {
        this.checkpointHook = hook;
    }

    public void enterHostCall(String owner, String name) {
        hostCallInProgress = owner + '.' + name;
        scheduler.enterHostCall();
    }

    public void exitHostCall() {
        hostCallInProgress = null;
        scheduler.exitHostCall();
    }

    public String hostCallInProgress() {
        return hostCallInProgress;
    }

    // ------------------------------------------------------------------ guest call stack

    /** The call stack of the guest thread that currently holds the baton. */
    private List<Frame> frames() {
        return scheduler.frames();
    }

    void pushFrame(Frame frame) {
        if (frames().size() >= config.limits.maxCallDepth) {
            throw raise(new StackOverflowError());
        }
        frames().add(frame);
    }

    /**
     * @param depth the stack depth before the matching push. Truncating to it, rather than
     *              removing "the top", stays correct if an abort unwound past inner frames.
     */
    void popFrame(List<Frame> stack, int depth) {
        while (stack.size() > depth) {
            stack.remove(stack.size() - 1);
        }
    }

    List<Frame> currentStack() {
        return frames();
    }

    /** The guest method currently executing, or null between entry points. */
    public GuestMethod currentMethod() {
        return frames().isEmpty() ? null : frames().get(frames().size() - 1).method;
    }

    public String callerLocation() {
        return frames().isEmpty() ? null : frames().get(frames().size() - 1).location();
    }

    /**
     * The JDK frames that Java 8's {@code Method.invoke} puts between the caller and the method
     * it invokes, innermost first. Stack-walking string decryptors index into traces taken
     * inside reflectively called methods, so these frames must be there.
     */
    public static final List<StackTraceElement> METHOD_INVOKE_FRAMES = List.of(
            new StackTraceElement("sun.reflect.NativeMethodAccessorImpl", "invoke0", null, -2),
            new StackTraceElement("sun.reflect.NativeMethodAccessorImpl", "invoke", "NativeMethodAccessorImpl.java", 62),
            new StackTraceElement("sun.reflect.DelegatingMethodAccessorImpl", "invoke", "DelegatingMethodAccessorImpl.java", 43),
            new StackTraceElement("java.lang.reflect.Method", "invoke", "Method.java", 498));

    /** The JDK frames that Java 8's {@code Constructor.newInstance} adds; see {@link #METHOD_INVOKE_FRAMES}. */
    public static final List<StackTraceElement> CONSTRUCTOR_NEW_INSTANCE_FRAMES = List.of(
            new StackTraceElement("sun.reflect.NativeConstructorAccessorImpl", "newInstance0", null, -2),
            new StackTraceElement("sun.reflect.NativeConstructorAccessorImpl", "newInstance",
                    "NativeConstructorAccessorImpl.java", 62),
            new StackTraceElement("sun.reflect.DelegatingConstructorAccessorImpl", "newInstance",
                    "DelegatingConstructorAccessorImpl.java", 45),
            new StackTraceElement("java.lang.reflect.Constructor", "newInstance", "Constructor.java", 423));

    /**
     * Runs a call that, on a real JVM, would pass through JDK code (reflection, for example),
     * so that stack traces taken during it show that code's frames between the current guest
     * frame and whatever the call runs. Stubs for {@code Method.invoke} and
     * {@code Constructor.newInstance} use it with the frames above.
     *
     * @param jdkFrames the frames to show, innermost first
     */
    public <T> T callThroughJdk(List<StackTraceElement> jdkFrames, java.util.function.Supplier<T> call) {
        List<Frame> stack = frames();
        if (stack.isEmpty()) {
            return call.get();
        }
        Frame caller = stack.get(stack.size() - 1);
        List<StackTraceElement> previous = caller.callingThrough;
        caller.callingThrough = jdkFrames;
        try {
            return call.get();
        } finally {
            caller.callingThrough = previous;
        }
    }

    /**
     * The guest call stack in the form the sample would get from {@code getStackTrace()}.
     *
     * <p>This matters beyond diagnostics: several obfuscators derive string-decryption keys
     * from the calling class and method names found in a stack trace, and some index into it,
     * so the JDK frames recorded by {@link #callThroughJdk} are included too.
     */
    public StackTraceElement[] guestStackTrace() {
        List<StackTraceElement> trace = new ArrayList<>(frames().size());
        for (int i = frames().size() - 1; i >= 0; i--) {
            Frame frame = frames().get(i);
            if (frame.callingThrough != null) {
                trace.addAll(frame.callingThrough);
            }
            GuestMethod method = frame.method;
            trace.add(new StackTraceElement(method.owner.dottedName(), method.name, method.owner.node.sourceFile, frame.line()));
        }
        return trace.toArray(StackTraceElement[]::new);
    }

    // ------------------------------------------------------------------ exceptions

    /** Prepares an emulator-created exception for delivery to the guest. */
    public GuestThrow raise(Throwable throwable) {
        stampTrace(throwable);
        return new GuestThrow(throwable);
    }

    /**
     * Prepares an exception that came out of real JDK code. If it originated in guest code and
     * merely passed through the JDK, its trace is already a guest trace and is left alone.
     *
     * <p>Otherwise the JDK frames where it was thrown are kept on top of the guest trace, as a
     * real JVM shows them: {@code Integer.parseInt("zz")} fails in
     * {@code NumberFormatException.forInputString}, not in the sample's own method, and samples
     * that index into the trace rely on that.
     */
    public GuestThrow raiseKeepingTrace(Throwable throwable) {
        StackTraceElement[] trace = throwable.getStackTrace();
        boolean alreadyGuest = trace.length > 0 && classes.contains(trace[0].getClassName().replace('.', '/'));
        if (!alreadyGuest) {
            List<StackTraceElement> combined = leadingJdkFrames(trace);
            combined.addAll(java.util.Arrays.asList(guestStackTrace()));
            throwable.setStackTrace(combined.toArray(StackTraceElement[]::new));
        }
        return new GuestThrow(throwable);
    }

    /**
     * The frames at the top of a host exception's trace that belong to the JDK library code
     * that threw it. The walk stops at the first frame of the emulator, of the reflection or
     * method-handle machinery it calls the JDK through, or of the sample: below that point the
     * guest trace takes over.
     *
     * <p>Each frame is copied without its module and loader names. A Java 8 trace has neither,
     * and they would name the analysis host's real JDK version. Frames of {@code jdk.internal}
     * packages, which Java 8 lacks, are left out for the same reason.
     */
    private List<StackTraceElement> leadingJdkFrames(StackTraceElement[] trace) {
        List<StackTraceElement> kept = new ArrayList<>();
        for (StackTraceElement frame : trace) {
            String cls = frame.getClassName();
            if (cls.startsWith("dev.javabyte.") || cls.startsWith("jdk.internal.reflect.") || cls.startsWith("sun.reflect.")
                    || cls.startsWith("java.lang.invoke.") || classes.contains(cls.replace('.', '/'))) {
                break;
            }
            if (!cls.startsWith("jdk.internal.")) {
                kept.add(new StackTraceElement(cls, frame.getMethodName(), frame.getFileName(), frame.getLineNumber()));
            }
        }
        return kept;
    }

    /**
     * Remembers an exception that guest code threw while being called back from JDK or emulator
     * code. It travels through those frames as an ordinary exception; whoever catches it next
     * uses {@link #isGuestOrigin} to tell it apart from an emulator defect.
     */
    public Throwable markGuestOrigin(Throwable throwable) {
        escapingGuestException = throwable;
        return throwable;
    }

    /** True if the exception, or one it wraps, was thrown by guest code during a callback. */
    public boolean isGuestOrigin(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current == escapingGuestException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    public void stampTrace(Throwable throwable) {
        throwable.setStackTrace(guestStackTrace());
    }

    // ------------------------------------------------------------------ classes and objects

    /** Runs static initialisation for a class if it has not run yet (JVMS 5.5). */
    public void ensureInitialised(GuestClass cls) {
        if (cls.initState == GuestClass.InitState.IN_PROGRESS && cls.initialisingThread != scheduler.currentThread()) {
            // Another guest thread is running the initialiser. The JVM makes this one wait,
            // so that it never sees a half-initialised class.
            scheduler.blockUntil(() -> cls.initState != GuestClass.InitState.IN_PROGRESS,
                    "initialisation of " + cls.name);
        }
        switch (cls.initState) {
            case INITIALISED, IN_PROGRESS:
                // IN_PROGRESS on the same thread means recursive initialisation, which the JVM
                // lets proceed with the class as it currently stands.
                return;
            case FAILED:
                throw raise(new NoClassDefFoundError("Could not initialize class " + cls.dottedName()));
            default:
                break;
        }
        cls.initialisingThread = scheduler.currentThread();
        cls.initState = GuestClass.InitState.IN_PROGRESS;
        if (!cls.isInterface() && cls.superClass() != null) {
            ensureInitialised(cls.superClass());
        }
        GuestMethod initialiser = cls.declared("<clinit>", "()V");
        if (initialiser != null) {
            try {
                router.callGuest(initialiser, null, new Object[0]);
            } catch (GuestThrow thrown) {
                cls.initState = GuestClass.InitState.FAILED;
                if (thrown.value instanceof Error) {
                    throw thrown;
                }
                throw raise(new ExceptionInInitializerError(thrown.asThrowable()));
            }
        }
        cls.initState = GuestClass.InitState.INITIALISED;
    }

    /**
     * Implements the {@code NEW} instruction.
     *
     * @return a ready {@link PlainObject}, or a {@link PendingInstance} when the real object can
     *         only be created once constructor arguments are known
     */
    public Object allocate(String internalName) {
        GuestClass cls = classes.find(internalName);
        if (cls == null) {
            return new PendingInstance(internalName);
        }
        if ((cls.node.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0) {
            throw raise(new InstantiationError(cls.dottedName()));
        }
        ensureInitialised(cls);
        InstanceState state = new InstanceState(cls);
        return shells.shellFor(cls) == null ? new PlainObject(state) : new PendingInstance(state);
    }

    /** Allocates and constructs an object in one step, as reflection and method handles do. */
    public Object instantiate(String internalName, String constructorDesc, Object[] args) {
        Object target = allocate(internalName);
        router.construct(internalName, constructorDesc, target, args);
        return target instanceof PendingInstance pending ? pending.resolved : target;
    }

    /** Guest state of a value, or null if the value is not an instance of an interpreted class. */
    public InstanceState stateOf(Object value) {
        if (value instanceof GuestInstance instance) {
            return instance.javabyte$state();
        }
        return value instanceof PendingInstance pending ? pending.state : null;
    }

    /**
     * Registers a class the sample defines at run time and records it as evidence.
     *
     * <p>This is the single funnel for every route by which code can appear during execution
     * ({@code ClassLoader.defineClass}, {@code Lookup.defineClass}, {@code Unsafe.defineClass}).
     * The bytes are parsed as data and interpreted like any other class; they are never given
     * to the host JVM.
     *
     * @param via which API the sample used, for the report
     */
    public GuestClass defineClass(String requestedName, byte[] bytes, String via) {
        return defineClass(requestedName, bytes, via, null);
    }

    /**
     * @param loader the sample's class loader object that is defining the class, or null. It is
     *               remembered so that classes this one refers to can be requested from it later.
     */
    public GuestClass defineClass(String requestedName, byte[] bytes, String via, Object loader) {
        if (loader != null && !guestLoaders.contains(loader)) {
            guestLoaders.addFirst(loader);
        }
        definedClassCount++;
        String name;
        try {
            name = new ClassReader(bytes).getClassName();
        } catch (RuntimeException e) {
            artifacts.save("defined-classes", "invalid-" + sha256(bytes).substring(0, 16) + ".bin", bytes);
            events.add("class", "define-invalid", "via", via, "size", bytes.length, "sha256", sha256(bytes));
            throw raise(new ClassFormatError("Truncated or invalid class file"));
        }
        boolean duplicate = classes.contains(name);
        if (!duplicate) {
            classes.addClassBytes(name, bytes, via);
        }
        if (config.output.dumpDefinedClasses) {
            artifacts.save("defined-classes", name + ".class", bytes);
        }
        GuestClass cls = classes.find(name);
        events.add("class", "define", "name", name, "via", via, "size", bytes.length, "sha256", sha256(bytes),
                "superclass", cls == null ? null : cls.node.superName,
                "requestedName", requestedName != null && !requestedName.replace('.', '/').equals(name) ? requestedName : null,
                "duplicate", duplicate ? Boolean.TRUE : null);
        if (cls == null) {
            throw raise(new ClassFormatError(name));
        }
        return cls;
    }

    /**
     * Reproduces implicit class loading. On a real JVM, when a class defined by a custom loader
     * first refers to another class, the JVM asks that loader for it. Loaders that decrypt and
     * define classes on demand depend on this, so an unknown class name is offered to the
     * sample's loaders before it is declared missing.
     *
     * @return true if a loader defined the class
     */
    private boolean loadThroughGuestLoaders(String internalName) {
        if (guestLoaders.isEmpty() || internalName.startsWith("[") || !resolving.add(internalName)) {
            return false;
        }
        try {
            Integer failedAt = unresolved.get(internalName);
            // Retry a failed name only after more classes have been defined since.
            if ((failedAt != null && failedAt == definedClassCount) || host.findClass(internalName) != null) {
                return false;
            }
            for (Object loader : new ArrayList<>(guestLoaders)) {
                try {
                    router.invokeVirtual("java/lang/ClassLoader", "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;",
                            loader, new Object[] {internalName.replace('/', '.')});
                } catch (GuestThrow notFound) {
                    continue; // this loader does not know the class
                }
                if (classes.contains(internalName)) {
                    events.addOnce("implicit:" + internalName, "class", "implicit-load", "name", internalName,
                            "loader", types.nameOf(loader));
                    return true;
                }
            }
            unresolved.put(internalName, definedClassCount);
            return false;
        } finally {
            resolving.remove(internalName);
        }
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
