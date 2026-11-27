package dev.javabyte.stubs;

import dev.javabyte.env.SimExecutor;
import dev.javabyte.env.SimThreadBuilder;
import dev.javabyte.env.SimTimer;
import dev.javabyte.host.CallContext;
import dev.javabyte.host.Stub;
import dev.javabyte.host.StubRegistry;
import dev.javabyte.vm.EmulationAbort;
import dev.javabyte.vm.VirtualMachine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;
import org.objectweb.asm.Type;

/**
 * Threads and everything else that would start one.
 *
 * <p>The JDK is never allowed to start or block a thread on the sample's behalf. Starting a
 * thread hands its body to the {@link dev.javabyte.vm.Scheduler}, which interleaves guest
 * threads one at a time; pools, timers and asynchronous futures are redirected the same way;
 * and calls that would block waiting for another thread wait cooperatively instead. Timed
 * waits ({@code join(millis)}, {@code parkNanos}, {@code Future.get(timeout)}, ...) take their
 * time in virtual time, so a sample that times them sees what a real JVM would show.
 *
 * <p>Thread objects also present themselves as on a Java 8 desktop JVM: the main thread has
 * id 1 and is not a daemon, and {@code Thread.getAllStackTraces()} lists the live guest
 * threads and the JVM's usual system threads, never an empty map.
 */
final class ThreadStubs {

    private ThreadStubs() {}

    static void install(VirtualMachine vm) {
        StubRegistry stubs = vm.stubs;
        String thread = "java/lang/Thread";

        GuestThreads threads = new GuestThreads(vm);
        stubs.on(thread, "currentThread", ctx -> vm.scheduler.currentThread());
        BuiltThreads built = new BuiltThreads();
        stubs.on(thread, "start", ctx -> {
            Object target = ctx.receiver;
            if (target instanceof Thread real && built.isBuilt(real)) {
                if (!built.tasks.containsKey(real)) {
                    throw ctx.raise(new IllegalThreadStateException()); // started already
                }
                built.start(vm, threads, real);
                return null;
            }
            if (target instanceof Thread real) {
                threads.idOf(real); // ids follow start order, as they roughly do on a real JVM
            }
            String name = target instanceof Thread real ? real.getName() : vm.types.nameOf(target);
            vm.events.add("thread", "start", "name", name, "class", vm.types.nameOf(target));
            vm.scheduler.submit("thread:" + name, target,
                    () -> vm.router.invokeVirtual(thread, "run", "()V", target, new Object[0]));
            return null;
        });
        installBuilders(vm, threads, built);
        stubs.on(thread, "sleep", ctx -> {
            long millis = ctx.arg(0) instanceof Long l ? l : ((java.time.Duration) ctx.arg(0)).toMillis();
            vm.scheduler.sleep(millis);
            return null;
        });
        stubs.on(thread, "join", ctx -> join(vm, ctx));
        stubs.on(thread, "isAlive", ctx -> threads.isSystem(ctx.receiver) || vm.scheduler.isAlive(ctx.receiver) ? 1 : 0);
        stubs.on(thread, "yield", ctx -> yieldNow(vm, "Thread.yield"));
        stubs.on(thread, "onSpinWait", ctx -> yieldNow(vm, "Thread.onSpinWait"));
        stubs.on(thread, "interrupt", ctx -> null);
        stubs.on(thread, "isInterrupted", ctx -> 0);
        stubs.on(thread, "interrupted", ctx -> 0);
        stubs.on(thread, "stop", ctx -> null);
        stubs.on(thread, "suspend", ctx -> null);
        stubs.on(thread, "resume", ctx -> null);
        stubs.on(thread, "holdsLock", ctx -> 1);
        // The caller's thread group is "main", which holds the guest's threads but not the JVM's.
        stubs.on(thread, "activeCount", ctx -> vm.scheduler.liveThreads().size());
        stubs.on(thread, "enumerate", ctx -> {
            Object[] into = (Object[]) ctx.arg(0);
            List<Object> live = vm.scheduler.liveThreads();
            int count = Math.min(into.length, live.size());
            for (int i = 0; i < count; i++) {
                into[i] = live.get(i);
            }
            return count;
        });
        stubs.on(thread, "dumpStack", ctx -> null);
        stubs.on(thread, "getState", ctx -> threads.isSystem(ctx.receiver) ? threads.stateOf((Thread) ctx.receiver)
                : vm.scheduler.stateOf(ctx.receiver));
        installIdentity(stubs, threads);
        stubs.on(thread, "getContextClassLoader", ctx -> vm.env.appLoader);
        stubs.on(thread, "setContextClassLoader", ctx -> null);
        stubs.on(thread, "setDefaultUncaughtExceptionHandler", ctx -> null);
        stubs.on(thread, "getDefaultUncaughtExceptionHandler", ctx -> null);
        stubs.on(thread, "getAllStackTraces", ctx -> {
            // A real JVM always lists the caller and its own system threads; never an agent's.
            Map<Thread, StackTraceElement[]> all = new LinkedHashMap<>();
            for (Object live : vm.scheduler.liveThreads()) {
                if (live instanceof Thread real) {
                    all.put(real, vm.scheduler.isCurrent(real)
                            ? withTop(vm.guestStackTrace(), new StackTraceElement("java.lang.Thread", "dumpThreads", "Thread.java", -2),
                                    new StackTraceElement("java.lang.Thread", "getAllStackTraces", "Thread.java", 1610))
                            : vm.scheduler.stackOf(real));
                }
            }
            for (Thread system : threads.system()) {
                all.put(system, threads.stackOf(system));
            }
            return all;
        });
        stubs.on(thread, "getStackTrace", ctx -> {
            Object target = ctx.receiver;
            if (threads.isSystem(target)) {
                return threads.stackOf((Thread) target);
            }
            if (target instanceof Thread && !vm.scheduler.isCurrent(target)) {
                // Another thread's stack: its own frames, or nothing if it is not running.
                return vm.scheduler.stackOf(target);
            }
            // A real trace starts with the getStackTrace frame itself; samples index past it.
            return withTop(vm.guestStackTrace(), new StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", 1559));
        });

        stubs.on("java/util/concurrent/TimeUnit", "sleep", ctx -> {
            vm.scheduler.sleep(((TimeUnit) ctx.receiver).toMillis((Long) ctx.arg(0)));
            return null;
        });

        installExecutors(vm);
        installBlockingCalls(vm);
        installParallelism(vm);
    }

    /**
     * {@code Thread.join}, {@code join(millis)}, {@code join(millis, nanos)} and
     * {@code join(Duration)}. A timed join returns when the thread ends or when that much
     * virtual time has passed, whichever is first.
     */
    private static Object join(VirtualMachine vm, CallContext ctx) {
        if (ctx.argCount() == 0) {
            vm.scheduler.join(ctx.receiver);
            return null;
        }
        if (ctx.arg(0) instanceof java.time.Duration duration) {
            // join(Duration) returns whether the thread has ended.
            long millis = duration.isNegative() ? 0 : duration.toMillis();
            return millis == 0 ? (vm.scheduler.isAlive(ctx.receiver) ? 0 : 1) : vm.scheduler.join(ctx.receiver, millis) ? 1 : 0;
        }
        long millis = (Long) ctx.arg(0);
        int nanos = ctx.argCount() > 1 ? ctx.intArg(1) : 0;
        if (millis < 0) {
            throw ctx.raise(new IllegalArgumentException("timeout value is negative"));
        }
        if (nanos < 0 || nanos > 999_999) {
            throw ctx.raise(new IllegalArgumentException("nanosecond timeout value out of range"));
        }
        if (nanos > 0 && millis < Long.MAX_VALUE) {
            millis++; // as the JDK rounds
        }
        if (millis == 0) {
            vm.scheduler.join(ctx.receiver);
        } else {
            vm.scheduler.join(ctx.receiver, millis);
        }
        return null;
    }

    /** {@code trace} with {@code top} frames put in front of it, outermost last. */
    private static StackTraceElement[] withTop(StackTraceElement[] trace, StackTraceElement... top) {
        StackTraceElement[] result = new StackTraceElement[top.length + trace.length];
        System.arraycopy(top, 0, result, 0, top.length);
        System.arraycopy(trace, 0, result, top.length, trace.length);
        return result;
    }

    /**
     * How guest thread objects present themselves. They are real {@code Thread} objects that
     * are never started, made on the emulator's daemon carrier threads, so left alone they
     * would show the host's numbering (ids in the twenties), daemon status inherited from the
     * carrier, and the newer JDK's text form ({@code Thread[#24,main,5,main]}). A Java 8 JVM,
     * which is what the guest is told it runs on, shows {@code Thread[main,5,main]} with id 1.
     */
    private static void installIdentity(StubRegistry stubs, GuestThreads threads) {
        String thread = "java/lang/Thread";
        Stub id = ctx -> ctx.receiver instanceof Thread real ? (Object) threads.idOf(real) : ctx.proceed();
        stubs.on(thread, "getId", id);
        stubs.on(thread, "threadId", id);
        stubs.on(thread, "isDaemon", ctx -> ctx.receiver instanceof Thread real ? (threads.isDaemon(real) ? 1 : 0) : ctx.proceed());
        stubs.on(thread, "setDaemon", ctx -> {
            if (ctx.receiver instanceof Thread real) {
                if (threads.vm.scheduler.isAlive(real) || threads.isSystem(real)) {
                    throw ctx.raise(new IllegalThreadStateException());
                }
                threads.setDaemon(real, ctx.boolArg(0));
            }
            return ctx.proceed();
        });
        stubs.on(thread, "toString", "()Ljava/lang/String;",
                ctx -> ctx.receiver instanceof Thread real ? threads.describe(real) : ctx.proceed());
    }

    /**
     * Guest-side facts about thread objects that the host objects cannot carry: ids in Java 8
     * numbering, daemon status, and the JVM's own system threads, which a real
     * {@code Thread.getAllStackTraces()} always lists. One instance per run.
     */
    private static final class GuestThreads {
        /** User threads on a fresh Java 8 desktop JVM get ids after the JVM's own threads. */
        private static final long FIRST_USER_ID = 11;

        final VirtualMachine vm;
        private final Map<Thread, Long> ids = new IdentityHashMap<>();
        private final Map<Thread, Boolean> daemon = new IdentityHashMap<>();
        private final Map<Thread, Thread.State> systemStates = new IdentityHashMap<>();
        private final Map<Thread, StackTraceElement[]> systemStacks = new IdentityHashMap<>();
        private final List<Thread> system = new ArrayList<>();
        private long nextId = FIRST_USER_ID;

        GuestThreads(VirtualMachine vm) {
            this.vm = vm;
        }

        /** The id the guest sees: 1 for main, 2 to 4 for the system threads, then in order of first use. */
        long idOf(Thread thread) {
            if (thread == vm.scheduler.mainThread()) {
                return 1;
            }
            return ids.computeIfAbsent(thread, t -> nextId++);
        }

        /** Daemon status as the guest set it. A thread the guest made is not a daemon unless it says so. */
        boolean isDaemon(Thread thread) {
            return isSystem(thread) || daemon.getOrDefault(thread, false);
        }

        void setDaemon(Thread thread, boolean on) {
            daemon.put(thread, on);
        }

        boolean isSystem(Object thread) {
            return thread instanceof Thread real && systemStates.containsKey(real);
        }

        Thread.State stateOf(Thread systemThread) {
            return systemStates.get(systemThread);
        }

        StackTraceElement[] stackOf(Thread systemThread) {
            return systemStacks.get(systemThread).clone();
        }

        /** Java 8's {@code Thread.toString()}: name, priority and group, which is empty once the thread has ended. */
        String describe(Thread thread) {
            String group = isSystem(thread) ? "system"
                    : vm.scheduler.stateOf(thread) == Thread.State.TERMINATED ? "" : "main";
            return "Thread[" + thread.getName() + "," + thread.getPriority() + "," + group + "]";
        }

        /**
         * The JVM's own threads as a Java 8 desktop JVM shows them, made on first use. They are
         * plain, never-started objects: nothing runs.
         */
        List<Thread> system() {
            if (system.isEmpty()) {
                addSystem("Reference Handler", 2, Thread.MAX_PRIORITY, Thread.State.WAITING,
                        frame("java.lang.Object", "wait", "Object.java", -2),
                        frame("java.lang.Object", "wait", "Object.java", 502),
                        frame("java.lang.ref.Reference", "tryHandlePending", "Reference.java", 191),
                        frame("java.lang.ref.Reference$ReferenceHandler", "run", "Reference.java", 153));
                addSystem("Finalizer", 3, Thread.MAX_PRIORITY - 2, Thread.State.WAITING,
                        frame("java.lang.Object", "wait", "Object.java", -2),
                        frame("java.lang.ref.ReferenceQueue", "remove", "ReferenceQueue.java", 144),
                        frame("java.lang.ref.ReferenceQueue", "remove", "ReferenceQueue.java", 165),
                        frame("java.lang.ref.Finalizer$FinalizerThread", "run", "Finalizer.java", 216));
                addSystem("Signal Dispatcher", 4, Thread.MAX_PRIORITY - 1, Thread.State.RUNNABLE);
            }
            return system;
        }

        private void addSystem(String name, long id, int priority, Thread.State state, StackTraceElement... stack) {
            Thread thread = new Thread(name);
            thread.setDaemon(true);
            thread.setPriority(priority);
            system.add(thread);
            ids.put(thread, id);
            systemStates.put(thread, state);
            systemStacks.put(thread, stack);
        }

        private static StackTraceElement frame(String cls, String method, String file, int line) {
            return new StackTraceElement(cls, method, file, line);
        }
    }

    /**
     * Threads made by a Java 21 thread builder or {@code Thread.startVirtualThread}. Each is a
     * plain, never-started host {@code Thread} that serves as the scheduler's key and as what
     * {@code Thread.currentThread()} returns; the task it runs is kept here, since a real
     * builder's thread would run it in place of {@code Thread.run}.
     */
    private static final class BuiltThreads {
        /** Tasks of built threads not started yet. */
        final Map<Thread, Object> tasks = Collections.synchronizedMap(new IdentityHashMap<>());
        final Set<Thread> virtual = Collections.newSetFromMap(Collections.synchronizedMap(new IdentityHashMap<>()));
        private final Set<Thread> all = Collections.newSetFromMap(Collections.synchronizedMap(new IdentityHashMap<>()));
        private int unnamed;

        boolean isBuilt(Thread thread) {
            return all.contains(thread);
        }

        Thread unstarted(SimThreadBuilder builder, CallContext ctx) {
            Object task = ctx.arg(0);
            if (task == null) {
                throw ctx.raise(new NullPointerException("task"));
            }
            String name = builder.nextName();
            Thread key = new Thread(name != null ? name : "Thread-" + unnamed++);
            tasks.put(key, task);
            all.add(key);
            if (builder.virtual) {
                virtual.add(key);
            }
            return key;
        }

        /** Starts a built thread; returns it, as {@code Thread.Builder.start} does. */
        Thread start(VirtualMachine vm, GuestThreads threads, Thread key) {
            Object task = tasks.remove(key); // a thread starts once
            boolean isVirtual = virtual.contains(key);
            threads.idOf(key);
            vm.events.add("thread", "start", "name", key.getName(), "class", vm.types.nameOf(task), "virtual", isVirtual);
            vm.scheduler.submit((isVirtual ? "vthread:" : "thread:") + key.getName(), key,
                    () -> vm.router.invokeVirtual("java/lang/Runnable", "run", "()V", task, new Object[0]));
            return key;
        }
    }

    /**
     * Java 21's {@code Thread.ofVirtual()}, {@code Thread.ofPlatform()} and
     * {@code Thread.startVirtualThread}. The builders are records of their settings
     * ({@link SimThreadBuilder}); threads they start run on the scheduler like any other.
     * Samples built for Java 21 use these for their worker threads, so without them the work
     * never ran. {@code factory()} gives the same thread factory as {@code Executors}.
     */
    private static void installBuilders(VirtualMachine vm, GuestThreads threads, BuiltThreads built) {
        StubRegistry stubs = vm.stubs;
        String thread = "java/lang/Thread";
        stubs.on(thread, "ofVirtual", ctx -> new SimThreadBuilder(true));
        stubs.on(thread, "ofPlatform", ctx -> new SimThreadBuilder(false));
        stubs.on(thread, "startVirtualThread", ctx -> built.start(vm, threads, built.unstarted(new SimThreadBuilder(true), ctx)));
        stubs.on(thread, "isVirtual", ctx -> ctx.receiver instanceof Thread real && built.virtual.contains(real) ? 1 : 0);

        String builder = "java/lang/Thread$Builder";
        for (String owner : List.of(builder, builder + "$OfVirtual", builder + "$OfPlatform")) {
            stubs.on(owner, "name", ctx -> {
                SimThreadBuilder settings = (SimThreadBuilder) ctx.receiver;
                try {
                    if (ctx.argCount() == 2) {
                        settings.name((String) ctx.arg(0), (Long) ctx.arg(1));
                    } else {
                        settings.name((String) ctx.arg(0));
                    }
                } catch (IllegalArgumentException wrong) {
                    throw ctx.raise(wrong);
                }
                return settings;
            });
            for (String setting : List.of("inheritInheritableThreadLocals", "uncaughtExceptionHandler",
                    "group", "daemon", "priority", "stackSize")) {
                stubs.on(owner, setting, ctx -> ctx.receiver);
            }
            stubs.on(owner, "unstarted", ctx -> built.unstarted((SimThreadBuilder) ctx.receiver, ctx));
            stubs.on(owner, "start", ctx -> {
                return built.start(vm, threads, built.unstarted((SimThreadBuilder) ctx.receiver, ctx));
            });
            stubs.on(owner, "factory", ctx -> Executors.defaultThreadFactory());
        }
    }

    private static Object yieldNow(VirtualMachine vm, String reason) {
        vm.scheduler.yieldToOthers(reason);
        return null;
    }

    private static void installExecutors(VirtualMachine vm) {
        StubRegistry stubs = vm.stubs;
        stubs.onClass("java/util/concurrent/Executors", ctx -> {
            switch (ctx.name) {
                case "callable":
                    return ctx.argCount() == 1 && ctx.arg(0) instanceof Runnable r ? Executors.callable(r)
                            : ctx.argCount() == 2 ? Executors.callable((Runnable) ctx.arg(0), ctx.arg(1))
                            : ctx.vm.router.gap("Executors.callable" + ctx.desc, ctx.desc);
                case "defaultThreadFactory", "privilegedThreadFactory":
                    return Executors.defaultThreadFactory();
                default:
                    // Every factory method returns some kind of executor service.
                    return new SimExecutor();
            }
        });
        for (String pool : new String[] {"java/util/concurrent/ThreadPoolExecutor",
                "java/util/concurrent/ScheduledThreadPoolExecutor", "java/util/concurrent/ForkJoinPool"}) {
            stubs.on(pool, "<init>", ctx -> new SimExecutor());
        }
        stubs.on("java/util/concurrent/ForkJoinPool", "commonPool", ctx -> new SimExecutor());
        stubs.on("java/util/Timer", "<init>", ctx -> new SimTimer());

        // CompletableFuture is safe as a value, but its *Async methods hand work to a thread
        // pool. Each is redirected to its synchronous twin. The same calls made through the
        // CompletionStage interface name that interface in the bytecode, so it is covered too.
        stubs.onClass("java/util/concurrent/CompletableFuture", ThreadStubs::completableFuture);
        stubs.onClass("java/util/concurrent/CompletionStage", ThreadStubs::completableFuture);
        // Waiting on a JDK future (CompletableFuture, FutureTask) would park the carrier for real.
        stubs.on("java/util/concurrent/Future", "get", ThreadStubs::awaitHostFuture);
    }

    /**
     * {@code get()}, {@code get(timeout, unit)} and {@code join()} on a JDK future that is not
     * done yet: waits cooperatively, in virtual time, so that the real call that follows returns
     * at once. A timed wait that runs out throws {@code TimeoutException} as the JDK does. The
     * emulator's own futures wait by themselves and are passed straight through.
     */
    private static Object awaitHostFuture(CallContext ctx) throws Exception {
        if (ctx.receiver instanceof Future<?> future && !(ctx.receiver instanceof dev.javabyte.env.SimFuture<?>) && !future.isDone()) {
            String what = "Future." + ctx.name;
            if (ctx.argCount() == 2) {
                if (!ctx.vm.scheduler.awaitForNanos(future::isDone, nanos(ctx, 0), what)) {
                    throw new TimeoutException();
                }
            } else {
                ctx.vm.scheduler.blockUntil(future::isDone, what);
            }
        }
        return ctx.proceed();
    }

    private static Object completableFuture(CallContext ctx) throws Exception {
        if (ctx.name.equals("runAsync") || ctx.name.equals("supplyAsync")) {
            try {
                if (ctx.name.equals("runAsync")) {
                    ((Runnable) ctx.arg(0)).run();
                    return CompletableFuture.completedFuture(null);
                }
                return CompletableFuture.completedFuture(((Supplier<?>) ctx.arg(0)).get());
            } catch (RuntimeException thrown) {
                // On a real JVM the task's exception does not reach the caller: it completes
                // the future, wrapped the way the asynchronous machinery wraps it. A guest
                // exception arrives either still wrapped or, after crossing a JDK functional
                // interface, as the bare exception.
                Throwable cause = thrown instanceof dev.javabyte.vm.GuestThrow guest && guest.value instanceof Throwable inner
                        ? inner : thrown;
                return CompletableFuture.failedFuture(new java.util.concurrent.CompletionException(cause));
            }
        }
        if (ctx.name.equals("delayedExecutor") || ctx.name.equals("defaultExecutor")) {
            return new SimExecutor();
        }
        if (ctx.name.equals("get") || ctx.name.equals("join")) {
            return awaitHostFuture(ctx);
        }
        if (ctx.name.equals("orTimeout") || ctx.name.equals("completeOnTimeout")) {
            // These arm a timer on a JDK background thread. Virtual time never reaches a real
            // deadline, so leaving the future untouched is the faithful answer.
            return ctx.receiver;
        }
        if (ctx.name.endsWith("Async") && ctx.receiver != null) {
            // foo Async(fn[, executor]) -> foo(fn): drop the suffix and any trailing executor.
            Type[] types = Type.getArgumentTypes(ctx.desc);
            boolean hasExecutor = types.length > 0
                    && types[types.length - 1].getInternalName().equals("java/util/concurrent/Executor");
            Type[] kept = hasExecutor ? java.util.Arrays.copyOf(types, types.length - 1) : types;
            Object[] args = hasExecutor ? java.util.Arrays.copyOf(ctx.args, ctx.args.length - 1) : ctx.args;
            String desc = Type.getMethodDescriptor(Type.getReturnType(ctx.desc), kept);
            String name = ctx.name.substring(0, ctx.name.length() - "Async".length());
            return ctx.vm.host.invoke(ctx.owner, name, desc, ctx.receiver, args, false);
        }
        return ctx.proceed();
    }

    /**
     * Calls that wait for another thread. On one thread the awaited event can only happen if
     * the other tasks run, so each call first lets them, then either succeeds or ends the task.
     */
    private static void installBlockingCalls(VirtualMachine vm) {
        StubRegistry stubs = vm.stubs;
        // wait/notify on JDK objects (guest objects are handled by the router).
        stubs.on("java/lang/Object", "wait", ctx -> {
            vm.scheduler.objectWait(ctx.receiver, ctx.argCount() == 0 ? 0 : (Long) ctx.arg(0));
            return null;
        });
        stubs.on("java/lang/Object", "notify", ctx -> notifyWaiters(vm, ctx.receiver, false));
        stubs.on("java/lang/Object", "notifyAll", ctx -> notifyWaiters(vm, ctx.receiver, true));

        stubs.on("java/util/concurrent/CountDownLatch", "await", ctx -> {
            CountDownLatch latch = (CountDownLatch) ctx.receiver;
            if (ctx.argCount() == 0) {
                vm.scheduler.blockUntil(() -> latch.getCount() == 0, "CountDownLatch.await");
                return null;
            }
            // Timed form: wait in virtual time until the count reaches zero or the time is up.
            return vm.scheduler.awaitForNanos(() -> latch.getCount() == 0, nanos(ctx, 0), "CountDownLatch.await") ? 1 : 0;
        });
        // A real lock() would park the host thread while it holds the baton.
        stubs.on("java/util/concurrent/locks/Lock", "lock", ctx -> acquire(vm, (Lock) ctx.receiver));
        stubs.on("java/util/concurrent/locks/Lock", "lockInterruptibly", ctx -> acquire(vm, (Lock) ctx.receiver));
        stubs.on("java/util/concurrent/locks/Lock", "tryLock", "(JLjava/util/concurrent/TimeUnit;)Z", ctx -> {
            Lock lock = (Lock) ctx.receiver;
            return vm.scheduler.awaitForNanos(lock::tryLock, nanos(ctx, 0), "Lock.tryLock") ? 1 : 0;
        });
        installConditions(vm);
        installParking(vm);
        stubs.on("java/util/concurrent/BlockingQueue", "take", ctx -> {
            BlockingQueue<?> queue = (BlockingQueue<?>) ctx.receiver;
            vm.scheduler.blockUntil(() -> !queue.isEmpty(), "BlockingQueue.take");
            return queue.poll();
        });
        stubs.on("java/util/concurrent/BlockingQueue", "poll", ctx -> {
            BlockingQueue<?> queue = (BlockingQueue<?>) ctx.receiver;
            if (ctx.argCount() == 2) {
                vm.scheduler.awaitForNanos(() -> !queue.isEmpty(), nanos(ctx, 0), "BlockingQueue.poll");
            }
            return queue.poll();
        });
        // A full bounded queue would park the carrier for real until another thread takes.
        stubs.on("java/util/concurrent/BlockingQueue", "put", ctx -> {
            BlockingQueue<?> queue = (BlockingQueue<?>) ctx.receiver;
            vm.scheduler.blockUntil(() -> queue.remainingCapacity() > 0, "BlockingQueue.put");
            return ctx.proceed();
        });
        stubs.on("java/util/concurrent/BlockingQueue", "offer", "(Ljava/lang/Object;JLjava/util/concurrent/TimeUnit;)Z", ctx -> {
            @SuppressWarnings("unchecked")
            BlockingQueue<Object> queue = (BlockingQueue<Object>) ctx.receiver;
            vm.scheduler.awaitForNanos(() -> queue.remainingCapacity() > 0, nanos(ctx, 1), "BlockingQueue.offer");
            return queue.offer(ctx.arg(0)) ? 1 : 0;
        });
        stubs.on("java/util/concurrent/Semaphore", "acquire", ctx -> {
            Semaphore semaphore = (Semaphore) ctx.receiver;
            int permits = ctx.argCount() == 0 ? 1 : ctx.intArg(0);
            vm.scheduler.blockUntil(() -> semaphore.tryAcquire(permits), "Semaphore.acquire");
            return null;
        });
        // The timed tryAcquire forms would park the carrier for real while it holds the baton.
        stubs.on("java/util/concurrent/Semaphore", "tryAcquire", ctx -> {
            Semaphore semaphore = (Semaphore) ctx.receiver;
            if (ctx.argCount() < 2) {
                return ctx.proceed();
            }
            int permits = ctx.argCount() == 3 ? ctx.intArg(0) : 1;
            return vm.scheduler.awaitForNanos(() -> semaphore.tryAcquire(permits), nanos(ctx, ctx.argCount() - 2), "Semaphore.tryAcquire") ? 1 : 0;
        });
        stubs.on("java/util/concurrent/CyclicBarrier", "await", ctx -> {
            throw wouldBlockForever("CyclicBarrier.await");
        });
    }

    /** A timeout given as a {@code long} at {@code index} and a {@code TimeUnit} after it, in nanoseconds. */
    private static long nanos(CallContext ctx, int index) {
        return ((TimeUnit) ctx.arg(index + 1)).toNanos((Long) ctx.arg(index));
    }

    /**
     * {@code Condition.await} and its timed forms. The untimed form returns at once after the
     * others have had a turn (a spurious wake-up, which callers must allow for anyway). The
     * timed forms wait in virtual time until a signal or until the time is up, so a sample
     * timing the wait sees it last as long as asked. Signals are counted per condition object.
     *
     * <p>The condition's lock is not released while waiting (the stub cannot reach it), so a
     * signal from a thread that needs the same lock first cannot arrive; the wait then simply
     * times out.
     */
    private static void installConditions(VirtualMachine vm) {
        StubRegistry stubs = vm.stubs;
        String condition = "java/util/concurrent/locks/Condition";
        Map<Object, Integer> signals = new IdentityHashMap<>();
        Stub signal = ctx -> {
            signals.merge(ctx.receiver, 1, Integer::sum);
            return ctx.proceed();
        };
        stubs.on(condition, "signal", signal);
        stubs.on(condition, "signalAll", signal);
        stubs.on(condition, "await", ctx -> {
            if (ctx.argCount() == 0) {
                // Returning early is allowed (a spurious wake-up); callers re-check their condition.
                vm.scheduler.sleep(0);
                return null;
            }
            return awaitSignal(vm, signals, ctx.receiver, nanos(ctx, 0)) > 0 ? 1 : 0;
        });
        stubs.on(condition, "awaitNanos", ctx -> {
            long nanos = (Long) ctx.arg(0);
            return awaitSignal(vm, signals, ctx.receiver, nanos);
        });
        stubs.on(condition, "awaitUntil", ctx -> {
            long millis = ((java.util.Date) ctx.arg(0)).getTime() - vm.env.clock.peekMillis();
            return awaitSignal(vm, signals, ctx.receiver, TimeUnit.MILLISECONDS.toNanos(Math.max(millis, 0))) > 0 ? 1 : 0;
        });
    }

    /**
     * Waits up to {@code nanos} of virtual time for a signal on {@code condition}.
     *
     * @return the time left, as {@code awaitNanos} reports it: more than zero if signalled
     */
    private static long awaitSignal(VirtualMachine vm, Map<Object, Integer> signals, Object condition, long nanos) {
        if (nanos <= 0) {
            return nanos;
        }
        int before = signals.getOrDefault(condition, 0);
        long start = vm.env.clock.elapsedNanos();
        boolean signalled = vm.scheduler.awaitForNanos(() -> signals.getOrDefault(condition, 0) != before, nanos, "Condition.await");
        long left = nanos - (vm.env.clock.elapsedNanos() - start);
        return signalled ? Math.max(left, 1) : Math.min(left, 0);
    }

    /**
     * {@code LockSupport}. Parking never parks the carrier for real. Permits from
     * {@code unpark} are kept per guest thread object, so an unpark ends a park early.
     * {@code park()} returns after the others have had a turn (a spurious wake-up is allowed);
     * {@code parkNanos} and {@code parkUntil} wait in virtual time for a permit or the timeout.
     */
    private static void installParking(VirtualMachine vm) {
        StubRegistry stubs = vm.stubs;
        String support = "java/util/concurrent/locks/LockSupport";
        Set<Object> permits = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.function.BooleanSupplier takePermit = () -> permits.removeIf(vm.scheduler::isCurrent);
        stubs.on(support, "unpark", ctx -> {
            if (ctx.arg(0) != null) {
                permits.add(ctx.arg(0));
            }
            return null;
        });
        stubs.on(support, "park", ctx -> {
            if (!takePermit.getAsBoolean()) {
                vm.scheduler.yieldToOthers("LockSupport.park");
                takePermit.getAsBoolean();
            }
            return null;
        });
        stubs.on(support, "parkNanos", ctx -> {
            long nanos = (Long) ctx.arg(ctx.argCount() - 1);
            if (!takePermit.getAsBoolean() && nanos > 0) {
                vm.scheduler.awaitForNanos(takePermit, nanos, "LockSupport.parkNanos");
            }
            return null;
        });
        stubs.on(support, "parkUntil", ctx -> {
            long millis = (Long) ctx.arg(ctx.argCount() - 1) - vm.env.clock.peekMillis();
            if (!takePermit.getAsBoolean() && millis > 0) {
                vm.scheduler.awaitFor(takePermit, millis, "LockSupport.parkUntil");
            }
            return null;
        });
    }

    private static Object notifyWaiters(VirtualMachine vm, Object target, boolean everyone) {
        vm.scheduler.objectNotify(target, everyone);
        return null;
    }

    private static Object acquire(VirtualMachine vm, Lock lock) {
        vm.scheduler.blockUntil(lock::tryLock, "Lock.lock");
        return null;
    }

    /** Ends only the current guest thread: it is waiting for something that cannot happen. */
    private static EmulationAbort wouldBlockForever(String what) {
        return new EmulationAbort(EmulationAbort.Reason.BLOCKED, what + " would block forever");
    }

    /**
     * Parallel streams, sorts, prefixes and bulk map operations use the common pool, which
     * would start real threads and run guest lambdas on them (the emulator refuses that, and
     * the refusal would show the sample it is being emulated). They are made sequential.
     */
    private static void installParallelism(VirtualMachine vm) {
        StubRegistry stubs = vm.stubs;
        // StreamSupport.stream(spliterator, parallel) and friends: the last argument is the flag.
        stubs.onClass("java/util/stream/StreamSupport", ctx -> {
            if (ctx.desc.contains("Z)")) {
                Object[] args = ctx.args.clone();
                args[args.length - 1] = 0;
                return vm.host.invoke(ctx.owner, ctx.name, ctx.desc, null, args, false);
            }
            return ctx.proceed();
        });
        stubs.on("java/util/Arrays", "parallelPrefix", ThreadStubs::sequentialPrefix);
        // ConcurrentHashMap's bulk operations take a parallelism threshold first; the largest
        // value means "never split", so they run on the calling thread.
        Stub unsplit = ctx -> {
            if (ctx.receiver instanceof ConcurrentHashMap<?, ?> && ctx.desc.startsWith("(J")) {
                Object[] args = ctx.args.clone();
                args[0] = Long.MAX_VALUE;
                return vm.host.invoke(ctx.owner, ctx.name, ctx.desc, ctx.receiver, args, false);
            }
            return ctx.proceed(); // the ordinary Map forms, such as forEach(BiConsumer)
        };
        for (String name : new String[] {"forEach", "forEachKey", "forEachValue", "forEachEntry"}) {
            stubs.on("java/util/concurrent/ConcurrentHashMap", name, unsplit);
        }
        for (String kind : new String[] {"", "Keys", "Values", "Entries"}) {
            stubs.on("java/util/concurrent/ConcurrentHashMap", "search" + kind, unsplit);
            stubs.on("java/util/concurrent/ConcurrentHashMap", "reduce" + kind, unsplit);
            for (String to : new String[] {"ToInt", "ToLong", "ToDouble"}) {
                stubs.on("java/util/concurrent/ConcurrentHashMap", "reduce" + kind + to, unsplit);
            }
        }
        stubs.on("java/util/Collection", "parallelStream", ctx ->
                vm.host.invoke("java/util/Collection", "stream", "()Ljava/util/stream/Stream;", ctx.receiver, ctx.args, false));
        stubs.on("java/util/stream/BaseStream", "parallel", ctx -> ctx.receiver);
        stubs.on("java/util/Arrays", "parallelSort", ctx ->
                vm.host.invoke("java/util/Arrays", "sort", ctx.desc, null, ctx.args, false));
        stubs.on("java/util/Arrays", "parallelSetAll", ctx ->
                vm.host.invoke("java/util/Arrays", "setAll", ctx.desc, null, ctx.args, false));
    }

    /**
     * {@code Arrays.parallelPrefix}, done in a plain loop on the calling thread. Covers the
     * whole-array and range forms for object, int, long and double arrays, with the JDK's range
     * checks.
     */
    private static Object sequentialPrefix(CallContext ctx) {
        Object array = ctx.arg(0);
        if (array == null) {
            throw ctx.raise(new NullPointerException());
        }
        int length = java.lang.reflect.Array.getLength(array);
        boolean ranged = ctx.argCount() == 4;
        int from = ranged ? ctx.intArg(1) : 0;
        int to = ranged ? ctx.intArg(2) : length;
        Object op = ctx.arg(ctx.argCount() - 1);
        if (op == null) {
            throw ctx.raise(new NullPointerException());
        }
        if (from > to) {
            throw ctx.raise(new IllegalArgumentException("fromIndex(" + from + ") > toIndex(" + to + ")"));
        }
        if (from < 0) {
            throw ctx.raise(new ArrayIndexOutOfBoundsException(from));
        }
        if (to > length) {
            throw ctx.raise(new ArrayIndexOutOfBoundsException(to));
        }
        for (int i = from + 1; i < to; i++) {
            if (array instanceof int[] ints) {
                ints[i] = ((java.util.function.IntBinaryOperator) op).applyAsInt(ints[i - 1], ints[i]);
            } else if (array instanceof long[] longs) {
                longs[i] = ((java.util.function.LongBinaryOperator) op).applyAsLong(longs[i - 1], longs[i]);
            } else if (array instanceof double[] doubles) {
                doubles[i] = ((java.util.function.DoubleBinaryOperator) op).applyAsDouble(doubles[i - 1], doubles[i]);
            } else {
                Object[] objects = (Object[]) array;
                @SuppressWarnings("unchecked")
                java.util.function.BinaryOperator<Object> binary = (java.util.function.BinaryOperator<Object>) op;
                objects[i] = binary.apply(objects[i - 1], objects[i]);
            }
        }
        return null;
    }
}
