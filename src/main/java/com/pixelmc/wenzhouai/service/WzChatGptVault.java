package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** The operator supplies the AES key separately from Forge configuration and backups. */
final class WzChatGptVault {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path directory;
    private final Path file;
    private final SecretKeySpec key;
    private final byte[] identity;

    WzChatGptVault(Path directory, UUID owner, String encodedKey) {
        this.directory = directory.toAbsolutePath();
        this.file = this.directory.resolve("account.aesgcm");
        byte[] bytes = Base64.getDecoder().decode(encodedKey);
        try {
            if (bytes.length != 32) throw new IllegalArgumentException("ChatGPT 凭据密钥须为 32 字节");
            key = new SecretKeySpec(bytes, "AES");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
        identity = ("WenZhou-Agent/SIWC/v1/" + owner).getBytes(StandardCharsets.UTF_8);
    }

    JsonObject read() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return new JsonObject();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new JsonObject();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1_048_604) throw unavailable();
        restrict(directory, true);
        restrict(file, false);
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < 28) throw unavailable();
        byte[] plaintext = transform(Cipher.DECRYPT_MODE, Arrays.copyOfRange(bytes, 0, 12),
                Arrays.copyOfRange(bytes, 12, bytes.length));
        try {
            return WzChatGptProtocol.object(new String(plaintext, StandardCharsets.UTF_8));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    void write(JsonObject data) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.getFileStore(directory.getParent()).supportsFileAttributeView("posix"))
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            else Files.createDirectory(directory);
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
        restrict(directory, true);
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        byte[] plaintext = data.toString().getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext;
        try {
            ciphertext = transform(Cipher.ENCRYPT_MODE, iv, plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
        byte[] envelope = Arrays.copyOf(iv, iv.length + ciphertext.length);
        System.arraycopy(ciphertext, 0, envelope, iv.length, ciphertext.length);
        Path temporary = Files.createTempFile(directory, "account-", ".tmp");
        try {
            restrict(temporary, false);
            Files.write(temporary, envelope, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void restrict(Path target, boolean directory) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(target, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(target, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw unavailable();
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }

    private byte[] transform(int mode, byte[] iv, byte[] input) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(identity);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException failure) {
            throw unavailable();
        }
    }

    private static IOException unavailable() {
        return new IOException("ChatGPT 本地凭据存储不可用");
    }
}
