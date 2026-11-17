package dev.javabyte.stubs;

import dev.javabyte.env.VirtualClock;
import dev.javabyte.host.Stub;
import dev.javabyte.host.StubRegistry;
import dev.javabyte.vm.VirtualMachine;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

/**
 * Points every "what time is it" call at the virtual clock.
 *
 * <p>{@code System.currentTimeMillis} alone is not enough: {@code new Date()},
 * {@code Calendar.getInstance()} and the {@code java.time} {@code now()} methods read the real
 * clock inside the JDK. Left alone they would disagree with the virtual clock after a
 * simulated sleep (an hour-long sleep would show an hour on one clock and nothing on the
 * other, which samples use to detect sandboxes), ignore {@code environment.clockStart}, and
 * make reports differ from run to run.
 *
 * <p>Only the forms without a clock or instant argument are replaced; the others already take
 * their time from a value the sample supplies.
 */
final class TimeStubs {

    private TimeStubs() {}

    /** A {@code java.time.Clock} that reads the virtual clock, so that it advances with simulated sleeps. */
    private static final class GuestClock extends Clock {
        private final VirtualClock clock;
        private final ZoneId zone;

        GuestClock(VirtualClock clock, ZoneId zone) {
            this.clock = clock;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId other) {
            return new GuestClock(clock, other);
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(clock.currentTimeMillis());
        }

        @Override
        public long millis() {
            return clock.currentTimeMillis();
        }
    }

    static void install(VirtualMachine vm) {
        StubRegistry stubs = vm.stubs;
        VirtualClock clock = vm.env.clock;
        Clock system = new GuestClock(clock, ZoneId.systemDefault());

        stubs.on("java/util/Date", "<init>", "()V", ctx -> new Date(clock.currentTimeMillis()));
        stubs.on("java/util/GregorianCalendar", "<init>", "()V", ctx -> {
            GregorianCalendar calendar = new GregorianCalendar();
            calendar.setTimeInMillis(clock.currentTimeMillis());
            return calendar;
        });
        stubs.on("java/util/Calendar", "getInstance", ctx -> {
            Calendar calendar = (Calendar) ctx.proceed(); // keeps the requested zone and locale
            calendar.setTimeInMillis(clock.currentTimeMillis());
            return calendar;
        });

        stubs.on("java/time/Clock", "systemUTC", ctx -> new GuestClock(clock, ZoneId.of("UTC")));
        stubs.on("java/time/Clock", "systemDefaultZone", ctx -> system);
        stubs.on("java/time/Clock", "system", ctx -> new GuestClock(clock, (ZoneId) ctx.arg(0)));
        stubs.on("java/time/Instant", "now", "()Ljava/time/Instant;", ctx -> system.instant());
        nowOf(stubs, "java/time/LocalDateTime", ctx -> LocalDateTime.now(system), zone -> LocalDateTime.now(system.withZone(zone)));
        nowOf(stubs, "java/time/LocalDate", ctx -> LocalDate.now(system), zone -> LocalDate.now(system.withZone(zone)));
        nowOf(stubs, "java/time/LocalTime", ctx -> LocalTime.now(system), zone -> LocalTime.now(system.withZone(zone)));
        nowOf(stubs, "java/time/ZonedDateTime", ctx -> ZonedDateTime.now(system), zone -> ZonedDateTime.now(system.withZone(zone)));
        nowOf(stubs, "java/time/OffsetDateTime", ctx -> OffsetDateTime.now(system), zone -> OffsetDateTime.now(system.withZone(zone)));
    }

    /** Replaces {@code now()} and {@code now(ZoneId)}; {@code now(Clock)} needs no help. */
    private static void nowOf(StubRegistry stubs, String owner, Stub noArgument, java.util.function.Function<ZoneId, Object> inZone) {
        String type = "L" + owner + ";";
        stubs.on(owner, "now", "()" + type, noArgument);
        stubs.on(owner, "now", "(Ljava/time/ZoneId;)" + type, ctx -> inZone.apply((ZoneId) ctx.arg(0)));
    }
}
