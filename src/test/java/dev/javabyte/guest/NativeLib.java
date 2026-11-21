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

    /** Multiplies in native code: floating point both ways across the bridge. */
    static native double scale(double x, float y);

    /** Reads each box's value field from native code, as JNA's dispatcher does for boxed arguments. */
    static native long unbox(Boolean z, Byte b, Character c, Short s, Integer i, Long j, Float f, Double d);

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
        System.out.println("scale " + scale(2.5, 4.0f));
        System.out.println("unbox " + unbox(true, (byte) 2, 'A', (short) 300, 40_000, 5_000_000_000L, 7.0f, 250.0));
        dropMarker();
    }
}
