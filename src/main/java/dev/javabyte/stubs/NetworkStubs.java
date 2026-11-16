package dev.javabyte.stubs;

import dev.javabyte.env.NetworkSimulator;
import dev.javabyte.env.SimDataConnection;
import dev.javabyte.env.SimHttpConnection;
import dev.javabyte.env.SimNetworkInterface;
import dev.javabyte.env.SimSocket;
import dev.javabyte.host.CallContext;
import dev.javabyte.host.StubRegistry;
import dev.javabyte.model.PhantomObject;
import dev.javabyte.vm.VirtualMachine;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Networking. Name resolution, HTTP and raw sockets are all answered by the
 * {@link NetworkSimulator}; no packet leaves the emulator.
 *
 * <p>As with files, the interception points are where a connection comes into being. The
 * simulated connection and socket objects then handle their own instance methods.
 */
final class NetworkStubs {

    private NetworkStubs() {}

    static void install(VirtualMachine vm) {
        StubRegistry stubs = vm.stubs;
        NetworkSimulator network = vm.env.network;

        stubs.on("java/net/URL", "openConnection", ctx -> open(vm, (URL) ctx.receiver));
        stubs.on("java/net/URL", "openStream", ctx -> open(vm, (URL) ctx.receiver).getInputStream());
        stubs.on("java/net/URL", "getContent", ctx -> open(vm, (URL) ctx.receiver).getInputStream());
        // The real implementations resolve the host through DNS; compare the text instead.
        stubs.on("java/net/URL", "equals", ctx ->
                ctx.arg(0) instanceof URL other && other.toExternalForm().equals(((URL) ctx.receiver).toExternalForm()) ? 1 : 0);
        stubs.on("java/net/URL", "hashCode", ctx -> ((URL) ctx.receiver).toExternalForm().hashCode());

        stubs.on("java/net/InetAddress", "getByName", ctx -> network.resolve(ctx.stringArg(0)));
        stubs.on("java/net/InetAddress", "getAllByName", ctx -> new InetAddress[] {network.resolve(ctx.stringArg(0))});
        stubs.on("java/net/InetAddress", "getLocalHost", ctx -> network.localHost(vm.config.environment.hostName));
        stubs.on("java/net/InetAddress", "getLoopbackAddress", ctx -> network.resolve("localhost"));
        stubs.on("java/net/InetAddress", "getByAddress", ctx -> {
            byte[] raw = (byte[]) ctx.arg(ctx.argCount() - 1);
            // Always supply a name, so later getHostName() calls cannot trigger a reverse lookup.
            String name = ctx.argCount() == 2 && ctx.arg(0) != null ? ctx.stringArg(0)
                    : (raw[0] & 0xFF) + "." + (raw[1] & 0xFF) + "." + (raw[2] & 0xFF) + "." + (raw[3] & 0xFF);
            return InetAddress.getByAddress(name, raw);
        });
        stubs.on("java/net/InetAddress", "isReachable", ctx -> {
            vm.events.add("net", "ping", "host", ((InetAddress) ctx.receiver).getHostName());
            return "offline".equals(vm.config.network.mode) ? 0 : 1;
        });

        stubs.on("java/net/InetSocketAddress", "<init>", ctx -> {
            int port = ctx.intArg(ctx.argCount() - 1);
            if (ctx.argCount() == 1) {
                return new InetSocketAddress(port);
            }
            InetAddress address = ctx.arg(0) instanceof String host ? network.resolve(host) : (InetAddress) ctx.arg(0);
            return new InetSocketAddress(address, port);
        });

        stubs.on("java/net/Socket", "<init>", ctx -> socket(network, ctx));
        stubs.on("java/net/ServerSocket", "<init>", ctx -> {
            vm.events.add("net", "listen", "port", ctx.argCount() > 0 ? ctx.arg(0) : "unbound");
            return new PhantomObject("java/net/ServerSocket");
        });

        stubs.onClass("java/net/NetworkInterface", ctx -> {
            SimNetworkInterface nic = new SimNetworkInterface(network.macAddress(),
                    network.localHost(vm.config.environment.hostName));
            switch (ctx.name) {
                case "getNetworkInterfaces":
                    return Collections.enumeration(List.of(nic));
                case "networkInterfaces":
                    return Stream.of(nic);
                default:
                    return nic; // getByInetAddress, getByName, getByIndex
            }
        });

        // JVM-wide connection defaults: accepted and ignored.
        for (String name : List.of("setFollowRedirects", "setDefaultHostnameVerifier", "setDefaultSSLSocketFactory",
                "setDefaultUseCaches", "setDefaultAllowUserInteraction", "setContentHandlerFactory")) {
            stubs.on("java/net/URLConnection", name, ctx -> null);
            stubs.on("java/net/HttpURLConnection", name, ctx -> null);
            stubs.on("javax/net/ssl/HttpsURLConnection", name, ctx -> null);
        }
        stubs.on("java/net/Authenticator", "setDefault", ctx -> null);
        stubs.on("java/net/CookieHandler", "setDefault", ctx -> null);
    }

    private static URLConnection open(VirtualMachine vm, URL url) throws IOException {
        switch (url.getProtocol()) {
            case "http", "https":
                return new SimHttpConnection(url, vm.env.network);
            case "file": {
                String path = url.getPath();
                if (vm.env.windows && path.matches("/[A-Za-z]:.*")) {
                    path = path.substring(1);
                }
                byte[] data = vm.env.files.read(java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8));
                if (data == null) {
                    throw new FileNotFoundException(path);
                }
                return new SimDataConnection(url, data);
            }
            case "jar": {
                // jar:<archive-url>!/<entry>. Entries of the sample itself are served from the repository.
                String spec = url.getPath();
                int bang = spec.indexOf("!/");
                byte[] data = bang < 0 ? null : vm.classes.resource(spec.substring(bang + 2));
                if (data == null) {
                    throw new FileNotFoundException(url.toString());
                }
                return new SimDataConnection(url, data);
            }
            default:
                throw new MalformedURLException("unknown protocol: " + url.getProtocol());
        }
    }

    /** Handles the {@code Socket} constructors: none, proxy, or (host|address, port[, ...]). */
    private static Object socket(NetworkSimulator network, CallContext ctx) throws IOException {
        SimSocket socket = new SimSocket(network);
        if (ctx.argCount() >= 2 && ctx.arg(1) instanceof Integer port) {
            String host = ctx.arg(0) instanceof InetAddress address ? address.getHostName() : String.valueOf(ctx.arg(0));
            socket.connectTo(host, port);
        }
        return socket;
    }
}
