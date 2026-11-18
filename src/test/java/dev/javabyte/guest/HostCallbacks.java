package dev.javabyte.guest;

import java.lang.invoke.MethodType;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.Security;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import javax.crypto.Cipher;

/**
 * Fixture for objects that JDK code uses on the guest's behalf: URLs in collections, security
 * providers, class loaders passed to JDK methods, and reverse name lookups. Each line it prints
 * is {@code label=value}, so the test can check what the guest saw.
 *
 * <p>Argument: a real TCP port on localhost that the test is listening on.
 *
 * <p>This class is only ever interpreted. Do not run it on a real JVM.
 */
public final class HostCallbacks {

    private interface Probe {
        Object run() throws Throwable;
    }

    public static void main(String[] args) {
        int port = Integer.parseInt(args[0]);
        String remote = "http://127.0.0.1:" + port + "/cp/";

        // ---- URLs handled by JDK code
        probe("url.map", () -> {
            Map<Object, Object> map = new HashMap<>();
            map.put(new URL("http://jbprobe-map.invalid/a"), 1);
            return map.get(new URL("http://JBPROBE-MAP.invalid/a"));
        });
        probe("url.set", () -> new HashSet<Object>(List.of(new URL("http://jbprobe-set.invalid/"))).size());
        probe("url.contains", () -> List.of(new URL("http://jbprobe-a.invalid/")).contains(new URL("http://jbprobe-b.invalid/")));
        probe("url.sameFile", () -> new URL("http://jbprobe-c.invalid/x#one").sameFile(new URL("http://jbprobe-c.invalid/x#two")));
        probe("url.uri", () -> new HashSet<Object>(List.of(URI.create("http://jbprobe-uri.invalid/p?q#r").toURL())).iterator().next());
        probe("url.defaultPort", () -> new URL("https://jbprobe-port.invalid/").getDefaultPort());
        probe("url.parts", () -> {
            URL url = new URL("http://user@jbprobe-parts.invalid:8080/dir/file.txt?q=1#frag");
            return url.getUserInfo() + "|" + url.getHost() + "|" + url.getPort() + "|" + url.getPath() + "|"
                    + url.getQuery() + "|" + url.getRef();
        });
        probe("url.relative", () -> new URL(new URL("http://jbprobe-rel.invalid/a/b.html"), "../c.html"));
        probe("url.jar", () -> new URL("jar:file:/C:/x.jar!/a/b.class").getPath());
        probe("url.unknown", () -> new URL("jbprobe://x"));

        // ---- reverse lookups
        probe("dns.canonical.address", () -> InetAddress.getByAddress(new byte[] {(byte) 203, 0, 113, 10}).getCanonicalHostName());
        probe("dns.canonical.name", () -> InetAddress.getByName("jbprobe-canon.test").getCanonicalHostName());

        // ---- security providers
        probe("provider.same", () -> Security.getProvider("SUN") == Security.getProvider("SUN"));
        probe("provider.class", () -> Security.getProvider("SUN").getClass().getName());
        probe("provider.name", () -> Security.getProvider("SUN").getName());
        probe("provider.put", () -> {
            Provider sun = Security.getProvider("SUN");
            sun.put("MessageDigest.JBPROBE1", "java.util.Timer");
            return sun.get("MessageDigest.JBPROBE1");
        });
        probe("provider.use", () -> MessageDigest.getInstance("JBPROBE1"));
        probe("provider.mapPut", () -> {
            @SuppressWarnings("unchecked")
            Map<Object, Object> table = (Map<Object, Object>) (Object) Security.getProvider("SUN");
            table.put("MessageDigest.JBPROBE2", "java.util.Timer");
            return MessageDigest.getInstance("JBPROBE2", Security.getProvider("SUN"));
        });
        probe("provider.engine", () -> MessageDigest.getInstance("SHA-256").getProvider() == Security.getProvider("SUN"));
        probe("provider.digest", () -> MessageDigest.getInstance("SHA-256", Security.getProvider("SUN")).digest(new byte[0]).length);
        probe("provider.cipher", () -> Cipher.getInstance("AES/ECB/PKCS5Padding", Security.getProvider("SunJCE")).getBlockSize());
        probe("provider.list", () -> Security.getProviders()[0] == Security.getProvider(Security.getProviders()[0].getName()));
        probe("provider.service", () -> Security.getProvider("SUN").getService("MessageDigest", "SHA-256").getAlgorithm());

        // ---- class loaders passed to JDK code
        probe("loader.newBundle", () -> {
            URLClassLoader loader = new URLClassLoader(new URL[] {new URL(remote)});
            return ResourceBundle.Control.getControl(ResourceBundle.Control.FORMAT_DEFAULT)
                    .newBundle("beacon.ViaControl", Locale.ROOT, "java.properties", loader, false);
        });
        probe("loader.methodType", () -> MethodType.fromMethodDescriptorString("(Lbeacon/Remote;)V",
                new URLClassLoader(new URL[] {new URL(remote)})));
        // Not allowed by default; the test allows it to show that the router still refuses a
        // JDK method that would ask a guest loader for a class.
        probe("loader.beans", () -> java.beans.Beans.instantiate(new URLClassLoader(new URL[] {new URL(remote)}), "beacon.Remote"));
        probe("loader.class", () -> new URLClassLoader(new URL[] {new URL(remote)}).getClass().getName());
        probe("loader.urls", () -> new URLClassLoader(new URL[] {new URL(remote)}).getURLs()[0]);
        probe("loader.parent", () -> URLClassLoader.newInstance(new URL[0]).getParent() == ClassLoader.getSystemClassLoader());
        probe("loader.nullParent", () -> new URLClassLoader(new URL[0], null).getParent());
        probe("loader.resource", () -> new URLClassLoader(new URL[] {new URL(remote)}).getResource("beacon.txt"));
        probe("loader.toString", () -> new URLClassLoader(new URL[0]).toString().replaceAll("@.*", "@"));

        // ---- the built-in loader hierarchy
        ClassLoader app = ClassLoader.getSystemClassLoader();
        probe("app.toString", app::toString);
        probe("app.hashCode", () -> Integer.toHexString(app.hashCode()));
        probe("app.parent", () -> app.getParent());
        probe("app.parent.parent", () -> app.getParent().getParent());
        probe("app.name", app::getName);
        probe("app.parent.name", () -> app.getParent().getName());
        probe("platform.isParent", () -> ClassLoader.getPlatformClassLoader() == app.getParent());
        probe("platform.isApp", () -> ClassLoader.getPlatformClassLoader() == app);
    }

    private static void probe(String label, Probe probe) {
        try {
            System.out.println(label + "=" + probe.run());
        } catch (Throwable thrown) {
            System.out.println(label + "=" + thrown.getClass().getSimpleName());
        }
    }
}
