package dev.javabyte.env;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketImpl;
import java.net.SocketOption;
import java.nio.channels.SocketChannel;
import java.util.Set;

/**
 * A TCP socket connected to nothing. Reads return bytes configured for the endpoint and then
 * end-of-stream; writes are collected and reported.
 *
 * <p>The superclass is built with a null implementation, so no operating-system socket exists.
 * Every public method is overridden; a unit test enforces that for future JDK versions.
 */
public final class SimSocket extends Socket {

    private final NetworkSimulator network;
    private final ByteArrayOutputStream sent = new ByteArrayOutputStream();
    private InputStream received = new ByteArrayInputStream(new byte[0]);
    private InetAddress address;
    private String endpoint = "unconnected";
    private int port;
    private boolean connected;
    private boolean closed;
    private int soTimeout;

    public SimSocket(NetworkSimulator network) throws SocketException {
        super((SocketImpl) null);
        this.network = network;
        network.track(this);
    }

    /** Connects to a host given by name or literal address. */
    public void connectTo(String host, int port) throws IOException {
        this.address = network.resolve(host);
        this.port = port;
        this.endpoint = host + ':' + port;
        this.received = new ByteArrayInputStream(network.connect(host, port));
        this.connected = true;
    }

    @Override
    public void connect(SocketAddress remote) throws IOException {
        connect(remote, 0);
    }

    @Override
    public void connect(SocketAddress remote, int timeout) throws IOException {
        InetSocketAddress target = (InetSocketAddress) remote;
        connectTo(target.getHostString(), target.getPort());
    }

    @Override public void bind(SocketAddress local) { }
    @Override public InetAddress getInetAddress() { return address; }

    @Override
    public InetAddress getLocalAddress() {
        try {
            return network.localHost("localhost");
        } catch (IOException e) {
            return null;
        }
    }

    @Override public int getPort() { return port; }
    @Override public int getLocalPort() { return connected ? 49731 : -1; }
    @Override public SocketAddress getRemoteSocketAddress() { return connected ? new InetSocketAddress(address, port) : null; }
    @Override public SocketAddress getLocalSocketAddress() { return connected ? new InetSocketAddress(getLocalAddress(), 49731) : null; }
    @Override public SocketChannel getChannel() { return null; }

    @Override
    public InputStream getInputStream() throws IOException {
        requireOpen();
        return received;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        requireOpen();
        return sent;
    }

    private void requireOpen() throws SocketException {
        if (closed) {
            throw new SocketException("Socket is closed");
        }
        if (!connected) {
            throw new SocketException("Socket is not connected");
        }
    }

    /** Reports what was written so far; called on close and at the end of the run. */
    public void report() {
        if (sent.size() > 0) {
            network.sent(endpoint, sent.toByteArray());
            sent.reset();
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            report();
        }
    }

    @Override public void shutdownInput() { }
    @Override public void shutdownOutput() { report(); }
    @Override public boolean isConnected() { return connected; }
    @Override public boolean isBound() { return connected; }
    @Override public boolean isClosed() { return closed; }
    @Override public boolean isInputShutdown() { return false; }
    @Override public boolean isOutputShutdown() { return false; }
    @Override public String toString() { return "Socket[addr=" + endpoint + "]"; }

    // Socket options are accepted and remembered only where a program might read them back.
    @Override public void setTcpNoDelay(boolean on) { }
    @Override public boolean getTcpNoDelay() { return true; }
    @Override public void setSoLinger(boolean on, int linger) { }
    @Override public int getSoLinger() { return -1; }
    @Override public void sendUrgentData(int data) { }
    @Override public void setOOBInline(boolean on) { }
    @Override public boolean getOOBInline() { return false; }
    @Override public void setSoTimeout(int timeout) { soTimeout = timeout; }
    @Override public int getSoTimeout() { return soTimeout; }
    @Override public void setSendBufferSize(int size) { }
    @Override public int getSendBufferSize() { return 65536; }
    @Override public void setReceiveBufferSize(int size) { }
    @Override public int getReceiveBufferSize() { return 65536; }
    @Override public void setKeepAlive(boolean on) { }
    @Override public boolean getKeepAlive() { return false; }
    @Override public void setTrafficClass(int tc) { }
    @Override public int getTrafficClass() { return 0; }
    @Override public void setReuseAddress(boolean on) { }
    @Override public boolean getReuseAddress() { return false; }
    @Override public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) { }
    @Override public <T> Socket setOption(SocketOption<T> name, T value) { return this; }
    @Override public <T> T getOption(SocketOption<T> name) { return null; }
    @Override public Set<SocketOption<?>> supportedOptions() { return Set.of(); }
}
