package com.pixelmc.manager.service;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;

/** Manager-owned credential. Public status never contains a value derived from the secret. */
final class WzOpenAiCredential {
    private static final Set<PosixFilePermission> PRIVATE_FILE =
            PosixFilePermissions.fromString("rw-------");
    private final Path file;

    WzOpenAiCredential(Path data) {
        this.file = data.resolve("wenzhou/private/openai-project.key");
    }

    private record Saved(String revision, long updatedAt, String key, boolean invalid) {}

    private Saved read() {
        try {
            var attributes =
                    Files.readAttributes(
                            file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            long changed = attributes.lastModifiedTime().toMillis();
            if (attributes.isRegularFile() && attributes.size() <= 4608) {
                String[] fields = Files.readString(file, StandardCharsets.US_ASCII).split("\n", -1);
                if (fields.length == 2
                        && fields[0].matches("[0-9a-f-]{36}")
                        && (fields[1].isEmpty() || valid(fields[1]))) {
                    return new Saved(fields[0], changed, fields[1], false);
                }
            }
            return new Saved("invalid-" + changed, changed, "", true);
        } catch (NoSuchFileException missing) {
            return new Saved("initial", 0, "", false);
        } catch (IOException unreadable) {
            return new Saved("unreadable", 0, "", true);
        }
    }

    private static boolean valid(String key) {
        return !key.isEmpty() && key.length() <= 4096 && key.matches("[!-~]+");
    }

    synchronized String revision() {
        return read().revision();
    }

    synchronized String effectiveKey() {
        Saved saved = read();
        if (saved.invalid())
            throw new WenzhouOpenAi.Failure(503, "Manager 中保存的 OpenAI 项目密钥无法读取，请重新配置");
        return saved.key().isEmpty() ? WenzhouOpenAi.environmentKey() : saved.key();
    }

    synchronized JsonObject status() {
        Saved saved = read();
        boolean external =
                !System.getenv().getOrDefault("OPENAI_API_KEY_FILE", "").isBlank()
                        || !System.getenv().getOrDefault("OPENAI_API_KEY", "").isBlank();
        boolean managed = saved.invalid() || !saved.key().isEmpty();
        String source = managed ? (saved.invalid() ? "invalid" : "manager") : "none";
        if (!managed && external) {
            try {
                source = WenzhouOpenAi.environmentKey().isEmpty() ? "none" : "environment";
            } catch (WenzhouOpenAi.Failure invalid) {
                source = "invalid";
            }
        }
        var result = new JsonObject();
        result.addProperty("source", source);
        result.addProperty("configured", source.equals("manager") || source.equals("environment"));
        result.addProperty("hasManaged", managed);
        result.addProperty("hasExternal", external);
        result.addProperty("updatedAt", saved.updatedAt());
        result.addProperty("revision", saved.revision());
        return result;
    }

    synchronized JsonObject change(String key, String revision) throws IOException {
        if (key != null && !valid(key)) throw new IllegalArgumentException("请输入有效的 OpenAI 项目密钥");
        Saved saved = read();
        if (!saved.revision().equals(revision))
            throw new IllegalStateException("密钥已被其他窗口修改，请刷新后重试");
        if (saved.key().isEmpty() && !saved.invalid() && key == null) return status();
        var directory = file.getParent();
        Files.createDirectories(directory.getParent());
        boolean posix = directory.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (posix) {
            var directoryMode = PosixFilePermissions.fromString("rwx------");
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(directoryMode));
            Files.setPosixFilePermissions(directory, directoryMode);
        } else {
            Files.createDirectories(directory);
        }
        Path temp =
                posix
                        ? Files.createTempFile(
                                directory,
                                ".key-",
                                ".tmp",
                                PosixFilePermissions.asFileAttribute(PRIVATE_FILE))
                        : Files.createTempFile(directory, ".key-", ".tmp");
        try {
            Files.writeString(
                    temp,
                    UUID.randomUUID() + "\n" + (key == null ? "" : key),
                    StandardCharsets.US_ASCII);
            try {
                Files.move(
                        temp,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return status();
    }
}
