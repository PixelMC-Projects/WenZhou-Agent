package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Bounded, administrator-local metadata for a single question. No raw provider or shell payloads. */
final class WzDiagnostics {
    private static final Path LOG = Path.of("logs", "wenzhou", "diagnostics.jsonl");
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int ARCHIVES = 3;
    private static final Pattern SECRET = Pattern.compile("(?i)sk[-_]|bearer|password|api[-_]?key|token=|://");
    private static long retryAfter;
    private final String conversation;
    private final String turn;
    int round;

    WzDiagnostics(String conversation, String turn) {
        this.conversation = uuid(conversation);
        this.turn = uuid(turn).isEmpty() ? UUID.randomUUID().toString() : turn;
    }

    private static String uuid(String id) {
        return id != null && id.matches("[0-9a-fA-F-]{36}") ? id : "";
    }

    private JsonObject event(String type, long started) {
        var row = new JsonObject();
        row.addProperty("time", Instant.now().toString());
        row.addProperty("turn", turn);
        if (!conversation.isEmpty()) row.addProperty("conversation", conversation);
        row.addProperty("round", round);
        row.addProperty("event", type);
        row.addProperty("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return row;
    }

    void model(long started, JsonObject receipt, Exception failure) {
        var row = event("model", started);
        row.addProperty("receiptAvailable", receipt != null);
        if (receipt != null) for (String key : List.of("hit","miss","output","reasoning","nanoYuan"))
            if (receipt.has(key)) row.add(key, receipt.get(key));
        if (failure != null) failure(row, failure);
        append(row);
    }

    void tool(String name, String arguments, long started, JsonObject result, Exception failure) {
        var row = event("tool", started);
        row.addProperty("tool", token(name));
        try {
            var args = JsonParser.parseString(arguments).getAsJsonObject();
            for (String key : List.of("kind","view","facet","id","subject","reason","query"))
                if (args.has(key) && args.get(key).isJsonPrimitive())
                    row.addProperty(key, token(args.get(key).getAsString()));
            for (String key : List.of("page","sources","keywords","summary","detail","command","expression")) {
                JsonElement value = args.get(key);
                if (value == null) continue;
                if (key.equals("page") && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())
                    row.addProperty(key, value.getAsInt());
                else if (value.isJsonArray()) {
                    JsonArray items = new JsonArray();
                    for (JsonElement item : value.getAsJsonArray()) {
                        if (items.size() == 6) break;
                        if (item.isJsonPrimitive()) items.add(token(item.getAsString()));
                    }
                    row.add(key, items);
                } else if (value.isJsonPrimitive()) row.addProperty(key+"Length", value.getAsString().length());
            }
        } catch (RuntimeException invalid) { row.addProperty("argumentsInvalid", true); }
        if (result != null) {
            row.addProperty("status", token(WzData.string(result, "status")));
            for (String key : List.of("truncated","time_limit_reached","exit_code","total","unreadable","windows","created"))
                if (result.has(key) && result.get(key).isJsonPrimitive()) row.add(key, result.get(key));
            for (String key : List.of("saved","skipped")) if (result.has(key)) row.addProperty(key, true);
            for (String key : List.of("matches","methods","items","recipes","results"))
                if (result.has(key) && result.get(key).isJsonArray()) row.addProperty(key+"Count",result.getAsJsonArray(key).size());
            // Validation messages are server-generated. No shell output, user text or exception messages.
            if (result.has("field")) row.add("field",result.get("field"));
            if (result.has("message") && (name.endsWith("_memory") || "invalid_request".equals(WzData.string(result,"status")))) {
                row.add("message",result.get("message"));
            }
        }
        if (failure != null) failure(row, failure);
        append(row);
    }

    private static String token(String text) {
        return text.length() <= 300 && !SECRET.matcher(text).find()
                && text.matches("[\\p{L}\\p{N}_./:$#@!;()\\[\\]-]*") ? text : "[redacted]";
    }

    private static void failure(JsonObject row, Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause != cause.getCause()) cause = cause.getCause();
        row.addProperty("failure",cause.getClass().getSimpleName());
        if (cause instanceof java.util.concurrent.TimeoutException || cause instanceof java.net.http.HttpTimeoutException)
            row.addProperty("time_limit_reached",true);
    }

    private static synchronized void append(JsonObject row) {
        if (System.currentTimeMillis() < retryAfter) return;
        try {
            byte[] line = (row + "\n").getBytes(StandardCharsets.UTF_8);
            Files.createDirectories(LOG.getParent());
            if (Files.exists(LOG) && Files.size(LOG) + line.length > MAX_BYTES) {
                for (int i = ARCHIVES; i > 0; i--) {
                    Path from = i == 1 ? LOG : LOG.resolveSibling("diagnostics."+(i-1)+".jsonl");
                    if (Files.exists(from)) Files.move(from, LOG.resolveSibling("diagnostics."+i+".jsonl"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.write(LOG,line,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        } catch (IOException | SecurityException unavailable) {
            retryAfter = System.currentTimeMillis() + 60_000;
            com.mojang.logging.LogUtils.getLogger().warn("WenZhou diagnostics unavailable, retrying in one minute");
        }
    }
}
