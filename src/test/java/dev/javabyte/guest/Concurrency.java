package dev.javabyte.guest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A harmless multi-threaded program whose output does not depend on scheduling, provided the
 * scheduler honours blocking, mutual exclusion and visibility. Each section fails in a
 * recognisable way under a "run each thread to completion" model: it would hang, deadlock or
 * lose updates.
 */
public final class Concurrency {

    private static volatile boolean flag;
    private static int unsafeTotal;
    private static final Object LOCK = new Object();
    private static final ThreadLocal<String> NAME = ThreadLocal.withInitial(() -> "unset");

    static synchronized void add(int amount) {
        unsafeTotal += amount;
    }

    public static void main(String[] args) throws Exception {
        producerConsumer();
        busyWait();
        contendedCounters();
        waitNotifyPingPong();
        executorAndLatch();
        threadLocals();
    }

    /** The consumer starts first and must block on an empty queue until the producer runs. */
    static void producerConsumer() throws Exception {
        BlockingQueue<Integer> queue = new LinkedBlockingQueue<>();
        int[] sum = new int[1];
        Thread consumer = new Thread(() -> {
            try {
                for (int value = queue.take(); value >= 0; value = queue.take()) {
                    sum[0] += value;
                }
            } catch (InterruptedException e) {
                sum[0] = -1;
            }
        }, "consumer");
        consumer.start();
        Thread producer = new Thread(() -> {
            for (int i = 1; i <= 100; i++) {
                queue.add(i);
            }
            queue.add(-1);
        }, "producer");
        producer.start();
        consumer.join();
        System.out.println("producer/consumer sum=" + sum[0] + " alive=" + consumer.isAlive());
    }

    /** Main spins on a flag only another thread can set; without pre-emption this never ends. */
    static void busyWait() {
        flag = false;
        new Thread(() -> flag = true, "setter").start();
        long spins = 0;
        while (!flag) {
            spins++;
        }
        System.out.println("busy wait finished, spun=" + (spins >= 0));
    }

    /** Several threads update shared counters long enough to be interleaved. */
    static void contendedCounters() throws Exception {
        unsafeTotal = 0;
        int[] blockTotal = new int[1];
        int[] lockTotal = new int[1];
        ReentrantLock lock = new ReentrantLock();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 60_000; i++) {
                    add(1);
                    synchronized (LOCK) {
                        blockTotal[0]++;
                    }
                    lock.lock();
                    try {
                        lockTotal[0]++;
                    } finally {
                        lock.unlock();
                    }
                }
            }, "worker-" + t);
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        System.out.println("counters " + unsafeTotal + " " + blockTotal[0] + " " + lockTotal[0]);
    }

    /** Two threads alternate strictly using wait and notify on a shared monitor. */
    static void waitNotifyPingPong() throws Exception {
        StringBuilder log = new StringBuilder();
        boolean[] pingTurn = {true};
        Thread ping = new Thread(() -> rally(log, pingTurn, true, "ping "), "ping");
        Thread pong = new Thread(() -> rally(log, pingTurn, false, "pong "), "pong");
        pong.start();
        ping.start();
        ping.join();
        pong.join();
        System.out.println(log.toString().trim());
    }

    private static void rally(StringBuilder log, boolean[] pingTurn, boolean iAmPing, String word) {
        for (int i = 0; i < 4; i++) {
            synchronized (pingTurn) {
                while (pingTurn[0] != iAmPing) {
                    try {
                        pingTurn.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                log.append(word);
                pingTurn[0] = !iAmPing;
                pingTurn.notifyAll();
            }
        }
    }

    static void executorAndLatch() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch latch = new CountDownLatch(3);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int n = 1; n <= 3; n++) {
            int base = n;
            futures.add(pool.submit(() -> {
                int total = 0;
                for (int i = 0; i < 1000; i++) {
                    total += base;
                }
                latch.countDown();
                return total;
            }));
        }
        latch.await();
        int sum = 0;
        for (Future<Integer> future : futures) {
            sum += future.get();
        }
        pool.shutdown();
        System.out.println("executor sum=" + sum + " latch=" + latch.getCount());
    }

    /** Each thread must see its own thread-local value and its own Thread object. */
    static void threadLocals() throws Exception {
        NAME.set("main-value");
        String[] seen = new String[2];
        Thread other = new Thread(() -> {
            seen[0] = NAME.get() + "/" + Thread.currentThread().getName();
            NAME.set("other-value");
            seen[1] = NAME.get();
        }, "other");
        other.start();
        other.join();
        System.out.println(seen[0] + " " + seen[1] + " " + NAME.get() + "/" + Thread.currentThread().getName());
    }
}
