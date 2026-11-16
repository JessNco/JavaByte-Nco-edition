package dev.javabyte.host;

/**
 * Implemented by simulated objects that cannot subclass the JDK type they replace (because that
 * type is final or has no usable constructor) and so imitate it by having same-named methods.
 *
 * <p>Type checks in the interpreter consult {@link #standsFor()} so that casts and
 * {@code instanceof} in guest code behave as if the object were the real thing.
 */
public interface StandIn {

    /** Internal name of the JDK class this object imitates, e.g. {@code java/io/RandomAccessFile}. */
    String standsFor();
}
