package dev.javabyte.guest.sim;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Simulated sample that does its work away from the main thread: a timer, a scheduled executor,
 * an asynchronous future and a shutdown hook each launch something.
 */
public final class Background {

    public static void main(String[] args) throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> launch("cleanup.exe"), "hook"));
        new Timer("timer", true).schedule(new TimerTask() {
            @Override
            public void run() {
                launch("from-timer.exe");
            }
        }, 5_000, 60_000);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> launch("from-scheduler.exe"), 1, TimeUnit.HOURS);
        String async = CompletableFuture.supplyAsync(() -> {
            launch("from-future.exe");
            return "future-done";
        }).thenApply(String::toUpperCase).get();
        System.out.println("main finished after " + async);
    }

    private static void launch(String program) {
        try {
            Runtime.getRuntime().exec(program);
        } catch (Exception ignored) {
            // a real sample would stay quiet too
        }
    }
}
