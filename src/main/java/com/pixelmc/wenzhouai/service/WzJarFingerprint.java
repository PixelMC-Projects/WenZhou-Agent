package com.pixelmc.wenzhouai.service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.*;
import java.util.*;

/** Installed mods are immutable during a game session. Refresh on file replacement. */
final class WzJarFingerprint {
    private record FileId(long size, FileTime modified, Object key) {}
    private record Cached(FileId id, String sha) {}
    private static final Map<Path,Cached> CACHE = new HashMap<>();

    static synchronized String of(Path jar) throws IOException {
        var path = jar.toAbsolutePath().normalize();
        var attrs = Files.readAttributes(path, BasicFileAttributes.class);
        var id = new FileId(attrs.size(), attrs.lastModifiedTime(), attrs.fileKey());
        var old = CACHE.get(path);
        if (old != null && old.id.equals(id)) return old.sha;
        try (var input = Files.newInputStream(path)) {
            var md = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[65536];
            for (int n; (n = input.read(buffer)) != -1;) md.update(buffer, 0, n);
            String sha = HexFormat.of().formatHex(md.digest());
            CACHE.put(path, new Cached(id, sha));
            return sha;
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private WzJarFingerprint() {}
}
