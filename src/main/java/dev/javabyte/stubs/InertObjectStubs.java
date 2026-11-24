package dev.javabyte.stubs;

import dev.javabyte.env.SimProvider;
import dev.javabyte.env.SimURLStreamHandler;
import dev.javabyte.host.CallContext;
import dev.javabyte.host.StubRegistry;
import dev.javabyte.vm.VirtualMachine;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.Provider;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stubs that hand the guest inert versions of JDK objects which the JDK itself would otherwise
 * use to reach the outside world.
 *
 * <p>Most stubs replace a call the guest makes. These cover a different route: the guest
 * builds an ordinary JDK object, passes it to allow-listed JDK code, and that code calls the
 * object's methods directly, where neither stubs nor policy can see. Three such objects are
 * handled here:
 * <ul>
 *   <li>{@code URL}: collections call its {@code hashCode} and {@code equals}, which resolve the
 *       host name through real DNS. Every URL the guest builds gets a {@link SimURLStreamHandler},
 *       which compares text instead.
 *   <li>{@code InetAddress.getCanonicalHostName}: always a real reverse lookup, so it is
 *       answered from the name the simulated address already carries.
 *   <li>{@code Provider}: the JVM's security providers map algorithm names to classes that the
 *       JDK loads and constructs. The guest only ever receives a {@link SimProvider}, a private
 *       copy whose changes cannot name a class for the JDK to load.
 * </ul>
 */
final class InertObjectStubs {

    private InertObjectStubs() {}

    /**
     * JDK classes whose {@code getProvider()} the guest may call. Each would return one of the
     * JVM's shared providers, so each answers with the guest's copy instead.
     */
    static final List<String> PROVIDER_OWNERS = List.of(
            "java/security/MessageDigest", "java/security/Signature", "java/security/KeyFactory",
            "java/security/KeyPairGenerator", "java/security/SecureRandom", "java/security/AlgorithmParameters",
            "java/security/AlgorithmParameterGenerator", "java/security/KeyStore",
            "java/security/cert/CertificateFactory", "java/security/Provider$Service",
            "javax/crypto/Cipher", "javax/crypto/Mac", "javax/crypto/KeyGenerator", "javax/crypto/KeyAgreement",
            "javax/crypto/SecretKeyFactory", "javax/crypto/ExemptionMechanism");

    /** Installs the stubs for URLs, reverse lookups and security providers; called from {@link BuiltinStubs}. */
    static void install(VirtualMachine vm) {
        StubRegistry stubs = vm.stubs;
        installUrls(vm, stubs);

        stubs.on("java/net/InetAddress", "getCanonicalHostName", ctx -> canonicalHostName(vm, (InetAddress) ctx.receiver));

        // A stream's own filter is consulted before the JVM-wide one, and its ALLOWED is final,
        // so a sample's filter could switch the deserialization guard off for its stream.
        // The filter is not installed; the guard stays the only one.
        stubs.on("java/io/ObjectInputStream", "setObjectInputFilter", ctx -> {
            vm.events.addOnce("serial-filter", "policy", "blocked", "kind", "deserialization-filter",
                    "member", "java/io/ObjectInputStream.setObjectInputFilter: the sample's filter was not installed");
            return null;
        });
        // One copy per real provider, so identity comparisons in the guest behave as on a real JVM.
        Map<Provider, SimProvider> copies = new IdentityHashMap<>();
        stubs.on("java/security/Security", "getProvider", ctx -> guestProvider(vm, copies, (Provider) ctx.proceed()));
        stubs.on("java/security/Security", "getProviders", ctx -> {
            Provider[] real = (Provider[]) ctx.proceed();
            if (real == null) {
                return null;
            }
            Provider[] copied = new Provider[real.length];
            for (int i = 0; i < real.length; i++) {
                copied[i] = guestProvider(vm, copies, real[i]);
            }
            return copied;
        });
        for (String owner : PROVIDER_OWNERS) {
            stubs.on(owner, "getProvider", ctx -> guestProvider(vm, copies, (Provider) ctx.proceed()));
            if (!owner.equals("java/security/Provider$Service")) {
                stubs.on(owner, "getInstance", ctx -> engine(vm, ctx));
            }
        }
        stubs.on("javax/net/ssl/SSLContext", "getInstance", ctx -> engine(vm, ctx));
    }

    // ------------------------------------------------------------------ cryptography backed by the machine

    /**
     * Security providers that are a door to the analysis machine rather than computation:
     * the Windows certificate and key store, the macOS keychain, PKCS#11 tokens and smart
     * cards, and LDAP. Through them a sample could list the user's certificates, sign with
     * the user's private keys or create key containers in the user's profile.
     */
    private static boolean machineProvider(String name) {
        String upper = name == null ? "" : name.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("SUNMSCAPI") || upper.startsWith("SUNPKCS11") || upper.equals("APPLE")
                || upper.equals("SUNPCSC") || upper.equals("JDKLDAP");
    }

    /** Key store types that open a store of the machine or its user instead of reading a stream. */
    private static boolean machineKeyStore(String type) {
        String upper = type == null ? "" : type.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("WINDOWS-") || upper.startsWith("KEYCHAINSTORE") || upper.equals("PKCS11") || upper.equals("DKS");
    }

    /**
     * {@code getInstance} of an engine class. Algorithms are pure computation and run for
     * real; a provider or key store type that reaches the machine is refused the way a JVM
     * without it refuses, and reported.
     */
    private static Object engine(VirtualMachine vm, CallContext ctx) throws Exception {
        boolean keyStore = ctx.owner.equals("java/security/KeyStore");
        String algorithm = ctx.argCount() > 0 && ctx.arg(0) instanceof String text ? text : null;
        Object provider = ctx.argCount() > 1 ? ctx.arg(1) : null;
        String providerName = provider instanceof String text ? text : provider instanceof Provider given ? given.getName() : null;
        if ((keyStore && machineKeyStore(algorithm)) || machineProvider(providerName)) {
            String what = machineProvider(providerName) ? "provider " + providerName : "key store " + algorithm;
            vm.events.addOnce("machine-crypto:" + what, "policy", "blocked", "kind", "machine-key-store",
                    "member", ctx.owner + ".getInstance: " + what + " belongs to the analysis machine");
            if (provider instanceof String) {
                throw new java.security.NoSuchProviderException("no such provider: " + providerName);
            }
            throw keyStore ? new java.security.KeyStoreException(algorithm + " not found")
                    : new java.security.NoSuchAlgorithmException(algorithm + " not available");
        }
        return ctx.proceed();
    }

    // ------------------------------------------------------------------ URLs

    private static void installUrls(VirtualMachine vm, StubRegistry stubs) {
        // One handler per protocol: a handler's default port cannot depend on the URL.
        Map<String, SimURLStreamHandler> handlers = new HashMap<>();
        stubs.on("java/net/URL", "<init>", ctx -> {
            // The real constructor validates the arguments and throws exactly what the JDK
            // throws. Building a URL resolves nothing; the result is then rebuilt on the
            // inert handler. A handler passed by the guest is ignored: guest code cannot
            // subclass URLStreamHandler, so it can only be null.
            Object[] args = ctx.args;
            URL real = switch (ctx.desc) {
                case "(Ljava/lang/String;)V" -> new URL((String) args[0]);
                case "(Ljava/net/URL;Ljava/lang/String;)V", "(Ljava/net/URL;Ljava/lang/String;Ljava/net/URLStreamHandler;)V" ->
                        new URL((URL) args[0], (String) args[1]);
                case "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V" ->
                        new URL((String) args[0], (String) args[1], (String) args[2]);
                default -> new URL((String) args[0], (String) args[1], (Integer) args[2], (String) args[3]);
            };
            return inert(vm, handlers, real);
        });
        stubs.on("java/net/URI", "toURL", ctx -> inert(vm, handlers, ((URI) ctx.receiver).toURL()));
        // URL.of(URI, URLStreamHandler), from Java 20. The handler can only be null, as above.
        stubs.on("java/net/URL", "of", ctx -> inert(vm, handlers, ((URI) ctx.arg(0)).toURL()));
        // URLs built by the emulator itself (resource and code-source URLs) still have JDK
        // handlers, so both sides are rebuilt before comparing.
        stubs.on("java/net/URL", "sameFile", ctx -> ctx.arg(0) instanceof URL other
                && inert(vm, handlers, (URL) ctx.receiver).sameFile(inert(vm, handlers, other)) ? 1 : 0);
    }

    /**
     * Rebuilds a URL on the inert handler for its protocol. The text is the JDK's own
     * canonical form, so the result has the same parts as the URL the JDK built.
     */
    static URL inert(VirtualMachine vm, Map<String, SimURLStreamHandler> handlers, URL url) throws MalformedURLException {
        SimURLStreamHandler handler = handlers.computeIfAbsent(url.getProtocol(), protocol -> new SimURLStreamHandler(protocol,
                refused -> vm.events.addOnce("url-open:" + refused.toExternalForm(), "policy", "blocked",
                        "kind", "url-open-by-jdk", "member", refused.toExternalForm())));
        return new URL(null, url.toExternalForm(), handler);
    }

    // ------------------------------------------------------------------ name resolution

    /**
     * Answers {@code getCanonicalHostName} without a reverse lookup. Every address the
     * network stubs hand out already carries a name, which is what the simulated name server
     * would answer. The name is read from {@code toString()}, which never resolves, so even an
     * address without a name cannot cause a lookup. Offline, the JDK answers with the address
     * text, as it does here.
     */
    private static String canonicalHostName(VirtualMachine vm, InetAddress address) {
        String text = address.toString();
        String knownName = text.substring(0, Math.max(0, text.indexOf('/')));
        boolean offline = "offline".equals(vm.config.network.mode);
        String result = offline || knownName.isEmpty() ? address.getHostAddress() : knownName;
        vm.events.addOnce("dns-reverse:" + address.getHostAddress(), "net", "dns-reverse",
                "address", address.getHostAddress(), "result", result);
        return result;
    }

    // ------------------------------------------------------------------ security providers

    /** The guest's copy of a real provider, made on first use and reused after. */
    private static Provider guestProvider(VirtualMachine vm, Map<Provider, SimProvider> copies, Provider real) {
        if (real == null || real instanceof SimProvider) {
            return real;
        }
        synchronized (copies) {
            return copies.computeIfAbsent(real, provider -> new SimProvider(provider, (operation, key) ->
                    vm.events.add("crypto", "provider-change", "provider", provider.getName(),
                            "operation", operation, "key", key == null ? null : String.valueOf(key))));
        }
    }
}
