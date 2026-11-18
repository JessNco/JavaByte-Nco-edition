package dev.javabyte.env;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

/**
 * An HTTPS connection answered by the {@link NetworkSimulator}.
 *
 * <p>It extends {@code HttpsURLConnection} (itself an {@code HttpURLConnection}), so guest casts
 * to either type succeed. On a real JVM only an {@code https:} URL gives an
 * {@code HttpsURLConnection}, and a sample can tell the difference with {@code instanceof}. So
 * {@link #open} gives a {@link Plain} connection, which is only an {@code HttpURLConnection}, for
 * {@code http:}. Both share one {@link Exchange}, so they behave the same in every other way.
 */
public final class SimHttpConnection extends HttpsURLConnection {

    private final Exchange exchange;

    /**
     * An HTTPS connection, whatever the URL's scheme. Prefer {@link #open}, which picks the
     * connection type a real JVM would.
     */
    public SimHttpConnection(URL url, NetworkSimulator network) {
        super(url);
        // The JDK's connected flag is set when the exchange happens, whichever call caused it.
        this.exchange = new Exchange(url, network, () -> connected = true);
    }

    /**
     * The connection a real JVM returns for this URL: {@link Plain} for {@code http:}, and this
     * class (an {@code HttpsURLConnection}) for anything else, which in practice is {@code https:}.
     */
    public static HttpURLConnection open(URL url, NetworkSimulator network) {
        return "http".equalsIgnoreCase(url.getProtocol()) ? new Plain(url, network) : new SimHttpConnection(url, network);
    }

    @Override public void connect() throws IOException { exchange.response(method); }
    @Override public void disconnect() { }
    @Override public boolean usingProxy() { return false; }
    @Override public OutputStream getOutputStream() { return exchange.requestBody; }
    @Override public void setRequestProperty(String key, String value) { exchange.setRequestProperty(key, value); }
    @Override public void addRequestProperty(String key, String value) { exchange.addRequestProperty(key, value); }
    @Override public String getRequestProperty(String key) { return exchange.requestHeaders.get(key); }
    @Override public Map<String, List<String>> getRequestProperties() { return exchange.requestProperties(); }
    @Override public InputStream getInputStream() throws IOException { return exchange.inputStream(method); }
    @Override public InputStream getErrorStream() { return exchange.errorStream(); }
    @Override public int getResponseCode() throws IOException { return exchange.response(method).status(); }
    @Override public String getResponseMessage() throws IOException { return exchange.responseMessage(method); }
    @Override public String getHeaderField(String name) { return exchange.headerField(method, name); }
    @Override public Map<String, List<String>> getHeaderFields() { return exchange.headerFields(method); }
    @Override public String getHeaderFieldKey(int n) { return exchange.headerFieldKey(method, n); }
    @Override public String getHeaderField(int n) { return exchange.headerField(method, n); }
    @Override public int getContentLength() { return exchange.contentLength(method); }
    @Override public long getContentLengthLong() { return getContentLength(); }

    // TLS details: plausible constants, since no handshake takes place.
    @Override public String getCipherSuite() { return "TLS_AES_128_GCM_SHA256"; }
    @Override public Certificate[] getLocalCertificates() { return null; }
    @Override public Certificate[] getServerCertificates() { return new Certificate[0]; }

    /**
     * A plain {@code http:} connection. It is an {@code HttpURLConnection} but not an
     * {@code HttpsURLConnection}, as on a real JVM, and has no TLS methods.
     */
    public static final class Plain extends HttpURLConnection {

        private final Exchange exchange;

        /** A plain HTTP connection; {@link SimHttpConnection#open} chooses it for {@code http:} URLs. */
        public Plain(URL url, NetworkSimulator network) {
            super(url);
            // The JDK's connected flag is set when the exchange happens, whichever call caused it.
            this.exchange = new Exchange(url, network, () -> connected = true);
        }

        @Override public void connect() throws IOException { exchange.response(method); }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public OutputStream getOutputStream() { return exchange.requestBody; }
        @Override public void setRequestProperty(String key, String value) { exchange.setRequestProperty(key, value); }
        @Override public void addRequestProperty(String key, String value) { exchange.addRequestProperty(key, value); }
        @Override public String getRequestProperty(String key) { return exchange.requestHeaders.get(key); }
        @Override public Map<String, List<String>> getRequestProperties() { return exchange.requestProperties(); }
        @Override public InputStream getInputStream() throws IOException { return exchange.inputStream(method); }
        @Override public InputStream getErrorStream() { return exchange.errorStream(); }
        @Override public int getResponseCode() throws IOException { return exchange.response(method).status(); }
        @Override public String getResponseMessage() throws IOException { return exchange.responseMessage(method); }
        @Override public String getHeaderField(String name) { return exchange.headerField(method, name); }
        @Override public Map<String, List<String>> getHeaderFields() { return exchange.headerFields(method); }
        @Override public String getHeaderFieldKey(int n) { return exchange.headerFieldKey(method, n); }
        @Override public String getHeaderField(int n) { return exchange.headerField(method, n); }
        @Override public int getContentLength() { return exchange.contentLength(method); }
        @Override public long getContentLengthLong() { return getContentLength(); }
    }

    /**
     * The request the guest builds and the simulated response, shared by both connection types.
     * Each method takes the connection's current request method, which lives in the JDK
     * superclass.
     */
    private static final class Exchange {
        private final URL url;
        private final NetworkSimulator network;
        private final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
        private final Map<String, String> requestHeaders = new LinkedHashMap<>();
        private final Runnable onConnected;
        private NetworkSimulator.HttpResponse response;

        /** @param onConnected run once the exchange has happened, to set the connection's state */
        Exchange(URL url, NetworkSimulator network, Runnable onConnected) {
            this.url = url;
            this.network = network;
            this.onConnected = onConnected;
        }

        /** The exchange happens lazily, on the first call that needs the response, as with the real class. */
        NetworkSimulator.HttpResponse response(String method) throws IOException {
            if (response == null) {
                // A real connection silently turns GET into POST once a body has been written.
                String effectiveMethod = requestBody.size() > 0 && "GET".equals(method) ? "POST" : method;
                response = network.http(effectiveMethod, url.toString(), requestHeaders, requestBody.toByteArray());
                onConnected.run();
            }
            return response;
        }

        void setRequestProperty(String key, String value) {
            requestHeaders.put(key, value);
        }

        void addRequestProperty(String key, String value) {
            requestHeaders.merge(key, value, (a, b) -> a + ", " + b);
        }

        Map<String, List<String>> requestProperties() {
            Map<String, List<String>> properties = new LinkedHashMap<>();
            requestHeaders.forEach((key, value) -> properties.put(key, List.of(value)));
            return properties;
        }

        InputStream inputStream(String method) throws IOException {
            NetworkSimulator.HttpResponse r = response(method);
            if (r.status() == 404 || r.status() == 410) {
                throw new FileNotFoundException(url.toString());
            }
            if (r.status() >= 400) {
                throw new IOException("Server returned HTTP response code: " + r.status() + " for URL: " + url);
            }
            return new ByteArrayInputStream(r.body());
        }

        InputStream errorStream() {
            return response != null && response.status() >= 400 ? new ByteArrayInputStream(response.body()) : null;
        }

        String responseMessage(String method) throws IOException {
            int status = response(method).status();
            return status == 200 ? "OK" : "Status " + status;
        }

        private Map<String, String> responseHeaders(String method) {
            try {
                return response(method).headers();
            } catch (IOException e) {
                return Map.of();
            }
        }

        String headerField(String method, String name) {
            for (Map.Entry<String, String> header : responseHeaders(method).entrySet()) {
                if (header.getKey().equalsIgnoreCase(name)) {
                    return header.getValue();
                }
            }
            return null;
        }

        Map<String, List<String>> headerFields(String method) {
            Map<String, List<String>> fields = new LinkedHashMap<>();
            responseHeaders(method).forEach((key, value) -> fields.put(key, List.of(value)));
            return fields;
        }

        String headerFieldKey(String method, int n) {
            List<String> keys = new ArrayList<>(responseHeaders(method).keySet());
            return n >= 1 && n <= keys.size() ? keys.get(n - 1) : null;
        }

        String headerField(String method, int n) {
            if (n == 0) {
                return "HTTP/1.1 " + (response == null ? 200 : response.status());
            }
            List<String> values = new ArrayList<>(responseHeaders(method).values());
            return n >= 1 && n <= values.size() ? values.get(n - 1) : null;
        }

        int contentLength(String method) {
            responseHeaders(method); // performs the exchange if it has not happened yet
            return response == null ? -1 : response.body().length;
        }
    }
}
