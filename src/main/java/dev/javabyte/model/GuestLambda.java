package dev.javabyte.model;

import org.objectweb.asm.Handle;

/**
 * A lambda or method reference created by the sample through {@code invokedynamic}.
 *
 * @param interfaceName internal name of the functional interface
 * @param methodName    name of its single abstract method
 * @param implementation the method the lambda body lives in
 * @param captured      values captured when the lambda was created; they precede the call arguments
 */
public record GuestLambda(String interfaceName, String methodName, Handle implementation, Object[] captured) {

    @Override
    public String toString() {
        return "lambda:" + interfaceName + "->" + implementation.getOwner() + '.' + implementation.getName();
    }

    // Identity semantics: two lambdas are equal only if they are the same object, as on a real JVM.
    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
