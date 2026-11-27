package dev.javabyte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.config.ConfigLoader;
import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.guest.KeyStretcher;
import dev.javabyte.guest.ModernClient;
import dev.javabyte.report.Event;
import dev.javabyte.report.Report;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What samples built for current Java and current HTTP libraries need: Java 21 thread
 * builders, the default socket factory and the platform trust manager factory (which OkHttp
 * uses before its first request), and records of key derivations, hashes and MACs.
 */
class ModernJdkTest {

    @TempDir
    Path temp;

    private Report runClient(EmulatorConfig config) throws Exception {
        Report report = Fixtures.run(config);
        assertTrue(report.hasOutcome("completed"), Fixtures.outcomes(report));
        return report;
    }

    private EmulatorConfig clientConfig() throws Exception {
        return Fixtures.config(Fixtures.jar(temp, Map.of(), ModernClient.class));
    }

    private static Map<String, Object> only(Report report, String category, String action) {
        List<Event> found = Fixtures.events(report, category, action);
        assertEquals(1, found.size(), category + '.' + action + ": " + found);
        return found.get(0).data();
    }

    @Test
    void threadBuildersStartTheirThreadsOnTheScheduler() throws Exception {
        List<String> expected = List.of("ran:worker-0", "true", "ran:worker-1", "true", "true",
                "ran:plain", "false", "restart refused", "ran:", "done");
        byte[] bytes = GeneratedClasses.threadBuilders("ThreadBuilders");
        if (Runtime.version().feature() >= 21) {
            assertEquals(expected, Fixtures.realOutput("ThreadBuilders", bytes), "the fixture itself, on the real JVM");
        }
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("ThreadBuilders.class", bytes);
        Report report = Fixtures.run(Fixtures.config(Fixtures.rawJar(temp.resolve("builders.jar"), "ThreadBuilders", entries)));

        assertTrue(report.hasOutcome("completed"), Fixtures.outcomes(report));
        assertEquals(expected, Fixtures.stdout(report), Fixtures.outcomes(report));
        assertEquals(List.of(), report.events().byCategory("gap"));
        assertEquals(List.of(), report.events().byCategory("policy"));
        List<Event> started = Fixtures.events(report, "thread", "start");
        assertEquals(List.of("worker-0", "worker-1", "plain", ""), started.stream().map(e -> e.data().get("name")).toList());
        assertEquals(List.of(true, true, false, true), started.stream().map(e -> e.data().get("virtual")).toList());
    }

    @Test
    void theDefaultSocketFactoryAndTrustManagersReachTheSimulatedNetwork() throws Exception {
        Report report = runClient(clientConfig());

        assertTrue(Fixtures.stdout(report).contains("trust managers 1 x509 true"), Fixtures.outcomes(report));
        List<Object> endpoints = Fixtures.events(report, "net", "connect").stream().map(e -> e.data().get("endpoint")).toList();
        assertEquals(List.of("plain.client.test:8080", "tls.client.test:443"), endpoints);
        List<Object> sent = Fixtures.events(report, "net", "send").stream().map(e -> e.data().get("preview")).toList();
        assertEquals(List.of("PING", "HELLO"), sent);
        // Nothing was refused: the JDK's own factory, which reads the certificate store from
        // disk, was never called.
        assertEquals(List.of(), report.events().byCategory("policy"));
        assertEquals(List.of(), report.events().byCategory("gap"));
    }

    @Test
    void keyDerivationsHashesAndMacsAreRecordedWithTheirInputs() throws Exception {
        Report report = runClient(clientConfig());

        byte[] salt = "pepper".getBytes(StandardCharsets.US_ASCII);
        byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(new PBEKeySpec("hunter2".toCharArray(), salt, 1000, 256)).getEncoded();
        Map<String, Object> derivation = only(report, "crypto", "key-derivation");
        assertEquals("PBKDF2WithHmacSHA1", derivation.get("algorithm"));
        assertEquals("hunter2", derivation.get("password"));
        assertEquals(HexFormat.of().formatHex(salt), derivation.get("salt"));
        assertEquals(1000, derivation.get("iterations"));
        assertEquals(256, derivation.get("keyLength"));
        assertEquals(HexFormat.of().formatHex(key), derivation.get("key"));

        // Two updates are shown together, as the input of the one digest they made.
        Map<String, Object> digest = only(report, "crypto", "digest");
        assertEquals("SHA-256", digest.get("algorithm"));
        assertEquals("build-1234", digest.get("input"));
        assertEquals(10L, ((Number) digest.get("size")).longValue());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("build-1234".getBytes(StandardCharsets.US_ASCII))),
                digest.get("output"));

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("mac-key".getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
        Map<String, Object> recorded = only(report, "crypto", "mac");
        assertEquals("HmacSHA256", recorded.get("algorithm"));
        assertEquals(HexFormat.of().formatHex("mac-key".getBytes(StandardCharsets.US_ASCII)), recorded.get("key"));
        assertEquals("signed", recorded.get("input"));
        assertEquals(HexFormat.of().formatHex(mac.doFinal("signed".getBytes(StandardCharsets.US_ASCII))), recorded.get("output"));

        // The summary a person reads first names the keys too.
        String summary = report.toText();
        assertTrue(summary.contains("Keys derived from passwords (1)\n  PBKDF2WithHmacSHA1  password=hunter2  salt="), summary);
        assertTrue(summary.contains("Cipher keys (2)\n  AES/CBC/PKCS5Padding  mode=encrypt  key=" + HexFormat.of().formatHex(key)), summary);
        assertTrue(summary.contains("Cipher output (decrypted data appears here)"), summary);
    }

    private static String decryptedConfig(Report report) {
        return Fixtures.events(report, "crypto", "cipher-output").stream()
                .map(e -> (String) e.data().get("preview"))
                .filter(preview -> preview.startsWith("padding;"))
                .findFirst().orElseThrow();
    }

    private static String returnedConfig(Report report) {
        return Fixtures.events(report, "string", "returned").stream()
                .filter(e -> String.valueOf(e.data().get("method")).contains("longConfig"))
                .map(e -> (String) e.data().get("value"))
                .findFirst().orElseThrow();
    }

    @Test
    void previewLengthDecidesHowMuchOfAValueIsShown() throws Exception {
        String full = ModernClient.longConfig();

        Report standard = runClient(clientConfig());
        assertEquals(full.substring(0, 2000) + "...(3000 chars)", returnedConfig(standard));
        assertEquals(full.substring(0, 2000) + "...(3000 bytes)", decryptedConfig(standard));

        EmulatorConfig longer = clientConfig();
        longer.trace.previewLength = 4000;
        Report report = runClient(longer);
        assertEquals(full, returnedConfig(report));
        assertEquals(full, decryptedConfig(report));
        assertTrue(full.endsWith("END=c2.test"));
    }

    /**
     * A loop that hashes thousands of times gets a share of the category and no more, so the
     * key it leads to is still in the report; the cut is marked once.
     */
    @Test
    void endlessHashesLeaveRoomForTheKeyTheyLeadTo() throws Exception {
        EmulatorConfig config = Fixtures.config(Fixtures.jar(temp, Map.of(), KeyStretcher.class));
        config.limits.maxEventsPerCategory = 400;
        Report report = runClient(config);

        assertEquals(100, Fixtures.events(report, "crypto", "digest").size(), "a quarter of the category");
        assertEquals(100, only(report, "crypto", "not-recorded").get("after"));
        assertEquals(Map.of(), report.events().dropped(), "nothing was pushed out");

        byte[] key = "seed".getBytes(StandardCharsets.US_ASCII);
        for (int round = 0; round < 3000; round++) {
            key = MessageDigest.getInstance("SHA-256").digest(key);
        }
        String stretched = HexFormat.of().formatHex(key);
        List<Event> inits = Fixtures.events(report, "crypto", "cipher-init");
        assertTrue(inits.stream().anyMatch(e -> stretched.equals(e.data().get("key"))), "the stretched key: " + inits);

        // A password-based cipher shows the salt and iteration count that make the password a key.
        Map<String, Object> passwordBased = inits.stream().map(Event::data)
                .filter(data -> "PBEWithMD5AndDES".equals(data.get("algorithm"))).findFirst().orElseThrow();
        assertEquals(HexFormat.of().formatHex("letmein".getBytes(StandardCharsets.US_ASCII)), passwordBased.get("key"));
        assertEquals(HexFormat.of().formatHex("saltsalt".getBytes(StandardCharsets.US_ASCII)), passwordBased.get("salt"));
        assertEquals(77, passwordBased.get("iterations"));
        List<Map<String, Object>> derivations = Fixtures.events(report, "crypto", "key-derivation").stream().map(Event::data).toList();
        assertEquals("letmein", derivations.get(0).get("password"));
        assertEquals(null, derivations.get(0).get("passwordUtf16"), "plain text needs no second form");
        // A password that is not valid text is also given exactly, as hex of its characters.
        assertEquals("d800006103ae", derivations.get(1).get("passwordUtf16"));
    }

    /** Output of {@code Cipher.update} is shown too, marked as part of a longer stream. */
    @Test
    void blocksDecryptedOneAtATimeAreShown() throws Exception {
        Report report = runClient(Fixtures.config(Fixtures.jar(temp, Map.of(), KeyStretcher.class)));

        List<Map<String, Object>> outputs = Fixtures.events(report, "crypto", "cipher-output").stream().map(Event::data).toList();
        Map<String, Object> first = outputs.stream().filter(data -> Boolean.TRUE.equals(data.get("partial"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(first.get("preview")).startsWith("first block here"), String.valueOf(first));
        assertTrue(outputs.stream().anyMatch(data -> data.get("partial") == null
                && String.valueOf(data.get("preview")).endsWith("the end")), "the final block: " + outputs);
    }

    @Test
    void previewLengthMustBePositive() {
        EmulatorConfig config = new EmulatorConfig();
        config.trace.previewLength = 0;
        assertEquals(List.of("trace.previewLength"),
                ConfigLoader.problems(config).stream().map(ConfigLoader.Problem::key).toList());
    }
}
