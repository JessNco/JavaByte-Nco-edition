package dev.javabyte.env;

import dev.javabyte.config.EmulatorConfig;
import java.time.ZoneId;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The guest's time zone and locale, taken from {@code environment.timeZone} and
 * {@code environment.locale}.
 *
 * <p>The host JVM's own defaults are never shown to the sample. They would tell it where the
 * analysis computer is (and a server set to UTC looks like a sandbox), they could contradict the
 * simulated en-US desktop, and they would make the same run give different reports on different
 * computers. Every stub that would otherwise fall back on {@code TimeZone.getDefault()} or
 * {@code Locale.getDefault()} uses these values instead.
 */
public final class GuestRegion {

    /** The zone used when the config does not name one. */
    public static final String DEFAULT_TIME_ZONE = "America/New_York";

    /** The locale used when the config does not name one. */
    public static final String DEFAULT_LOCALE = "en-US";

    /** The guest's default time zone. */
    public final ZoneId zone;

    /** The guest's default locale, for every {@link Locale.Category}. */
    public final Locale locale;

    public GuestRegion(ZoneId zone, Locale locale) {
        this.zone = zone;
        this.locale = locale;
    }

    /**
     * Reads the region from the config. A missing value falls back to the default; an invalid
     * one has already been reported by config validation, so it fails loudly here.
     */
    public static GuestRegion of(EmulatorConfig.Environment config) {
        String zone = config.timeZone == null || config.timeZone.isBlank() ? DEFAULT_TIME_ZONE : config.timeZone;
        String locale = config.locale == null || config.locale.isBlank() ? DEFAULT_LOCALE : config.locale;
        return new GuestRegion(ZoneId.of(zone), new Locale.Builder().setLanguageTag(locale).build());
    }

    /**
     * A fresh {@code TimeZone} for the guest's zone. Fresh because {@code TimeZone} is mutable
     * and the real {@code TimeZone.getDefault()} also hands out a copy.
     */
    public TimeZone timeZone() {
        return TimeZone.getTimeZone(zone);
    }
}
