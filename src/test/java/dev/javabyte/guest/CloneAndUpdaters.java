package dev.javabyte.guest;

import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Differential fixture for two things loaders and obfuscators use and the emulator once could
 * not run: {@code Object.clone()} (protected and native, so only the class itself can reach it)
 * and the atomic field updaters, which find their field by reflection and check the caller.
 */
public final class CloneAndUpdaters {

    /** A plain class: no JDK superclass. */
    static class Point implements Cloneable {
        int x;
        String label;
        int[] data = {1, 2};

        Point copy() throws CloneNotSupportedException {
            return (Point) super.clone();
        }
    }

    /** Not Cloneable: super.clone() must throw. */
    static class Sealed {
        Object copy() throws CloneNotSupportedException {
            return super.clone();
        }
    }

    /** Extends a JDK class that does not override clone(): the clone comes from Object itself. */
    static class Words extends AbstractList<String> implements Cloneable {
        final ArrayList<String> items = new ArrayList<>();
        int edits;

        @Override
        public String get(int index) {
            return items.get(index);
        }

        @Override
        public int size() {
            return items.size();
        }

        @Override
        public Words clone() {
            try {
                return (Words) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** Extends a JDK class that does override clone() publicly. */
    static class Tagged extends ArrayList<String> {
        String tag = "first";
    }

    /** Extends a JDK class, but is not Cloneable. */
    static class Plain extends AbstractList<String> {
        @Override
        public String get(int index) {
            return "";
        }

        @Override
        public int size() {
            return 0;
        }

        Object copy() throws CloneNotSupportedException {
            return super.clone();
        }
    }

    /** Fields for the updaters, as a lock-free queue or a loader's state machine would have. */
    static class Node {
        volatile String value = "start";
        volatile int count;
        volatile long total = 10L;
        String notVolatile;
        volatile Integer boxed;

        static final AtomicReferenceFieldUpdater<Node, String> VALUE =
                AtomicReferenceFieldUpdater.newUpdater(Node.class, String.class, "value");
        static final AtomicIntegerFieldUpdater<Node> COUNT = AtomicIntegerFieldUpdater.newUpdater(Node.class, "count");
        static final AtomicLongFieldUpdater<Node> TOTAL = AtomicLongFieldUpdater.newUpdater(Node.class, "total");
    }

    /** A subclass: the updater for Node also works on it. */
    static class Leaf extends Node {
    }

    public static void main(String[] args) throws Exception {
        clones();
        updaters();
        updaterErrors();
    }

    private static void clones() throws Exception {
        Point p = new Point();
        p.x = 7;
        p.label = "origin";
        Point q = p.copy();
        q.x = 8;
        q.data[0] = 99;
        System.out.println("point " + p.x + " " + q.x + " " + (p.label == q.label) + " " + (p.data == q.data)
                + " " + p.data[0] + " " + (q.getClass() == Point.class) + " " + (p != q));

        try {
            new Sealed().copy();
            System.out.println("sealed copied");
        } catch (CloneNotSupportedException e) {
            System.out.println("sealed " + e.getClass().getSimpleName() + " " + e.getMessage());
        }

        Words words = new Words();
        words.items.add("a");
        words.edits = 3;
        Words twin = words.clone();
        twin.edits = 4;
        twin.items.add("b");
        System.out.println("words " + words.edits + " " + twin.edits + " " + twin.size() + " " + twin.get(1)
                + " " + (twin.getClass() == Words.class) + " " + (twin != words) + " " + twin);

        Tagged tagged = new Tagged();
        tagged.add("x");
        Tagged other = (Tagged) tagged.clone();
        other.tag = "second";
        other.add("y");
        System.out.println("tagged " + tagged.tag + " " + other.tag + " " + tagged.size() + " " + other.size()
                + " " + (other.getClass() == Tagged.class) + " " + other);

        try {
            new Plain().copy();
            System.out.println("plain copied");
        } catch (CloneNotSupportedException e) {
            System.out.println("plain " + e.getClass().getSimpleName() + " " + e.getMessage());
        }

        int[] numbers = {3, 4};
        int[] copied = numbers.clone();
        copied[0] = 5;
        System.out.println("array " + numbers[0] + " " + copied[0]);
        // Through a method handle, as obfuscators reach it.
        try {
            java.lang.invoke.MethodHandle arrayClone = java.lang.invoke.MethodHandles.lookup().findVirtual(String[].class,
                    "clone", java.lang.invoke.MethodType.methodType(Object.class));
            String[] names = {"a", "b"};
            String[] namesCopy = (String[]) arrayClone.invoke(names);
            namesCopy[1] = "c";
            System.out.println("handle " + names[1] + " " + namesCopy[1] + " " + (names != namesCopy));
        } catch (Throwable e) {
            System.out.println("handle " + e);
        }

        // Through reflection, as obfuscated code reaches it. (Object's own clone() cannot be
        // made accessible on Java 9 and later, so a class's public override is used.)
        Method clone = Words.class.getMethod("clone");
        Words r = (Words) clone.invoke(words);
        System.out.println("reflective " + r.edits + " " + r.size() + " " + (r != words));
    }

    private static void updaters() {
        Node node = new Node();
        System.out.println("ref " + Node.VALUE.get(node) + " " + Node.VALUE.compareAndSet(node, "start", "next")
                + " " + Node.VALUE.compareAndSet(node, "start", "never") + " " + node.value);
        System.out.println("ref getAndSet " + Node.VALUE.getAndSet(node, "third") + " " + node.value);
        Node.VALUE.set(node, "fourth");
        Node.VALUE.lazySet(node, "fifth");
        System.out.println("ref update " + Node.VALUE.getAndUpdate(node, v -> v + "!") + " "
                + Node.VALUE.updateAndGet(node, v -> v + "?") + " "
                + Node.VALUE.accumulateAndGet(node, "x", (a, b) -> a + b));

        System.out.println("int " + Node.COUNT.incrementAndGet(node) + " " + Node.COUNT.getAndIncrement(node) + " "
                + Node.COUNT.addAndGet(node, 5) + " " + Node.COUNT.getAndAdd(node, -2) + " "
                + Node.COUNT.decrementAndGet(node) + " " + Node.COUNT.compareAndSet(node, 5, 50) + " "
                + Node.COUNT.compareAndSet(node, 5, 60) + " " + node.count);
        System.out.println("int update " + Node.COUNT.getAndUpdate(node, v -> v * 2) + " "
                + Node.COUNT.accumulateAndGet(node, 3, Math::max) + " " + Node.COUNT.getAndSet(node, 1) + " " + node.count);

        System.out.println("long " + Node.TOTAL.addAndGet(node, 5) + " " + Node.TOTAL.getAndIncrement(node) + " "
                + Node.TOTAL.compareAndSet(node, 16L, 100L) + " " + Node.TOTAL.updateAndGet(node, v -> v / 4) + " "
                + node.total);

        Leaf leaf = new Leaf();
        System.out.println("subclass " + Node.COUNT.incrementAndGet(leaf) + " " + Node.VALUE.getAndSet(leaf, "leaf")
                + " " + leaf.value);
    }

    private static void updaterErrors() {
        attempt("missing", () -> AtomicIntegerFieldUpdater.newUpdater(Node.class, "absent"));
        attempt("not volatile", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, String.class, "notVolatile"));
        attempt("wrong int type", () -> AtomicIntegerFieldUpdater.newUpdater(Node.class, "total"));
        attempt("wrong long type", () -> AtomicLongFieldUpdater.newUpdater(Node.class, "count"));
        attempt("wrong ref type", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, Integer.class, "value"));
        attempt("primitive ref", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, int.class, "count"));
        AtomicReferenceFieldUpdater<Object, String> raw = rawUpdater();
        attempt("wrong object", () -> raw.get("not a node"));
        attempt("wrong value", () -> rawBoxed().set(new Node(), "text"));
        attempt("static", () -> AtomicIntegerFieldUpdater.newUpdater(Holder.class, "shared"));
        // Another top-level class may not use a private field. The message differs between
        // Java versions (8 says "can not", later ones "cannot"), so only the types are compared.
        try {
            UpdaterOutsider.privateField();
            System.out.println("outsider allowed");
        } catch (RuntimeException e) {
            System.out.println("outsider " + e.getClass().getSimpleName() + " caused by " + e.getCause().getClass().getSimpleName());
        }
        // A nested class is a nestmate of its outer class, so it may.
        System.out.println("nestmate " + Holder.privateUpdater().incrementAndGet(new Holder()));

        // What the guest can learn about the updaters themselves.
        for (Object updater : new Object[] {Node.VALUE, Node.COUNT, Node.TOTAL}) {
            String name = updater.getClass().getName();
            System.out.println("class " + name + " " + updater.toString().startsWith(name + "@")
                    + " " + (updater instanceof AtomicIntegerFieldUpdater));
        }
    }

    /** A static field, and a private one. */
    static class Holder {
        static volatile int shared;
        private volatile int hidden;

        static AtomicIntegerFieldUpdater<Holder> privateUpdater() {
            return AtomicIntegerFieldUpdater.newUpdater(Holder.class, "hidden");
        }
    }

    /** A private field the top-level {@code UpdaterOutsider} is not allowed to use. */
    static final class Locked {
        private volatile int value;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static AtomicReferenceFieldUpdater<Object, String> rawUpdater() {
        return (AtomicReferenceFieldUpdater) Node.VALUE;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static AtomicReferenceFieldUpdater<Node, Object> rawBoxed() {
        return (AtomicReferenceFieldUpdater) AtomicReferenceFieldUpdater.newUpdater(Node.class, Integer.class, "boxed");
    }

    private static void attempt(String label, Runnable action) {
        try {
            action.run();
            System.out.println(label + " allowed");
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            System.out.println(label + " " + e.getClass().getSimpleName()
                    + (cause == null ? "" : " caused by " + cause.getClass().getSimpleName() + " " + cause.getMessage()));
        }
    }
}

/** A separate top-level class: not a nestmate of the classes above. */
final class UpdaterOutsider {

    private UpdaterOutsider() {}

    /** Makes an updater for a private field of a class in another nest. */
    static void privateField() {
        AtomicIntegerFieldUpdater.newUpdater(CloneAndUpdaters.Locked.class, "value");
    }
}
