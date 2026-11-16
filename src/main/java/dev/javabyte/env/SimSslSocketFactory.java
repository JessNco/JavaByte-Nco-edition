package dev.javabyte.env;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import javax.net.ssl.SSLSocketFactory;

/** Produces {@link SimSslSocket}s wherever the sample asks a TLS context or default for sockets. */
public final class SimSslSocketFactory extends SSLSocketFactory {

    private final NetworkSimulator network;

    public SimSslSocketFactory(NetworkSimulator network) {
        this.network = network;
    }

    private SimSslSocket connected(String host, int port) throws IOException {
        SimSslSocket socket = new SimSslSocket(network);
        socket.connectTo(host, port);
        return socket;
    }

    @Override public Socket createSocket() throws IOException { return new SimSslSocket(network); }
    @Override public Socket createSocket(String host, int port) throws IOException { return connected(host, port); }
    @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { return connected(host, port); }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException { return connected(host.getHostName(), port); }
    @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { return connected(host.getHostName(), port); }

    /** Layering TLS over an existing socket: the named host is what matters for the report. */
    @Override
    public Socket createSocket(Socket underlying, String host, int port, boolean autoClose) throws IOException {
        return connected(host, port);
    }

    @Override public String[] getDefaultCipherSuites() { return new String[] {"TLS_AES_128_GCM_SHA256"}; }
    @Override public String[] getSupportedCipherSuites() { return new String[] {"TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384"}; }
}
