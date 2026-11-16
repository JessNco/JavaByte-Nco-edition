package dev.javabyte.vm;

/**
 * Ends emulation of the current task or of the whole run for a reason outside the guest program:
 * a budget was exhausted, the sample called {@code System.exit}, or policy demanded a stop.
 *
 * <p>It extends {@link Error} so that real JDK code sitting between two interpreter levels (for
 * example a comparator callback inside {@code Collections.sort}) does not catch and discard it.
 */
public final class EmulationAbort extends Error {

    public enum Reason {
        EXIT(false),
        INSTRUCTION_LIMIT(false),
        TIMEOUT(false),
        POLICY(false),
        /** Only the looping thread is ended; other guest threads still get their turn. */
        SLEEP_BUDGET(true),
        /** The thread waited for something no other thread can provide; only it is ended. */
        BLOCKED(true),
        /** The run is over and a parked guest thread is being unwound. Never reported. */
        SHUTDOWN(true);

        /** True when only the current task stops and the rest of the run continues. */
        public final boolean taskScoped;

        Reason(boolean taskScoped) {
            this.taskScoped = taskScoped;
        }
    }

    public final Reason reason;
    public final String detail;

    public EmulationAbort(Reason reason, String detail) {
        super(reason + ": " + detail, null, false, false);
        this.reason = reason;
        this.detail = detail;
    }
}
