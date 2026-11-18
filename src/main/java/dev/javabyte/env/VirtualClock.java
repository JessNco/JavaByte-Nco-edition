package dev.javabyte.env;

import java.util.function.LongSupplier;

/**
 * The guest's notion of time.
 *
 * <p>Both of the guest's clocks, {@code System.currentTimeMillis} and {@code System.nanoTime},
 * read one virtual timeline, counted in nanoseconds since the run started. Three things move it
 * forward:
 *
 * <ul>
 *   <li>executed bytecode instructions, at {@link #NANOS_PER_INSTRUCTION} each, so that a busy
 *       loop measured with either clock takes a believable time;
 *   <li>sleeps and timed waits, which jump straight to their end, so they cost no real time
 *       ("did this sleep really take five minutes?" sees yes);
 *   <li>each read of a clock, by a small step, so that a loop waiting for the clock to change
 *       always ends. A guest that reads the clock over and over with almost no work in between is
 *       busy-waiting, so the step grows (up to about a millisecond) and such a loop finishes in
 *       a reasonable number of turns.
 * </ul>
 *
 * <p>Because the two clocks share the timeline they always agree with each other, as on real
 * hardware. {@code nanoTime} does not start at zero: like a real JVM it counts from an
 * arbitrary origin, here a plausible machine uptime of a few hours to a few days, derived from
 * the start time so that the same {@code environment.clockStart} always gives the same values.
 * Nothing here reads the host's clock.
 */
public final class VirtualClock {

    /**
     * Virtual nanoseconds charged for each executed bytecode instruction. One nanosecond sits
     * between interpreted and compiled speed on a current desktop CPU.
     */
    public static final long NANOS_PER_INSTRUCTION = 1;

    /** The smallest step a single read of a clock moves time on. */
    static final long READ_NANOS = 1_000;

    /** Fewer instructions than this between two reads count as polling the clock. */
    static final long POLLING_INSTRUCTIONS = 10_000;

    /** Polling reads after which the read step doubles; it stops growing at about a millisecond. */
    static final int POLLING_READS_PER_DOUBLING = 100;

    /** The read step never grows beyond {@code READ_NANOS << MAX_DOUBLINGS} (about 1 ms). */
    static final int MAX_DOUBLINGS = 10;

    /** The shortest machine uptime {@code nanoTime} can start at: two hours. */
    static final long MIN_UPTIME_NANOS = 2L * 3_600_000_000_000L;

    /** The span of uptimes above the shortest one: about four days. */
    static final long UPTIME_SPAN_NANOS = 4L * 24 * 3_600_000_000_000L;

    private final long startMillis;
    private final long uptimeNanos;
    /** Virtual nanoseconds since the start from sleeps, waits and reads (not instructions). */
    private long elapsed;
    private LongSupplier instructions = () -> 0;
    /** Instruction count at the previous read, to tell a polling loop from real work. */
    private long instructionsAtLastRead;
    /** Reads in a row that came after very little work. */
    private int pollingReads;

    /** A clock that starts at {@code startMillis} with an uptime derived from it. */
    public VirtualClock(long startMillis) {
        this(startMillis, uptimeFor(startMillis));
    }

    /**
     * A clock that starts at {@code startMillis} on a machine that has been up for
     * {@code uptimeNanos}; {@code nanoTime} starts from that uptime.
     */
    public VirtualClock(long startMillis, long uptimeNanos) {
        this.startMillis = startMillis;
        this.uptimeNanos = Math.max(0, uptimeNanos);
    }

    /**
     * A plausible machine uptime for a run that starts at {@code startMillis}: between two hours
     * and about four days, always the same for the same start.
     */
    public static long uptimeFor(long startMillis) {
        // SplitMix64 finaliser: spreads nearby start times over the whole range.
        long z = startMillis + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return MIN_UPTIME_NANOS + Math.floorMod(z, UPTIME_SPAN_NANOS);
    }

    /**
     * Makes executed instructions cost virtual time. Called once when the stubs are installed,
     * with the VM's instruction counter; until then instructions are free.
     */
    public void chargeInstructions(LongSupplier executedInstructions) {
        this.instructions = executedInstructions;
        this.instructionsAtLastRead = executedInstructions.getAsLong();
    }

    /** Moves time forward by a sleep or timed wait. Negative values are ignored. */
    public void advance(long byMillis) {
        if (byMillis > 0) {
            advanceNanos(byMillis > Long.MAX_VALUE / 1_000_000L ? Long.MAX_VALUE : byMillis * 1_000_000L);
        }
    }

    /** Moves time forward by {@code byNanos}, saturating rather than overflowing. */
    public void advanceNanos(long byNanos) {
        if (byNanos > 0) {
            elapsed = saturatingAdd(elapsed, byNanos);
        }
    }

    /** The guest's {@code System.currentTimeMillis}. Each read moves time on slightly. */
    public long currentTimeMillis() {
        return startMillis + tick() / 1_000_000L;
    }

    /** The guest's {@code System.nanoTime}. Each read moves time on slightly. */
    public long nanoTime() {
        return saturatingAdd(uptimeNanos, tick());
    }

    /**
     * Virtual nanoseconds since the run started, without moving time on. For the scheduler's
     * deadlines, which must not disturb the guest's clock by looking at it.
     */
    public long elapsedNanos() {
        return saturatingAdd(elapsed, instructionNanos());
    }

    /** The current time in epoch milliseconds, without moving time on. */
    public long peekMillis() {
        return startMillis + elapsedNanos() / 1_000_000L;
    }

    /** One guest read: moves time on by the read step, then returns the elapsed nanoseconds. */
    private long tick() {
        long executed = instructions.getAsLong();
        if (executed - instructionsAtLastRead < POLLING_INSTRUCTIONS) {
            pollingReads++;
        } else {
            pollingReads = 0;
        }
        instructionsAtLastRead = executed;
        int doublings = Math.min(pollingReads / POLLING_READS_PER_DOUBLING, MAX_DOUBLINGS);
        elapsed = saturatingAdd(elapsed, READ_NANOS << doublings);
        return elapsedNanos();
    }

    private long instructionNanos() {
        long executed = instructions.getAsLong();
        return executed > Long.MAX_VALUE / NANOS_PER_INSTRUCTION ? Long.MAX_VALUE : executed * NANOS_PER_INSTRUCTION;
    }

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
