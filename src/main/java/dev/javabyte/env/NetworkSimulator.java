package dev.javabyte.env;

import dev.javabyte.config.EmulatorConfig;
import dev.javabyte.report.Artifacts;
import dev.javabyte.report.EventLog;
import dev.javabyte.vm.Values;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Answers every network request the guest makes from configuration. Nothing is ever sent.
 *
 * <p>Each request is recorded in full (destination, headers, body), since for malware the
 * requests themselves are usually the most valuable finding: they are the C2 addresses and the
 * data being exfiltrated.
 */
public final class NetworkSimulator {

    /** A canned HTTP response. */
    public record HttpResponse(int status, Map<String, String> headers, byte[] body) {}

    private final EmulatorConfig.Network config;
    private final EventLog events;
    private final Artifacts artifacts;
    private final java.util.List<SimSocket> openSockets = new java.util.ArrayList<>();
    private int payloadCounter;

    public NetworkSimulator(EmulatorConfig.Network config, EventLog events, Artifacts artifacts) {
        this.config = config;
        this.events = events;
        this.artifacts = artifacts;
    }

    private boolean offline() {
        return "offline".equals(config.mode);
    }

    /**
     * Resolves a host name without DNS. Address literals are parsed; names come from the
     * configured table or the default address.
     */
    public InetAddress resolve(String host) throws UnknownHostException {
        String name = host == null || host.isEmpty() ? "localhost" : host;
        String address = config.dns.get(name);
        boolean literal = name.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        if (name.equalsIgnoreCase("localhost")) {
            address = "127.0.0.1";
        } else if (literal) {
            address = name;
        } else if (address == null) {
            if (offline()) {
                events.add("net", "dns", "host", name, "result", "unresolved (offline)");
                throw new UnknownHostException(name);
            }
            address = config.defaultAddress;
        }
        if (!literal) {
            events.addOnce("dns:" + name, "net", "dns", "host", name, "result", address);
        }
        // getByAddress with an explicit host name performs no lookup in either direction.
        return InetAddress.getByAddress(name, parseIpv4(address));
    }

    public InetAddress localHost(String hostName) throws UnknownHostException {
        return InetAddress.getByAddress(hostName, parseIpv4(config.localAddress));
    }

    public static byte[] parseIpv4(String address) {
        String[] parts = address.split("\\.");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i]);
        }
        return bytes;
    }

    public byte[] macAddress() {
        String[] parts = config.macAddress.split("[:-]");
        byte[] mac = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            mac[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return mac;
    }

    /** Performs a simulated HTTP exchange. */
    public HttpResponse http(String method, String url, Map<String, ?> requestHeaders, byte[] body) throws IOException {
        String savedBody = body.length == 0 ? null : artifacts.save("network", "http-request-" + payloadCounter++ + ".bin", body);
        if (offline()) {
            events.add("net", "http", "method", method, "url", url, "result", "connection refused (offline)");
            throw new ConnectException("Connection refused: connect");
        }
        EmulatorConfig.HttpRule rule = null;
        for (EmulatorConfig.HttpRule candidate : config.http) {
            if (candidate.url != null && Pattern.compile(candidate.url).matcher(url).find()) {
                rule = candidate;
                break;
            }
        }
        byte[] responseBody = rule == null ? new byte[0] : content(rule.body, rule.bodyBase64, rule.bodyFile);
        int status = rule == null ? 200 : rule.status;
        events.add("net", "http", "method", method, "url", url,
                "requestHeaders", requestHeaders.isEmpty() ? null : requestHeaders.toString(),
                "requestBodySize", body.length == 0 ? null : body.length,
                "requestBodyPreview", body.length == 0 ? null : Values.preview(body, 200),
                "requestBodyFile", savedBody,
                "responseStatus", status, "responseSize", responseBody.length,
                "matchedRule", rule == null ? "none (default empty response)" : rule.url);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Length", String.valueOf(responseBody.length));
        if (rule != null) {
            headers.putAll(rule.headers);
        }
        return new HttpResponse(status, headers, responseBody);
    }

    /** Called when the guest opens a raw socket; returns the bytes it will be able to read. */
    public byte[] connect(String host, int port) throws IOException {
        String endpoint = host + ':' + port;
        if (offline()) {
            events.add("net", "connect", "endpoint", endpoint, "result", "connection refused (offline)");
            throw new ConnectException("Connection refused: connect");
        }
        EmulatorConfig.SocketRule rule = null;
        for (EmulatorConfig.SocketRule candidate : config.sockets) {
            if (candidate.endpoint != null && Pattern.compile(candidate.endpoint).matcher(endpoint).find()) {
                rule = candidate;
                break;
            }
        }
        byte[] data = rule == null ? new byte[0] : content(rule.text, rule.base64, rule.file);
        events.add("net", "connect", "endpoint", endpoint, "responseSize", data.length,
                "matchedRule", rule == null ? "none (peer sends nothing)" : rule.endpoint);
        return data;
    }

    void track(SimSocket socket) {
        openSockets.add(socket);
    }

    /** Reports data written to sockets the guest never closed. Called at the end of the run. */
    public void reportOpenSockets() {
        openSockets.forEach(SimSocket::report);
    }

    /** Records what the guest sent over a socket. */
    public void sent(String endpoint, byte[] data) {
        if (data.length == 0) {
            return;
        }
        events.add("net", "send", "endpoint", endpoint, "size", data.length, "preview", Values.preview(data, 200),
                "file", artifacts.save("network", "socket-send-" + payloadCounter++ + ".bin", data));
    }

    /** Resolves a config content triple (inline text, base64, or file) to bytes. */
    public static byte[] content(String text, String base64, String file) throws IOException {
        if (base64 != null) {
            return Base64.getDecoder().decode(base64);
        }
        if (file != null) {
            return Files.readAllBytes(Path.of(file));
        }
        return text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
    }
}
