package dev.javabyte.model;

import dev.javabyte.shell.ShellDispatch;

/**
 * Instance of a guest class whose only real supertype is {@code java.lang.Object}.
 *
 * <p>{@code hashCode}, {@code equals} and {@code toString} forward to the guest's own overrides.
 * Real JDK collections and string builders call these three methods on whatever they hold, so
 * without forwarding a guest object used as a map key or appended to a string would silently
 * behave differently from the real program.
 */
public final class PlainObject implements GuestInstance {

    private final InstanceState state;

    public PlainObject(InstanceState state) {
        this.state = state;
    }

    @Override
    public InstanceState javabyte$state() {
        return state;
    }

    @Override
    public int hashCode() {
        if (ShellDispatch.hasGuestImpl(this, "hashCode", "()I")) {
            return ShellDispatch.asInt(ShellDispatch.invoke(this, "hashCode", "()I", new Object[0]));
        }
        return System.identityHashCode(this);
    }

    @Override
    public boolean equals(Object other) {
        if (ShellDispatch.hasGuestImpl(this, "equals", "(Ljava/lang/Object;)Z")) {
            return ShellDispatch.asBoolean(
                    ShellDispatch.invoke(this, "equals", "(Ljava/lang/Object;)Z", new Object[] {other}));
        }
        return this == other;
    }

    @Override
    public String toString() {
        if (ShellDispatch.hasGuestImpl(this, "toString", "()Ljava/lang/String;")) {
            return (String) ShellDispatch.invoke(this, "toString", "()Ljava/lang/String;", new Object[0]);
        }
        return identityString();
    }

    /** What {@code Object.toString()} would print for the guest class. Never re-enters the interpreter. */
    public String identityString() {
        return state.type.dottedName() + '@' + Integer.toHexString(System.identityHashCode(this));
    }
}
