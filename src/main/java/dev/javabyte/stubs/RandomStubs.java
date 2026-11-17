package dev.javabyte.stubs;

import dev.javabyte.env.SimSecureRandom;
import dev.javabyte.host.StubRegistry;
import dev.javabyte.vm.VirtualMachine;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Makes the sample's random numbers repeatable.
 *
 * <p>All unseeded sources are fed from one generator seeded with
 * {@code environment.randomSeed}: {@code Math.random}, {@code new Random()},
 * {@code SecureRandom}, {@code ThreadLocalRandom} and {@code UUID.randomUUID}. With the same
 * seed and {@code environment.clockStart}, two runs of a sample give the same report, which is
 * what makes "change one config value and compare" a usable way of working.
 *
 * <p>A generator the sample seeds itself, {@code new Random(42)}, is left alone: it is already
 * repeatable and its exact stream may matter to the sample (seeded string decryption is
 * common).
 *
 * <p>Not covered: randomness the JDK draws internally, such as a {@code KeyGenerator} or a
 * cipher choosing its own IV without being given a random source.
 */
final class RandomStubs {

    private RandomStubs() {}

    static void install(VirtualMachine vm) {
        Long seed = vm.config.environment.randomSeed;
        if (seed == null) {
            return; // the analyst asked for real entropy
        }
        // One master generator hands a distinct seed to each source the sample creates, in
        // creation order. Guest threads never run in parallel, so this needs no locking.
        Random master = new Random(seed);
        StubRegistry stubs = vm.stubs;

        stubs.on("java/lang/Math", "random", ctx -> master.nextDouble());
        stubs.on("java/lang/StrictMath", "random", ctx -> master.nextDouble());
        stubs.on("java/util/Random", "<init>", "()V", ctx -> new Random(master.nextLong()));

        String secure = "java/security/SecureRandom";
        stubs.on(secure, "<init>", ctx -> new SimSecureRandom(master.nextLong()));
        stubs.on(secure, "getInstance", ctx -> new SimSecureRandom(master.nextLong()));
        stubs.on(secure, "getInstanceStrong", ctx -> new SimSecureRandom(master.nextLong()));
        stubs.on(secure, "getSeed", ctx -> {
            byte[] bytes = new byte[ctx.intArg(0)];
            master.nextBytes(bytes);
            return bytes;
        });

        stubs.on("java/util/UUID", "randomUUID", ctx -> {
            // The version (4) and variant bits are what make a value a valid random UUID.
            long high = (master.nextLong() & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L;
            long low = (master.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
            return new UUID(high, low);
        });

        installThreadLocalRandom(stubs, new Random(master.nextLong()));
    }

    /**
     * {@code ThreadLocalRandom} can be neither seeded nor subclassed, so its instance methods
     * are answered by the method of the same name and parameters on a seeded {@code Random}.
     * Anything {@code Random} lacks falls through to the real object.
     */
    private static void installThreadLocalRandom(StubRegistry stubs, Random seeded) {
        stubs.onClass("java/util/concurrent/ThreadLocalRandom", ctx -> {
            if (!(ctx.receiver instanceof ThreadLocalRandom)) {
                return ctx.proceed(); // the static current()
            }
            Method twin = twinOnRandom(ctx.name, ctx.args);
            if (twin == null) {
                return ctx.proceed();
            }
            try {
                return twin.invoke(seeded, ctx.args);
            } catch (InvocationTargetException e) {
                throw ctx.raise(e.getCause());
            } catch (IllegalAccessException | IllegalArgumentException e) {
                return ctx.proceed();
            }
        });
    }

    private static Method twinOnRandom(String name, Object[] args) {
        for (Method candidate : Random.class.getMethods()) {
            if (!candidate.getName().equals(name) || candidate.getParameterCount() != args.length) {
                continue;
            }
            Class<?>[] types = candidate.getParameterTypes();
            boolean fits = true;
            for (int i = 0; i < types.length && fits; i++) {
                fits = (types[i] == int.class && args[i] instanceof Integer) || (types[i] == long.class && args[i] instanceof Long)
                        || (types[i] == double.class && args[i] instanceof Double) || (types[i] == float.class && args[i] instanceof Float)
                        || (types[i] == byte[].class && args[i] instanceof byte[]);
            }
            if (fits) {
                return candidate;
            }
        }
        return null;
    }
}
