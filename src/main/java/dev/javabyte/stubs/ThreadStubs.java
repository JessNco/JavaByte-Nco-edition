package dev.javabyte.stubs;

import dev.javabyte.env.SimExecutor;
import dev.javabyte.env.SimTimer;
import dev.javabyte.host.CallContext;
import dev.javabyte.host.StubRegistry;
import dev.javabyte.vm.EmulationAbort;
import dev.javabyte.vm.VirtualMachine;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;
import org.objectweb.asm.Type;

/**
 * Threads and everything else that would start one.
 *
 * <p>The JDK is never allowed to start or block a thread on the sample's behalf. Starting a
 * thread hands its body to the {@link dev.javabyte.vm.Scheduler}, which interleaves guest
 * threads one at a time; pools, timers and asynchronous futures are redirected the same way;
 * and calls that would block waiting for another thread wait cooperatively instead.
 */
final class ThreadStubs {

    private ThreadStubs() {}

    static void install(VirtualMachine vm) {
        StubRegistry stubs = vm.stubs;
        String thread = "java/lang/Thread";

        stubs.on(thread, "currentThread", ctx -> vm.scheduler.currentThread());
        stubs.on(thread, "start", ctx -> {
            Object target = ctx.receiver;
            String name = target instanceof Thread real ? real.getName() : vm.types.nameOf(target);
            vm.events.add("thread", "start", "name", name, "class", vm.types.nameOf(target));
            vm.scheduler.submit("thread:" + name, target,
                    () -> vm.router.invokeVirtual(thread, "run", "()V", target, new Object[0]));
            return null;
        });
        stubs.on(thread, "sleep", ctx -> {
            long millis = ctx.arg(0) instanceof Long l ? l : ((java.time.Duration) ctx.arg(0)).toMillis();
            vm.scheduler.sleep(millis);
            return null;
        });
        stubs.on(thread, "join", ctx -> {
            vm.scheduler.join(ctx.receiver);
            return null;
        });
        stubs.on(thread, "isAlive", ctx -> vm.scheduler.isAlive(ctx.receiver) ? 1 : 0);
        stubs.on(thread, "yield", ctx -> yieldNow(vm, "Thread.yield"));
        stubs.on(thread, "onSpinWait", ctx -> yieldNow(vm, "Thread.onSpinWait"));
        stubs.on(thread, "interrupt", ctx -> null);
        stubs.on(thread, "isInterrupted", ctx -> 0);
        stubs.on(thread, "interrupted", ctx -> 0);
        stubs.on(thread, "stop", ctx -> null);
        stubs.on(thread, "suspend", ctx -> null);
        stubs.on(thread, "resume", ctx -> null);
        stubs.on(thread, "holdsLock", ctx -> 1);
        stubs.on(thread, "activeCount", ctx -> 1);
        stubs.on(thread, "dumpStack", ctx -> null);
        stubs.on(thread, "getState", ctx -> Thread.State.RUNNABLE);
        stubs.on(thread, "getContextClassLoader", ctx -> vm.env.appLoader);
        stubs.on(thread, "setContextClassLoader", ctx -> null);
        stubs.on(thread, "setDefaultUncaughtExceptionHandler", ctx -> null);
        stubs.on(thread, "getDefaultUncaughtExceptionHandler", ctx -> null);
        stubs.on(thread, "getAllStackTraces", ctx -> Map.of());
        stubs.on(thread, "getStackTrace", ctx -> {
            // A real trace starts with the getStackTrace frame itself; samples index past it.
            StackTraceElement[] guest = vm.guestStackTrace();
            StackTraceElement[] trace = new StackTraceElement[guest.length + 1];
            trace[0] = new StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", 1559);
            System.arraycopy(guest, 0, trace, 1, guest.length);
            return trace;
        });

        stubs.on("java/util/concurrent/TimeUnit", "sleep", ctx -> {
            vm.scheduler.sleep(((TimeUnit) ctx.receiver).toMillis((Long) ctx.arg(0)));
            return null;
        });

        installExecutors(vm);
        installBlockingCalls(vm);
        installParallelism(vm);
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
        // pool. Each is redirected to its synchronous twin.
        stubs.onClass("java/util/concurrent/CompletableFuture", ThreadStubs::completableFuture);
    }

    private static Object completableFuture(CallContext ctx) throws Exception {
        if (ctx.name.equals("runAsync")) {
            ((Runnable) ctx.arg(0)).run();
            return CompletableFuture.completedFuture(null);
        }
        if (ctx.name.equals("supplyAsync")) {
            return CompletableFuture.completedFuture(((Supplier<?>) ctx.arg(0)).get());
        }
        if (ctx.name.equals("delayedExecutor") || ctx.name.equals("defaultExecutor")) {
            return new SimExecutor();
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
            // Timed form: give the others one turn, then report whether the count reached zero.
            vm.scheduler.sleep(((TimeUnit) ctx.arg(1)).toMillis((Long) ctx.arg(0)));
            return latch.getCount() == 0 ? 1 : 0;
        });
        // A real lock() would park the host thread while it holds the baton.
        stubs.on("java/util/concurrent/locks/Lock", "lock", ctx -> acquire(vm, (Lock) ctx.receiver));
        stubs.on("java/util/concurrent/locks/Lock", "lockInterruptibly", ctx -> acquire(vm, (Lock) ctx.receiver));
        stubs.on("java/util/concurrent/locks/Condition", "await", ctx -> {
            // Returning early is allowed (a spurious wake-up); callers re-check their condition.
            vm.scheduler.sleep(0);
            return ctx.returnType().getSort() == Type.VOID ? null : (Object) 1;
        });
        stubs.on("java/util/concurrent/locks/LockSupport", "park", ctx -> yieldNow(vm, "LockSupport.park"));
        stubs.on("java/util/concurrent/locks/LockSupport", "parkNanos", ctx -> yieldNow(vm, "LockSupport.parkNanos"));
        stubs.on("java/util/concurrent/BlockingQueue", "take", ctx -> {
            BlockingQueue<?> queue = (BlockingQueue<?>) ctx.receiver;
            vm.scheduler.blockUntil(() -> !queue.isEmpty(), "BlockingQueue.take");
            return queue.poll();
        });
        stubs.on("java/util/concurrent/BlockingQueue", "poll", ctx -> {
            BlockingQueue<?> queue = (BlockingQueue<?>) ctx.receiver;
            if (ctx.argCount() == 2 && queue.isEmpty()) {
                vm.scheduler.sleep(((TimeUnit) ctx.arg(1)).toMillis((Long) ctx.arg(0)));
            }
            return queue.poll();
        });
        stubs.on("java/util/concurrent/Semaphore", "acquire", ctx -> {
            Semaphore semaphore = (Semaphore) ctx.receiver;
            int permits = ctx.argCount() == 0 ? 1 : ctx.intArg(0);
            vm.scheduler.blockUntil(() -> semaphore.tryAcquire(permits), "Semaphore.acquire");
            return null;
        });
        stubs.on("java/util/concurrent/CyclicBarrier", "await", ctx -> {
            throw wouldBlockForever("CyclicBarrier.await");
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

    /** Parallel streams and sorts use the common pool; they are made sequential. */
    private static void installParallelism(VirtualMachine vm) {
        StubRegistry stubs = vm.stubs;
        stubs.on("java/util/Collection", "parallelStream", ctx ->
                vm.host.invoke("java/util/Collection", "stream", "()Ljava/util/stream/Stream;", ctx.receiver, ctx.args, false));
        stubs.on("java/util/stream/BaseStream", "parallel", ctx -> ctx.receiver);
        stubs.on("java/util/Arrays", "parallelSort", ctx ->
                vm.host.invoke("java/util/Arrays", "sort", ctx.desc, null, ctx.args, false));
        stubs.on("java/util/Arrays", "parallelSetAll", ctx ->
                vm.host.invoke("java/util/Arrays", "setAll", ctx.desc, null, ctx.args, false));
    }
}
