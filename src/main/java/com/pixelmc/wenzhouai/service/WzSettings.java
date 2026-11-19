package com.pixelmc.wenzhouai.service;

import static com.pixelmc.wenzhouai.service.WzData.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
        loadDefault(state);
    }

    private static void loadDefault(JsonObject state) {
        if (!state.has("version")) {
            try (var source =
                    WzSettings.class.getResourceAsStream("/assets/wenzhouai/default-policy.json")) {
                if (source != null) {
                    JsonObject initial =
                            JsonParser.parseReader(
                                            new InputStreamReader(source, StandardCharsets.UTF_8))
                                    .getAsJsonObject();
                    state.add("text", object(initial, "text"));
                    state.addProperty("versionLabel", string(initial, "versionLabel"));
                    state.addProperty("version", 1);
                }
            } catch (IOException e) {
                throw new IllegalStateException("默认问舟协议不可用");
            }
        }
    }

    int version() {
        return (int) number(state, "version");
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

    JsonObject policy() {
        return policy(state);
    }

    private static JsonObject policy(JsonObject current) {
        JsonObject result = new JsonObject();
        result.addProperty("version", number(current, "version"));
        result.addProperty("versionLabel", string(current, "versionLabel"));
        result.addProperty(
                "showProtocol", !current.has("showAgreement") || flag(current, "showAgreement"));
        result.add("text", object(current, "text").deepCopy());
        result.addProperty("protocol", WzProtocolText.plain(object(current, "text").get("rules")));
        return result;
    }

    static JsonObject savedAdmin(Path directory, String operation) {
        if (!java.nio.file.Files.isDirectory(directory))
            throw new IllegalStateException("问舟存储目录不可用");
        JsonObject stored = read(directory.resolve("service.json"));
        loadDefault(stored);
        if (operation.equals("policy.summary")) return summary(stored);
        JsonObject encrypted = read(directory.resolve("credential.json"));
        return status(stored, !string(encrypted, "ciphertext").isEmpty()
                || !string(stored, "key").isEmpty(), number(encrypted, "updatedAt"));
    }

    private static JsonObject summary(JsonObject state) {
        JsonObject policy = new JsonObject();
        policy.addProperty("version", number(state, "version"));
        policy.addProperty("versionLabel", string(state, "versionLabel"));
        JsonObject result = new JsonObject();
        result.add("policy", policy);
        result.addProperty("showAgreement", !state.has("showAgreement") || flag(state, "showAgreement"));
        return result;
    }

    synchronized JsonObject admin(String operation, JsonObject input) {
        if (operation.equals("policy.summary")) return summary(state);
        JsonObject next = operation.equals("status") ? state : state.deepCopy();
        switch (operation) {
            case "status" -> {}
            case "policy.draft", "policy.publish" -> {
                long revision = number(next, "revision");
                if (number(input, "revision") != revision)
                    throw new IllegalArgumentException("协议已更新，请重新加载");
                JsonObject fields = new JsonObject();
                JsonObject submitted = object(input, "text");
                if (submitted.size() == 0 && operation.equals("policy.publish"))
                    submitted = object(next, "draft");
                for (String field : Set.of("rules", "brief", "details")) {
                    var value = WzProtocolText.sanitize(submitted.get(field));
                    if (operation.equals("policy.publish") && WzProtocolText.plain(value).isBlank())
                        throw new IllegalArgumentException("请填写有效的协议正文");
                    fields.add(field, value);
                }
                next.addProperty("revision", revision + 1);
                next.add("draft", fields);
                if (operation.equals("policy.publish")) {
                    next.add("text", fields);
                    next.addProperty("version", version() + 1);
                    next.addProperty("versionLabel", "");
                }
            }
            case "policy.visibility" -> next.addProperty("showAgreement", flag(input, "enabled"));
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
        JsonObject policy = policy(next);
        policy.addProperty("revision", number(next, "revision"));
        if (next.has("draft")) policy.add("draft", object(next, "draft"));
        result.add("policy", policy);
        result.addProperty("showAgreement", flag(policy, "showProtocol"));
        result.addProperty("reasoningEffort", reasoning(next));
        JsonObject credential = new JsonObject();
        credential.addProperty("configured", configured);
        credential.addProperty("updatedAt", updatedAt);
        result.add("credential", credential);
        return result;
    }
}
