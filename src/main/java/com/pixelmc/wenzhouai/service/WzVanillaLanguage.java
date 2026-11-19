package com.pixelmc.wenzhouai.service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** Resolve the 1.20.1 launcher language asset even on a standalone dedicated server. */
final class WzVanillaLanguage {
    static final String ASSET = "bd3b3df108c1eaf78b6b8904c9f0842a7b110d5c";
    private static final int SIZE = 563202;
    private static final String SHA256 = "61eb3cb2533f3d764b681715d27d4d28221f2caad07d9f4aa8f6a100bdab29dc";

    static Optional<Path> assets(Path directory) {
        Path file = directory.resolve("minecraft/lang/zh_cn.json");
        try {
            if (Files.isRegularFile(file) && Files.size(file) == SIZE && verified(Files.readAllBytes(file))) {
                return Optional.of(directory);
            }
            var url = URI.create("https://resources.download.minecraft.net/bd/" + ASSET).toURL();
            var connection = url.openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(4000);
            byte[] bytes;
            try (var input = connection.getInputStream()) {
                bytes = input.readNBytes(SIZE + 1);
            }
            if (!verified(bytes)) return Optional.empty();
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "zh_cn-", ".tmp");
            try {
                Files.write(temp, bytes);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
            return Optional.of(directory);
        } catch (IOException failure) {
            return Optional.empty();
        }
    }

    private static boolean verified(byte[] data) {
        try {
            return data.length == SIZE && SHA256.equals(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private WzVanillaLanguage() {}
}
