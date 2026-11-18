package dev.javabyte.env;

import dev.javabyte.host.StandIn;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Imitates {@code java.lang.Package} for interpreted classes. A real {@code Package} only
 * exists for classes the host JVM has loaded, which guest classes never are.
 *
 * <p>The title, version, vendor and sealed attributes come from the sample's manifest. Stubs on
 * {@code java.lang.Package} answer them through {@link #attribute}, because the manifest
 * belongs to the run rather than to this object.
 */
public final class SimPackage implements StandIn {

    private final String name;

    public SimPackage(String name) {
        this.name = name;
    }

    @Override
    public String standsFor() {
        return "java/lang/Package";
    }

    public String getName() { return name; }

    /**
     * One manifest attribute as a real JVM applies it to this package: the value in the
     * package's own section ({@code com/example/app/}) if it has one, otherwise the value in
     * the main section, otherwise null.
     *
     * @param manifest  the sample's manifest, or null if it has none
     * @param attribute the attribute name, such as {@code Implementation-Version}
     */
    public String attribute(Manifest manifest, String attribute) {
        if (manifest == null) {
            return null;
        }
        Attributes section = manifest.getAttributes(name.replace('.', '/') + "/");
        String value = section == null ? null : section.getValue(attribute);
        return value != null ? value : manifest.getMainAttributes().getValue(attribute);
    }

    /** Parses manifest bytes; null if there are none or they cannot be read, as a JVM ignores a broken manifest. */
    public static Manifest readManifest(byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            return new Manifest(new ByteArrayInputStream(data));
        } catch (IOException | RuntimeException malformed) {
            return null;
        }
    }

    @Override public String toString() { return "package " + name; }
    @Override public int hashCode() { return name.hashCode(); }
    @Override public boolean equals(Object other) { return other instanceof SimPackage that && that.name.equals(name); }
}
