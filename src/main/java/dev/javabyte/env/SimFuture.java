package dev.javabyte.env;

import dev.javabyte.vm.EmulationAbort;
import dev.javabyte.vm.VirtualMachine;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Future for a task queued on the emulator's scheduler. Asking for the result runs the task on
 * the spot if it has not run yet, which is the single-threaded equivalent of waiting for it.
 */
public final class SimFuture<V> implements RunnableScheduledFuture<V> {

    private final Callable<V> task;
    private V result;
    private Throwable failure;
    private boolean done;
    private boolean cancelled;

    SimFuture(Callable<V> task) {
        this.task = task;
    }

    @Override
    public void run() {
        if (done || cancelled) {
            return;
        }
        done = true;
        try {
            result = task.call();
        } catch (EmulationAbort abort) {
            throw abort;
        } catch (Throwable thrown) {
            // As with a real executor, a failing task does not propagate; it is stored for get().
            failure = thrown;
        }
    }

    /** Waits for the task, as a real {@code get()} would, by letting other guest threads run. */
    private void ensureRun() {
        VirtualMachine vm = VirtualMachine.current();
        if (vm != null && vm.scheduler.isAlive(this)) {
            vm.scheduler.join(this);
        }
        if (!done && !cancelled) {
            // Not scheduled (an invokeAll task, or use outside a run): run it here.
            run();
        }
    }

    @Override
    public V get() throws ExecutionException {
        ensureRun();
        if (failure != null) {
            throw new ExecutionException(failure);
        }
        return result;
    }

    @Override
    public V get(long timeout, TimeUnit unit) throws ExecutionException {
        return get();
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (done) {
            return false;
        }
        cancelled = true;
        VirtualMachine vm = VirtualMachine.current();
        if (vm != null) {
            vm.scheduler.cancel(this);
        }
        return true;
    }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public boolean isDone() { return done || cancelled; }
    @Override public boolean isPeriodic() { return false; }
    @Override public long getDelay(TimeUnit unit) { return 0; }
    @Override public int compareTo(Delayed other) { return 0; }
}
