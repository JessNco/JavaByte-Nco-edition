package dev.javabyte.guest.sim;

import java.security.SecureRandom;
import java.util.Date;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Draws from every source of randomness and time a sample commonly uses for bot identifiers,
 * dropped-file names and session keys. Used to check that a run is repeatable.
 */
public final class Dice {

    public static void main(String[] args) throws Exception {
        System.out.println("math " + Math.random());
        System.out.println("random " + new Random().nextInt(1_000_000) + " " + new Random().nextLong());
        System.out.println("seeded " + new Random(42).nextInt(1_000_000));
        byte[] key = new byte[8];
        new SecureRandom().nextBytes(key);
        System.out.println("secure " + java.util.Arrays.toString(key) + " " + SecureRandom.getInstance("SHA1PRNG").nextInt());
        System.out.println("thread-local " + ThreadLocalRandom.current().nextInt(100, 200) + " " + ThreadLocalRandom.current().nextLong());
        System.out.println("uuid " + UUID.randomUUID());
        // Every way of asking for the time must agree, including after a simulated sleep.
        long before = System.currentTimeMillis();
        Thread.sleep(90_000);
        long now = System.currentTimeMillis();
        System.out.println("time " + now + " slept " + (now - before));
        System.out.println("date " + (new Date().getTime() - now) + " calendar " + (java.util.Calendar.getInstance().getTimeInMillis() - now)
                + " gregorian " + (new java.util.GregorianCalendar().getTimeInMillis() - now)
                + " instant " + (java.time.Instant.now().toEpochMilli() - now)
                + " clock " + (java.time.Clock.systemUTC().millis() - now)
                + " zoned " + (java.time.ZonedDateTime.now().toInstant().toEpochMilli() - now)
                + " utc-date " + java.time.LocalDate.now(java.time.ZoneId.of("UTC"))
                + " utc-time " + java.time.LocalDateTime.now(java.time.ZoneId.of("UTC")));
        Runtime.getRuntime().exec("drop_" + (int) (Math.random() * 100000) + ".exe");
    }
}
