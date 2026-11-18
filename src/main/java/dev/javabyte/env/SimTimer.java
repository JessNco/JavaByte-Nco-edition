package dev.javabyte.env;

import dev.javabyte.host.StandIn;
import dev.javabyte.vm.VirtualMachine;
import java.util.Date;
import java.util.TimerTask;

/**
 * Imitates {@code java.util.Timer}, whose constructor starts a real thread. Scheduled tasks are
 * queued on the emulator's scheduler instead.
 */
public final class SimTimer implements StandIn {

    @Override
    public String standsFor() {
        return "java/util/Timer";
    }

    /**
     * Queues the runs of a task. The first is due after {@code delayMillis}, and for a repeating
     * task each further run (up to {@code threads.periodicRuns}) one period later. Each run waits
     * for its due time in virtual time, so the sample sees the clock where a real timer would
     * have left it, without the run taking that long.
     *
     * @param periodMillis 0 for a task that runs once
     */
    private static void enqueue(TimerTask task, long delayMillis, long periodMillis) {
        VirtualMachine vm = VirtualMachine.current();
        int runs = periodMillis > 0 ? Math.max(1, vm.config.threads.periodicRuns) : 1;
        long start = vm.env.clock.peekMillis();
        for (int i = 0; i < runs; i++) {
            long due = start + Math.max(delayMillis, 0) + i * periodMillis;
            vm.scheduler.submit("timer", task, () -> {
                vm.scheduler.awaitFor(() -> false, due - vm.env.clock.peekMillis(), "timer delay");
                runTask(task);
            });
        }
    }

    /** The delay until {@code time}, in virtual time; a time in the past means "now". */
    private static long until(Date time) {
        return time.getTime() - VirtualMachine.current().env.clock.peekMillis();
    }

    /**
     * The task is a shell around guest code, which throws guest exceptions in their raw form.
     * They are re-wrapped here so the scheduler can tell them from emulator defects.
     */
    private static void runTask(TimerTask task) {
        try {
            task.run();
        } catch (dev.javabyte.vm.GuestThrow | dev.javabyte.vm.EmulationAbort passThrough) {
            throw passThrough;
        } catch (Throwable thrown) {
            throw new dev.javabyte.vm.GuestThrow(thrown);
        }
    }

    public void schedule(TimerTask task, long delay) { enqueue(task, delay, 0); }
    public void schedule(TimerTask task, Date time) { enqueue(task, until(time), 0); }
    public void schedule(TimerTask task, long delay, long period) { enqueue(task, delay, period); }
    public void schedule(TimerTask task, Date firstTime, long period) { enqueue(task, until(firstTime), period); }
    public void scheduleAtFixedRate(TimerTask task, long delay, long period) { enqueue(task, delay, period); }
    public void scheduleAtFixedRate(TimerTask task, Date firstTime, long period) { enqueue(task, until(firstTime), period); }
    public void cancel() { }
    public int purge() { return 0; }
}
