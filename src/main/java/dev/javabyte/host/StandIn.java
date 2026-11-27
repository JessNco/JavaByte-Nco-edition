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

    /**
     * Whether a cast to {@code type} succeeds, for types the analysis JDK may not have: the
     * imitated class and any of its supertypes that the guest's Java has but this one lacks.
     * Supertypes the analysis JDK has are found through {@link #standsFor()} anyway.
     *
     * @param type internal class name
     */
    default boolean isA(String type) {
        return type.equals(standsFor());
    }
}
