package dev.javabyte.guest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A deterministic, side-effect-free program covering the language features the interpreter must
 * get right. The differential test runs it on the real JVM and in the emulator and requires
 * identical output, so any divergence in bytecode semantics shows up as a diff.
 */
public final class Basics {

    interface Shape {
        double area();

        default String describe() {
            return getClass().getSimpleName() + " area=" + String.format("%.2f", area());
        }
    }

    record Circle(double radius) implements Shape {
        @Override
        public double area() {
            return Math.PI * radius * radius;
        }
    }

    static class Rect implements Shape, Comparable<Rect> {
        final int width;
        final int height;

        Rect(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public double area() {
            return width * height;
        }

        @Override
        public int compareTo(Rect other) {
            return Double.compare(area(), other.area());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Rect rect && rect.width == width && rect.height == height;
        }

        @Override
        public int hashCode() {
            return width * 31 + height;
        }

        @Override
        public String toString() {
            return "Rect[" + width + "x" + height + "]";
        }
    }

    enum Colour {
        RED, GREEN, BLUE;

        Colour next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    static class CheckedFailure extends Exception {
        final int code;

        CheckedFailure(String message, int code) {
            super(message);
            this.code = code;
        }
    }

    static int counter = initialCounter();

    static int initialCounter() {
        return 40 + 2;
    }

    public static void main(String[] args) {
        System.out.println("args=" + args.length + " counter=" + counter);
        arithmetic();
        controlFlow();
        arraysAndStrings();
        objects();
        exceptions();
        collectionsAndLambdas();
        wideStackOps();
    }

    static void arithmetic() {
        int a = 0x7fffffff;
        long b = a + 1L;
        System.out.println(a + 1);
        System.out.println(b * b);
        System.out.println((byte) 200 + " " + (short) 70000 + " " + (char) 97 + " " + (int) 3.99 + " " + (long) -2.5);
        System.out.println(7 / 2 + " " + -7 / 2 + " " + 7 % -3 + " " + 7.0 / 2 + " " + 1e308 * 10 + " " + 0.0 / 0.0);
        System.out.println((1 << 33) + " " + (1L << 33) + " " + (-8 >> 1) + " " + (-8 >>> 28) + " " + (-8L >>> 60));
        System.out.println((5 & 3) + " " + (5 | 3) + " " + (5 ^ 3) + " " + ~5);
        System.out.println((int) Float.NaN + " " + (int) 1e20 + " " + (long) -1e30 + " " + (float) 1 / 3);
        System.out.println(Long.MAX_VALUE + 1 + " " + Integer.MIN_VALUE / -1 + " " + Math.floorMod(-7, 3));
        double nan = Double.NaN;
        System.out.println((nan < 1) + " " + (nan > 1) + " " + (nan == nan) + " " + (1.5f > 1.4f) + " " + (2L > 3L));
    }

    static void controlFlow() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            switch (i) {
                case 0, 1, 2 -> out.append('a');
                case 3 -> out.append('b');
                case 1000 -> out.append('z');
                default -> out.append(i % 2 == 0 ? 'e' : 'o');
            }
        }
        for (String word : new String[] {"alpha", "beta", "gamma", "delta"}) {
            switch (word) {
                case "alpha":
                    out.append(1);
                    break;
                case "gamma":
                    out.append(3);
                    // fall through
                case "beta":
                    out.append(2);
                    break;
                default:
                    out.append('?');
            }
        }
        int n = 27;
        int steps = 0;
        while (n != 1) {
            n = n % 2 == 0 ? n / 2 : 3 * n + 1;
            steps++;
        }
        outer:
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                if (i * j == 6) {
                    out.append('!').append(i).append(j);
                    break outer;
                }
            }
        }
        System.out.println(out + " collatz=" + steps + " fib=" + fib(20) + " colour=" + Colour.BLUE.next());
    }

    static long fib(int n) {
        return n < 2 ? n : fib(n - 1) + fib(n - 2);
    }

    static void arraysAndStrings() {
        int[][] grid = new int[3][4];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 4; j++) {
                grid[i][j] = i * j;
            }
        }
        long[] longs = {1L, 2L, 3L};
        longs[1] += longs[2];
        char[] chars = "hello".toCharArray();
        chars[0] -= 32;
        boolean[] flags = new boolean[2];
        flags[1] = true;
        byte[] bytes = "bytes".getBytes();
        bytes[0] ^= 0x20;
        Object[] mixed = {1, "two", 3.0, 'c', null, new int[] {4}};
        String joined = String.join(",", "a", "b", "c");
        System.out.println(Arrays.deepToString(grid) + " " + Arrays.toString(longs) + " " + new String(chars) + " "
                + flags[1] + flags[0] + " " + new String(bytes) + " " + mixed.length + " " + joined);
        String text = "The quick brown fox";
        System.out.println(text.toUpperCase().substring(4, 9) + text.indexOf("brown") + text.replace('o', '0')
                + text.chars().filter(Character::isUpperCase).count() + String.valueOf(new char[] {'x', 'y'}) + text.hashCode());
        System.out.println(sum(1, 2, 3) + sum() + " " + (mixed[1] instanceof String s ? s.length() : -1));
    }

    static int sum(int... values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }

    static void objects() {
        List<Shape> shapes = List.of(new Circle(1.5), new Rect(2, 3), new Rect(1, 1));
        for (Shape shape : shapes) {
            System.out.println(shape.describe());
        }
        Rect a = new Rect(2, 3);
        Map<Rect, String> byRect = new HashMap<>();
        byRect.put(a, "first");
        byRect.put(new Rect(2, 3), "second");
        Circle circle = new Circle(2);
        System.out.println(byRect.size() + byRect.get(a) + " " + a + " " + circle + " " + circle.equals(new Circle(2))
                + " " + (circle.hashCode() == new Circle(2).hashCode()) + " " + a.getClass().getName());
        Object anonymous = new Object() {
            @Override
            public String toString() {
                return "anon:" + counter;
            }
        };
        System.out.println(anonymous + " " + (anonymous instanceof Shape) + " " + Colour.valueOf("GREEN").ordinal());
    }

    static void exceptions() {
        StringBuilder out = new StringBuilder();
        try {
            risky(3);
        } catch (CheckedFailure e) {
            out.append(e.getMessage()).append(e.code);
        } finally {
            out.append(" finally1");
        }
        try {
            Object o = "text";
            Integer wrong = (Integer) o;
            out.append(wrong);
        } catch (ClassCastException e) {
            out.append(" cce");
        }
        try {
            int[] small = new int[2];
            small[2] = 1;
        } catch (ArrayIndexOutOfBoundsException e) {
            out.append(" aioobe:").append(e.getMessage());
        }
        try {
            out.append(10 / zero());
        } catch (ArithmeticException e) {
            out.append(" div:").append(e.getMessage());
        }
        try {
            String missing = null;
            out.append(missing.length());
        } catch (NullPointerException e) {
            out.append(" npe");
        }
        try {
            Integer.parseInt("not a number");
        } catch (NumberFormatException e) {
            out.append(" nfe");
        }
        try {
            throw new IllegalStateException("outer", new RuntimeException("inner"));
        } catch (RuntimeException e) {
            out.append(' ').append(e.getCause().getMessage()).append(nested());
        }
        System.out.println(out);
    }

    static int zero() {
        return 0;
    }

    static String nested() {
        try {
            try {
                throw new UnsupportedOperationException("deep");
            } finally {
                counter++;
            }
        } catch (UnsupportedOperationException e) {
            return " " + e.getMessage() + counter;
        }
    }

    static void risky(int code) throws CheckedFailure {
        if (code > 2) {
            throw new CheckedFailure("failed", code);
        }
    }

    static void collectionsAndLambdas() {
        List<Rect> rects = new ArrayList<>(List.of(new Rect(3, 3), new Rect(1, 2), new Rect(2, 2)));
        Collections.sort(rects);
        String sorted = rects.toString();
        rects.sort(Comparator.comparingInt((Rect r) -> r.width).reversed());
        int base = 10;
        Function<Integer, Integer> addBase = x -> x + base;
        Function<Integer, Integer> twice = addBase.andThen(x -> x * 2);
        String mapped = rects.stream().map(r -> r.width + ":" + r.height).collect(Collectors.joining("|"));
        Runnable bump = () -> counter += 100;
        bump.run();
        StringBuilder order = new StringBuilder();
        new Thread(() -> order.append("thread-body")).run();
        Map<String, Integer> counts = new HashMap<>();
        for (String word : "a b a c b a".split(" ")) {
            counts.merge(word, 1, Integer::sum);
        }
        System.out.println(sorted + " " + rects + " " + twice.apply(1) + " " + mapped + " " + counter + " " + order
                + " " + new java.util.TreeMap<>(counts));
    }

    static void wideStackOps() {
        long[] cells = {5L, 7L};
        double[] reals = {1.5, 2.5};
        int index = 0;
        cells[index++] += 10L;
        cells[index] <<= 2;
        reals[0] *= reals[1]++;
        long total = 0;
        total += cells[0]++ + --cells[1];
        double d = reals[1]--;
        System.out.println(Arrays.toString(cells) + Arrays.toString(reals) + total + " " + d + " " + Long.compare(cells[0], cells[1]));
    }
}
