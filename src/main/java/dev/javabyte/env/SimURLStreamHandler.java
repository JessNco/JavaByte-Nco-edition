package dev.javabyte.env;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.util.function.Consumer;

/**
 * The protocol handler behind every {@code URL} the guest builds.
 *
 * <p>A {@code URL} delegates {@code hashCode}, {@code equals} and {@code sameFile} to its
 * handler, and the JDK's own handlers resolve the host name through real DNS to answer them.
 * Guest bytecode calling those methods directly meets a stub, but JDK code does not: putting a
 * URL in a {@code HashMap} or calling {@code List.contains} makes the collection call
 * {@code hashCode} or {@code equals} itself. That is the "URLDNS" channel, which leaks a host
 * name of the sample's choosing to a real name server. This handler answers all of them from
 * the text of the URL, so no path through it ever resolves a name.
 *
 * <p>It never opens a connection either. Guest calls to {@code openConnection} are answered by
 * the network stubs before they reach a handler; if JDK code tries the same, the attempt is
 * reported and refused as an offline machine would refuse it.
 */
public final class SimURLStreamHandler extends java.net.URLStreamHandler {

    private final int defaultPort;
    private final Consumer<URL> refusedOpen;

    /**
     * One handler serves one protocol, because a handler's default port (which equality and
     * hashing also use) cannot depend on the URL.
     *
     * @param protocol    the protocol this handler serves, in lower case
     * @param refusedOpen told about every connection JDK code tried to open through this
     *                    handler, so the attempt appears in the report
     */
    public SimURLStreamHandler(String protocol, Consumer<URL> refusedOpen) {
        this.defaultPort = switch (protocol) {
            case "http" -> 80;
            case "https" -> 443;
            case "ftp" -> 21;
            default -> -1;
        };
        this.refusedOpen = refusedOpen;
    }

    /** Refuses: a connection opened by JDK code would bypass the network simulator. */
    @Override
    protected URLConnection openConnection(URL url) throws IOException {
        refusedOpen.accept(url);
        throw new ConnectException("Connection refused: connect");
    }

    /** Refuses, like {@link #openConnection(URL)}; the proxy changes nothing. */
    @Override
    protected URLConnection openConnection(URL url, Proxy proxy) throws IOException {
        return openConnection(url);
    }

    /** Parses with the generic rules every built-in protocol shares; no lookups are involved. */
    @Override
    protected void parseURL(URL url, String spec, int start, int limit) {
        super.parseURL(url, spec, start, limit);
    }

    /**
     * The port a protocol uses when the URL names none. The JDK's handlers know this per
     * protocol; the generic handler would answer -1 for all of them, which a sample could notice.
     */
    @Override
    protected int getDefaultPort() {
        return defaultPort;
    }

    /** Never resolves: this is the method through which the JDK's handlers reach DNS. */
    @Override
    protected InetAddress getHostAddress(URL url) {
        return null;
    }

    /** Text-only, because {@link #getHostAddress} never resolves. */
    @Override
    protected boolean equals(URL first, URL second) {
        return super.equals(first, second);
    }

    /** Text-only, because {@link #getHostAddress} never resolves. */
    @Override
    protected int hashCode(URL url) {
        return super.hashCode(url);
    }

    /** Text-only, because {@link #getHostAddress} never resolves. */
    @Override
    protected boolean sameFile(URL first, URL second) {
        return super.sameFile(first, second);
    }

    /** Compares host names as text, ignoring case, as the JDK does when a name does not resolve. */
    @Override
    protected boolean hostsEqual(URL first, URL second) {
        return super.hostsEqual(first, second);
    }

    /** Uses the generic form, which the JDK's own handlers also use. */
    @Override
    protected String toExternalForm(URL url) {
        return super.toExternalForm(url);
    }

    /** Called only by {@code URL.set}, which is not public; kept for the override check. */
    @Override
    protected void setURL(URL url, String protocol, String host, int port, String authority, String userInfo,
            String path, String query, String ref) {
        super.setURL(url, protocol, host, port, authority, userInfo, path, query, ref);
    }

    /** Deprecated form of {@link #setURL(URL, String, String, int, String, String, String, String, String)}. */
    @Override
    @SuppressWarnings("deprecation")
    protected void setURL(URL url, String protocol, String host, int port, String file, String ref) {
        super.setURL(url, protocol, host, port, file, ref);
    }
}
