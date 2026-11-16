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
 */
public record GuestProxy(List<Object> interfaces, Object handler) {

    @Override
    public String toString() {
        return "proxy:" + interfaces;
    }

    // Identity semantics, as for any object; the handler decides what equals() means to the guest.
    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
