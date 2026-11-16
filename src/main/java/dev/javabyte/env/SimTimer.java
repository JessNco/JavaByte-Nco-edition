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

    private static void enqueue(TimerTask task, boolean periodic) {
        VirtualMachine vm = VirtualMachine.current();
        int runs = periodic ? Math.max(1, vm.config.threads.periodicRuns) : 1;
        for (int i = 0; i < runs; i++) {
            vm.scheduler.submit("timer", task, () -> runTask(task));
        }
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

    public void schedule(TimerTask task, long delay) { enqueue(task, false); }
    public void schedule(TimerTask task, Date time) { enqueue(task, false); }
    public void schedule(TimerTask task, long delay, long period) { enqueue(task, true); }
    public void schedule(TimerTask task, Date firstTime, long period) { enqueue(task, true); }
    public void scheduleAtFixedRate(TimerTask task, long delay, long period) { enqueue(task, true); }
    public void scheduleAtFixedRate(TimerTask task, Date firstTime, long period) { enqueue(task, true); }
    public void cancel() { }
    public int purge() { return 0; }
}
