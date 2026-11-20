package dev.javabyte.guest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Test fixture for the native bridge. Like a real sample, it carries a Windows DLL as a
 * resource, drops it to disk and loads it. The DLL is built from src/test/native/testlib.c.
 * This class is only ever interpreted; its natives run in the CPU emulator.
 */
public final class NativeLib {

    static native int add(int a, int b);

    static native String greet(String name);

    static native int viaJava(int x);

    static native byte[] xorBytes(byte[] data, byte key);

    static native long triple(long value);

    static native int dropMarker();

    /** Shaped like JNA's {@code Native.invokeInt(Function, long, int, Object[])}. */
    static native int jnaInvoke(Object function, long fp, int flags, Object[] args);

    /** Called back from native code by {@link #viaJava}. */
    static int twice(int x) {
        return x * 2;
    }

    public static void main(String[] args) throws Exception {
        File library = File.createTempFile("lib", ".dll");
        try (InputStream in = NativeLib.class.getResourceAsStream("/native/testlib.dll");
                FileOutputStream out = new FileOutputStream(library)) {
            out.write(in.readAllBytes());
        }
        System.load(library.getAbsolutePath());
        System.out.println("add " + add(20, 22));
        System.out.println("greet " + greet("world"));
        System.out.println("viaJava " + viaJava(10));
        System.out.println("xor " + new String(xorBytes("ABC".getBytes(), (byte) 0x20)));
        System.out.println("triple " + triple(-14_000_000_000L));
        System.out.println("jna " + jnaInvoke("function", 7L, 30, new Object[] {"a", "b", "c"}));
        dropMarker();
    }
}
