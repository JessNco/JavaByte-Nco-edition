package dev.javabyte.env;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

/**
 * An HTTP(S) connection answered by the {@link NetworkSimulator}.
 *
 * <p>It extends {@code HttpsURLConnection} (itself an {@code HttpURLConnection}) so that guest
 * casts to either type succeed whichever scheme the URL uses.
 */
public final class SimHttpConnection extends HttpsURLConnection {

    private final NetworkSimulator network;
    private final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
    private final Map<String, String> requestHeaders = new LinkedHashMap<>();
    private NetworkSimulator.HttpResponse response;

    public SimHttpConnection(URL url, NetworkSimulator network) {
        super(url);
        this.network = network;
    }

    /** The exchange happens lazily, on the first call that needs the response, as with the real class. */
    private NetworkSimulator.HttpResponse exchange() throws IOException {
        if (response == null) {
            // A real connection silently turns GET into POST once a body has been written.
            String effectiveMethod = requestBody.size() > 0 && "GET".equals(method) ? "POST" : method;
            response = network.http(effectiveMethod, url.toString(), requestHeaders, requestBody.toByteArray());
            connected = true;
        }
        return response;
    }

    @Override public void connect() throws IOException { exchange(); }
    @Override public void disconnect() { }
    @Override public boolean usingProxy() { return false; }
    @Override public OutputStream getOutputStream() { return requestBody; }

    @Override
    public void setRequestProperty(String key, String value) {
        requestHeaders.put(key, value);
    }

    @Override
    public void addRequestProperty(String key, String value) {
        requestHeaders.merge(key, value, (a, b) -> a + ", " + b);
    }

    @Override
    public String getRequestProperty(String key) {
        return requestHeaders.get(key);
    }

    @Override
    public Map<String, List<String>> getRequestProperties() {
        Map<String, List<String>> properties = new LinkedHashMap<>();
        requestHeaders.forEach((key, value) -> properties.put(key, List.of(value)));
        return properties;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        NetworkSimulator.HttpResponse r = exchange();
        if (r.status() == 404 || r.status() == 410) {
            throw new FileNotFoundException(url.toString());
        }
        if (r.status() >= 400) {
            throw new IOException("Server returned HTTP response code: " + r.status() + " for URL: " + url);
        }
        return new ByteArrayInputStream(r.body());
    }

    @Override
    public InputStream getErrorStream() {
        return response != null && response.status() >= 400 ? new ByteArrayInputStream(response.body()) : null;
    }

    @Override public int getResponseCode() throws IOException { return exchange().status(); }
    @Override public String getResponseMessage() throws IOException { return exchange().status() == 200 ? "OK" : "Status " + exchange().status(); }

    private Map<String, String> responseHeaders() {
        try {
            return exchange().headers();
        } catch (IOException e) {
            return Map.of();
        }
    }

    @Override
    public String getHeaderField(String name) {
        for (Map.Entry<String, String> header : responseHeaders().entrySet()) {
            if (header.getKey().equalsIgnoreCase(name)) {
                return header.getValue();
            }
        }
        return null;
    }

    @Override
    public Map<String, List<String>> getHeaderFields() {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        responseHeaders().forEach((key, value) -> fields.put(key, List.of(value)));
        return fields;
    }

    @Override
    public String getHeaderFieldKey(int n) {
        List<String> keys = new ArrayList<>(responseHeaders().keySet());
        return n >= 1 && n <= keys.size() ? keys.get(n - 1) : null;
    }

    @Override
    public String getHeaderField(int n) {
        if (n == 0) {
            return "HTTP/1.1 " + (response == null ? 200 : response.status());
        }
        List<String> values = new ArrayList<>(responseHeaders().values());
        return n >= 1 && n <= values.size() ? values.get(n - 1) : null;
    }

    @Override public int getContentLength() { return response == null ? responseHeaders().isEmpty() ? -1 : response.body().length : response.body().length; }
    @Override public long getContentLengthLong() { return getContentLength(); }

    // TLS details: plausible constants, since no handshake takes place.
    @Override public String getCipherSuite() { return "TLS_AES_128_GCM_SHA256"; }
    @Override public Certificate[] getLocalCertificates() { return null; }
    @Override public Certificate[] getServerCertificates() { return new Certificate[0]; }
}
