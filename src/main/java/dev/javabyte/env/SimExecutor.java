package dev.javabyte.env;

import dev.javabyte.vm.VirtualMachine;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Stands in for every JDK executor service. Submitted work is queued on the emulator's
 * scheduler and runs on the emulator thread; no worker thread is ever started.
 *
 * <p>It extends {@code ScheduledThreadPoolExecutor} purely for type compatibility (that class is
 * also a {@code ThreadPoolExecutor} and an {@code ExecutorService}). A pool only starts threads
 * when its own {@code execute} is called, and every submission method here is overridden.
 */
public final class SimExecutor extends ScheduledThreadPoolExecutor {

    private boolean shutdown;

    public SimExecutor() {
        super(0);
    }

    private <V> SimFuture<V> enqueue(Callable<V> task, String kind) {
        return enqueue(task, kind, 0);
    }

    /**
     * Queues a task that starts once {@code delayMillis} of virtual time has passed.
     *
     * <p>The delay is waited out in virtual time on the task's own guest thread, so a task
     * scheduled an hour ahead finds the clock an hour on when it runs, as a sample timing it
     * expects, yet the run does not take an hour.
     */
    private <V> SimFuture<V> enqueue(Callable<V> task, String kind, long delayMillis) {
        VirtualMachine vm = VirtualMachine.current();
        if (vm == null) {
            // Only reachable from host code; the wording is the JDK's, so nothing tells of the emulator.
            throw new RejectedExecutionException("Task rejected");
        }
        long due = delayMillis <= 0 ? Long.MIN_VALUE : vm.env.clock.peekMillis() + delayMillis;
        SimFuture<V> future = new SimFuture<>(task, due);
        Runnable body = delayMillis <= 0 ? future : () -> {
            vm.scheduler.awaitFor(future::isCancelled, due - vm.env.clock.peekMillis(), "scheduled delay");
            future.run();
        };
        vm.scheduler.submit("executor:" + kind, future, body);
        return future;
    }

    @Override public void execute(Runnable command) { enqueue(Executors.callable(command), "execute"); }
    @Override public Future<?> submit(Runnable task) { return enqueue(Executors.callable(task), "submit"); }
    @Override public <T> Future<T> submit(Runnable task, T result) { return enqueue(Executors.callable(task, result), "submit"); }
    @Override public <T> Future<T> submit(Callable<T> task) { return enqueue(task, "submit"); }

    // Delays pass in virtual time (see enqueue). Repeating tasks run threads.periodicRuns times,
    // one period apart.
    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return enqueue(Executors.callable(command), "schedule", unit.toMillis(delay));
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return enqueue(callable, "schedule", unit.toMillis(delay));
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return repeat(command, unit.toMillis(initialDelay), unit.toMillis(period));
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return repeat(command, unit.toMillis(initialDelay), unit.toMillis(delay));
    }

    /**
     * Queues the runs of a repeating task, the n-th one due at {@code initial + n * period}.
     * Each run is its own task, so the sleep budget and task limit apply as for any other.
     */
    private ScheduledFuture<?> repeat(Runnable command, long initialMillis, long periodMillis) {
        VirtualMachine vm = VirtualMachine.current();
        int runs = vm == null ? 1 : Math.max(1, vm.config.threads.periodicRuns);
        SimFuture<Object> last = null;
        for (int i = 0; i < runs; i++) {
            long delay = Math.max(initialMillis, 0) + (long) i * Math.max(periodMillis, 0);
            last = enqueue(Executors.callable(command), "periodic", delay);
        }
        return last;
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            SimFuture<T> future = new SimFuture<>(task);
            future.run();
            futures.add(future);
        }
        return futures;
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
        return invokeAll(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws ExecutionException {
        ExecutionException last = null;
        for (Future<T> future : invokeAll(tasks)) {
            try {
                return future.get();
            } catch (ExecutionException e) {
                last = e;
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        throw last != null ? last : new ExecutionException(new IllegalArgumentException("no tasks"));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws ExecutionException {
        return invokeAny(tasks);
    }

    @Override public void shutdown() { shutdown = true; }
    @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
    @Override public boolean isShutdown() { return shutdown; }
    @Override public boolean isTerminated() { return shutdown; }
    @Override public boolean isTerminating() { return false; }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        VirtualMachine vm = VirtualMachine.current();
        if (vm != null) {
            vm.scheduler.sleep(0);
        }
        return true;
    }
}
