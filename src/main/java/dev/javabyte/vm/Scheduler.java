package dev.javabyte.vm;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Runs everything the sample starts concurrently (threads, timers, executor jobs, shutdown
 * hooks) as queued tasks on the single emulator thread.
 *
 * <p>Tasks are cooperative. A task runs until it finishes or reaches a point where a real thread
 * would block ({@code sleep}, {@code join}, {@code wait}); at those points queued tasks get a
 * turn. This ordering is deterministic, so two runs of the same sample with the same config
 * produce the same report, which a real thread scheduler could never promise.
 *
 * <p>The trade-off is that code relying on true parallelism or precise timing sees a different
 * interleaving from a real machine. For behavioural analysis that is almost always acceptable.
 */
public final class Scheduler {

    /** One queued unit of guest work. {@code key} identifies it for {@code join}/{@code get}. */
    private record Task(String label, Object key, Runnable body) {}

    /** Nested yields are capped so tasks that all sleep cannot recurse without bound. */
    private static final int MAX_YIELD_DEPTH = 6;

    private final VirtualMachine vm;
    private final Deque<Task> queue = new ArrayDeque<>();
    private final Deque<Task> shutdownHooks = new ArrayDeque<>();
    private int submitted;
    private int sleepsInCurrentTask;
    private int yieldDepth;
    private Object currentThread;
    private Thread mainThread;

    Scheduler(VirtualMachine vm) {
        this.vm = vm;
    }

    /**
     * Queues a task, or runs it at once in {@code inline} thread mode.
     *
     * @param key the guest object that represents the task (a Thread, a future); may be null
     */
    public void submit(String label, Object key, Runnable body) {
        if (++submitted > vm.config.limits.maxTasks) {
            vm.events.addOnce("task-limit", "thread", "task-limit-reached", "limit", vm.config.limits.maxTasks);
            return;
        }
        Task task = new Task(label, key, body);
        if ("inline".equals(vm.config.threads.mode)) {
            run(task);
        } else {
            queue.add(task);
        }
    }

    public void addShutdownHook(String label, Object key, Runnable body) {
        shutdownHooks.add(new Task(label, key, body));
    }

    public boolean isQueued(Object key) {
        return queue.stream().anyMatch(task -> task.key() == key);
    }

    /** Runs the task for {@code key} now if it is still queued; the basis of join() and Future.get(). */
    public void runNow(Object key) {
        for (Iterator<Task> it = queue.iterator(); it.hasNext();) {
            Task task = it.next();
            if (task.key() == key) {
                it.remove();
                run(task);
                return;
            }
        }
    }

    /** Removes a queued task without running it (Future.cancel, Timer.cancel). */
    public boolean cancel(Object key) {
        return queue.removeIf(task -> task.key() == key);
    }

    /** Gives every currently queued task a turn. Called where a real thread would block. */
    public void yieldToOthers(String reason) {
        if (yieldDepth >= MAX_YIELD_DEPTH) {
            return;
        }
        yieldDepth++;
        try {
            // Only tasks queued before this point run, so a task that re-queues itself
            // cannot keep the yielding task waiting forever.
            for (int pending = queue.size(); pending > 0 && !queue.isEmpty(); pending--) {
                run(queue.poll());
            }
        } finally {
            yieldDepth--;
        }
    }

    /**
     * Implements a guest sleep: virtual time moves forward instantly and other tasks get a turn.
     *
     * <p>Sleeping never waits in real time. That defeats "sleep through the sandbox" evasion
     * and lets beacon loops be observed quickly; the per-task sleep budget then ends them.
     */
    public void sleep(long millis) {
        vm.env.clock.advance(Math.max(millis, 0));
        if (++sleepsInCurrentTask > vm.config.limits.maxSleeps) {
            throw new EmulationAbort(EmulationAbort.Reason.SLEEP_BUDGET,
                    "task slept " + vm.config.limits.maxSleeps + " times");
        }
        yieldToOthers("sleep");
    }

    /** Runs queued tasks until none remain. Called after each entry point returns. */
    public void drain() {
        while (!queue.isEmpty()) {
            run(queue.poll());
        }
    }

    /** Runs registered shutdown hooks, once, at the end of the run. */
    public void runShutdownHooks() {
        while (!shutdownHooks.isEmpty()) {
            run(shutdownHooks.poll());
        }
    }

    private void run(Task task) {
        int savedSleeps = sleepsInCurrentTask;
        Object savedThread = currentThread;
        sleepsInCurrentTask = 0;
        if (task.key() instanceof Thread thread) {
            currentThread = thread;
        }
        vm.events.add("thread", "run", "task", task.label());
        try {
            task.body().run();
        } catch (GuestThrow thrown) {
            vm.events.add("thread", "uncaught-exception", "task", task.label(),
                    "exception", vm.types.nameOf(thrown.value), "message", messageOf(thrown));
        } catch (EmulationAbort abort) {
            if (!abort.reason.taskScoped) {
                throw abort;
            }
            vm.events.add("thread", "stopped", "task", task.label(), "reason", abort.getMessage());
        } catch (RuntimeException | Error raw) {
            // Tasks run through a JDK interface (a Runnable handed to an executor) deliver
            // guest exceptions unwrapped.
            if (!vm.isGuestOrigin(raw)) {
                throw raw;
            }
            vm.events.add("thread", "uncaught-exception", "task", task.label(), "exception", raw.getClass().getName());
        } finally {
            sleepsInCurrentTask = savedSleeps;
            currentThread = savedThread;
        }
    }

    /** Message of a guest exception, read without running guest code. */
    public static String messageOf(GuestThrow thrown) {
        if (thrown.value instanceof Throwable throwable && !(thrown.value instanceof dev.javabyte.model.GuestInstance)) {
            return throwable.getMessage();
        }
        return null;
    }

    /** Resets the per-task sleep budget; called at the start of each entry point. */
    public void beginEntry() {
        sleepsInCurrentTask = 0;
    }

    /**
     * The guest's view of {@code Thread.currentThread()}: the Thread object whose body is
     * running, or a "main" thread object. The latter is a real but never-started Thread, so the
     * guest can name it or read its properties without affecting the emulator thread.
     */
    public Object currentThread() {
        if (currentThread != null) {
            return currentThread;
        }
        if (mainThread == null) {
            mainThread = new Thread("main");
        }
        return mainThread;
    }

    public int pending() {
        return queue.size();
    }
}
