package dev.javabyte.model;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * Every class and resource the guest program can see.
 *
 * <p>There is deliberately one flat namespace instead of a tree of class loaders. Malware uses
 * custom loaders to hide code, not to isolate it; putting everything in one place means a class
 * defined at run time is immediately reachable however the sample later asks for it.
 */
public final class ClassRepository {

    /** Largest archive entry that is read. Real classes and payloads are far smaller. */
    static final int MAX_ENTRY_BYTES = 256 * 1024 * 1024;

    /** Raw class files by internal name, parsed on first use so huge bundled libraries cost nothing. */
    private final Map<String, byte[]> classBytes = new LinkedHashMap<>();
    private final Map<String, String> classOrigins = new LinkedHashMap<>();
    private final Map<String, GuestClass> loaded = new LinkedHashMap<>();
    private final Map<String, byte[]> resources = new LinkedHashMap<>();
    private final List<String> unparseable = new ArrayList<>();
    private BiConsumer<GuestClass, String> onLoad = (cls, origin) -> {};
    private java.util.function.Predicate<String> missingClassResolver = name -> false;

    /** Called whenever a class is parsed; the VM uses it to attach hooks and trace flags. */
    public void setLoadListener(BiConsumer<GuestClass, String> listener) {
        this.onLoad = listener;
    }

    /**
     * Sets the callback tried when a class is not known. It may define the class (by asking the
     * sample's own class loaders) and returns true if the class now exists.
     */
    public void setMissingClassResolver(java.util.function.Predicate<String> resolver) {
        this.missingClassResolver = resolver;
    }

    /**
     * Adds every entry of a jar or zip held in memory.
     *
     * <p>The archive is read as data by a stream reader; it is never handed to a class loader.
     * Entries are keyed by path because that is how the JVM locates classes, whatever name the
     * class file claims internally.
     *
     * @return the archive's manifest, or null if it has none
     */
    public Manifest addArchive(byte[] archive, String origin) throws IOException {
        Manifest manifest = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                // Some protectors store classes as "Foo.class/" so that zip tools show a directory.
                if (name.endsWith(".class/")) {
                    name = name.substring(0, name.length() - 1);
                } else if (entry.isDirectory()) {
                    continue;
                }
                // Read one byte past the limit to tell "exactly at the limit" from "over it".
                byte[] data = zip.readNBytes(MAX_ENTRY_BYTES + 1);
                if (data.length > MAX_ENTRY_BYTES) {
                    // A decompression bomb. Skipping the entry costs one resource; reading it
                    // could cost the whole analysis.
                    unparseable.add(name + ": entry larger than " + MAX_ENTRY_BYTES + " bytes, skipped");
                    continue;
                }
                // Requiring the class-file magic keeps decoy entries (obfuscators add thousands of
                // one-byte ".class" files to confuse tools) from being reported as broken classes.
                if (name.endsWith(".class") && !name.startsWith("META-INF/versions/") && hasClassMagic(data)) {
                    addClassBytes(name.substring(0, name.length() - ".class".length()), data, origin);
                }
                // Classes are also kept as resources: loaders read their own .class files as data.
                resources.putIfAbsent(name, data);
                if (name.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                    manifest = new Manifest(new ByteArrayInputStream(data));
                }
            }
        }
        return manifest;
    }

    private static boolean hasClassMagic(byte[] data) {
        return data.length > 8 && (data[0] & 0xFF) == 0xCA && (data[1] & 0xFF) == 0xFE
                && (data[2] & 0xFF) == 0xBA && (data[3] & 0xFF) == 0xBE;
    }

    /** Registers one class file under an explicit internal name. First definition wins. */
    public void addClassBytes(String internalName, byte[] data, String origin) {
        if (classBytes.putIfAbsent(internalName, data) == null) {
            classOrigins.put(internalName, origin);
        }
    }

    /** Registers a single class file, using the name recorded inside it. */
    public String addClassFile(byte[] data, String origin) {
        String name = new ClassReader(data).getClassName();
        addClassBytes(name, data, origin);
        return name;
    }

    public void addResource(String path, byte[] data) {
        resources.putIfAbsent(path, data);
    }

    /** Returns the interpreted class with this internal name, or null if it is not a guest class. */
    public GuestClass find(String internalName) {
        GuestClass cls = loaded.get(internalName);
        if (cls != null) {
            return cls;
        }
        byte[] data = classBytes.get(internalName);
        if (data == null) {
            if (!missingClassResolver.test(internalName)) {
                return null;
            }
            // The resolver may already have parsed the class while defining it.
            cls = loaded.get(internalName);
            data = classBytes.get(internalName);
            if (cls != null || data == null) {
                return cls;
            }
        }
        try {
            ClassNode node = new ClassNode();
            // Stack map frames are only needed by a verifier; skipping them also avoids failing
            // on samples that corrupt frames on purpose to break decompilers.
            new ClassReader(data).accept(node, ClassReader.SKIP_FRAMES);
            // Key by the requested name even if the file claims another, matching JVM lookup by path.
            node.name = internalName;
            cls = new GuestClass(this, node, data, classOrigins.get(internalName));
        } catch (RuntimeException e) {
            // Remember the failure so the class is reported once and then treated as absent.
            classBytes.remove(internalName);
            unparseable.add(internalName + ": " + e);
            return null;
        }
        loaded.put(internalName, cls);
        onLoad.accept(cls, cls.origin);
        return cls;
    }

    public boolean contains(String internalName) {
        return loaded.containsKey(internalName) || classBytes.containsKey(internalName);
    }

    public byte[] resource(String path) {
        return resources.get(path);
    }

    public Collection<String> classNames() {
        return classBytes.keySet();
    }

    public Collection<String> resourceNames() {
        return resources.keySet();
    }

    public List<String> unparseable() {
        return unparseable;
    }
}
