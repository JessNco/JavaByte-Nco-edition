package dev.javabyte.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.report.Artifacts;
import dev.javabyte.report.EventLog;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.junit.jupiter.api.Test;

/** Simulated web connections have the type a real JVM would give for the URL's scheme. */
class SimHttpConnectionTest {

    private final EventLog events = new EventLog(1000, null);

    private NetworkSimulator network() {
        EmulatorConfig.Network config = new EmulatorConfig.Network();
        EmulatorConfig.HttpRule rule = new EmulatorConfig.HttpRule();
        rule.url = "c2\\.example";
        rule.body = "task";
        config.http.add(rule);
        return new NetworkSimulator(config, events, new Artifacts(null));
    }

    @Test
    void plainHttpIsNotAnHttpsConnection() throws Exception {
        HttpURLConnection plain = SimHttpConnection.open(URI.create("http://c2.example/gate").toURL(), network());
        assertFalse(plain instanceof HttpsURLConnection, "a real JVM gives https connections only for https: URLs");
        assertEquals(200, plain.getResponseCode());
        assertEquals("task", new String(plain.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

        HttpURLConnection secure = SimHttpConnection.open(URI.create("https://c2.example/gate").toURL(), network());
        assertTrue(secure instanceof HttpsURLConnection);
        assertEquals(4, secure.getContentLength());
    }

    @Test
    void writingABodyTurnsGetIntoPostOnBothTypes() throws Exception {
        for (String scheme : new String[] {"http", "https"}) {
            HttpURLConnection connection = SimHttpConnection.open(URI.create(scheme + "://c2.example/up").toURL(), network());
            connection.setDoOutput(true);
            connection.setRequestProperty("User-Agent", "x");
            connection.getOutputStream().write("id=1".getBytes(StandardCharsets.UTF_8));
            assertEquals("4", connection.getHeaderField("Content-Length"));
        }
        assertEquals(2, events.byCategory("net").stream().filter(event -> "POST".equals(event.data().get("method"))).count());
    }
}
