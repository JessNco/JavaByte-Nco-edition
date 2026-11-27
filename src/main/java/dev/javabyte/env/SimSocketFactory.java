package dev.javabyte.env;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import javax.net.SocketFactory;

/**
 * Produces {@link SimSocket}s where the sample asks {@code SocketFactory.getDefault()} for plain
 * sockets. HTTP clients such as OkHttp take their sockets this way rather than with
 * {@code new Socket}, so without it their connections never reached the simulation.
 */
public final class SimSocketFactory extends SocketFactory {

    private final NetworkSimulator network;

    public SimSocketFactory(NetworkSimulator network) {
        this.network = network;
    }

    private Socket connected(String host, int port) throws IOException {
        SimSocket socket = new SimSocket(network);
        socket.connectTo(host, port);
        return socket;
    }

    @Override public Socket createSocket() throws IOException { return new SimSocket(network); }
    @Override public Socket createSocket(String host, int port) throws IOException { return connected(host, port); }
    @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { return connected(host, port); }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException { return connected(NetworkSimulator.nameOf(host), port); }
    @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { return connected(NetworkSimulator.nameOf(host), port); }
}
