package dev.javabyte.vm;

import dev.javabyte.model.GuestInstance;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;

/**
 * Runs guest threads (and timers, executor jobs and shutdown hooks) with real interleaving but
 * without real parallelism.
 *
 * <p>Each guest thread is carried by its own host thread, so it has its own call stack and can
 * be suspended anywhere, even underneath JDK frames. A single baton decides which carrier runs:
 * exactly one executes guest code at any moment and all the others are parked. The baton moves
 *
 * <ul>
 *   <li>when the running thread blocks (sleep, join, wait, an empty queue, a held monitor), and
 *   <li>after a fixed number of bytecode instructions (pre-emption), so that a thread spinning
 *       on a flag cannot starve the thread that would set it.
 * </ul>
 *
 * <p>Why not let guest threads run freely in parallel? Because the baton gives three properties
 * that matter more to an analysis tool than throughput: the emulator's own data structures need
 * no locking, a run is repeatable (switch points depend only on instruction counts), and guest
 * code can never execute outside the emulator's control.
 *
 * <p>Monitors and {@code wait}/{@code notify} are implemented here rather than by the host JVM,
 * because a carrier that blocked on a real monitor would stop while still holding the baton.
 */
public final class Scheduler {

    /** Host stack for a carrier; guest recursion depth is bounded separately by config. */
    private static final long CARRIER_STACK_BYTES = 256L << 20;

    /** Instructions a thread may run before others get a turn (in units of the VM's check interval). */
    private static final int SLICE_CHECKS = 8;

    /** One guest thread of execution and the host thread that carries it. */
    final class Carrier {
        final String label;
        /** The guest object that represents this task (a Thread, a future); may be null. */
        final Object key;
        final Runnable body;
        final List<Frame> frames = new ArrayList<>();
        final Semaphore turn = new Semaphore(0);
        Thread host;
        boolean started;
        boolean finished;
        int sleeps;
        /** Depth of real JDK calls in progress; pre-emption is unsafe while it is non-zero. */
        int hostDepth;
        /** Set by notify(); cleared when the waiter resumes. */
        boolean notified;
        Object guestThread;

        Carrier(String label, Object key, Runnable body) {
            this.label = label;
            this.key = key;
            this.body = body;
        }
    }

    /** Ownership of one object's monitor. */
    private static final class Monitor {
        Carrier owner;
        int count;
    }

    private final VirtualMachine vm;
    /** Carriers waiting for the baton, in the order they will receive it. */
    private final Deque<Carrier> runnable = new ArrayDeque<>();
    private final List<Carrier> all = new ArrayList<>();
    private final Deque<Carrier> shutdownHooks = new ArrayDeque<>();
    private final Map<Object, Monitor> monitors = new IdentityHashMap<>();
    private final Map<Object, List<Carrier>> waiters = new IdentityHashMap<>();
    private final Carrier main = new Carrier("main", null, null);
    private Carrier current = main;
    private int submitted;
    private int sliceChecks;
    /** Consecutive turns in which a blocked carrier found its condition still false. */
    private int fruitlessPolls;
    /** An abort or defect raised on a carrier that must end the whole run; rethrown on main. */
    private Throwable pendingFatal;
    private boolean shuttingDown;

    Scheduler(VirtualMachine vm) {
        this.vm = vm;
        main.started = true;
    }

    // ------------------------------------------------------------------ state for the VM

    List<Frame> frames() {
        return current.frames;
    }

    void enterHostCall() {
        current.hostDepth++;
    }

    void exitHostCall() {
        current.hostDepth--;
    }

    /** Resets the main thread's sleep budget; called at the start of each entry point. */
    public void beginEntry() {
        main.sleeps = 0;
    }

    /**
     * The guest's view of {@code Thread.currentThread()}. Tasks that are not themselves threads
     * (executor jobs, timers) and the main thread get a real but never-started Thread object, so
     * the guest can name it or read its properties without touching a carrier.
     */
    public Object currentThread() {
        if (current.guestThread == null) {
            current.guestThread = current.key instanceof Thread thread ? thread
                    : new Thread(current == main ? "main" : "pool-1-thread-" + all.size());
        }
        return current.guestThread;
    }

    // ------------------------------------------------------------------ creating tasks

    /**
     * Makes a new guest thread of execution runnable.
     *
     * @param key the guest object that represents it, used by join and Future.get; may be null
     */
    public void submit(String label, Object key, Runnable body) {
        if (++submitted > vm.config.limits.maxTasks) {
            vm.events.addOnce("task-limit", "thread", "task-limit-reached", "limit", vm.config.limits.maxTasks);
            return;
        }
        Carrier carrier = new Carrier(label, key, body);
        all.add(carrier);
        fruitlessPolls = 0;
        if ("inline".equals(vm.config.threads.mode)) {
            // Run the new thread first; the starter continues when it blocks or finishes.
            runnable.addFirst(carrier);
            yieldToOthers("inline start");
        } else {
            runnable.addLast(carrier);
        }
    }

    public void addShutdownHook(String label, Object key, Runnable body) {
        shutdownHooks.add(new Carrier(label, key, body));
    }

    private Carrier find(Object key) {
        for (Carrier carrier : all) {
            if (carrier.key == key) {
                return carrier;
            }
        }
        return null;
    }

    /** True while the task for {@code key} has not finished; the basis of Thread.isAlive(). */
    public boolean isAlive(Object key) {
        Carrier carrier = find(key);
        return carrier != null && !carrier.finished;
    }

    /** Waits for the task for {@code key} to finish: Thread.join() and Future.get(). */
    public void join(Object key) {
        Carrier target = find(key);
        if (target != null && target != current) {
            blockUntil(() -> target.finished, "join " + target.label);
        }
    }

    /** Withdraws a task that has not started yet (Future.cancel, TimerTask.cancel). */
    public boolean cancel(Object key) {
        Carrier target = find(key);
        if (target == null || target.started) {
            return false;
        }
        target.finished = true;
        return runnable.remove(target);
    }

    // ------------------------------------------------------------------ passing the baton

    /** Lets every other runnable thread have a turn before the caller continues. */
    public void yieldToOthers(String reason) {
        if (runnable.isEmpty()) {
            return;
        }
        Carrier me = current;
        runnable.addLast(me);
        switchTo(runnable.poll());
    }

    /** Hands the baton to {@code next} and parks the calling carrier until it comes back. */
    private void switchTo(Carrier next) {
        Carrier me = current;
        if (next == me) {
            return;
        }
        resume(next);
        me.turn.acquireUninterruptibly();
        // From here the baton is ours again.
        if (me == main) {
            rethrowPendingFatal();
        } else if (shuttingDown) {
            throw new EmulationAbort(EmulationAbort.Reason.SLEEP_BUDGET, "run ended");
        }
    }

    private void resume(Carrier next) {
        current = next;
        if (next.started) {
            next.turn.release();
            return;
        }
        next.started = true;
        next.host = new Thread(null, () -> carry(next), "javabyte-guest-" + next.label, CARRIER_STACK_BYTES);
        // Daemon: a carrier stuck in a real JDK call must not keep the tool alive.
        next.host.setDaemon(true);
        next.host.start();
    }

    /** Body of a carrier's host thread. */
    private void carry(Carrier carrier) {
        vm.bindToCurrentThread();
        try {
            vm.events.add("thread", "run", "task", carrier.label);
            carrier.body.run();
        } catch (GuestThrow thrown) {
            vm.events.add("thread", "uncaught-exception", "task", carrier.label,
                    "exception", vm.types.nameOf(thrown.value), "message", messageOf(thrown));
        } catch (EmulationAbort abort) {
            if (abort.reason.taskScoped) {
                if (!shuttingDown) {
                    vm.events.add("thread", "stopped", "task", carrier.label, "reason", abort.getMessage());
                }
            } else {
                pendingFatal = abort;
            }
        } catch (RuntimeException | Error raw) {
            // Tasks run through a JDK interface (a Runnable handed to an executor) deliver
            // guest exceptions unwrapped; anything else is an emulator defect for main to report.
            if (vm.isGuestOrigin(raw)) {
                vm.events.add("thread", "uncaught-exception", "task", carrier.label, "exception", raw.getClass().getName());
            } else {
                pendingFatal = raw;
            }
        } finally {
            carrier.finished = true;
            releaseMonitorsOf(carrier);
            vm.unbind();
            fruitlessPolls = 0;
            passBatonOnExit();
        }
    }

    private void passBatonOnExit() {
        if (shuttingDown) {
            return;
        }
        // A fatal condition goes straight to main, which owns the run's outcome.
        Carrier next = pendingFatal != null && runnable.remove(main) ? main : runnable.poll();
        if (next != null) {
            resume(next);
        }
    }

    private void rethrowPendingFatal() {
        Throwable fatal = pendingFatal;
        if (fatal != null) {
            pendingFatal = null;
            if (fatal instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) fatal;
        }
    }

    /** Called by the VM at its periodic check; switches threads once the time slice is used up. */
    void preempt() {
        if (++sliceChecks < SLICE_CHECKS || runnable.isEmpty()) {
            return;
        }
        sliceChecks = 0;
        // Never switch away underneath a real JDK call: it may hold a JDK lock that another
        // guest thread would then block on for real, with the baton parked.
        if (current.hostDepth == 0) {
            fruitlessPolls = 0;
            yieldToOthers("time slice");
        }
    }

    // ------------------------------------------------------------------ blocking

    /**
     * Suspends the calling guest thread until {@code ready} holds.
     *
     * <p>The condition is re-checked each time the thread gets the baton. If every other thread
     * is also only waiting, nothing can ever make the condition true; the caller is then ended
     * with a task-scoped abort, which is the emulator's rendering of "this thread would hang
     * forever".
     */
    public void blockUntil(BooleanSupplier ready, String what) {
        while (!ready.getAsBoolean()) {
            if (runnable.isEmpty() || ++fruitlessPolls > 2 * (runnable.size() + 1)) {
                fruitlessPolls = 0;
                throw new EmulationAbort(EmulationAbort.Reason.SLEEP_BUDGET, what + " would block forever");
            }
            yieldToOthers(what);
        }
        fruitlessPolls = 0;
    }

    /**
     * Implements a guest sleep: virtual time moves forward instantly and other threads run.
     *
     * <p>Sleeping never waits in real time. That defeats "sleep through the sandbox" evasion
     * and lets beacon loops be observed quickly; the per-thread sleep budget then ends them.
     */
    public void sleep(long millis) {
        vm.env.clock.advance(Math.max(millis, 0));
        if (++current.sleeps > vm.config.limits.maxSleeps) {
            throw new EmulationAbort(EmulationAbort.Reason.SLEEP_BUDGET,
                    "task slept " + vm.config.limits.maxSleeps + " times");
        }
        // A sleeping thread is making progress in virtual time, so blocked threads keep waiting.
        fruitlessPolls = 0;
        yieldToOthers("sleep");
    }

    /** Runs until no guest thread is left. Called on main after each entry point returns. */
    public void drain() {
        while (!runnable.isEmpty()) {
            yieldToOthers("drain");
        }
        rethrowPendingFatal();
    }

    /** Runs registered shutdown hooks, once, at the end of the run. */
    public void runShutdownHooks() {
        while (!shutdownHooks.isEmpty()) {
            Carrier hook = shutdownHooks.poll();
            all.add(hook);
            runnable.addLast(hook);
        }
        drain();
    }

    /**
     * Ends every carrier that is still parked. Called once on main when the run is over, so no
     * host thread outlives the analysis.
     *
     * <p>Carriers are woken one at a time and each is given the baton while it unwinds, which
     * keeps the "one runs at a time" rule true to the very end.
     */
    public void shutdown() {
        shuttingDown = true;
        runnable.clear();
        for (Carrier carrier : all) {
            if (carrier.started && !carrier.finished && carrier.host != null) {
                current = carrier;
                carrier.turn.release();
                try {
                    carrier.host.join(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        current = main;
    }

    public int pending() {
        return runnable.size();
    }

    // ------------------------------------------------------------------ monitors

    /** {@code monitorenter}, and entry to a synchronized method. */
    public void monitorEnter(Object object) {
        acquireMonitor(object, 1);
    }

    /**
     * Takes an object's monitor, waiting for the current owner if there is one.
     *
     * @param count the hold count to add (more than one when re-entering after a wait)
     */
    private void acquireMonitor(Object object, int count) {
        Carrier me = current;
        while (true) {
            // Looked up afresh on every attempt: the entry is discarded whenever the monitor
            // becomes free, so a reference kept across a wait could be stale.
            Monitor monitor = monitors.computeIfAbsent(object, key -> new Monitor());
            if (monitor.owner == null || monitor.owner == me) {
                monitor.owner = me;
                monitor.count += count;
                return;
            }
            blockUntil(() -> {
                Monitor now = monitors.get(object);
                return now == null || now.owner == null;
            }, "monitor of " + Values.describe(object));
        }
    }

    /** {@code monitorexit}, and exit from a synchronized method. */
    public void monitorExit(Object object) {
        Monitor monitor = monitors.get(object);
        if (monitor != null && monitor.owner == current && --monitor.count == 0) {
            monitor.owner = null;
            monitors.remove(object);
        }
    }

    private void releaseMonitorsOf(Carrier carrier) {
        monitors.values().removeIf(monitor -> {
            if (monitor.owner == carrier) {
                monitor.owner = null;
                return true;
            }
            return false;
        });
        waiters.values().forEach(list -> list.remove(carrier));
    }

    /**
     * {@code Object.wait}: releases the monitor, suspends until notified (or, with a timeout,
     * until virtual time has passed), then takes the monitor back.
     *
     * @param timeoutMillis 0 for no timeout
     */
    public void objectWait(Object object, long timeoutMillis) {
        Monitor monitor = monitors.get(object);
        int held = monitor != null && monitor.owner == current ? monitor.count : 0;
        if (held > 0) {
            monitors.remove(object);
        }
        Carrier me = current;
        me.notified = false;
        waiters.computeIfAbsent(object, key -> new ArrayList<>()).add(me);
        try {
            if (timeoutMillis > 0) {
                // A timed wait behaves like a sleep that a notify can cut short.
                sleep(timeoutMillis);
            } else {
                blockUntil(() -> me.notified, "Object.wait");
            }
        } finally {
            List<Carrier> waiting = waiters.get(object);
            if (waiting != null) {
                waiting.remove(me);
                if (waiting.isEmpty()) {
                    waiters.remove(object);
                }
            }
            me.notified = false;
        }
        if (held > 0) {
            acquireMonitor(object, held);
        }
    }

    /** {@code Object.notify} and {@code notifyAll}. */
    public void objectNotify(Object object, boolean everyone) {
        List<Carrier> waiting = waiters.get(object);
        if (waiting == null) {
            return;
        }
        for (Carrier carrier : waiting) {
            if (!carrier.notified) {
                carrier.notified = true;
                fruitlessPolls = 0;
                if (!everyone) {
                    return;
                }
            }
        }
    }

    /** Message of a guest exception, read without running guest code. */
    public static String messageOf(GuestThrow thrown) {
        if (thrown.value instanceof Throwable throwable && !(thrown.value instanceof GuestInstance)) {
            return throwable.getMessage();
        }
        return null;
    }
}
