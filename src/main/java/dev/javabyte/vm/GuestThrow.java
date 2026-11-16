package dev.javabyte.vm;

/**
 * Carries an exception thrown by guest code through the interpreter's own Java frames.
 *
 * <p>A dedicated wrapper keeps guest exceptions apart from defects in the emulator itself: only a
 * {@code GuestThrow} is ever offered to the sample's catch blocks, so a bug such as a
 * NullPointerException inside the interpreter can never be swallowed by malware that catches
 * {@code Throwable}.
 */
public final class GuestThrow extends RuntimeException {

    /**
     * The thrown guest value. Normally a real {@link Throwable}; it is typed as Object because a
     * guest exception class whose JDK ancestor is unavailable is represented by a plain object.
     */
    public final Object value;

    public GuestThrow(Object value) {
        // No message, cause, suppression or host stack trace: this is control flow on a hot path.
        super(null, null, false, false);
        this.value = value;
    }

    /** The guest exception as a Throwable, wrapping it if the guest value is not one. */
    public Throwable asThrowable() {
        return value instanceof Throwable throwable
                ? throwable
                : new RuntimeException("guest exception " + Values.describe(value));
    }
}
