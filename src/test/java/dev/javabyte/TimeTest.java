package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.config.ConfigLoader;
import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.env.GuestRegion;
import dev.javabyte.env.VirtualClock;
import dev.javabyte.guest.sim.Timing;
import dev.javabyte.report.Report;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The virtual clock, the guest's time zone and locale, and the absence of real threads. The
 * guest-visible behaviour is pinned down by the {@code timing-*} corpus scenarios; this class
 * covers what a scenario cannot: the clock on its own, config validation, and that the host's
 * own settings never reach the report.
 */
class TimeTest {

    @TempDir
    Path temp;

    /** Both clocks read one timeline, so their differences always agree. */
    @Test
    void bothClocksShareOneTimeline() {
        VirtualClock clock = new VirtualClock(1_700_000_000_000L);
        long millis = clock.currentTimeMillis();
        long nanos = clock.nanoTime();
        clock.advanceNanos(90_000L * 1_000_000L);
        for (int i = 0; i < 5_000; i++) {
            clock.currentTimeMillis();
        }
        long millisLater = clock.currentTimeMillis();
        long nanosLater = clock.nanoTime();
        assertTrue(Math.abs((millisLater - millis) - (nanosLater - nanos) / 1_000_000) <= 1,
                (millisLater - millis) + " ms against " + (nanosLater - nanos) + " ns");
        assertTrue(millisLater - millis >= 90_000);
    }

    /** Executed instructions cost time once they are charged, and looking at the clock does not move it. */
    @Test
    void instructionsCostTime() {
        long[] executed = {0};
        VirtualClock clock = new VirtualClock(0);
        clock.chargeInstructions(() -> executed[0]);
        long before = clock.nanoTime();
        executed[0] += 15_000_000;
        long after = clock.nanoTime();
        assertTrue(after - before >= 15_000_000 * VirtualClock.NANOS_PER_INSTRUCTION, String.valueOf(after - before));
        long peeked = clock.elapsedNanos();
        assertEquals(peeked, clock.elapsedNanos(), "peeking must not move the clock");
        assertEquals(clock.peekMillis(), clock.peekMillis());
    }

    /** A loop polling the clock with no work in between sees time speed up, so a busy-wait ends. */
    @Test
    void pollingSpeedsUpButStaysBounded() {
        VirtualClock clock = new VirtualClock(0);
        long start = clock.currentTimeMillis();
        int reads = 0;
        while (clock.currentTimeMillis() - start < 30_000) {
            reads++;
        }
        assertTrue(reads < 40_000, "a 30 s busy-wait took " + reads + " reads");
        long a = clock.nanoTime();
        long b = clock.nanoTime();
        assertTrue(b - a <= 1_100_000, "one read never moves the clock by more than about a millisecond: " + (b - a));
    }

    /** nanoTime starts at a plausible uptime that depends only on the start time. */
    @Test
    void uptimeIsPlausibleAndRepeatable() {
        long start = java.time.Instant.parse("2024-03-01T09:00:00Z").toEpochMilli();
        long uptime = VirtualClock.uptimeFor(start);
        assertEquals(uptime, VirtualClock.uptimeFor(start));
        assertTrue(uptime >= 2L * 3_600_000_000_000L && uptime <= 5L * 24 * 3_600_000_000_000L, String.valueOf(uptime));
        assertTrue(new VirtualClock(start).nanoTime() >= uptime);
        assertTrue(VirtualClock.uptimeFor(start + 1) != uptime, "nearby starts give different uptimes");
    }

    /** A sleep of any length cannot overflow the clock. */
    @Test
    void hugeSleepsSaturate() {
        VirtualClock clock = new VirtualClock(0);
        clock.advanceNanos(Long.MAX_VALUE);
        clock.advanceNanos(Long.MAX_VALUE);
        assertTrue(clock.nanoTime() > 0);
    }

    /** The new options have defaults that match the simulated desktop, and bad values are reported. */
    @Test
    void timeZoneAndLocaleAreValidated() {
        EmulatorConfig config = new EmulatorConfig();
        assertEquals(GuestRegion.DEFAULT_TIME_ZONE, config.environment.timeZone);
        assertEquals(GuestRegion.DEFAULT_LOCALE, config.environment.locale);
        assertTrue(ConfigLoader.problems(config).isEmpty());

        config.environment.timeZone = "Mars/Olympus";
        config.environment.locale = "en_US";
        Set<String> keys = ConfigLoader.problems(config).stream().map(ConfigLoader.Problem::key).collect(Collectors.toSet());
        assertEquals(Set.of("environment.timeZone", "environment.locale"), keys);

        config.environment.timeZone = "Europe/Berlin";
        config.environment.locale = "de-DE";
        assertTrue(ConfigLoader.problems(config).isEmpty());
        GuestRegion region = GuestRegion.of(config.environment);
        assertEquals("Europe/Berlin", region.zone.getId());
        assertEquals(Locale.GERMANY, region.locale);
    }

    /**
     * The report must not depend on the analysis computer's time zone or language. The same
     * run on two hosts set up differently prints the same lines.
     */
    @Test
    void hostTimeZoneAndLocaleNeverReachTheGuest() throws Exception {
        Path jar = Fixtures.jar(Files.createDirectory(temp.resolve("region")), Map.of(), Timing.class);
        TimeZone hostZone = TimeZone.getDefault();
        Locale hostLocale = Locale.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            Locale.setDefault(Locale.FRANCE);
            List<String> tokyo = Fixtures.stdout(Fixtures.run(region(jar)));
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            Locale.setDefault(Locale.ROOT);
            List<String> utc = Fixtures.stdout(Fixtures.run(region(jar)));
            assertEquals(tokyo, utc);
            assertTrue(tokyo.contains("zone America/New_York America/New_York property America/New_York"), tokyo.toString());
            assertTrue(tokyo.contains("format 1,234.50"), tokyo.toString());
        } finally {
            TimeZone.setDefault(hostZone);
            Locale.setDefault(hostLocale);
        }
    }

    private static EmulatorConfig region(Path jar) {
        EmulatorConfig config = Fixtures.config(jar);
        config.environment.clockStart = "2001-02-03T04:05:06Z";
        EmulatorConfig.EntryPoint entry = new EmulatorConfig.EntryPoint();
        entry.args.add("region");
        config.entryPoints.add(entry);
        return config;
    }

    /** Parallel APIs give their answers without starting any real thread, common pool included. */
    @Test
    void parallelApisStartNoRealThreads() throws Exception {
        Path jar = Fixtures.jar(Files.createDirectory(temp.resolve("parallel")), Map.of(), Timing.class);
        Set<Long> before = Thread.getAllStackTraces().keySet().stream().map(Thread::getId).collect(Collectors.toSet());
        EmulatorConfig config = Fixtures.config(jar);
        EmulatorConfig.EntryPoint entry = new EmulatorConfig.EntryPoint();
        entry.args.add("parallel");
        config.entryPoints.add(entry);
        Report report = Fixtures.run(config);
        assertTrue(Fixtures.outcomes(report).startsWith("dev.javabyte.guest.sim.Timing.main -> completed"), Fixtures.outcomes(report));
        List<String> lines = Fixtures.stdout(report);
        assertTrue(lines.contains("stream sum 55") && lines.contains("stage STAGE") && lines.contains("prefix last 100"), lines.toString());
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            assertFalse(!before.contains(thread.getId()) && thread.getName().startsWith("ForkJoinPool"),
                    "a real pool thread was started: " + thread.getName());
        }
    }

    /** A loop of timed waits that nothing ever ends is stopped by the sleep budget, like a sleep loop. */
    @Test
    void endlessTimedWaitsUseTheSleepBudget() throws Exception {
        Path jar = Fixtures.jar(Files.createDirectory(temp.resolve("waits")), Map.of(), Timing.class);
        EmulatorConfig config = Fixtures.config(jar);
        EmulatorConfig.EntryPoint entry = new EmulatorConfig.EntryPoint();
        entry.args.add("wait-loop");
        config.entryPoints.add(entry);
        Report report = Fixtures.run(config);
        assertTrue(Fixtures.outcomes(report).contains("sleep-budget"), Fixtures.outcomes(report));
    }
}
