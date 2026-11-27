package com.pixelmc.manager.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;

/** Persists confirmed costs before attempting database settlement. */
final class WzTerraSettlement {
    private static final int MAX_RECORD_BYTES = 512;
    private static final int REPLAY_BATCH = 64;
    private final WzTerraLedger ledger;
    private final Path pending;

    WzTerraSettlement(WzTerraLedger ledger, Path data) {
        this.ledger = ledger;
        this.pending = data == null ? null : data.resolve("wenzhou").resolve("terra-settlements");
    }

    void settle(UUID player, UUID operation, long nanoUsd) throws Exception {
        if (nanoUsd < 0) throw new IllegalArgumentException("Terra 结算金额不能为负");
        if (pending == null) {
            ledger.settle(player, operation, nanoUsd);
            return;
        }
        Path file = pending.resolve(operation + ".json");
        var receipt = new Receipt(player, operation, nanoUsd);
        try {
            persist(file, receipt);
        } catch (IOException unavailable) {
            // A writable SQL store can still confirm the cost when local storage is unavailable.
            try {
                ledger.settle(player, operation, nanoUsd);
            } catch (Exception failed) {
                failed.addSuppressed(unavailable);
                throw failed;
            }
            return;
        }
        apply(file, receipt);
    }

    void replay() {
        if (pending == null || !Files.isDirectory(pending)) return;
        int processed = 0;
        boolean incomplete = false;
        try (var files = Files.newDirectoryStream(pending, "*.json")) {
            for (Path file : files) {
                try {
                    apply(file, read(file));
                    if (++processed >= REPLAY_BATCH) break;
                } catch (SQLException unavailable) {
                    String state = unavailable.getSQLState();
                    if (state != null && state.startsWith("08")) return;
                    incomplete = true;
                } catch (NoSuchFileException concurrentlySettled) {
                    // The live request settled the same receipt.
                } catch (Exception invalid) {
                    incomplete = true;
                }
            }
        } catch (IOException | DirectoryIteratorException unavailable) {
            incomplete = true;
        }
        if (incomplete) System.err.println("Some Terra settlements remain pending.");
    }

    private synchronized void persist(Path file, Receipt receipt) throws Exception {
        try {
            if (!read(file).equals(receipt))
                throw new IllegalStateException("Terra 待结算回执与已保存金额不一致");
        } catch (NoSuchFileException absent) {
            var body = new JsonObject();
            body.addProperty("player", receipt.player().toString());
            body.addProperty("nanoUsd", receipt.nanoUsd());
            ServiceConfig.atomic(file, body.toString());
        }
    }

    private void apply(Path file, Receipt receipt) throws Exception {
        ledger.settle(receipt.player(), receipt.operation(), receipt.nanoUsd());
        try {
            Files.deleteIfExists(file);
        } catch (IOException unavailable) {
            // SQL is committed. Replaying this file is safe until removal succeeds.
            System.err.println("A committed Terra settlement is still pending file cleanup.");
        }
    }

    private static Receipt read(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException("");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("Terra 待结算回执文件无效");
        String name = file.getFileName().toString();
        UUID operation = UUID.fromString(name.substring(0, name.length() - ".json".length()));
        if (!name.equals(operation + ".json")) throw new IllegalStateException("Terra 待结算回执标识无效");
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_RECORD_BYTES + 1);
            if (bytes.length > MAX_RECORD_BYTES) throw new IllegalStateException("Terra 待结算回执文件过大");
            var body =
                    JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            UUID player = UUID.fromString(Wire.text(body, "player", 36));
            long amount = Wire.number(body, "nanoUsd", -1, 0, Long.MAX_VALUE);
            if (amount < 0) throw new IllegalStateException("Terra 待结算回执金额缺失");
            return new Receipt(player, operation, amount);
        }
    }

    private record Receipt(UUID player, UUID operation, long nanoUsd) {}
}
