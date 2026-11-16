package dev.javabyte.env;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketOption;
import java.nio.channels.SocketChannel;
import java.util.Set;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/**
 * A TLS socket connected to nothing. It behaves like {@link SimSocket} (to which every plain
 * socket operation is delegated) and reports a completed handshake without performing one.
 *
 * <p>Because no encryption takes place, whatever the sample writes is captured in clear. That
 * is the point: the request a sample hides inside TLS is exactly what an analyst wants to read.
 */
public final class SimSslSocket extends SSLSocket {

    private static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};
    private static final String[] SUITES = {"TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384"};

    private final SimSocket plain;
    private SSLParameters parameters = new SSLParameters(SUITES, PROTOCOLS);
    private boolean clientMode = true;

    public SimSslSocket(NetworkSimulator network) throws SocketException {
        this.plain = new SimSocket(network);
    }

    public void connectTo(String host, int port) throws IOException {
        plain.connectTo(host, port);
    }

    // ---- plain socket behaviour, delegated
    @Override public void connect(SocketAddress remote) throws IOException { plain.connect(remote); }
    @Override public void connect(SocketAddress remote, int timeout) throws IOException { plain.connect(remote, timeout); }
    @Override public void bind(SocketAddress local) { plain.bind(local); }
    @Override public InetAddress getInetAddress() { return plain.getInetAddress(); }
    @Override public InetAddress getLocalAddress() { return plain.getLocalAddress(); }
    @Override public int getPort() { return plain.getPort(); }
    @Override public int getLocalPort() { return plain.getLocalPort(); }
    @Override public SocketAddress getRemoteSocketAddress() { return plain.getRemoteSocketAddress(); }
    @Override public SocketAddress getLocalSocketAddress() { return plain.getLocalSocketAddress(); }
    @Override public SocketChannel getChannel() { return plain.getChannel(); }
    @Override public InputStream getInputStream() throws IOException { return plain.getInputStream(); }
    @Override public OutputStream getOutputStream() throws IOException { return plain.getOutputStream(); }
    @Override public void setTcpNoDelay(boolean on) { plain.setTcpNoDelay(on); }
    @Override public boolean getTcpNoDelay() { return plain.getTcpNoDelay(); }
    @Override public void setSoLinger(boolean on, int linger) { plain.setSoLinger(on, linger); }
    @Override public int getSoLinger() { return plain.getSoLinger(); }
    @Override public void sendUrgentData(int data) { plain.sendUrgentData(data); }
    @Override public void setOOBInline(boolean on) { plain.setOOBInline(on); }
    @Override public boolean getOOBInline() { return plain.getOOBInline(); }
    @Override public void setSoTimeout(int timeout) { plain.setSoTimeout(timeout); }
    @Override public int getSoTimeout() { return plain.getSoTimeout(); }
    @Override public void setSendBufferSize(int size) { plain.setSendBufferSize(size); }
    @Override public int getSendBufferSize() { return plain.getSendBufferSize(); }
    @Override public void setReceiveBufferSize(int size) { plain.setReceiveBufferSize(size); }
    @Override public int getReceiveBufferSize() { return plain.getReceiveBufferSize(); }
    @Override public void setKeepAlive(boolean on) { plain.setKeepAlive(on); }
    @Override public boolean getKeepAlive() { return plain.getKeepAlive(); }
    @Override public void setTrafficClass(int tc) { plain.setTrafficClass(tc); }
    @Override public int getTrafficClass() { return plain.getTrafficClass(); }
    @Override public void setReuseAddress(boolean on) { plain.setReuseAddress(on); }
    @Override public boolean getReuseAddress() { return plain.getReuseAddress(); }
    @Override public void close() { plain.close(); }
    @Override public void shutdownInput() { plain.shutdownInput(); }
    @Override public void shutdownOutput() { plain.shutdownOutput(); }
    @Override public boolean isConnected() { return plain.isConnected(); }
    @Override public boolean isBound() { return plain.isBound(); }
    @Override public boolean isClosed() { return plain.isClosed(); }
    @Override public boolean isInputShutdown() { return plain.isInputShutdown(); }
    @Override public boolean isOutputShutdown() { return plain.isOutputShutdown(); }
    @Override public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) { plain.setPerformancePreferences(connectionTime, latency, bandwidth); }
    @Override public Set<SocketOption<?>> supportedOptions() { return plain.supportedOptions(); }
    @Override public <T> Socket setOption(SocketOption<T> name, T value) { return this; }
    @Override public <T> T getOption(SocketOption<T> name) { return null; }
    @Override public String toString() { return "SSL" + plain; }

    // ---- TLS surface: a handshake that always "succeeds"
    @Override public String[] getSupportedCipherSuites() { return SUITES.clone(); }
    @Override public String[] getEnabledCipherSuites() { return SUITES.clone(); }
    @Override public void setEnabledCipherSuites(String[] suites) { }
    @Override public String[] getSupportedProtocols() { return PROTOCOLS.clone(); }
    @Override public String[] getEnabledProtocols() { return PROTOCOLS.clone(); }
    @Override public void setEnabledProtocols(String[] protocols) { }
    @Override public SSLSession getSession() { return null; }
    @Override public SSLSession getHandshakeSession() { return null; }
    @Override public void addHandshakeCompletedListener(HandshakeCompletedListener listener) { }
    @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) { }
    @Override public void startHandshake() { }
    @Override public void setUseClientMode(boolean mode) { clientMode = mode; }
    @Override public boolean getUseClientMode() { return clientMode; }
    @Override public void setNeedClientAuth(boolean need) { }
    @Override public boolean getNeedClientAuth() { return false; }
    @Override public void setWantClientAuth(boolean want) { }
    @Override public boolean getWantClientAuth() { return false; }
    @Override public void setEnableSessionCreation(boolean flag) { }
    @Override public boolean getEnableSessionCreation() { return true; }
    @Override public SSLParameters getSSLParameters() { return parameters; }
    @Override public void setSSLParameters(SSLParameters params) { parameters = params; }
    @Override public String getApplicationProtocol() { return null; }
    @Override public String getHandshakeApplicationProtocol() { return null; }
    @Override public void setHandshakeApplicationProtocolSelector(java.util.function.BiFunction<SSLSocket, java.util.List<String>, String> selector) { }
    @Override public java.util.function.BiFunction<SSLSocket, java.util.List<String>, String> getHandshakeApplicationProtocolSelector() { return null; }
}
