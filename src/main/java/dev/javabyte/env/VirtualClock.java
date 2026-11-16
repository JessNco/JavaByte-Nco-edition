package dev.javabyte.env;

/**
 * The guest's notion of time.
 *
 * <p>Time advances only when the guest sleeps or reads the clock. Sleeps therefore cost no real
 * time, and timing-based checks ("did this sleep really take five minutes?") see a consistent,
 * plausible answer.
 */
public final class VirtualClock {

    private long millis;
    private long nanos;

    public VirtualClock(long startMillis) {
        this.millis = startMillis;
    }

    public void advance(long byMillis) {
        millis += byMillis;
        nanos += byMillis * 1_000_000L;
    }

    /** Each read moves time on slightly so that loops waiting for the clock to change terminate. */
    public long currentTimeMillis() {
        return millis++;
    }

    public long nanoTime() {
        nanos += 1_000;
        return nanos;
    }
}
