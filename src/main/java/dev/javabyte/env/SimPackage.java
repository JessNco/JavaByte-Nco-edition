package dev.javabyte.env;

import dev.javabyte.host.StandIn;

/**
 * Imitates {@code java.lang.Package} for interpreted classes. A real {@code Package} only
 * exists for classes the host JVM has loaded, which guest classes never are.
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
    public String getImplementationTitle() { return null; }
    public String getImplementationVersion() { return null; }
    public String getImplementationVendor() { return null; }
    public String getSpecificationTitle() { return null; }
    public String getSpecificationVersion() { return null; }
    public String getSpecificationVendor() { return null; }
    public boolean isSealed() { return false; }
    @Override public String toString() { return "package " + name; }
    @Override public int hashCode() { return name.hashCode(); }
    @Override public boolean equals(Object other) { return other instanceof SimPackage that && that.name.equals(name); }
}
