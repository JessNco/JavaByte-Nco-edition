package dev.javabyte.env;

import dev.javabyte.host.StandIn;

/**
 * What {@code Thread.ofVirtual()} and {@code Thread.ofPlatform()} return (Java 21). The real
 * builders start host threads, so the guest gets this record of the settings instead, and the
 * stubs in {@code ThreadStubs} start its threads on the emulator's scheduler.
 *
 * <p>Only the settings a report or the guest can observe are kept: the name, its counter and
 * whether the threads are virtual. Priority, stack size and the like are accepted and ignored,
 * as they change nothing in an interleaved run.
 */
public final class SimThreadBuilder implements StandIn {

    private static final String BUILDER = "java/lang/Thread$Builder";

    public final boolean virtual;
    private String name;
    private long counter = -1;

    public SimThreadBuilder(boolean virtual) {
        this.virtual = virtual;
        this.name = virtual ? "" : null;
    }

    /** {@code name(String)}: every thread gets exactly this name. */
    public void name(String name) {
        this.name = name;
        this.counter = -1;
    }

    /** {@code name(String, long)}: threads are named prefix0, prefix1, and so on. */
    public void name(String prefix, long start) {
        if (start < 0) {
            throw new IllegalArgumentException("'start' is negative");
        }
        this.name = prefix;
        this.counter = start;
    }

    /**
     * The name for the next thread, advancing the counter when there is one. Null for a
     * platform builder without a name, which leaves the JDK's default ({@code Thread-N}).
     */
    public String nextName() {
        if (name == null || counter < 0) {
            return name;
        }
        return name + counter++;
    }

    @Override
    public String standsFor() {
        return virtual ? BUILDER + "$OfVirtual" : BUILDER + "$OfPlatform";
    }

    @Override
    public boolean isA(String type) {
        return type.equals(standsFor()) || type.equals(BUILDER);
    }
}
