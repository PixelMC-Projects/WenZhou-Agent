package com.pixelmc.wenzhouai.service;

import static com.pixelmc.wenzhouai.service.WzData.*;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class WzCredential {
    private final Path ciphertext;
    private final Path localKey;
    private final SecureRandom random = new SecureRandom();
    private volatile JsonObject state;
    private volatile String secret;

    WzCredential(Path directory) {
        ciphertext = directory.resolve("credential.json");
        localKey = directory.resolve("credential-local-key.json");
        state = read(ciphertext);
        secret = string(state, "ciphertext").isEmpty() ? "" : decrypt(state);
    }

    String key() {
        return secret;
    }

    long updatedAt() {
        return number(state, "updatedAt");
    }

    synchronized void set(String value) {
        JsonObject next = new JsonObject();
        next.addProperty("updatedAt", System.currentTimeMillis());
        if (!value.isEmpty()) {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            next.addProperty("nonce", encode(nonce));
            next.addProperty(
                    "ciphertext",
                    encode(
                            transform(
                                    Cipher.ENCRYPT_MODE,
                                    nonce,
                                    value.getBytes(StandardCharsets.UTF_8),
                                    true)));
        }
        write(ciphertext, next);
        state = next;
        secret = value;
    }

    private String decrypt(JsonObject data) {
        return new String(
                transform(
                        Cipher.DECRYPT_MODE,
                        decode(string(data, "nonce")),
                        decode(string(data, "ciphertext")),
                        false),
                StandardCharsets.UTF_8);
    }

    private byte[] transform(int mode, byte[] nonce, byte[] bytes, boolean create) {
        JsonObject material = read(localKey);
        String encoded = string(material, "key");
        if (encoded.isEmpty() && create) {
            byte[] key = new byte[32];
            random.nextBytes(key);
            encoded = encode(key);
            material.addProperty("key", encoded);
            write(localKey, material);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    mode,
                    new SecretKeySpec(decode(encoded), "AES"),
                    new GCMParameterSpec(128, nonce));
            return cipher.doFinal(bytes);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("问舟凭据暂不可用");
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] decode(String value) {
        return Base64.getDecoder().decode(value);
    }
}
