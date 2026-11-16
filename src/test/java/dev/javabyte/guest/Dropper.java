package dev.javabyte.guest;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

/**
 * Test fixture shaped like a typical Java dropper: it reads an encrypted resource from its own
 * jar, decrypts it, defines the resulting class with a custom class loader and runs it by
 * reflection. The "payload" is {@link Stage2}. This class is only ever interpreted.
 */
public final class Dropper {

    static final byte KEY = 0x5A;

    /** A loader that exposes defineClass, the usual way packed code gets into a JVM. */
    static final class MemoryLoader extends ClassLoader {
        Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }

    public static void main(String[] args) throws Exception {
        byte[] encrypted;
        try (InputStream in = Dropper.class.getResourceAsStream("/payload.bin")) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[512];
            for (int read = in.read(chunk); read > 0; read = in.read(chunk)) {
                buffer.write(chunk, 0, read);
            }
            encrypted = buffer.toByteArray();
        }
        for (int i = 0; i < encrypted.length; i++) {
            encrypted[i] ^= KEY;
        }
        Class<?> stage = new MemoryLoader().define(encrypted);
        Method entry = stage.getMethod("run", String.class);
        Object result = entry.invoke(stage.getDeclaredConstructor().newInstance(), "from-dropper");
        System.out.println("stage returned " + result);
    }
}
