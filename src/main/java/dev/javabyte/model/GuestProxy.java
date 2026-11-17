package dev.javabyte.model;

import java.util.List;

/**
 * A dynamic proxy created by the sample (with {@code Proxy.newProxyInstance}) for at least one
 * interface that is itself interpreted.
 *
 * <p>The JDK cannot build such a proxy, because it has no class for an interpreted interface.
 * The interpreter does the job directly: every call on the proxy is handed to the sample's
 * invocation handler, exactly as {@code java.lang.reflect.Proxy} specifies.
 *
 * @param interfaces class mirrors of the proxied interfaces ({@link GuestClass} or {@code Class})
 * @param handler    the sample's {@code InvocationHandler}
 * @param dispatcher how to call a method on this proxy, so that code outside the interpreter
 *                   (string concatenation inside the JDK, for example) reaches the handler too
 */
public record GuestProxy(List<Object> interfaces, Object handler, Dispatcher dispatcher) {

    /** Calls a proxy method through the sample's invocation handler. */
    @FunctionalInterface
    public interface Dispatcher {
        Object call(GuestProxy proxy, String name, String descriptor, Object[] args);
    }

    /** Routed to the handler, as {@code java.lang.reflect.Proxy} does for {@code toString}. */
    @Override
    public String toString() {
        return String.valueOf(dispatcher.call(this, "toString", "()Ljava/lang/String;", new Object[0]));
    }

    // equals and hashCode keep identity semantics when called from outside the interpreter:
    // the emulator's own collections hold proxies and must not run sample code to hash them.
    // Calls the sample makes itself go through the router and do reach the handler.
    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
