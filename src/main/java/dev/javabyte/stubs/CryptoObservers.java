package dev.javabyte.stubs;

import dev.javabyte.host.CallContext;
import dev.javabyte.vm.Values;
import dev.javabyte.vm.VirtualMachine;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.Key;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.WeakHashMap;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;

/**
 * Observers on the JDK's cryptography. The real code still runs, since it is pure computation;
 * these stubs only record keys, IVs, inputs and results as they pass.
 *
 * <p>This is what turns encrypted configuration and payloads into plain findings: the sample
 * supplies its own key and the emulator writes it down. Key derivations, hashes and MACs are
 * recorded too, because samples derive their keys with them: a password, salt and iteration
 * count, or the text a key is the hash of, is often the finding that unlocks everything else.
 *
 * <p>Keys, key derivations and final cipher output are always recorded. Hashes, MACs and
 * partial cipher output can be produced without end (a hand-written key stretching loop, a
 * large file decrypted in blocks), so they draw on a {@link Budget} that leaves most of the
 * category free for the rest.
 */
final class CryptoObservers {

    private CryptoObservers() {}

    /** Installs this class's stubs on the machine; called once per run from {@code BuiltinStubs}. */
    static void install(VirtualMachine vm) {
        vm.stubs.on("javax/crypto/Cipher", "init", CryptoObservers::recordInit);
        Budget budget = new Budget(Math.max(1, vm.config.limits.maxEventsPerCategory / 4));
        vm.stubs.on("javax/crypto/Cipher", "doFinal", ctx -> recordOutput(ctx, null));
        vm.stubs.on("javax/crypto/Cipher", "update", ctx -> recordOutput(ctx, budget));
        vm.stubs.on("javax/crypto/SecretKeyFactory", "generateSecret", CryptoObservers::recordDerivation);

        Inputs inputs = new Inputs(budget);
        for (String owner : new String[] {"java/security/MessageDigest", "javax/crypto/Mac"}) {
            vm.stubs.on(owner, "update", ctx -> inputs.update(ctx));
            vm.stubs.on(owner, "reset", ctx -> {
                inputs.forget(ctx.receiver);
                return ctx.proceed();
            });
        }
        vm.stubs.on("java/security/MessageDigest", "digest", ctx -> inputs.finish(ctx, "digest"));
        vm.stubs.on("javax/crypto/Mac", "doFinal", ctx -> inputs.finish(ctx, "mac"));
        vm.stubs.on("javax/crypto/Mac", "init", ctx -> {
            inputs.forget(ctx.receiver);
            inputs.keys.remove(ctx.receiver); // not the last key's, should this one have no encoding
            Object result = ctx.proceed();
            if (ctx.arg(0) instanceof Key key && key.getEncoded() != null) {
                inputs.keys.put(ctx.receiver, HexFormat.of().formatHex(key.getEncoded()));
            }
            return result;
        });
    }

    /**
     * {@code Cipher.init}: the key, the IV when the parameters carry one, and for a
     * password-based cipher the salt and iteration count that turn the password into the key.
     */
    private static Object recordInit(CallContext ctx) {
        VirtualMachine vm = ctx.vm;
        Object result = ctx.proceed();
        Cipher cipher = (Cipher) ctx.receiver;
        String key = ctx.argCount() > 1 && ctx.arg(1) instanceof Key k && k.getEncoded() != null
                ? HexFormat.of().formatHex(k.getEncoded()) : null;
        String iv = null;
        if (ctx.argCount() > 2 && ctx.arg(2) instanceof IvParameterSpec spec) {
            iv = HexFormat.of().formatHex(spec.getIV());
        } else if (ctx.argCount() > 2 && ctx.arg(2) instanceof GCMParameterSpec spec) {
            iv = HexFormat.of().formatHex(spec.getIV());
        }
        // Opmode 1 is ENCRYPT_MODE, 2 is DECRYPT_MODE.
        String mode = ctx.intArg(0) == Cipher.ENCRYPT_MODE ? "encrypt" : ctx.intArg(0) == Cipher.DECRYPT_MODE ? "decrypt" : "mode " + ctx.intArg(0);
        if (ctx.argCount() > 2 && ctx.arg(2) instanceof PBEParameterSpec pbe) {
            // A password-based cipher: the key above is the password, and the real key
            // comes from it with this salt and iteration count.
            if (pbe.getParameterSpec() instanceof IvParameterSpec spec) {
                iv = HexFormat.of().formatHex(spec.getIV());
            }
            String salt = HexFormat.of().formatHex(pbe.getSalt());
            vm.events.addOnce(cipher.getAlgorithm() + key + iv + mode + salt + pbe.getIterationCount(), "crypto", "cipher-init",
                    "algorithm", cipher.getAlgorithm(), "mode", mode, "key", key, "iv", iv,
                    "salt", salt, "iterations", pbe.getIterationCount());
            return result;
        }
        vm.events.addOnce(cipher.getAlgorithm() + key + iv + mode, "crypto", "cipher-init",
                "algorithm", cipher.getAlgorithm(), "mode", mode, "key", key, "iv", iv);
        return result;
    }

    /** {@code trace.previewLength}: how many characters or bytes of a value an event shows. */
    private static int previewLength(CallContext ctx) {
        return ctx.vm.config.trace.previewLength;
    }

    /**
     * A share of the crypto category for records a sample can produce without end. When it is
     * used up, one {@code crypto.not-recorded} event says so and the rest go unrecorded, which
     * also lets a loop that only hashes be recognised as a silent loop.
     */
    private static final class Budget {
        private final int limit;
        private int used;

        Budget(int limit) {
            this.limit = limit;
        }

        boolean spent() {
            return used >= limit;
        }

        /** Records the event unless the budget is spent; a repeat of an earlier event costs nothing. */
        void record(VirtualMachine vm, String dedupeKey, String action, Object... keyValues) {
            if (spent() || !vm.events.addOnce(dedupeKey, "crypto", action, keyValues)) {
                return;
            }
            if (++used == limit) {
                vm.events.add("crypto", "not-recorded", "after", limit,
                        "note", "further hashes, MACs and partial cipher output are not recorded; "
                                + "keys, key derivations and final cipher output still are");
            }
        }
    }

    /**
     * The result of {@code Cipher.doFinal}, or of {@code Cipher.update} when {@code partial} is
     * given: one block of a longer stream, marked as such and counted against that budget.
     * Only the forms that return the bytes are recorded, not those that fill the caller's array.
     */
    private static Object recordOutput(CallContext ctx, Budget partial) {
        Object result = ctx.proceed();
        if (result instanceof byte[] output && output.length > 0) {
            String algorithm = ((Cipher) ctx.receiver).getAlgorithm();
            String preview = Values.preview(output, previewLength(ctx));
            if (partial == null) {
                ctx.vm.events.addOnce("out:" + VirtualMachine.sha256(output), "crypto", "cipher-output",
                        "algorithm", algorithm, "size", output.length, "preview", preview);
            } else if (!partial.spent()) {
                partial.record(ctx.vm, "part:" + VirtualMachine.sha256(output), "cipher-output",
                        "algorithm", algorithm, "size", output.length, "preview", preview, "partial", true);
            }
        }
        return result;
    }

    /** {@code SecretKeyFactory.generateSecret} with a password-based spec, such as PBKDF2. */
    private static Object recordDerivation(CallContext ctx) {
        Object result = ctx.proceed();
        if (ctx.arg(0) instanceof PBEKeySpec spec && spec.getPassword() != null) {
            String algorithm = ((SecretKeyFactory) ctx.receiver).getAlgorithm();
            char[] characters = spec.getPassword();
            String password = Values.truncate(new String(characters), previewLength(ctx));
            byte[] salt = spec.getSalt();
            String key = result instanceof SecretKey secret && secret.getEncoded() != null
                    ? HexFormat.of().formatHex(secret.getEncoded()) : null;
            ctx.vm.events.addOnce("kdf:" + algorithm + password + (salt == null ? "" : HexFormat.of().formatHex(salt))
                            + spec.getIterationCount() + spec.getKeyLength(), "crypto", "key-derivation",
                    "algorithm", algorithm, "password", password, "passwordUtf16", utf16IfNotPlain(characters, previewLength(ctx)),
                    "salt", salt == null ? null : hex(salt, previewLength(ctx)),
                    "iterations", spec.getIterationCount(), "keyLength", spec.getKeyLength(), "key", key);
        }
        return result;
    }

    /**
     * A password's characters as hex (UTF-16, big-endian), or null if it is plain printable
     * ASCII. Obfuscators build passwords from arbitrary 16-bit values, some of which are not
     * valid text and cannot be written to a report as such; the hex is exact.
     */
    private static String utf16IfNotPlain(char[] password, int max) {
        boolean plain = true;
        for (char c : password) {
            plain &= c >= 0x20 && c < 0x7F;
        }
        if (plain) {
            return null;
        }
        // Unit by unit: an encoder would replace the very values that are not valid text.
        byte[] units = new byte[2 * password.length];
        for (int i = 0; i < password.length; i++) {
            units[2 * i] = (byte) (password[i] >> 8);
            units[2 * i + 1] = (byte) password[i];
        }
        return hex(units, 2 * max);
    }

    /** Hex of the first {@code max} bytes, marked the way {@link Values#preview} marks a cut. */
    private static String hex(byte[] data, int max) {
        int length = Math.min(data.length, max);
        return HexFormat.of().formatHex(data, 0, length) + (data.length > max ? "...(" + data.length + " bytes)" : "");
    }

    /**
     * What has been fed to each digest and MAC since it was last finished, up to the preview
     * length, so that the input can be shown when the result is. Kept weakly: a digest the
     * sample drops without finishing is forgotten with it.
     */
    private static final class Inputs {
        private final Map<Object, Feed> feeds = Collections.synchronizedMap(new WeakHashMap<>());
        /** The key each MAC was last initialised with, as hex. */
        final Map<Object, String> keys = Collections.synchronizedMap(new WeakHashMap<>());
        private final Budget budget;

        Inputs(Budget budget) {
            this.budget = budget;
        }

        /** The start of what one digest or MAC was fed, and how much it was fed in all. */
        private static final class Feed {
            final ByteArrayOutputStream kept = new ByteArrayOutputStream();
            long size;
        }

        /** {@code reset()} or a new key: what was fed so far is not part of the next result. */
        void forget(Object receiver) {
            feeds.remove(receiver);
        }

        /** update(byte), update(byte[]), update(byte[], offset, length) and update(ByteBuffer). */
        Object update(CallContext ctx) {
            if (budget.spent()) {
                return ctx.proceed(); // nothing more will be shown, so nothing is kept
            }
            byte[] data = null;
            int offset = 0;
            int length = 0;
            if (ctx.argCount() == 1 && ctx.arg(0) instanceof byte[] all) {
                data = all;
                length = all.length;
            } else if (ctx.argCount() == 3 && ctx.arg(0) instanceof byte[] part) {
                data = part;
                offset = ctx.intArg(1);
                length = ctx.intArg(2);
            } else if (ctx.argCount() == 1 && ctx.arg(0) instanceof ByteBuffer buffer) {
                ByteBuffer copy = buffer.duplicate();
                data = new byte[copy.remaining()];
                copy.get(data);
                length = data.length;
            } else if (ctx.argCount() == 1 && ctx.arg(0) instanceof Integer single) {
                data = new byte[] {single.byteValue()};
                length = 1;
            }
            Object result = ctx.proceed(); // invalid arguments throw here, before anything is kept
            if (data != null) {
                add(ctx, data, offset, length);
            }
            return result;
        }

        private void add(CallContext ctx, byte[] data, int offset, int length) {
            Feed feed = feeds.computeIfAbsent(ctx.receiver, unused -> new Feed());
            int room = (int) Math.max(0, Math.min(length, previewLength(ctx) - feed.kept.size()));
            feed.kept.write(data, offset, room);
            feed.size += length;
        }

        /**
         * {@code digest()}/{@code doFinal()}, the forms that take the last input, and the forms
         * that write the result into the caller's array.
         */
        Object finish(CallContext ctx, String action) {
            byte[] last = ctx.argCount() == 1 && ctx.arg(0) instanceof byte[] input ? input : null;
            Object result = ctx.proceed();
            if (last != null) {
                add(ctx, last, 0, last.length);
            }
            byte[] output = result instanceof byte[] bytes ? bytes : null;
            if (output == null && ctx.argCount() >= 2 && ctx.arg(0) instanceof byte[] into) {
                // digest(buf, offset, length) returns the length written; Mac.doFinal(out, offset)
                // writes the MAC's length.
                int offset = ctx.intArg(1);
                int written = result instanceof Integer n ? n : ((Mac) ctx.receiver).getMacLength();
                output = Arrays.copyOfRange(into, offset, offset + written);
            }
            Feed feed = feeds.remove(ctx.receiver);
            if (output == null || budget.spent()) {
                return result;
            }
            byte[] kept = feed == null ? new byte[0] : feed.kept.toByteArray();
            long size = feed == null ? 0 : feed.size;
            String algorithm = ctx.receiver instanceof MessageDigest digest ? digest.getAlgorithm() : ((Mac) ctx.receiver).getAlgorithm();
            String outputHex = HexFormat.of().formatHex(output);
            String input = Values.preview(kept, kept.length) + (size > kept.length ? "...(" + size + " bytes)" : "");
            if (action.equals("mac")) {
                String key = keys.get(ctx.receiver);
                budget.record(ctx.vm, "mac:" + algorithm + key + outputHex, "mac",
                        "algorithm", algorithm, "key", key, "size", size, "input", input, "output", outputHex);
            } else {
                budget.record(ctx.vm, "digest:" + algorithm + outputHex, "digest",
                        "algorithm", algorithm, "size", size, "input", input, "output", outputHex);
            }
            return result;
        }
    }
}
