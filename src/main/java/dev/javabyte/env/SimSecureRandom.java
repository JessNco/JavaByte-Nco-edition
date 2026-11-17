package dev.javabyte.env;

import java.security.SecureRandom;
import java.util.Random;

/**
 * A {@link SecureRandom} whose output is a repeatable function of a seed.
 *
 * <p>It is the opposite of secure on purpose. The sample's random choices (file names, bot
 * identifiers, session keys, padding) end up in the report; drawing them from real entropy
 * would make two runs of the same sample differ for no reason an analyst cares about.
 *
 * <p>Every way of drawing numbers from a {@code SecureRandom} ends in {@link #nextBytes}, so
 * overriding that method and the seeding methods is enough.
 */
public final class SimSecureRandom extends SecureRandom {

    private static final long serialVersionUID = 1L;

    /** Null while the superclass constructor runs, which already calls {@link #setSeed(long)}. */
    private final Random source;

    public SimSecureRandom(long seed) {
        this.source = new Random(seed);
    }

    @Override
    public void nextBytes(byte[] bytes) {
        source.nextBytes(bytes);
    }

    @Override
    public byte[] generateSeed(int numBytes) {
        byte[] seed = new byte[numBytes];
        source.nextBytes(seed);
        return seed;
    }

    /** Ignored: mixing in the sample's seed must not make the stream depend on host entropy. */
    @Override
    public void setSeed(long seed) {
    }

    @Override
    public void setSeed(byte[] seed) {
    }

    @Override
    public String getAlgorithm() {
        return "SHA1PRNG";
    }

    @Override
    public String toString() {
        return "SHA1PRNG";
    }
}
