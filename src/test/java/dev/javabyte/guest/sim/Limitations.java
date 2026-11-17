package dev.javabyte.guest.sim;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.jar.JarInputStream;

/**
 * Uses features the emulator documents as unsupported. The tests pin down what happens
 * instead, so that a limitation is always visible in the report and never a silent wrong
 * answer, and so that lifting one shows up as a test to update.
 */
public final class Limitations {

    private static native int nativeCheck(int value);

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "http-client": {
                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create("http://modern.limit.test/gate")).build(),
                        HttpResponse.BodyHandlers.ofString());
                System.out.println("status " + response);
                break;
            }
            case "server-socket":
                try (ServerSocket server = new ServerSocket(4444)) {
                    Socket client = server.accept();
                    System.out.println("accepted " + client);
                }
                break;
            case "udp":
                try (DatagramSocket socket = new DatagramSocket()) {
                    byte[] data = "beacon".getBytes();
                    socket.send(new DatagramPacket(data, data.length, InetAddress.getByName("udp.limit.test"), 53));
                    System.out.println("sent");
                }
                break;
            case "virtual-time": {
                long wallBefore = System.currentTimeMillis();
                long nanoBefore = System.nanoTime();
                Thread.sleep(3_600_000); // an hour: a common way to outwait a sandbox
                long wall = System.currentTimeMillis() - wallBefore;
                long nano = (System.nanoTime() - nanoBefore) / 1_000_000;
                System.out.println("wall clock advanced an hour: " + (wall >= 3_600_000));
                System.out.println("nano clock advanced an hour: " + (nano >= 3_600_000));
                break;
            }
            case "native-unbound":
                try {
                    System.out.println("native returned " + nativeCheck(7));
                } catch (UnsatisfiedLinkError e) {
                    System.out.println("unsatisfied link");
                }
                break;
            case "native-missing-library":
                try {
                    System.loadLibrary("notshipped");
                    System.out.println("loaded");
                } catch (UnsatisfiedLinkError e) {
                    System.out.println("library not found");
                }
                break;
            case "missing-class":
                // Payload is deliberately left out of the jar, like a library the analyst lacks.
                System.out.println("phantom result: " + new Payload().run("phantom"));
                System.out.println("continued after the missing class");
                break;
            case "nested-jar": {
                // The inner archive is ordinary data to the emulator: readable, but not mounted.
                int entries = 0;
                try (InputStream in = Limitations.class.getResourceAsStream("/lib/inner.jar");
                        JarInputStream jar = new JarInputStream(in)) {
                    while (jar.getNextEntry() != null) {
                        entries++;
                    }
                }
                System.out.println("inner entries readable: " + entries);
                try {
                    Class.forName("dev.javabyte.guest.sim.Payload");
                    System.out.println("inner class found");
                } catch (ClassNotFoundException e) {
                    System.out.println("inner class not found");
                }
                break;
            }
            case "racing-threads": {
                // Unsynchronised increments lose updates on real hardware. Interleaving only at
                // instruction boundaries of one host thread can lose them too, but far more
                // rarely; what must hold is that the run is repeatable.
                int[] counter = new int[1];
                Thread[] workers = new Thread[4];
                for (int i = 0; i < workers.length; i++) {
                    workers[i] = new Thread(() -> {
                        for (int n = 0; n < 20_000; n++) {
                            counter[0]++;
                        }
                    });
                    workers[i].start();
                }
                for (Thread worker : workers) {
                    worker.join();
                }
                System.out.println("counter " + counter[0]);
                break;
            }
            default:
                throw new IllegalArgumentException(args[0]);
        }
    }
}
