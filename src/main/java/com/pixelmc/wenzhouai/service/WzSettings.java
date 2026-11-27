package com.pixelmc.wenzhouai.service;

import static com.pixelmc.wenzhouai.service.WzData.*;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.Set;

final class WzSettings {
    private final Path path;
    private final WzCredential credential;
    private volatile JsonObject state;

    WzSettings(Path path) {
        this.path = path;
        this.state = read(path);
        this.credential = new WzCredential(path.getParent());
        if (state.has("key")) {
            if (credential.key().isEmpty() && !string(state, "key").isEmpty())
                credential.set(string(state, "key"));
            state.remove("key");
            write(path, state);
        }
    }

    String key() {
        return credential.key();
    }

    String reasoning() {
        return reasoning(state);
    }

    private static String reasoning(JsonObject state) {
        return switch (string(state, "reasoningEffort")) {
            case "off" -> "off";
            case "medium", "high" -> "high";
            case "max" -> "max";
            default -> "low";
        };
    }

    static void applyReasoning(JsonObject request, String effort) {
        JsonObject thinking = new JsonObject();
        boolean enabled = !"off".equals(effort);
        thinking.addProperty("type", enabled ? "enabled" : "disabled");
        request.add("thinking", thinking);
        if (enabled) request.addProperty("reasoning_effort", effort);
        else request.remove("reasoning_effort");
    }

    static JsonObject savedAdmin(Path directory, String operation) {
        if (!java.nio.file.Files.isDirectory(directory))
            throw new IllegalStateException("问舟存储目录不可用");
        if (!"status".equals(operation)) throw new IllegalStateException("此设置需要游戏服连接");
        JsonObject stored = read(directory.resolve("service.json"));
        JsonObject encrypted = read(directory.resolve("credential.json"));
        return status(
                stored,
                !string(encrypted, "ciphertext").isEmpty() || !string(stored, "key").isEmpty(),
                number(encrypted, "updatedAt"));
    }

    synchronized JsonObject admin(String operation, JsonObject input) {
        JsonObject next = operation.equals("status") ? state : state.deepCopy();
        switch (operation) {
            case "status" -> {}
            case "credential.set" -> {
                String key = string(input, "key").strip();
                if (!key.matches("[A-Za-z0-9_-]{16,256}"))
                    throw new IllegalArgumentException("密钥格式无效");
                credential.set(key);
            }
            case "credential.remove" -> credential.set("");
            case "reasoning.set" -> {
                String effort = string(input, "value");
                if (!Set.of("off", "low", "medium", "high", "max").contains(effort))
                    throw new IllegalArgumentException("无效思考强度");
                next.addProperty("reasoningEffort", effort);
            }
            default -> throw new IllegalArgumentException("未知的服务管理操作");
        }
        if (!operation.equals("status") && !operation.startsWith("credential.")) {
            write(path, next);
            state = next;
        }
        return status(next, !key().isEmpty(), credential.updatedAt());
    }

    private static JsonObject status(JsonObject next, boolean configured, long updatedAt) {
        JsonObject result = new JsonObject();
        result.addProperty("reasoningEffort", reasoning(next));
        JsonObject credential = new JsonObject();
        credential.addProperty("configured", configured);
        credential.addProperty("updatedAt", updatedAt);
        result.add("credential", credential);
        return result;
    }
}
