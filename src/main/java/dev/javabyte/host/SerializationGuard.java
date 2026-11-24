package dev.javabyte.host;

import dev.javabyte.vm.VirtualMachine;
import java.io.ObjectInputFilter;
import java.util.Set;

/**
 * Restricts Java deserialization in the emulator's JVM to inert value types.
 *
 * <p>Samples legitimately use {@code ObjectInputStream} to unpack their own configuration, so
 * blocking it outright stops many of them early. Allowing it unfiltered would be dangerous:
 * the data is attacker-controlled, and deserialization "gadget" classes in the JDK or on the
 * emulator's class path can turn a byte stream into network access or code execution. A
 * JVM-wide filter resolves this: strings, numbers, dates and plain collections may be read;
 * every other class is rejected and reported.
 *
 * <p>The JVM-wide filter is the fallback. A filter factory makes the same guard every stream's
 * own filter as well, because the JDK asks a stream's filter first and takes its "allowed" as
 * final. If the factory cannot be installed (something in this JVM used a stream first), the
 * stub for {@code ObjectInputStream.setObjectInputFilter} still keeps a sample's filter out.
 */
public final class SerializationGuard {

    private SerializationGuard() {}

    /**
     * Classes that are pure data. Primitive arrays are always allowed; other arrays are allowed
     * only when their element type is.
     */
    private static final Set<String> SAFE_CLASSES = Set.of(
            "java.lang.String", "java.lang.Number", "java.lang.Integer", "java.lang.Long", "java.lang.Short",
            "java.lang.Byte", "java.lang.Double", "java.lang.Float", "java.lang.Boolean", "java.lang.Character",
            "java.lang.Enum", "java.lang.Object", "java.math.BigInteger", "java.math.BigDecimal",
            "java.util.ArrayList", "java.util.LinkedList", "java.util.Vector", "java.util.Stack",
            "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap", "java.util.Hashtable",
            // HashMap and Hashtable allocate their bucket arrays as Map.Entry[].
            "java.util.Map$Entry", "java.util.Properties", "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet",
            "java.util.ArrayDeque", "java.util.Date", "java.util.UUID", "java.util.BitSet",
            "java.util.concurrent.ConcurrentHashMap", "java.util.concurrent.atomic.AtomicInteger",
            "java.util.concurrent.atomic.AtomicLong", "java.util.concurrent.atomic.AtomicBoolean",
            // Serialized cryptographic keys: samples ship the key that unlocks their config.
            // Reading one only rebuilds key material through KeyFactory, which is pure computation.
            "java.security.KeyRep", "java.security.KeyRep$Type", "javax.crypto.spec.SecretKeySpec",
            "javax.crypto.SealedObject", "java.security.KeyPair");

    /**
     * JDK-internal key implementations, which older samples serialize directly instead of
     * through {@code KeyRep}. Matched by prefix because the concrete classes vary by JDK.
     */
    private static final java.util.List<String> SAFE_PREFIXES = java.util.List.of(
            "sun.security.rsa.", "sun.security.ec.", "sun.security.pkcs.PKCS8Key", "sun.security.x509.X509Key",
            "sun.security.x509.AlgorithmId", "sun.security.util.ObjectIdentifier", "sun.security.util.BitArray",
            "sun.security.provider.DSAP", "com.sun.crypto.provider.");

    /** Upper bounds that stop a crafted stream from exhausting memory. */
    private static final long MAX_ARRAY_LENGTH = 64L * 1024 * 1024;
    private static final long MAX_DEPTH = 64;
    private static final long MAX_REFERENCES = 5_000_000;

    private static boolean installed;

    /** Installs the filter once per JVM. Safe to call repeatedly. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        try {
            ObjectInputFilter.Config.setSerialFilter(GUARD);
        } catch (IllegalStateException alreadySet) {
            // The embedding application set its own filter first; that one stays in force.
        }
        try {
            // The JDK lets each stream be given a filter of its own, and consults that one
            // before the JVM-wide filter: a stream filter that answers ALLOWED is final. With
            // this factory every stream's filter is the guard, whatever a stream is given.
            ObjectInputFilter.Config.setSerialFilterFactory((current, requested) -> GUARD);
        } catch (IllegalStateException alreadyInUse) {
            // A stream was created, or a factory set, before the first run in this JVM. The
            // stub for ObjectInputStream.setObjectInputFilter still keeps guest filters out.
        }
        installed = true;
    }

    /** The one filter: JVM-wide, and every stream's own; see {@link #install}. */
    private static final ObjectInputFilter GUARD = SerializationGuard::check;

    private static ObjectInputFilter.Status check(ObjectInputFilter.FilterInfo info) {
        if (info.arrayLength() > MAX_ARRAY_LENGTH || info.depth() > MAX_DEPTH || info.references() > MAX_REFERENCES) {
            return ObjectInputFilter.Status.REJECTED;
        }
        Class<?> type = info.serialClass();
        if (type == null) {
            return ObjectInputFilter.Status.UNDECIDED;
        }
        while (type.isArray()) {
            type = type.getComponentType();
        }
        String name = type.getName();
        if (type.isPrimitive() || SAFE_CLASSES.contains(name) || SAFE_PREFIXES.stream().anyMatch(name::startsWith)) {
            return ObjectInputFilter.Status.ALLOWED;
        }
        VirtualMachine vm = VirtualMachine.current();
        if (vm != null) {
            vm.events.addOnce("deserialize:" + type.getName(), "policy", "blocked",
                    "kind", "deserialization", "member", type.getName());
        }
        return ObjectInputFilter.Status.REJECTED;
    }
}
