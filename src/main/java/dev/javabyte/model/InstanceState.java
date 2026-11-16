package dev.javabyte.model;

/**
 * Guest-visible state of one instance: its interpreted class and its field values.
 *
 * <p>Fields live in a flat array indexed by slots assigned in {@link GuestClass}. An array is used
 * instead of a map because field access is one of the hottest interpreter operations.
 */
public final class InstanceState {

    public final GuestClass type;
    public final Object[] fields;

    public InstanceState(GuestClass type) {
        this.type = type;
        this.fields = type.newFieldArray();
    }

    /** Copy constructor used by {@code Object.clone()}; field values are copied shallowly, as the JVM does. */
    public InstanceState(InstanceState other) {
        this.type = other.type;
        this.fields = other.fields.clone();
    }
}
