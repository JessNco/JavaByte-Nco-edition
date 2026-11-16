package dev.javabyte.model;

/**
 * Implemented by every host object that represents an instance of an interpreted class.
 *
 * <p>There are two implementations. {@link PlainObject} is used when the guest class has no real
 * JDK supertypes. Otherwise a generated "shell" class is used: it genuinely extends the JDK
 * superclass and implements the JDK interfaces, so the object can be handed to real JDK code.
 * Both keep the guest-visible fields in an {@link InstanceState}.
 */
public interface GuestInstance {

    /** The guest class and field values of this instance. The odd name avoids clashing with sample methods. */
    InstanceState javabyte$state();
}
