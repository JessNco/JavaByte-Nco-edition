package dev.javabyte.model;

/**
 * Stand-in for an instance of a class the sample may not really use: one the policy blocks
 * (for example {@code java.awt.Robot}) or one that is not available at all.
 *
 * <p>Calls on a phantom are recorded and answered with neutral values. That is usually more
 * informative than stopping, because the sample carries on and reveals what it does next.
 */
public final class PhantomObject {

    /** Internal name of the class this object pretends to be. */
    public final String type;

    public PhantomObject(String type) {
        this.type = type;
    }

    @Override
    public String toString() {
        return "phantom:" + type;
    }
}
