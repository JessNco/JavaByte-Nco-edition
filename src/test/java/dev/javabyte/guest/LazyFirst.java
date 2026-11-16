package dev.javabyte.guest;

/** Loaded explicitly by {@link LazyLoader}; its use of {@link LazySecond} triggers implicit loading. */
public final class LazyFirst {

    private LazyFirst() {}

    public static String greet() {
        return "first+" + new LazySecond().name();
    }
}
