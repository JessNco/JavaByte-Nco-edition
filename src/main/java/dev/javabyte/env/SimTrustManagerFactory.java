package dev.javabyte.env;

import java.security.KeyStore;
import java.security.Provider;
import java.security.cert.X509Certificate;
import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.TrustManagerFactorySpi;
import javax.net.ssl.X509TrustManager;

/**
 * What {@code TrustManagerFactory.getInstance} gives the sample. The JDK's factory reads the
 * analysis machine's certificate store from disk when initialised, so it cannot run for real.
 * TLS connections are simulated and nothing is ever verified, so this one hands out a trust
 * manager that accepts every certificate and knows no issuers.
 *
 * <p>HTTP clients such as OkHttp build their TLS setup this way before the first request;
 * without it their requests never reached the simulation.
 */
public final class SimTrustManagerFactory extends TrustManagerFactory {

    public SimTrustManagerFactory(String algorithm) {
        super(new Spi(), PROVIDER, algorithm);
    }

    /** Named like the JDK's own provider, which is what {@code getProvider()} shows there. */
    private static final Provider PROVIDER = new Provider("SunJSSE", "21", "Sun JSSE provider") {
        private static final long serialVersionUID = 1L;
    };

    private static final class Spi extends TrustManagerFactorySpi {
        @Override protected void engineInit(KeyStore keyStore) {}
        @Override protected void engineInit(ManagerFactoryParameters parameters) {}
        @Override protected TrustManager[] engineGetTrustManagers() { return new TrustManager[] {new AcceptAll()}; }
    }

    /** Accepts every chain; a simulated peer has no real certificate to check. */
    public static final class AcceptAll implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
