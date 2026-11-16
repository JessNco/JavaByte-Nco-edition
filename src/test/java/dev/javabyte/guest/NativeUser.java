package dev.javabyte.guest;

/**
 * Test fixture that depends on a native method. On a real JVM it would fail to link; under the
 * emulator the call is reported, or answered by a plugin when one is configured.
 */
public final class NativeUser {

    static native int scramble(int value);

    public static void main(String[] args) {
        try {
            System.loadLibrary("scrambler");
        } catch (UnsatisfiedLinkError notInstalled) {
            System.out.println("library missing");
        }
        System.out.println("scrambled " + scramble(7));
    }
}
