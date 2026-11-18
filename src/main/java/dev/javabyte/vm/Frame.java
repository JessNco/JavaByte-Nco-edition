package dev.javabyte.vm;

import dev.javabyte.model.GuestMethod;
import dev.javabyte.model.PendingInstance;

/**
 * One guest method activation.
 *
 * <p>Unlike the class-file model, a {@code long} or {@code double} occupies a single operand-stack
 * entry here (as a boxed value). That halves the bookkeeping for wide values; the handful of
 * stack-shuffling opcodes whose behaviour depends on width inspect the value's type instead.
 * Local variable numbering is left exactly as compiled, so a wide local still leaves the next
 * index unused.
 */
public final class Frame {

    public final GuestMethod method;
    public final Object[] locals;
    public final Object[] stack;
    /** Index of the instruction being executed; kept current so stack traces show the right line. */
    public int pc;
    /**
     * JDK frames to show between this frame and the frames it calls, while a call that would
     * pass through JDK code (such as {@code Method.invoke}) is in progress; null otherwise.
     * Set by {@link VirtualMachine#callThroughJdk}.
     */
    java.util.List<StackTraceElement> callingThrough;

    Frame(GuestMethod method) {
        this.method = method;
        this.locals = new Object[method.node.maxLocals + 1];
        // maxStack counts wide values twice, so it is always enough for one-entry-per-value.
        this.stack = new Object[method.node.maxStack + 2];
    }

    /**
     * Swaps finished {@link PendingInstance} placeholders for the objects they resolved to.
     * Called after each constructor call returns; see {@link PendingInstance} for why.
     */
    void replaceResolved(int stackDepth) {
        for (int i = 0; i < stackDepth; i++) {
            if (stack[i] instanceof PendingInstance pending && pending.resolved != null) {
                stack[i] = pending.resolved;
            }
        }
        for (int i = 0; i < locals.length; i++) {
            if (locals[i] instanceof PendingInstance pending && pending.resolved != null) {
                locals[i] = pending.resolved;
            }
        }
    }

    /**
     * Source line for the current pc, or -1 when the method has no line table. Both the guest's
     * stack traces and the report's caller locations use this, so they always agree; obfuscators
     * derive keys from those traces.
     */
    public int line() {
        return method.lines.length == 0 ? -1 : method.lines[Math.min(pc, method.lines.length - 1)];
    }

    /** Where this frame is, as {@code owner.name:line}, for the report's caller locations. */
    public String location() {
        int line = line();
        return method.owner.name + '.' + method.name + (line >= 0 ? ":" + line : "");
    }
}
