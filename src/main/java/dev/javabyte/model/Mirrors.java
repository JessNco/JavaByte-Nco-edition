package dev.javabyte.model;

import org.objectweb.asm.tree.FieldNode;

/**
 * Stand-ins for the reflection objects of interpreted classes.
 *
 * <p>{@code java.lang.reflect.Method} and friends are final and cannot be created for classes the
 * host JVM never loaded, so these records take their place. The interpreter does not check
 * static types, which lets a mirror flow through guest code wherever the real type is declared;
 * the reflection stubs recognise mirrors by their runtime class.
 */
public final class Mirrors {

    private Mirrors() {}

    /** A method of an interpreted class, where guest code expects a {@code java.lang.reflect.Method}. */
    public record MethodMirror(GuestMethod method) {
        @Override
        public String toString() {
            return method.id;
        }
    }

    /** A constructor of an interpreted class, in place of a {@code java.lang.reflect.Constructor}. */
    public record ConstructorMirror(GuestMethod method) {
        @Override
        public String toString() {
            return method.id;
        }
    }

    /** A field of an interpreted class, in place of a {@code java.lang.reflect.Field}. */
    public record FieldMirror(GuestClass declaring, FieldNode field) {
        @Override
        public String toString() {
            return declaring.name + '.' + field.name;
        }
    }

    /** Stand-in for {@code MethodHandles.Lookup}; remembers which class asked for it. */
    public record LookupMirror(String lookupClass) {}
}
