package dev.javabyte.model;

/**
 * Placeholder for an object between its {@code NEW} instruction and the end of its constructor.
 *
 * <p>The JVM lets bytecode allocate first and pass constructor arguments later. A real host
 * object (a JDK object, a stand-in chosen by a stub, or a shell that extends a JDK class) can
 * only be created once those arguments are known. Until then this placeholder travels on the
 * operand stack; when the constructor call returns, the interpreter swaps every copy for
 * {@link #resolved}. The verifier guarantees an uninitialised reference cannot escape the frames
 * of the constructor chain, so swapping within those frames is sufficient.
 */
public final class PendingInstance {

    /** Guest state, when a guest class with JDK supertypes is being constructed; otherwise null. */
    public final InstanceState state;

    /** Internal name of the JDK class being constructed, when {@link #state} is null. */
    public final String hostClass;

    /** The real object once construction has finished. */
    public Object resolved;

    public PendingInstance(InstanceState state) {
        this.state = state;
        this.hostClass = null;
    }

    public PendingInstance(String hostClass) {
        this.state = null;
        this.hostClass = hostClass;
    }

    @Override
    public String toString() {
        return "uninitialised " + (state != null ? state.type.name : hostClass);
    }
}
