package dev.javabyte.guest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A sample that stretches its key by hand, hashing three thousand times over, before it uses
 * the key; then decrypts in blocks, and with a password-based cipher. The hashes must not push
 * the key out of the report.
 */
public final class KeyStretcher {

    public static void main(String[] args) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] key = "seed".getBytes(StandardCharsets.US_ASCII);
        for (int round = 0; round < 3000; round++) {
            key = sha.digest(key);
        }

        Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
        SecretKeySpec aesKey = new SecretKeySpec(key, "AES");
        IvParameterSpec iv = new IvParameterSpec(new byte[16]);
        aes.init(Cipher.ENCRYPT_MODE, aesKey, iv);
        byte[] sealed = aes.doFinal("first block here; second block here; the end".getBytes(StandardCharsets.US_ASCII));
        aes.init(Cipher.DECRYPT_MODE, aesKey, iv);
        byte[] start = aes.update(sealed, 0, 32);
        byte[] rest = aes.doFinal(sealed, 32, sealed.length - 32);
        System.out.println("blocks " + start.length + " " + rest.length);

        Cipher pbe = Cipher.getInstance("PBEWithMD5AndDES");
        pbe.init(Cipher.ENCRYPT_MODE,
                SecretKeyFactory.getInstance("PBEWithMD5AndDES").generateSecret(new PBEKeySpec("letmein".toCharArray())),
                new PBEParameterSpec("saltsalt".getBytes(StandardCharsets.US_ASCII), 77));
        System.out.println("pbe " + pbe.doFinal(new byte[8]).length);

        // A password an obfuscator made from arbitrary values: half a surrogate pair is not text.
        char[] built = {'\uD800', 'a', '\u03AE'};
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(new PBEKeySpec(built, new byte[] {1, 2, 3, 4}, 5, 128));
    }
}
