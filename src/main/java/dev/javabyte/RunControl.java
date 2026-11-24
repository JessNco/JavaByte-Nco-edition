package dev.javabyte;

import dev.javabyte.vm.EmulationAbort;
import dev.javabyte.vm.VirtualMachine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Links a running emulation with whoever started it: listeners that watch it, and a way to stop
 * it early while keeping everything gathered so far.
 *
 * <p>One control belongs to one run. Stopping is cooperative: the request is noticed at the
 * emulator's next checkpoint (every few thousand instructions, where the time and instruction
 * limits are checked), so asking costs the running code nothing.
 */
public final class RunControl {

    /** How often progress is reported while guest code runs. */
    public static final long PROGRESS_INTERVAL_MILLIS = 500;

    private final List<RunListener> listeners = new ArrayList<>();
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile boolean stopRequested;
    private volatile VirtualMachine vm;
    private long startedNanos;
    private long lastProgressNanos;
    private String currentEntry = "";

    public RunControl listen(RunListener listener) {
        listeners.add(listener);
        return this;
    }

    /**
     * Ends the run as soon as possible with outcome {@code stopped}. The report is still
     * written. Safe to call from any thread, any number of times, before or during the run.
     */
    public void requestStop() {
        stopRequested = true;
        VirtualMachine running = vm;
        if (running != null) {
            running.cancel(EmulationAbort.Reason.STOPPED);
        }
    }

    public boolean stopRequested() {
        return stopRequested;
    }

    /** Waits until the run has finished and its report is complete. */
    public boolean awaitFinished(long timeout, TimeUnit unit) throws InterruptedException {
        return finished.await(timeout, unit);
    }

    // ------------------------------------------------------------------ called by the emulator

    /**
     * Connects this control to the run's machine, so that events and progress reach the listeners
     * and a stop request reaches the run.
     */
    void attach(VirtualMachine machine) {
        this.vm = machine;
        startedNanos = System.nanoTime();
        lastProgressNanos = startedNanos;
        if (stopRequested) {
            machine.cancel(EmulationAbort.Reason.STOPPED);
        }
        machine.setCheckpointHook(this::checkpoint);
        machine.events.setListener(event -> listeners.forEach(l -> l.event(event)));
    }

    void entryStarted(String label) {
        currentEntry = label;
    }

    /** Runs on the emulator thread at every limit check. */
    private void checkpoint() {
        long now = System.nanoTime();
        if (now - lastProgressNanos >= TimeUnit.MILLISECONDS.toNanos(PROGRESS_INTERVAL_MILLIS)) {
            lastProgressNanos = now;
            sendProgress(now);
        }
    }

    /** A last progress report with the final counts, sent just before the run finishes. */
    void finalProgress() {
        sendProgress(System.nanoTime());
    }

    private void sendProgress(long now) {
        VirtualMachine machine = vm;
        if (machine == null || listeners.isEmpty()) {
            return;
        }
        Map<String, Integer> counts = Map.copyOf(machine.events.counts());
        long total = counts.values().stream().mapToLong(Integer::longValue).sum();
        RunListener.Progress progress = new RunListener.Progress(
                TimeUnit.NANOSECONDS.toMillis(now - startedNanos), machine.instructionCount(), currentEntry, total, counts);
        listeners.forEach(l -> l.progress(progress));
    }

    void started(dev.javabyte.report.Report report) {
        listeners.forEach(l -> l.started(report));
    }

    void entryFinished(dev.javabyte.report.Report.Run run) {
        listeners.forEach(l -> l.entryFinished(run));
    }

    void markFinished() {
        finished.countDown();
    }
}
