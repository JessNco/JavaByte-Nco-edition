package dev.javabyte.guest.sim;

import java.io.InputStream;
import java.io.ObjectInputStream;
import java.util.Map;

/**
 * Simulated sample that keeps its settings as serialized Java objects, the way Adwind does.
 * The first resource holds plain collections; the second holds a class the emulator's
 * deserialization filter must refuse.
 */
public final class ConfigReader {

    public static void main(String[] args) throws Exception {
        try (InputStream in = ConfigReader.class.getResourceAsStream("/config.ser")) {
            Map<?, ?> config = (Map<?, ?>) new ObjectInputStream(in).readObject();
            System.out.println("server " + config.get("server") + " port " + config.get("port"));
        }
        try (InputStream in = ConfigReader.class.getResourceAsStream("/gadget.ser")) {
            Object value = new ObjectInputStream(in).readObject();
            System.out.println("gadget loaded " + value.getClass().getName());
        } catch (java.io.InvalidClassException refused) {
            System.out.println("gadget refused");
        }
    }
}
