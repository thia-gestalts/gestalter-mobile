package thia.gestalter.core;

import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

final class Crypto {
    static { System.loadLibrary("gestalter_crypto"); }
    private static final SecureRandom RANDOM = new SecureRandom();

    private Crypto() {}

    static native byte[] opaqueStart(byte[] password, boolean register) throws GeneralSecurityException;
    static native byte[] opaqueFinish(byte[] password, byte[] response, byte[] state, boolean register)
            throws GeneralSecurityException;
    static native byte[] content(byte[] key, byte[] context, byte[] data, boolean seal)
            throws GeneralSecurityException;

    static byte[] random(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    static byte[] derive(byte[] input, String info) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(input);
        try {
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(Json.bytes(info));
            return mac.doFinal(new byte[]{1});
        } finally {
            Arrays.fill(prk, (byte) 0);
        }
    }

    static byte[] rootEnvelope(byte[] export, byte[] context, byte[] data, boolean seal)
            throws GeneralSecurityException {
        byte[] key = derive(export, "thia-personal-key-wrap-v1");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = seal ? random(12) : Arrays.copyOf(data, 12);
            cipher.init(seal ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(context);
            if (!seal) return cipher.doFinal(data, 12, data.length - 12);
            byte[] encrypted = cipher.doFinal(data);
            byte[] result = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
            return result;
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    static String digest(byte[] bytes) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        char[] hex = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            hex[i * 2] = Character.forDigit((digest[i] & 255) >>> 4, 16);
            hex[i * 2 + 1] = Character.forDigit(digest[i] & 15, 16);
        }
        return new String(hex);
    }

    static String encode(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }
    static byte[] decode(String base64) { return Base64.getDecoder().decode(base64); }
}
