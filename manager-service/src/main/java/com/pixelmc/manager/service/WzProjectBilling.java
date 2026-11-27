package com.pixelmc.manager.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** A confirmed upstream billing refusal applies to one credential and one recovery generation. */
final class WzProjectBilling {
    static final String CODE = "project_billing_unavailable";
    static final String MESSAGE = "服务器 OpenAI 项目额度已用尽或已达支出上限";
    private static final Set<String> REFUSALS =
            Set.of(
                    "insufficient_quota",
                    "credit_balance_exhausted",
                    "organization_usage_limit_exceeded",
                    "organization_spend_limit_exceeded",
                    "project_spend_limit_exceeded");
    private final Path file;
    private final Supplier<String> revision;
    private final Runnable changed;
    private String blockedRevision = "";
    private String holdId = "";
    private long generation;

    record Attempt(String revision, long generation) {}

    WzProjectBilling(Path data, Supplier<String> revision, Runnable changed) {
        this.revision = revision;
        this.changed = changed;
        file = data == null ? null : data.resolve("wenzhou/openai-billing-hold");
        if (file == null) return;
        try {
            if (Files.size(file) > 256) throw new IOException();
            String[] fields = Files.readString(file, StandardCharsets.UTF_8).split("\n", -1);
            if (fields.length != 2 || fields[0].isBlank()) throw new IOException();
            holdId = UUID.fromString(fields[1]).toString();
            blockedRevision = fields[0];
        } catch (NoSuchFileException absent) {
            // No confirmed refusal for this installation.
        } catch (IOException | IllegalArgumentException invalid) {
            blockedRevision = revision.get();
            holdId = UUID.randomUUID().toString();
            System.err.println("OpenAI billing state could not be read; project requests paused.");
        }
    }

    synchronized boolean blocked() {
        return !holdId.isEmpty() && blockedRevision.equals(revision.get());
    }

    synchronized JsonObject status() {
        var result = new JsonObject();
        boolean blocked = blocked();
        result.addProperty("billingBlocked", blocked);
        if (blocked) result.addProperty("billingHoldId", holdId);
        return result;
    }

    synchronized Attempt attempt() {
        return new Attempt(revision.get(), generation);
    }

    void refuse(Attempt attempt) {
        synchronized (this) {
            if (attempt.generation() != generation
                    || !attempt.revision().equals(revision.get())
                    || blocked()) return;
            blockedRevision = attempt.revision();
            holdId = UUID.randomUUID().toString();
            if (file != null) {
                try {
                    ServiceConfig.atomic(file, blockedRevision + "\n" + holdId);
                } catch (Exception unavailable) {
                    System.err.println(
                            "OpenAI billing hold is active in memory; persistence failed.");
                }
            }
        }
        changed.run();
    }

    void resume(String expectedRevision, String expectedHoldId) throws IOException {
        synchronized (this) {
            if (!blocked()
                    || !blockedRevision.equals(expectedRevision)
                    || !holdId.equals(expectedHoldId))
                throw new IllegalStateException("OpenAI 项目状态已变化，请刷新后重试");
            if (file != null) Files.deleteIfExists(file);
            generation++;
            blockedRevision = "";
            holdId = "";
        }
        changed.run();
    }

    static boolean httpRefusal(int status, javax.net.ssl.HttpsURLConnection connection) {
        if (status != 402 && status != 403 && status != 429) return false;
        try (InputStream body = connection.getErrorStream()) {
            if (body == null) return false;
            byte[] bytes = body.readNBytes(8193);
            if (bytes.length > 8192) return false;
            var root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            return root instanceof JsonObject obj
                    && obj.get("error") instanceof JsonObject failure
                    && refusal(failure);
        } catch (IOException | RuntimeException malformed) {
            return false;
        }
    }

    static boolean refusal(JsonObject error) {
        if (error == null) return false;
        var code = error.get("code");
        if (code != null && !code.isJsonNull()) return recognized(code);
        return recognized(error.get("type"));
    }

    private static boolean recognized(com.google.gson.JsonElement field) {
        return field instanceof JsonPrimitive text
                && text.isString()
                && REFUSALS.contains(text.getAsString());
    }
}
