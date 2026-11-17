package dev.javabyte.guest.sim;

import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Programs that never finish or use unbounded resources, one per emulator limit. Samples do
 * this on purpose (to outlast sandboxes) and by accident; either way the run has to end with a
 * clear outcome and keep everything observed up to that point.
 */
public final class Limits {

    private static int depth;

    public static void main(String[] args) throws Exception {
        System.out.println("started " + args[0]);
        switch (args[0]) {
            case "spin":
                for (long i = 0; ; i++) {
                    if (i == 1000) {
                        Runtime.getRuntime().exec("seen-before-the-limit.exe");
                    }
                }
            case "recurse":
                try {
                    recurse();
                } catch (StackOverflowError e) {
                    // Real samples use a deliberate overflow as an anti-emulation check.
                    System.out.println("overflow caught, depth over 100: " + (depth > 100));
                }
                break;
            case "beacon":
                while (true) {
                    Thread.sleep(60_000);
                }
            case "wait": {
                Object lock = new Object();
                synchronized (lock) {
                    lock.wait(); // nobody will ever notify
                }
                break;
            }
            case "tasks": {
                ExecutorService pool = Executors.newFixedThreadPool(2);
                spawn(pool, 0);
                break;
            }
            case "bigwrite":
                try (FileOutputStream out = new FileOutputStream("fill.bin")) {
                    byte[] block = new byte[64 * 1024];
                    while (true) {
                        out.write(block);
                    }
                }
            case "flood":
                for (int i = 0; i < 500; i++) {
                    System.out.println("line " + i);
                }
                Runtime.getRuntime().exec("after-the-flood.exe");
                break;
            default:
                throw new IllegalArgumentException(args[0]);
        }
        System.out.println("finished " + args[0]);
    }

    private static void recurse() {
        depth++;
        recurse();
    }

    /** Every task queues two more: an unbounded fan-out. */
    private static void spawn(ExecutorService pool, int generation) {
        pool.submit(() -> spawn(pool, generation + 1));
        pool.submit(() -> spawn(pool, generation + 1));
    }
}
