package dev.javabyte.stubs;

import dev.javabyte.env.GuestRegion;
import dev.javabyte.env.VirtualClock;
import dev.javabyte.host.CallContext;
import dev.javabyte.host.StubRegistry;
import dev.javabyte.vm.VirtualMachine;
import java.text.Collator;
import java.text.DateFormat;
import java.text.DateFormatSymbols;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.chrono.Chronology;
import java.time.chrono.HijrahDate;
import java.time.chrono.JapaneseDate;
import java.time.chrono.MinguoDate;
import java.time.chrono.ThaiBuddhistDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Calendar;
import java.util.Date;
import java.util.Formatter;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Function;

/**
 * Points every "what time is it" and "where am I" call at the virtual environment.
 *
 * <p>{@code System.currentTimeMillis} alone is not enough: {@code new Date()},
 * {@code Calendar.getInstance()}, {@code Year.now()} and the other {@code now()} methods read the
 * real clock inside the JDK. Left alone they would disagree with the virtual clock after a
 * simulated sleep (an hour-long sleep would show an hour on one clock and nothing on the
 * other, which samples use to detect sandboxes), ignore {@code environment.clockStart}, and
 * make reports differ from run to run. Only the forms without a clock or instant argument are
 * replaced; the others already take their time from a value the sample supplies.
 *
 * <p>The same goes for the time zone and the locale. The JDK falls back on the host's defaults
 * in many places ({@code TimeZone.getDefault()}, {@code new SimpleDateFormat(pattern)},
 * {@code Date.toString()}, {@code String.format}, ...). Those would tell the sample where the
 * analysis computer is. Here they use {@code environment.timeZone} and
 * {@code environment.locale} instead (see {@link GuestRegion}). With a fixed
 * {@code environment.clockStart} and {@code environment.randomSeed}, the same sample therefore
 * gives the same report on every computer, whatever its own clock, zone or language.
 *
 * <p>Known gaps, left to the host's defaults: {@code Date(String)} and {@code Date.parse},
 * {@code Calendar.Builder}, {@code Scanner}, {@code BreakIterator}, {@code %t} conversions in
 * format strings, JDK subclasses the sample extends itself, and a {@code Date} or
 * {@code Thread} turned into text by string concatenation compiled for Java 9 or later
 * (handled in {@code IndyLinker}, which calls the host's {@code toString}).
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

    /** Installs this class's stubs on the machine; called once per run from {@code BuiltinStubs}. */
    static void install(VirtualMachine vm) {
        VirtualClock clock = vm.env.clock;
        // From here on, executed instructions cost virtual time.
        clock.chargeInstructions(vm::instructionCount);
        GuestRegion region = vm.env.region;
        Clock system = new GuestClock(clock, region.zone);

        installCurrentTime(vm.stubs, clock, system, region);
        installCalendars(vm.stubs, clock, region);
        installDates(vm, region);
        installRegionDefaults(vm, region);
        installImplicitText(vm);
    }

    /**
     * Calls that turn an object into text inside the JDK ({@code StringBuilder.append(Object)},
     * {@code String.valueOf}, {@code println(Object)}, ...) call {@code toString()} on the host,
     * where no stub can see it. For the types whose text the emulator replaces (a plain
     * {@code Date}, which shows the time zone, and a {@code Thread}, whose host form shows the
     * newer JDK), the text is made through the guest's own {@code toString()} call instead, so
     * {@code sb.append(date)} and {@code sb.append(date.toString())} agree.
     */
    private static void installImplicitText(VirtualMachine vm) {
        StubRegistry stubs = vm.stubs;
        for (String builder : new String[] {"java/lang/StringBuilder", "java/lang/StringBuffer"}) {
            stubs.on(builder, "append", "(Ljava/lang/Object;)L" + builder + ";", ctx -> withText(vm, ctx, 0));
        }
        for (String printer : new String[] {"java/io/PrintStream", "java/io/PrintWriter"}) {
            stubs.on(printer, "print", "(Ljava/lang/Object;)V", ctx -> withText(vm, ctx, 0));
            stubs.on(printer, "println", "(Ljava/lang/Object;)V", ctx -> withText(vm, ctx, 0));
        }
        stubs.on("java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", ctx -> withText(vm, ctx, 0));
        stubs.on("java/util/Objects", "toString", ctx -> withText(vm, ctx, 0));
    }

    /** Performs the call with argument {@code index} replaced by its guest text, if it has one. */
    private static Object withText(VirtualMachine vm, CallContext ctx, int index) {
        Object value = ctx.arg(index);
        String owner = value instanceof Date && isPlainDate(value) ? "java/util/Date"
                : value instanceof Thread ? "java/lang/Thread" : null;
        if (owner == null) {
            return ctx.proceed();
        }
        Object[] args = ctx.args.clone();
        args[index] = vm.router.invokeVirtual(owner, "toString", "()Ljava/lang/String;", value, new Object[0]);
        return ctx.proceedWith(args);
    }

    /** {@code now()} on every {@code java.time} type and chronology, and {@code java.time.Clock}. */
    private static void installCurrentTime(StubRegistry stubs, VirtualClock clock, Clock system, GuestRegion region) {
        stubs.on("java/time/Clock", "systemUTC", ctx -> new GuestClock(clock, ZoneId.of("UTC")));
        stubs.on("java/time/Clock", "systemDefaultZone", ctx -> system);
        stubs.on("java/time/Clock", "system", ctx -> new GuestClock(clock, (ZoneId) ctx.arg(0)));
        stubs.on("java/time/Instant", "now", "()Ljava/time/Instant;", ctx -> system.instant());
        stubs.on("java/time/ZoneId", "systemDefault", ctx -> region.zone);
        nowOf(stubs, "java/time/LocalDateTime", system, LocalDateTime::now);
        nowOf(stubs, "java/time/LocalDate", system, LocalDate::now);
        nowOf(stubs, "java/time/LocalTime", system, LocalTime::now);
        nowOf(stubs, "java/time/ZonedDateTime", system, ZonedDateTime::now);
        nowOf(stubs, "java/time/OffsetDateTime", system, OffsetDateTime::now);
        nowOf(stubs, "java/time/OffsetTime", system, OffsetTime::now);
        nowOf(stubs, "java/time/Year", system, Year::now);
        nowOf(stubs, "java/time/YearMonth", system, YearMonth::now);
        nowOf(stubs, "java/time/MonthDay", system, MonthDay::now);
        nowOf(stubs, "java/time/chrono/HijrahDate", system, HijrahDate::now);
        nowOf(stubs, "java/time/chrono/JapaneseDate", system, JapaneseDate::now);
        nowOf(stubs, "java/time/chrono/MinguoDate", system, MinguoDate::now);
        nowOf(stubs, "java/time/chrono/ThaiBuddhistDate", system, ThaiBuddhistDate::now);
        // Chronology.dateNow() reads the host's clock inside the JDK. The concrete chronologies
        // narrow the return type, so the stub matches by name and looks at the arguments.
        stubs.on("java/time/chrono/Chronology", "dateNow", ctx -> {
            Chronology chronology = (Chronology) ctx.receiver;
            if (ctx.argCount() == 0) {
                return chronology.dateNow(system);
            }
            return ctx.arg(0) instanceof ZoneId zone ? chronology.dateNow(system.withZone(zone)) : ctx.proceed();
        });
    }

    /**
     * Replaces {@code now()} and {@code now(ZoneId)}; {@code now(Clock)} needs no help.
     *
     * @param fromClock the type's own {@code now(Clock)}, given the guest clock in the right zone
     */
    private static void nowOf(StubRegistry stubs, String owner, Clock system, Function<Clock, Object> fromClock) {
        String type = "L" + owner + ";";
        stubs.on(owner, "now", "()" + type, ctx -> fromClock.apply(system));
        stubs.on(owner, "now", "(Ljava/time/ZoneId;)" + type, ctx -> fromClock.apply(system.withZone((ZoneId) ctx.arg(0))));
    }

    /**
     * {@code Calendar} and {@code GregorianCalendar}: the current time is virtual, and a missing
     * zone or locale is the guest's.
     */
    private static void installCalendars(StubRegistry stubs, VirtualClock clock, GuestRegion region) {
        stubs.on("java/util/Calendar", "getInstance", ctx -> {
            TimeZone zone = region.timeZone();
            Locale locale = region.locale;
            for (Object arg : ctx.args) {
                if (arg instanceof TimeZone given) {
                    zone = given;
                } else if (arg instanceof Locale given) {
                    locale = given;
                }
            }
            Calendar calendar = Calendar.getInstance(zone, locale);
            calendar.setTimeInMillis(clock.currentTimeMillis());
            return calendar;
        });
        String gregorian = "java/util/GregorianCalendar";
        for (String desc : new String[] {"()V", "(Ljava/util/Locale;)V", "(Ljava/util/TimeZone;)V",
                "(Ljava/util/TimeZone;Ljava/util/Locale;)V"}) {
            stubs.on(gregorian, "<init>", desc, ctx -> {
                GregorianCalendar calendar = guestCalendar(region, ctx);
                calendar.setTimeInMillis(clock.currentTimeMillis());
                return calendar;
            });
        }
        // The constructors that take date fields read them in the default zone.
        for (String desc : new String[] {"(III)V", "(IIIII)V", "(IIIIII)V"}) {
            stubs.on(gregorian, "<init>", desc, ctx -> {
                GregorianCalendar calendar = guestCalendar(region, ctx);
                calendar.clear();
                setFields(calendar, ctx.args);
                return calendar;
            });
        }
    }

    /** A Gregorian calendar in the zone and locale the call names, or else the guest's. */
    private static GregorianCalendar guestCalendar(GuestRegion region, CallContext ctx) {
        TimeZone zone = region.timeZone();
        Locale locale = region.locale;
        for (Object arg : ctx.args) {
            if (arg instanceof TimeZone given) {
                zone = given;
            } else if (arg instanceof Locale given) {
                locale = given;
            }
        }
        return new GregorianCalendar(zone, locale);
    }

    /** Year, month, day and optionally hour, minute and second, in that order, as the constructors take them. */
    private static void setFields(Calendar calendar, Object[] fields) {
        int[] which = {Calendar.YEAR, Calendar.MONTH, Calendar.DAY_OF_MONTH, Calendar.HOUR_OF_DAY, Calendar.MINUTE, Calendar.SECOND};
        for (int i = 0; i < fields.length; i++) {
            calendar.set(which[i], (Integer) fields[i]);
        }
    }

    /**
     * {@code java.util.Date}: the current time is virtual, and the methods that work in the
     * default zone (its text form and the old field accessors) use the guest's zone.
     */
    private static void installDates(VirtualMachine vm, GuestRegion region) {
        StubRegistry stubs = vm.stubs;
        VirtualClock clock = vm.env.clock;
        String date = "java/util/Date";
        stubs.on(date, "<init>", "()V", ctx -> new Date(clock.currentTimeMillis()));
        for (String desc : new String[] {"(III)V", "(IIIII)V", "(IIIIII)V"}) {
            stubs.on(date, "<init>", desc, ctx -> {
                Calendar calendar = new GregorianCalendar(region.timeZone(), Locale.ROOT);
                calendar.clear();
                Object[] fields = ctx.args.clone();
                fields[0] = (Integer) fields[0] + 1900;
                setFields(calendar, fields);
                return new Date(calendar.getTimeInMillis());
            });
        }
        // java.sql.Date and Timestamp override several of these; they keep their own behaviour.
        stubs.on(date, "toString", ctx -> !isPlainDate(ctx.receiver) ? ctx.proceed()
                : dateFormat(new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US), region).format((Date) ctx.receiver));
        stubs.on(date, "toLocaleString", ctx -> !isPlainDate(ctx.receiver) ? ctx.proceed()
                : dateFormat(DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, region.locale), region)
                        .format((Date) ctx.receiver));
        String[][] getters = {{"getYear", "-1"}, {"getMonth", String.valueOf(Calendar.MONTH)},
                {"getDate", String.valueOf(Calendar.DAY_OF_MONTH)}, {"getDay", "-2"},
                {"getHours", String.valueOf(Calendar.HOUR_OF_DAY)}, {"getMinutes", String.valueOf(Calendar.MINUTE)},
                {"getSeconds", String.valueOf(Calendar.SECOND)}, {"getTimezoneOffset", "-3"}};
        for (String[] getter : getters) {
            int field = Integer.parseInt(getter[1]);
            stubs.on(date, getter[0], "()I", ctx -> {
                if (!isPlainDate(ctx.receiver)) {
                    return ctx.proceed();
                }
                Calendar calendar = calendarAt(region, (Date) ctx.receiver);
                return switch (field) {
                    case -1 -> calendar.get(Calendar.YEAR) - 1900;
                    case -2 -> calendar.get(Calendar.DAY_OF_WEEK) - 1;
                    case -3 -> -(calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET)) / 60_000;
                    default -> calendar.get(field);
                };
            });
        }
        String[][] setters = {{"setYear", "-1"}, {"setMonth", String.valueOf(Calendar.MONTH)},
                {"setDate", String.valueOf(Calendar.DAY_OF_MONTH)}, {"setHours", String.valueOf(Calendar.HOUR_OF_DAY)},
                {"setMinutes", String.valueOf(Calendar.MINUTE)}, {"setSeconds", String.valueOf(Calendar.SECOND)}};
        for (String[] setter : setters) {
            int field = Integer.parseInt(setter[1]);
            stubs.on(date, setter[0], "(I)V", ctx -> {
                if (!isPlainDate(ctx.receiver)) {
                    return ctx.proceed();
                }
                Date target = (Date) ctx.receiver;
                Calendar calendar = calendarAt(region, target);
                if (field == -1) {
                    calendar.set(Calendar.YEAR, ctx.intArg(0) + 1900);
                } else {
                    calendar.set(field, ctx.intArg(0));
                }
                target.setTime(calendar.getTimeInMillis());
                return null;
            });
        }
    }

    /** True for a {@code java.util.Date} itself, not a JDK subclass with its own rules. */
    private static boolean isPlainDate(Object receiver) {
        return receiver != null && receiver.getClass() == Date.class;
    }

    /** A calendar in the guest's zone, set to the given date. */
    private static Calendar calendarAt(GuestRegion region, Date date) {
        Calendar calendar = new GregorianCalendar(region.timeZone(), Locale.ROOT);
        calendar.setTime(date);
        return calendar;
    }

    /** Puts a date format into the guest's zone and returns it. */
    private static <T extends DateFormat> T dateFormat(T format, GuestRegion region) {
        format.setTimeZone(region.timeZone());
        return format;
    }

    /**
     * Calls that fall back on the default time zone or locale. Each is rebuilt with the guest's
     * values; calls that name a zone or locale themselves are left alone.
     */
    private static void installRegionDefaults(VirtualMachine vm, GuestRegion region) {
        StubRegistry stubs = vm.stubs;
        Locale locale = region.locale;

        // As on a real JVM, the first question for the default zone also sets user.timezone.
        stubs.on("java/util/TimeZone", "getDefault", ctx -> {
            if (vm.env.properties.getProperty("user.timezone", "").isEmpty()) {
                vm.env.properties.setProperty("user.timezone", region.zone.getId());
            }
            return region.timeZone();
        });
        stubs.on("java/util/Locale", "getDefault", ctx -> locale);

        // Text and number formatting.
        stubs.on("java/lang/String", "format", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;",
                ctx -> vm.host.invoke("java/lang/String", "format",
                        "(Ljava/util/Locale;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;",
                        null, new Object[] {locale, ctx.arg(0), ctx.arg(1)}, false));
        stubs.on("java/lang/String", "formatted", "([Ljava/lang/Object;)Ljava/lang/String;",
                ctx -> vm.host.invoke("java/lang/String", "format",
                        "(Ljava/util/Locale;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;",
                        null, new Object[] {locale, ctx.receiver, ctx.arg(0)}, false));
        stubs.on("java/lang/String", "toLowerCase", "()Ljava/lang/String;", ctx -> ((String) ctx.receiver).toLowerCase(locale));
        stubs.on("java/lang/String", "toUpperCase", "()Ljava/lang/String;", ctx -> ((String) ctx.receiver).toUpperCase(locale));
        for (String printer : new String[] {"java/io/PrintStream", "java/io/PrintWriter"}) {
            for (String name : new String[] {"printf", "format"}) {
                String type = "L" + printer + ";";
                stubs.on(printer, name, "(Ljava/lang/String;[Ljava/lang/Object;)" + type,
                        ctx -> vm.host.invoke(printer, name, "(Ljava/util/Locale;Ljava/lang/String;[Ljava/lang/Object;)" + type,
                                ctx.receiver, new Object[] {locale, ctx.arg(0), ctx.arg(1)}, false));
            }
        }
        stubs.on("java/util/Formatter", "<init>", "()V", ctx -> new Formatter(locale));
        stubs.on("java/util/Formatter", "<init>", "(Ljava/lang/Appendable;)V", ctx -> new Formatter((Appendable) ctx.arg(0), locale));
        stubs.on("java/text/NumberFormat", "getInstance", "()Ljava/text/NumberFormat;", ctx -> NumberFormat.getInstance(locale));
        stubs.on("java/text/NumberFormat", "getNumberInstance", "()Ljava/text/NumberFormat;", ctx -> NumberFormat.getNumberInstance(locale));
        stubs.on("java/text/NumberFormat", "getIntegerInstance", "()Ljava/text/NumberFormat;", ctx -> NumberFormat.getIntegerInstance(locale));
        stubs.on("java/text/NumberFormat", "getCurrencyInstance", "()Ljava/text/NumberFormat;", ctx -> NumberFormat.getCurrencyInstance(locale));
        stubs.on("java/text/NumberFormat", "getPercentInstance", "()Ljava/text/NumberFormat;", ctx -> NumberFormat.getPercentInstance(locale));
        stubs.on("java/text/DecimalFormat", "<init>", "()V", ctx -> new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(locale)));
        stubs.on("java/text/DecimalFormat", "<init>", "(Ljava/lang/String;)V",
                ctx -> new DecimalFormat(ctx.stringArg(0), DecimalFormatSymbols.getInstance(locale)));
        stubs.on("java/text/DecimalFormatSymbols", "<init>", "()V", ctx -> new DecimalFormatSymbols(locale));
        stubs.on("java/text/DecimalFormatSymbols", "getInstance", "()Ljava/text/DecimalFormatSymbols;",
                ctx -> DecimalFormatSymbols.getInstance(locale));
        stubs.on("java/text/MessageFormat", "<init>", "(Ljava/lang/String;)V", ctx -> {
            MessageFormat format = new MessageFormat(ctx.stringArg(0), locale);
            for (java.text.Format inner : format.getFormats()) {
                if (inner instanceof DateFormat dates) {
                    dates.setTimeZone(region.timeZone());
                }
            }
            return format;
        });
        stubs.on("java/text/Collator", "getInstance", "()Ljava/text/Collator;", ctx -> Collator.getInstance(locale));

        // Dates as text.
        String simple = "java/text/SimpleDateFormat";
        stubs.on(simple, "<init>", "()V", ctx -> dateFormat(
                (SimpleDateFormat) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale), region));
        stubs.on(simple, "<init>", "(Ljava/lang/String;)V", ctx -> dateFormat(new SimpleDateFormat(ctx.stringArg(0), locale), region));
        stubs.on(simple, "<init>", "(Ljava/lang/String;Ljava/util/Locale;)V",
                ctx -> dateFormat(new SimpleDateFormat(ctx.stringArg(0), (Locale) ctx.arg(1)), region));
        stubs.on(simple, "<init>", "(Ljava/lang/String;Ljava/text/DateFormatSymbols;)V",
                ctx -> dateFormat(new SimpleDateFormat(ctx.stringArg(0), (DateFormatSymbols) ctx.arg(1)), region));
        stubs.on("java/text/DateFormatSymbols", "<init>", "()V", ctx -> new DateFormatSymbols(locale));
        stubs.on("java/text/DateFormatSymbols", "getInstance", "()Ljava/text/DateFormatSymbols;", ctx -> DateFormatSymbols.getInstance(locale));
        for (String name : new String[] {"getInstance", "getDateInstance", "getTimeInstance", "getDateTimeInstance"}) {
            stubs.on("java/text/DateFormat", name, ctx -> dateFormat(dateFormatFor(ctx, locale), region));
        }
        stubs.on("java/time/format/DateTimeFormatter", "ofPattern", "(Ljava/lang/String;)Ljava/time/format/DateTimeFormatter;",
                ctx -> DateTimeFormatter.ofPattern(ctx.stringArg(0), locale));
        for (String name : new String[] {"ofLocalizedDate", "ofLocalizedTime", "ofLocalizedDateTime"}) {
            stubs.on("java/time/format/DateTimeFormatter", name, ctx -> ((DateTimeFormatter) ctx.proceed()).withLocale(locale));
        }
        stubs.on("java/time/format/DateTimeFormatterBuilder", "toFormatter", "()Ljava/time/format/DateTimeFormatter;",
                ctx -> ((DateTimeFormatterBuilder) ctx.receiver).toFormatter(locale));
    }

    /**
     * The {@code DateFormat} factory the call names, with the guest's locale when it gives none.
     * Styles default to {@code DEFAULT}, and {@code getInstance()} is {@code SHORT, SHORT}, as in
     * the JDK.
     */
    private static DateFormat dateFormatFor(CallContext ctx, Locale fallback) {
        Locale locale = ctx.argCount() > 0 && ctx.arg(ctx.argCount() - 1) instanceof Locale given ? given : fallback;
        int first = ctx.argCount() > 0 && ctx.arg(0) instanceof Integer style ? style : DateFormat.DEFAULT;
        int second = ctx.argCount() > 1 && ctx.arg(1) instanceof Integer style ? style : DateFormat.DEFAULT;
        return switch (ctx.name) {
            case "getInstance" -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale);
            case "getDateInstance" -> DateFormat.getDateInstance(first, locale);
            case "getTimeInstance" -> DateFormat.getTimeInstance(first, locale);
            default -> DateFormat.getDateTimeInstance(first, second, locale);
        };
    }
}
