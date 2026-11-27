package dev.javabyte.env;

import dev.javabyte.config.ConfigLoader;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** An IPv4 address in dotted-quad form: four decimal parts, each 0 to 255, no leading signs. */
    private static final Pattern IPV4 = Pattern.compile("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");

    /** A MAC address: six two-digit hex octets separated by colons or hyphens. */
    private static final Pattern MAC = Pattern.compile("[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}");

    /**
     * @throws ConfigLoader.ConfigException if an address in the config cannot be parsed. This is
     *     checked here, before the run starts, because a bad value would otherwise surface only
     *     when the guest first looks up a host or reads its network card, as an emulator failure.
     */
    public NetworkSimulator(EmulatorConfig.Network config, EventLog events, Artifacts artifacts) {
        List<ConfigLoader.Problem> problems = configProblems(config);
        if (!problems.isEmpty()) {
            throw new ConfigLoader.ConfigException(String.join("\n", problems.stream().map(ConfigLoader.Problem::message).toList()));
        }
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
        // Something shaped like an address but out of range, such as 999.1.1.1, is not a literal:
        // a real JVM would look it up as a name, so it is treated as one here too.
        boolean literal = isIpv4(name);
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

    /** The pretend computer's own address, under the host name it is given. */
    /**
     * The name an address carries, or its text if it carries none. Never a lookup: the name is
     * read from {@code toString()}, where {@code getHostName()} would ask the analysis machine's
     * name server about an address that has no name yet. Emulator code that needs the name of an
     * address a sample supplied uses this.
     */
    public static String nameOf(InetAddress address) {
        String text = address.toString();
        int slash = text.indexOf('/');
        return slash > 0 ? text.substring(0, slash) : address.getHostAddress();
    }

    public InetAddress localHost(String hostName) throws UnknownHostException {
        return InetAddress.getByAddress(hostName, parseIpv4(config.localAddress));
    }

    /**
     * Problems with the addresses in a network config, each with the key it belongs to. Every
     * address must be an IPv4 dotted quad and the MAC address six hex octets, because the
     * simulation parses them as such. {@code ConfigLoader} should list these too, so that
     * {@code validate} reports them; the constructor refuses them either way.
     */
    public static List<ConfigLoader.Problem> configProblems(EmulatorConfig.Network config) {
        List<ConfigLoader.Problem> problems = new ArrayList<>();
        address(problems, "network.defaultAddress", config.defaultAddress);
        address(problems, "network.localAddress", config.localAddress);
        config.dns.forEach((host, value) -> address(problems, "network.dns." + host, value));
        if (!isMacAddress(config.macAddress)) {
            problems.add(new ConfigLoader.Problem("network.macAddress", "network.macAddress must be six two-digit hex "
                    + "octets separated by ':' or '-', such as 00:1B:44:11:3A:B7, found '" + config.macAddress + "'"));
        }
        return problems;
    }

    private static void address(List<ConfigLoader.Problem> problems, String key, String value) {
        if (!isIpv4(value)) {
            problems.add(new ConfigLoader.Problem(key, key + " must be an IPv4 address such as 203.0.113.10, found '" + value + "'"));
        }
    }

    /** True for an IPv4 address in dotted-quad form, each part 0 to 255. */
    public static boolean isIpv4(String address) {
        return address != null && IPV4.matcher(address).matches();
    }

    /** True for six two-digit hex octets separated by colons or hyphens. */
    public static boolean isMacAddress(String address) {
        return address != null && MAC.matcher(address).matches();
    }

    /**
     * The four bytes of a dotted-quad IPv4 address.
     *
     * @throws IllegalArgumentException if it is not one; callers check config values up front
     */
    public static byte[] parseIpv4(String address) {
        if (!isIpv4(address)) {
            throw new IllegalArgumentException("not an IPv4 address: '" + address + "'");
        }
        String[] parts = address.split("\\.");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i]);
        }
        return bytes;
    }

    /** The configured network card address as six bytes; the constructor has checked its form. */
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
