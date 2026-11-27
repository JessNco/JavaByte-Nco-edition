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

    /** Installs this class's stubs on the machine; called once per run from {@code BuiltinStubs}. */
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
        // Every route to a TLS socket factory leads to the simulated one.
        stubs.on("javax/net/ssl/SSLContext", "getSocketFactory", ctx -> new dev.javabyte.env.SimSslSocketFactory(network));
        stubs.on("javax/net/ssl/SSLSocketFactory", "getDefault", ctx -> new dev.javabyte.env.SimSslSocketFactory(network));
        stubs.on("javax/net/ssl/HttpsURLConnection", "getDefaultSSLSocketFactory", ctx -> new dev.javabyte.env.SimSslSocketFactory(network));
        stubs.on("javax/net/SocketFactory", "getDefault", ctx -> new dev.javabyte.env.SimSocketFactory(network));
        // The JDK's trust manager factory reads the certificate store from disk when initialised.
        stubs.on("javax/net/ssl/TrustManagerFactory", "getDefaultAlgorithm", ctx -> "PKIX");
        stubs.on("javax/net/ssl/TrustManagerFactory", "getInstance", ctx -> {
            String algorithm = (String) ctx.arg(0);
            if (algorithm == null) {
                throw ctx.raise(new NullPointerException("null algorithm name"));
            }
            if (!algorithm.equalsIgnoreCase("PKIX") && !algorithm.equalsIgnoreCase("SunX509")
                    && !algorithm.equalsIgnoreCase("X509") && !algorithm.equalsIgnoreCase("SunPKIX")) {
                throw ctx.raise(new java.security.NoSuchAlgorithmException(algorithm + " TrustManagerFactory not available"));
            }
            return new dev.javabyte.env.SimTrustManagerFactory(algorithm);
        });
        stubs.on("java/net/ServerSocket", "<init>", ctx -> {
            vm.events.add("net", "listen", "port", ctx.argCount() > 0 ? ctx.arg(0) : "unbound");
            return new PhantomObject("java/net/ServerSocket");
        });

        // The machine has its network card and, like every machine, a loopback interface. Lookups
        // search both and, as in the JDK, give null when nothing matches.
        List<Object> interfaces = new java.util.ArrayList<>();
        stubs.onClass("java/net/NetworkInterface", ctx -> {
            if (ctx.receiver != null) {
                // An instance call on a simulated interface this stub handed out earlier.
                return ctx.name.equals("getClass") ? SystemStubs.classOf(vm, ctx.receiver) : ctx.proceed();
            }
            if (interfaces.isEmpty()) {
                interfaces.add(new SimNetworkInterface(network.macAddress(), network.localHost(vm.config.environment.hostName)));
                interfaces.add(new Loopback(vm.env.windows));
            }
            switch (ctx.name) {
                case "getNetworkInterfaces":
                    return Collections.enumeration(interfaces);
                case "networkInterfaces":
                    return interfaces.stream();
                case "getByName":
                    return interfaces.stream().filter(nic -> name(nic).equals(ctx.arg(0))).findFirst().orElse(null);
                case "getByIndex":
                    return interfaces.stream().filter(nic -> index(nic) == ctx.intArg(0)).findFirst().orElse(null);
                case "getByInetAddress":
                    return interfaces.stream().filter(nic -> Collections.list(addresses(nic)).contains(ctx.arg(0)))
                            .findFirst().orElse(null);
                default:
                    return vm.router.blocked(ctx.owner, ctx.name, ctx.desc, "method");
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

    private static String name(Object nic) {
        return nic instanceof Loopback loopback ? loopback.getName() : ((SimNetworkInterface) nic).getName();
    }

    private static int index(Object nic) {
        return nic instanceof Loopback loopback ? loopback.getIndex() : ((SimNetworkInterface) nic).getIndex();
    }

    private static java.util.Enumeration<InetAddress> addresses(Object nic) {
        return nic instanceof Loopback loopback ? loopback.getInetAddresses() : ((SimNetworkInterface) nic).getInetAddresses();
    }

    /**
     * Imitates the loopback {@code java.net.NetworkInterface}. Every machine has one, so a list
     * of interfaces without it is a sign of a simulation. The JDK calls it {@code lo} on Windows
     * as on Linux, with index 1 and address 127.0.0.1; only the display name differs.
     */
    public static final class Loopback implements dev.javabyte.host.StandIn {
        private final boolean windows;

        Loopback(boolean windows) {
            this.windows = windows;
        }

        @Override
        public String standsFor() {
            return "java/net/NetworkInterface";
        }

        public byte[] getHardwareAddress() { return null; }
        public String getName() { return "lo"; }
        public String getDisplayName() { return windows ? "Software Loopback Interface 1" : "lo"; }
        public boolean isUp() { return true; }
        public boolean isLoopback() { return true; }
        public boolean isVirtual() { return false; }
        public boolean isPointToPoint() { return false; }
        public boolean supportsMulticast() { return true; }
        public int getMTU() { return windows ? -1 : 65536; }
        public int getIndex() { return 1; }
        public java.util.Enumeration<InetAddress> getInetAddresses() {
            try {
                return Collections.enumeration(List.of(InetAddress.getByAddress("localhost", new byte[] {127, 0, 0, 1})));
            } catch (java.net.UnknownHostException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        public Stream<InetAddress> inetAddresses() { return Collections.list(getInetAddresses()).stream(); }
        public java.util.Enumeration<Object> getSubInterfaces() { return Collections.emptyEnumeration(); }
        public Object getParent() { return null; }
        @Override public String toString() { return "name:lo (" + getDisplayName() + ")"; }
    }

    /**
     * The simulated connection for a URL: HTTP and HTTPS through the network simulator, file:
     * from the virtual disk, and jar: entries from the sample. Nothing is fetched for real.
     */
    private static URLConnection open(VirtualMachine vm, URL url) throws IOException {
        switch (url.getProtocol()) {
            case "http", "https":
                // An http: URL gives a plain HttpURLConnection, an https: one an HttpsURLConnection.
                return SimHttpConnection.open(url, vm.env.network);
            case "file": {
                String path = FileStubs.pathFromFileUrl(url.getPath(), vm.env.windows);
                byte[] data = vm.env.files.read(path);
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
