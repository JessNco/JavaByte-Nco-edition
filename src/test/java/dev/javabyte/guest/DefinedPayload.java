package dev.javabyte.guest;

/**
 * The class {@link OwnLoader} defines from bytes. It ships only as an encoded resource, never
 * as a class in the sample's archive, so the emulator can reach it only through the sample's
 * own {@code defineClass}.
 *
 * <p>This class is only ever interpreted. Do not run it on a real JVM.
 */
public final class DefinedPayload {

    /** Proof that the defined class ran. */
    public String greet() {
        return "payload ran";
    }
}
