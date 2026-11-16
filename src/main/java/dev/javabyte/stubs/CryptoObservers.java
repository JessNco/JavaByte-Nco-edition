package dev.javabyte.stubs;

import dev.javabyte.host.CallContext;
import dev.javabyte.vm.Values;
import dev.javabyte.vm.VirtualMachine;
import java.security.Key;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;

/**
 * Observers on the JDK's cryptography. The real code still runs, since it is pure computation;
 * these stubs only record keys, IVs and results as they pass.
 *
 * <p>This is what turns encrypted configuration and payloads into plain findings: the sample
 * supplies its own key and the emulator writes it down.
 */
final class CryptoObservers {

    private CryptoObservers() {}

    static void install(VirtualMachine vm) {
        vm.stubs.on("javax/crypto/Cipher", "init", ctx -> {
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
            vm.events.addOnce(cipher.getAlgorithm() + key + iv + mode, "crypto", "cipher-init",
                    "algorithm", cipher.getAlgorithm(), "mode", mode, "key", key, "iv", iv);
            return result;
        });
        vm.stubs.on("javax/crypto/Cipher", "doFinal", CryptoObservers::recordOutput);
    }

    private static Object recordOutput(CallContext ctx) {
        Object result = ctx.proceed();
        if (result instanceof byte[] output && output.length > 0) {
            ctx.vm.events.addOnce("out:" + VirtualMachine.sha256(output), "crypto", "cipher-output",
                    "algorithm", ((Cipher) ctx.receiver).getAlgorithm(), "size", output.length,
                    "preview", Values.preview(output, 512));
        }
        return result;
    }
}
