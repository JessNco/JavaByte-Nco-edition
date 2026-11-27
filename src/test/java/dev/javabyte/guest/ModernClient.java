package dev.javabyte.guest;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * What a client built on a modern HTTP library does before its first request, and how a
 * stealer derives its keys: plain sockets from {@code SocketFactory.getDefault()}, TLS set up
 * through the platform's trust manager factory, a PBKDF2 key, a hash and a MAC, and a long
 * decrypted configuration.
 */
public final class ModernClient {

    public static void main(String[] args) throws Exception {
        try (Socket plain = SocketFactory.getDefault().createSocket("plain.client.test", 8080)) {
            OutputStream out = plain.getOutputStream();
            out.write("PING".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init((KeyStore) null);
        TrustManager[] managers = trust.getTrustManagers();
        System.out.println("trust managers " + managers.length + " x509 " + (managers[0] instanceof X509TrustManager));
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, managers, null);
        try (Socket secure = tls.getSocketFactory().createSocket("tls.client.test", 443)) {
            secure.getOutputStream().write("HELLO".getBytes(StandardCharsets.US_ASCII));
        }

        SecretKeyFactory pbkdf2 = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        byte[] key = pbkdf2.generateSecret(new PBEKeySpec("hunter2".toCharArray(),
                "pepper".getBytes(StandardCharsets.US_ASCII), 1000, 256)).getEncoded();
        System.out.println("derived " + HexFormat.of().formatHex(key));

        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update("build-".getBytes(StandardCharsets.US_ASCII));
        sha.update("1234".getBytes(StandardCharsets.US_ASCII));
        System.out.println("digest " + HexFormat.of().formatHex(sha.digest()));

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("mac-key".getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
        System.out.println("mac " + HexFormat.of().formatHex(mac.doFinal("signed".getBytes(StandardCharsets.US_ASCII))));

        String config = longConfig();
        Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
        SecretKeySpec aesKey = new SecretKeySpec(key, "AES");
        IvParameterSpec iv = new IvParameterSpec(new byte[16]);
        aes.init(Cipher.ENCRYPT_MODE, aesKey, iv);
        byte[] sealed = aes.doFinal(config.getBytes(StandardCharsets.US_ASCII));
        aes.init(Cipher.DECRYPT_MODE, aesKey, iv);
        System.out.println("config " + new String(aes.doFinal(sealed), StandardCharsets.US_ASCII).length());
    }

    /** 3,000 characters, longer than the default preview, ending in something recognisable. */
    public static String longConfig() {
        StringBuilder text = new StringBuilder();
        while (text.length() < 2989) {
            text.append("padding;");
        }
        text.setLength(2989);
        return text.append("END=c2.test").toString();
    }
}
