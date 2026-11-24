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
        /** The guest's {@code Thread.currentThread()} object for this carrier, made on first use. */
        Object guestThread;
        /**
         * While the carrier is in a timed wait: the virtual time, in nanoseconds since the run
         * started, at which the wait ends. {@code Long.MAX_VALUE} otherwise.
         */
        long deadlineNanos = Long.MAX_VALUE;

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
    /**
     * The carrier holding the baton. Like the rest of this class's state it is neither volatile
     * nor locked, although many host threads touch it: only the carrier holding the baton
     * does, and the baton is handed over with {@code Semaphore.release}/{@code acquire} (or
     * {@code Thread.start}), which gives the next holder a happens-before edge over everything
     * the previous one wrote.
     */
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
        if (current == main) {
            return mainThread();
        }
        if (current.guestThread == null) {
            current.guestThread = current.key instanceof Thread thread ? thread
                    : new Thread("pool-1-thread-" + all.size());
        }
        return current.guestThread;
    }

    /**
     * The guest's main thread object, made on first use from whichever guest thread asks.
     *
     * <p>It is created on a carrier, which is a daemon host thread, so it would inherit
     * daemon status; a real main thread is not a daemon, and samples check. Its id, text form
     * and the like are presented by {@code ThreadStubs}.
     */
    public Object mainThread() {
        if (main.guestThread == null) {
            Thread thread = new Thread("main");
            thread.setDaemon(false);
            thread.setPriority(Thread.NORM_PRIORITY);
            main.guestThread = thread;
        }
        return main.guestThread;
    }

    /** True if {@code thread} is the guest thread object of the carrier running now. */
    public boolean isCurrent(Object thread) {
        return thread != null && (thread == current.key || thread == current.guestThread
                || (current == main && thread == mainThread()));
    }

    /**
     * The guest thread objects that are alive, in a fixed order: main first, then the others in
     * the order they were started. Used for {@code Thread.getAllStackTraces()} and
     * {@code Thread.activeCount()}, which on a real JVM always include the caller.
     */
    public List<Object> liveThreads() {
        List<Object> live = new ArrayList<>();
        live.add(mainThread());
        for (Carrier carrier : all) {
            Object thread = carrier.key instanceof Thread ? carrier.key : carrier.guestThread;
            if (!carrier.finished && thread != null && !live.contains(thread)) {
                live.add(thread);
            }
        }
        Object me = currentThread();
        if (!live.contains(me)) {
            live.add(me);
        }
        return live;
    }

    /**
     * The guest call stack of another live guest thread, innermost frame first, as
     * {@code Thread.getStackTrace()} on that thread would give it. Empty for a thread that has
     * not started, has finished, or is not a guest thread.
     */
    public StackTraceElement[] stackOf(Object thread) {
        Carrier carrier = thread != null && thread == main.guestThread ? main : find(thread);
        if (carrier == null || !carrier.started || carrier.finished) {
            return new StackTraceElement[0];
        }
        List<Frame> frames = carrier.frames;
        StackTraceElement[] trace = new StackTraceElement[frames.size()];
        for (int i = 0; i < trace.length; i++) {
            Frame frame = frames.get(frames.size() - 1 - i);
            dev.javabyte.model.GuestMethod method = frame.method;
            int line = method.lines.length == 0 ? -1 : method.lines[Math.min(frame.pc, method.lines.length - 1)];
            trace[i] = new StackTraceElement(method.owner.dottedName(), method.name, method.owner.node.sourceFile, line);
        }
        return trace;
    }

    /**
     * The state a real JVM would report for a guest thread object: NEW before it is started,
     * TERMINATED after it ends, and RUNNABLE in between (the emulator does not track finer
     * states for threads other than the caller).
     */
    public Thread.State stateOf(Object thread) {
        if (isCurrent(thread) || (thread != null && thread == main.guestThread)) {
            return Thread.State.RUNNABLE;
        }
        Carrier carrier = find(thread);
        if (carrier == null) {
            return Thread.State.NEW;
        }
        return carrier.finished ? Thread.State.TERMINATED : Thread.State.RUNNABLE;
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

    /**
     * The carrier for a task, found by the guest object that represents it or by the thread
     * object its code saw as {@code Thread.currentThread()}. Main is not included.
     */
    private Carrier find(Object key) {
        if (key == null) {
            return null;
        }
        for (Carrier carrier : all) {
            if (carrier.key == key) {
                return carrier;
            }
        }
        for (Carrier carrier : all) {
            if (carrier.guestThread == key) {
                return carrier;
            }
        }
        return null;
    }

    /**
     * True while the task for {@code key} has not finished; the basis of Thread.isAlive(). The
     * main thread is alive for the whole run, as it is while a real program runs.
     */
    public boolean isAlive(Object key) {
        if (key != null && key == main.guestThread) {
            return true;
        }
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

    /**
     * {@code Thread.join(millis)}: waits for the task for {@code key} to finish, but for no more
     * than {@code timeoutMillis} of virtual time.
     *
     * @return true if the task has finished (or was never started), false if the time ran out
     */
    public boolean join(Object key, long timeoutMillis) {
        Carrier target = find(key);
        if (target == null || target == current) {
            return target == null || target.finished;
        }
        return awaitFor(() -> target.finished, timeoutMillis, "join " + target.label);
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

    /**
     * Lets every other runnable thread have a turn before the caller continues.
     *
     * @param reason why the caller is giving way. Not used by the scheduler itself; it documents
     *               each call site and is the natural place to add switch tracing later.
     */
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
        if (!resume(next)) {
            // The other thread could not be started, so the baton never left. Undo the
            // caller's place in the queue and carry on.
            runnable.remove(me);
            return;
        }
        me.turn.acquireUninterruptibly();
        // From here the baton is ours again.
        if (me == main) {
            rethrowPendingFatal();
        } else if (shuttingDown) {
            throw new EmulationAbort(EmulationAbort.Reason.SHUTDOWN, "run ended");
        }
    }

    /**
     * Makes {@code next} the running carrier, starting its host thread on first use.
     *
     * @return false if the host refused to create a thread; the baton then stays with the caller
     */
    private boolean resume(Carrier next) {
        Carrier previous = current;
        current = next;
        if (next.started) {
            next.turn.release();
            return true;
        }
        next.started = true;
        next.host = new Thread(null, () -> carry(next), "javabyte-guest-" + next.label, CARRIER_STACK_BYTES);
        // Daemon: a carrier stuck in a real JDK call must not keep the tool alive.
        next.host.setDaemon(true);
        try {
            next.host.start();
            return true;
        } catch (OutOfMemoryError noThread) {
            // The guest sees a thread that never ran, and the report says why.
            current = previous;
            next.finished = true;
            vm.events.add("gap", "thread-not-started", "task", next.label, "error", String.valueOf(noThread.getMessage()));
            return false;
        }
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
                if (abort.reason != EmulationAbort.Reason.SHUTDOWN) {
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
        // Skip over any thread the host cannot start; main is already running and always resumes.
        while (next != null && !resume(next)) {
            next = runnable.poll();
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
            // A thread in a timed wait will move virtual time on when everyone is stuck, which
            // may unblock this one, so "forever" is only certain when there is none.
            if (runnable.isEmpty() || (pollWasFruitless() && !anyTimedWaiter())) {
                fruitlessPolls = 0;
                throw new EmulationAbort(EmulationAbort.Reason.BLOCKED, what + " would block forever");
            }
            yieldToOthers(what);
        }
        fruitlessPolls = 0;
    }

    /**
     * Counts one more look at a condition that was still false, and says whether everyone is
     * now taken to be stuck. Two full rounds of the other runnable threads (plus one, so a
     * lone waiter is not condemned on its first look) is long enough for any thread that
     * could make the condition true to have had its turn twice.
     */
    private boolean pollWasFruitless() {
        return ++fruitlessPolls > 2 * (runnable.size() + 1);
    }

    /** True if a runnable carrier is in a timed wait. */
    private boolean anyTimedWaiter() {
        for (Carrier carrier : runnable) {
            if (carrier.deadlineNanos != Long.MAX_VALUE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Timed waits ({@code wait(millis)}, {@code join(millis)}, {@code parkNanos}, a delayed
     * executor task, ...): suspends the calling guest thread until {@code ready} holds or
     * {@code timeoutMillis} of virtual time has passed, whichever is first.
     *
     * @return the final value of {@code ready}: false means the wait timed out
     * @see #awaitForNanos
     */
    public boolean awaitFor(BooleanSupplier ready, long timeoutMillis, String what) {
        long nanos = timeoutMillis <= 0 ? 0 : timeoutMillis > Long.MAX_VALUE / 1_000_000L ? Long.MAX_VALUE : timeoutMillis * 1_000_000L;
        return awaitForNanos(ready, nanos, what);
    }

    /**
     * Like {@link #awaitFor}, with the timeout in nanoseconds.
     *
     * <p>Other threads run while the caller waits. Their work moves virtual time on (executed
     * instructions cost time, and their sleeps jump it forward), so a notify, an unpark or a
     * finishing task that comes in time ends the wait early, with only the time really used
     * gone from the clock, as on a real JVM. When no other thread can make progress, or the
     * caller has waited {@link #PATIENT_TURNS} turns, the clock jumps to the caller's deadline
     * (or to another waiter's, if that comes first): the wait times out at no real cost. Only a
     * jump to the caller's own deadline counts against the sleep budget
     * ({@code limits.maxSleeps}), so a loop that waits on nothing is still ended, while a wait
     * cut short by a notify is free.
     */
    public boolean awaitForNanos(BooleanSupplier ready, long timeoutNanos, String what) {
        return timedWait(ready, timeoutNanos, PATIENT_TURNS, true, what);
    }

    /**
     * Turns a timed wait lets the other threads have before it gives up on them and lets its
     * time pass. Enough for a notify that needs a few million instructions of work first; a
     * thread that computes for longer then sees the waiter time out, as if it were slow.
     */
    private static final int PATIENT_TURNS = 64;

    /**
     * The common core of sleeps and timed waits.
     *
     * <p>Every waiting carrier records its deadline, so that the clock never jumps past one: a
     * thread jumps only to its own deadline when that is the nearest, or, when every thread is
     * stuck, to the nearest deadline overall, and the thread that is due then runs. A sleep of
     * 100 s and a join of 30 s therefore end in the right order, at the right virtual times,
     * and a sleeping thread never cuts short another thread's wait that a notify could end.
     *
     * @param patience   turns to give the other threads before jumping although they are busy
     * @param countJump  whether jumping to the caller's own deadline counts as a sleep
     * @return the final value of {@code ready}
     */
    private boolean timedWait(BooleanSupplier ready, long timeoutNanos, int patience, boolean countJump, String what) {
        dev.javabyte.env.VirtualClock clock = vm.env.clock;
        Carrier me = current;
        long start = clock.elapsedNanos();
        long deadline = start + Math.max(timeoutNanos, 0);
        if (deadline < start || deadline == Long.MAX_VALUE) {
            deadline = Long.MAX_VALUE - 1; // saturate; MAX_VALUE itself means "not waiting"
        }
        me.deadlineNanos = deadline;
        int turns = 0;
        try {
            while (!ready.getAsBoolean()) {
                long now = clock.elapsedNanos();
                if (now >= deadline) {
                    return false;
                }
                boolean stuck = runnable.isEmpty() || pollWasFruitless();
                if (stuck || turns >= patience) {
                    // Out of patience, a thread may only let its own time pass, and only if no
                    // other deadline comes first: that other waiter decides for itself. When
                    // everyone is stuck, the nearest deadline overall is the one to reach.
                    long other = nearestOtherDeadline();
                    long target = stuck ? Math.min(deadline, other) : deadline <= other ? deadline : now;
                    if (target > now) {
                        // Nothing will happen before the target: let that much time pass.
                        clock.advanceNanos(target - now);
                        fruitlessPolls = 0;
                        if (target == deadline) {
                            if (countJump) {
                                countSleep();
                            }
                            return ready.getAsBoolean();
                        }
                    }
                }
                turns++;
                yieldToOthers(what);
            }
            fruitlessPolls = 0;
            return true;
        } finally {
            me.deadlineNanos = Long.MAX_VALUE;
        }
    }

    /** The nearest deadline of the runnable carriers that are waiting, or {@code Long.MAX_VALUE}. */
    private long nearestOtherDeadline() {
        long nearest = Long.MAX_VALUE;
        for (Carrier carrier : runnable) {
            nearest = Math.min(nearest, carrier.deadlineNanos);
        }
        return nearest;
    }

    /**
     * Implements a guest sleep: virtual time moves forward at once and other threads run.
     *
     * <p>Sleeping never waits in real time. That defeats "sleep through the sandbox" evasion
     * and lets beacon loops be observed quickly; the per-thread sleep budget then ends them.
     * The other threads get one turn first, and the clock never jumps past another thread's
     * deadline (see {@link #timedWait}), so threads still wake in the order their times say.
     * A busy thread does not hold a sleeper back.
     */
    public void sleep(long millis) {
        countSleep();
        // A sleeping thread is making progress in virtual time, so blocked threads keep waiting.
        fruitlessPolls = 0;
        if (millis <= 0) {
            yieldToOthers("sleep");
            return;
        }
        long nanos = millis > Long.MAX_VALUE / 1_000_000L ? Long.MAX_VALUE : millis * 1_000_000L;
        timedWait(() -> false, nanos, 1, false, "sleep");
        fruitlessPolls = 0;
        yieldToOthers("sleep");
    }

    /** Charges one sleep to the current thread's budget, ending the thread when it is spent. */
    private void countSleep() {
        if (++current.sleeps > vm.config.limits.maxSleeps) {
            throw new EmulationAbort(EmulationAbort.Reason.SLEEP_BUDGET,
                    "task slept " + vm.config.limits.maxSleeps + " times");
        }
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
                    // Unwinding takes microseconds. The bound is for a carrier stuck inside a real
                    // JDK call, which must not hold up the report; it is a daemon and is left behind.
                    carrier.host.join(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        current = main;
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
     * until that much virtual time has passed, whichever comes first), then takes the monitor
     * back.
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
                // A notify ends the wait early, and only the time actually waited passes on
                // the clock. A wait that times out counts against the sleep budget only when
                // the clock had to jump for it (see awaitForNanos).
                awaitFor(() -> me.notified, timeoutMillis, "Object.wait");
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
