package dev.javabyte.host;

/**
 * Replacement behaviour for one JDK method or constructor.
 *
 * <p>Contract for exceptions: a <em>checked</em> exception thrown by a stub is delivered to the
 * guest program, because stubs model APIs that declare such exceptions (a missing file raises
 * {@code FileNotFoundException}). An <em>unchecked</em> exception is treated as a defect in the
 * emulator and stops the run, unless it was created with {@link CallContext#raise}.
 */
@FunctionalInterface
public interface Stub {

    /**
     * @return the call's result in interpreter representation (an {@code Integer} for any
     *         int-like or boolean result); for a constructor, the object that was "constructed"
     */
    Object call(CallContext ctx) throws Exception;
}
