package dev.javabyte.guest;

import java.io.InputStream;

/**
 * Test fixture: a class loader that decrypts classes on demand in {@code findClass}. The class
 * it starts refers to a second encrypted class, which a JVM would request from the same loader
 * implicitly. The emulator must reproduce that or the second class would be "missing".
 */
public final class LazyLoader extends ClassLoader {

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try (InputStream in = LazyLoader.class.getResourceAsStream("/enc/" + name + ".bin")) {
            if (in == null) {
                throw new ClassNotFoundException(name);
            }
            byte[] bytes = in.readAllBytes();
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] ^= 0x33;
            }
            return defineClass(name, bytes, 0, bytes.length);
        } catch (java.io.IOException e) {
            throw new ClassNotFoundException(name, e);
        }
    }

    public static void main(String[] args) throws Exception {
        Class<?> first = new LazyLoader().loadClass("dev.javabyte.guest.LazyFirst");
        System.out.println(first.getMethod("greet").invoke(null));
    }
}
