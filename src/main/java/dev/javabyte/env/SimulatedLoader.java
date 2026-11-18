package dev.javabyte.env;

/**
 * A class loader the emulator hands the guest in place of a real one: a {@code URLClassLoader},
 * or the base of a sample's own subclass of {@code ClassLoader}, {@code SecureClassLoader} or
 * {@code URLClassLoader}.
 *
 * <p>The real superclass of every such loader is given the bootstrap loader as its parent, so
 * that JDK code which asks it for something can never reach the emulator's own class path.
 * The parent the sample asked for is only remembered, and the class-loader stubs report it
 * from {@code getParent()}, which is final in {@code ClassLoader} and so cannot be overridden.
 */
public interface SimulatedLoader {

    /** The parent the guest passed to the constructor; null for the bootstrap loader. */
    ClassLoader guestParent();

    /**
     * True if the guest named no parent, so that {@code getParent} should report the
     * application class loader, which the stubs hold.
     */
    boolean hasDefaultParent();
}
