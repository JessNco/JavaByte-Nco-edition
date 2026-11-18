package dev.javabyte.guest.sim;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.chrono.HijrahDate;
import java.time.chrono.IsoChronology;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.StreamSupport;

/**
 * Simulated anti-emulation checks on time, threads and region. Each case prints lines that a
 * real desktop JVM would print; a scenario pins them down.
 */
public final class Timing {

    private static final Object LOCK = new Object();

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "clocks" -> clocks();
            case "timed-waits" -> timedWaits();
            case "threads" -> threads();
            case "region" -> region();
            case "parallel" -> parallel();
            case "wait-loop" -> {
                // A beacon loop that uses a timed wait as its sleep; nothing ever notifies.
                while (true) {
                    synchronized (LOCK) {
                        LOCK.wait(60_000);
                    }
                }
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    /** The two clocks agree, computation takes time, and nanoTime does not start near zero. */
    private static void clocks() {
        System.out.println("nano origin over an hour: " + (System.nanoTime() > 3_600_000_000_000L));
        long millisBefore = System.currentTimeMillis();
        long nanosBefore = System.nanoTime();
        long sink = 0;
        for (int i = 0; i < 1000; i++) {
            sink += System.currentTimeMillis();
        }
        long millis = System.currentTimeMillis() - millisBefore;
        long nanosAsMillis = (System.nanoTime() - nanosBefore) / 1_000_000;
        System.out.println("clocks agree: " + (Math.abs(millis - nanosAsMillis) <= 1) + (sink == 0 ? "!" : ""));

        long start = System.nanoTime();
        long acc = 0;
        for (int i = 0; i < 3_000_000; i++) {
            acc += i * 31 ^ (acc >>> 3);
        }
        long busy = System.nanoTime() - start;
        System.out.println("busy loop took a plausible time: " + (busy > 1_000_000 && busy < 1_000_000_000) + (acc == 42 ? "!" : ""));

        long waitStart = System.currentTimeMillis();
        while (System.currentTimeMillis() - waitStart < 30_000) {
            // busy-wait instead of sleeping, as samples do to dodge sleep hooks
        }
        System.out.println("busy wait finished");
    }

    /** Every kind of timed wait takes its full time in virtual time, and no longer. */
    private static void timedWaits() throws Exception {
        ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor();
        long t = System.currentTimeMillis();
        pool.schedule(() -> { }, 60, TimeUnit.SECONDS).get();
        System.out.println("schedule waited a minute: " + (System.currentTimeMillis() - t >= 60_000));

        t = System.currentTimeMillis();
        LockSupport.parkNanos(60_000_000_000L);
        System.out.println("parkNanos waited a minute: " + (System.currentTimeMillis() - t >= 60_000));

        t = System.currentTimeMillis();
        try {
            new CompletableFuture<String>().get(60, TimeUnit.SECONDS);
            System.out.println("future completed");
        } catch (TimeoutException expected) {
            System.out.println("future timed out after a minute: " + (System.currentTimeMillis() - t >= 60_000));
        }

        Future<?> late = pool.schedule(() -> { }, 60, TimeUnit.SECONDS);
        t = System.currentTimeMillis();
        try {
            late.get(1, TimeUnit.SECONDS);
            System.out.println("late task finished");
        } catch (TimeoutException expected) {
            long waited = System.currentTimeMillis() - t;
            System.out.println("executor future timed out after a second: " + (waited >= 1_000 && waited < 2_000));
        }

        Thread sleeper = new Thread(() -> {
            try {
                Thread.sleep(100_000);
            } catch (InterruptedException ignored) {
                // not interrupted here
            }
        });
        sleeper.start();
        t = System.currentTimeMillis();
        sleeper.join(30_000);
        long joined = System.currentTimeMillis() - t;
        System.out.println("join gave up after 30 s: " + (joined >= 30_000 && joined < 31_000));

        Thread notifier = new Thread(() -> {
            synchronized (LOCK) {
                LOCK.notifyAll();
            }
        });
        t = System.currentTimeMillis();
        synchronized (LOCK) {
            notifier.start();
            LOCK.wait(5_000);
        }
        System.out.println("notify ended the wait early: " + (System.currentTimeMillis() - t < 5_000));

        t = System.currentTimeMillis();
        synchronized (LOCK) {
            LOCK.wait(5_000);
        }
        System.out.println("unnotified wait lasted its timeout: " + (System.currentTimeMillis() - t >= 5_000));

        CountDownLatch latch = new CountDownLatch(1);
        new Thread(latch::countDown).start();
        t = System.currentTimeMillis();
        boolean counted = latch.await(10, TimeUnit.SECONDS);
        System.out.println("latch counted down early: " + (counted && System.currentTimeMillis() - t < 10_000));
    }

    /** The main thread and the thread list look like those of a real Java 8 JVM. */
    private static void threads() throws Exception {
        Thread main = Thread.currentThread();
        System.out.println("main id " + main.getId() + " daemon " + main.isDaemon() + " text " + main.toString());
        System.out.println(new StringBuilder("appended ").append(main));
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        boolean jdwp = all.keySet().stream().anyMatch(thread -> thread.getName().contains("JDWP"));
        System.out.println("all threads include me: " + all.containsKey(main) + " size>=4: " + (all.size() >= 4) + " jdwp: " + jdwp);
        System.out.println("system thread: " + all.keySet().stream().anyMatch(thread -> thread.getName().equals("Reference Handler")));
        System.out.println("active " + Thread.activeCount());
        Thread worker = new Thread(() -> { }, "worker");
        System.out.println("new thread daemon " + worker.isDaemon() + " state " + worker.getState()
                + " stack " + worker.getStackTrace().length + " id>10 " + (worker.getId() > 10));
        worker.start();
        worker.join();
        System.out.println("finished state " + worker.getState() + " text " + worker.toString());
    }

    /** Time zone and locale come from the config, and every date source agrees with clockStart. */
    private static void region() {
        System.out.println("zone " + TimeZone.getDefault().getID() + " " + ZoneId.systemDefault()
                + " property " + System.getProperty("user.timezone"));
        System.out.println("locale " + Locale.getDefault() + " language " + System.getProperty("user.language")
                + " country " + System.getProperty("user.country"));
        System.out.println("format " + String.format("%,.2f", 1234.5));
        System.out.println("date " + new Date().toString());
        System.out.println(new StringBuilder("built ").append(new Date()));
        System.out.println("pattern " + new SimpleDateFormat("yyyy-MM-dd HH:mm zzz").format(new Date()));
        System.out.println("year " + Year.now() + " month " + YearMonth.now() + " local " + LocalDate.now());
        System.out.println("hijrah agrees " + HijrahDate.now().equals(HijrahDate.from(LocalDate.now())));
        System.out.println("chronology agrees " + IsoChronology.INSTANCE.dateNow().equals(LocalDate.now()));
        System.out.println("calendars " + new GregorianCalendar(TimeZone.getTimeZone("UTC")).get(Calendar.YEAR)
                + " " + new GregorianCalendar(Locale.UK).get(Calendar.YEAR) + " " + Calendar.getInstance().getTimeZone().getID());
        @SuppressWarnings("deprecation")
        int hours = new Date(0).getHours();
        System.out.println("epoch hour " + hours);
    }

    /** Parallel APIs that would use the common pool give the right answer, sequentially. */
    private static void parallel() throws Exception {
        List<Integer> numbers = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        int sum = StreamSupport.stream(numbers.spliterator(), true).mapToInt(Integer::intValue).sum();
        System.out.println("stream sum " + sum);
        CompletionStage<String> stage = CompletableFuture.completedFuture("stage");
        System.out.println("stage " + stage.thenApplyAsync(String::toUpperCase).toCompletableFuture().get());
        ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
        for (int i = 0; i < 100; i++) {
            map.put("k" + i, i);
        }
        AtomicInteger seen = new AtomicInteger();
        map.forEach(1, (key, value) -> seen.incrementAndGet());
        System.out.println("map seen " + seen.get() + " reduced " + map.reduceValues(1, Integer::sum));
        int[] prefix = new int[100];
        Arrays.fill(prefix, 1);
        Arrays.parallelPrefix(prefix, Integer::sum);
        System.out.println("prefix last " + prefix[99]);
    }
}
