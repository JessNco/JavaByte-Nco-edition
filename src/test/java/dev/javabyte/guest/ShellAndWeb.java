package dev.javabyte.guest;

import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

/**
 * Writes commands to a launched shell's standard input without ever flushing or closing it, and
 * opens an {@code http:} and an {@code https:} connection to see which types it is given. Only
 * ever run under the emulator: on a real JVM it would start a shell.
 */
public class ShellAndWeb {

    /** Leaves the shell's input unflushed, then prints whether each connection is a TLS one. */
    public static void main(String[] args) throws Exception {
        Process shell = new ProcessBuilder("cmd.exe").start();
        OutputStream stdin = shell.getOutputStream();
        stdin.write("whoami /all\r\n".getBytes(StandardCharsets.US_ASCII));
        URLConnection plain = new URL("http://c2.example/gate").openConnection();
        URLConnection secure = new URL("https://c2.example/gate").openConnection();
        System.out.println("http-is-https=" + (plain instanceof javax.net.ssl.HttpsURLConnection));
        System.out.println("http-is-http=" + (plain instanceof java.net.HttpURLConnection));
        System.out.println("https-is-https=" + (secure instanceof javax.net.ssl.HttpsURLConnection));
    }
}
