package dev.javabyte.guest;

import java.io.InputStream;
import java.security.SecureClassLoader;

/**
 * Fixture for a sample's own direct subclasses of {@code ClassLoader} and
 * {@code SecureClassLoader}, the usual shape of a packer's stub: it decodes a class from a
 * resource and defines it with {@code defineClass}. Each line it prints is {@code label=value}.
 *
 * <p>The emulator builds these loaders on simulated bases whose real parent is the bootstrap
 * loader. Defining and loading must still work, and the loaders must look real to the sample.
 *
 * <p>This class is only ever interpreted. Do not run it on a real JVM.
 */
public final class OwnLoader {

    /** The class the loader defines, by name. */
    static final String PAYLOAD = "dev.javabyte.guest.DefinedPayload";

    /** Defines the one class it was given the bytes of; finds nothing else. */
    public static final class Memory extends ClassLoader {
        private final byte[] bytes;

        /** {@code super()}: the parent is the application loader. */
        public Memory(byte[] bytes) {
            super();
            this.bytes = bytes;
        }

        /** {@code super(parent)}. */
        public Memory(byte[] bytes, ClassLoader parent) {
            super(parent);
            this.bytes = bytes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (bytes != null && name.equals(PAYLOAD)) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            throw new ClassNotFoundException(name);
        }
    }

    /** A subclass of SecureClassLoader with no overrides, to check its shape. */
    public static final class Secure extends SecureClassLoader {
        /** {@code super(parent)}. */
        public Secure(ClassLoader parent) {
            super(parent);
        }
    }

    private OwnLoader() {}

    /** Builds a loader with nothing to define; the test calls this to hold the loader itself. */
    public static ClassLoader make() {
        return new Memory(null);
    }

    public static void main(String[] args) throws Exception {
        byte[] bytes;
        try (InputStream in = OwnLoader.class.getResourceAsStream("/payload.bin")) {
            bytes = in.readAllBytes();
        }
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] ^= 0x2C;
        }
        Memory loader = new Memory(bytes);
        Class<?> payload = loader.loadClass(PAYLOAD);
        System.out.println("defined=" + payload.getName());
        System.out.println("result=" + payload.getMethod("greet").invoke(payload.getDeclaredConstructor().newInstance()));
        System.out.println("again=" + (loader.loadClass(PAYLOAD) == payload));
        System.out.println("byName=" + (Class.forName(PAYLOAD, false, loader) == payload));
        System.out.println("class=" + loader.getClass().getName());
        System.out.println("super=" + loader.getClass().getSuperclass().getName());
        System.out.println("parent=" + (loader.getParent() == ClassLoader.getSystemClassLoader()));
        System.out.println("nullParent=" + new Memory(null, null).getParent());
        System.out.println("toString=" + loader.toString().replaceAll("@[0-9a-f]+$", "@"));
        System.out.println("hashText=" + loader.toString().endsWith('@' + Integer.toHexString(loader.hashCode())));
        try {
            loader.loadClass("dev.javabyte.Emulator");
            System.out.println("emulatorClass=found");
        } catch (ClassNotFoundException e) {
            System.out.println("emulatorClass=ClassNotFoundException");
        }
        System.out.println("jdkClass=" + loader.loadClass("java.lang.String").getName());
        System.out.println("emulatorResource=" + loader.getResource("dev/javabyte/default-policy.txt"));
        System.out.println("ownResource=" + (loader.getResource("payload.bin") != null));

        Secure secure = new Secure(loader);
        System.out.println("secure.class=" + secure.getClass().getName());
        System.out.println("secure.super=" + secure.getClass().getSuperclass().getName());
        System.out.println("secure.parent=" + (secure.getParent() == loader));
        System.out.println("secure.isLoader=" + (secure instanceof ClassLoader));
        System.out.println("secure.toString=" + secure.toString().replaceAll("@[0-9a-f]+$", "@"));
    }
}
