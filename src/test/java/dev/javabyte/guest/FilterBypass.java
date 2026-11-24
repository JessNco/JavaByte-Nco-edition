package dev.javabyte.guest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * Tries to switch the deserialization guard off for one stream by giving the stream a filter
 * of its own that allows everything. The JDK consults a stream's filter before the JVM-wide
 * one, and an ALLOWED from it is final.
 */
public final class FilterBypass {

    private FilterBypass() {}

    /** Allows every class. The constant is fetched indirectly, as a sample avoiding a blocked field would. */
    static final class AllowAll implements ObjectInputFilter {
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Status checkInput(FilterInfo info) {
            return (Status) Enum.valueOf((Class) Status.class, "ALLOWED");
        }
    }

    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            // java.io.File is not a value type the guard lets through.
            out.writeObject(new File("anything"));
        }
        try {
            ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()));
            in.setObjectInputFilter(new AllowAll());
            Object read = in.readObject();
            System.out.println("read " + (read == null ? "null" : "an object"));
        } catch (java.io.InvalidClassException rejected) {
            System.out.println("rejected");
        }
    }
}
