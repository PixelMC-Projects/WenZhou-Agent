package com.pixelmc.manager.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/** Project-key calls made by Manager Service for the authenticated owner. */
final class WenzhouOpenAi {
    private static final URI RESPONSES = URI.create("https://api.openai.com/v1/responses");
    private static final List<String> MODELS = List.of("gpt-6.1-terra", "gpt-6.2-sol");
    private static final List<String> TIERS = List.of("default", "fast");
    private static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh", "max");
    private static final Set<String> FIELDS =
            Set.of("input", "model", "service_tier", "reasoning_effort", "max_output_tokens");
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private final Semaphore slot = new Semaphore(1);

    private final WzOpenAiCredential credential;

    private final WzProjectBilling billing;

    WenzhouOpenAi(WzOpenAiCredential credential) {
        this(credential, new WzProjectBilling(null, credential::revision, () -> {}));
    }

    WenzhouOpenAi(WzOpenAiCredential credential, WzProjectBilling billing) {
        this.credential = credential;
        this.billing = billing;
    }

    JsonObject status() {
        var status = new JsonObject();
        status.addProperty("configured", credential.status().get("configured").getAsBoolean());
        status.addProperty("usesProxy", proxy() != Proxy.NO_PROXY);
        status.add("models", Wire.JSON.toJsonTree(MODELS));
        status.add("serviceTiers", Wire.JSON.toJsonTree(TIERS));
        status.add("reasoningEfforts", Wire.JSON.toJsonTree(EFFORTS));
        return status;
    }

    JsonObject respond(JsonObject input) {
        JsonObject body = requestBody(input);
        var attempt = billing.attempt();
        if (billing.blocked()) throw new Failure(429, WzProjectBilling.MESSAGE);
        String key = credential.effectiveKey();
        if (key.isEmpty()) throw new Failure(503, "Manager 尚未配置 OpenAI 项目 API Key");
        Proxy proxy = proxy();
        if (!slot.tryAcquire()) throw new Failure(429, "已有 OpenAI 管理请求正在处理");
        try {
            return send(body, key, proxy, attempt);
        } finally {
            slot.release();
        }
    }

    static JsonObject requestBody(JsonObject input) {
        if (!FIELDS.containsAll(input.keySet()))
            throw new IllegalArgumentException("OpenAI 请求含有不支持的字段");
        String prompt = string(input, "input", "");
        if (prompt.isBlank() || prompt.length() > 32768)
            throw new IllegalArgumentException("输入须为 1 至 32768 个字符");
        String model = option(input, "model", MODELS);
        String tier = option(input, "service_tier", TIERS);
        var reasoning = new JsonObject();
        reasoning.addProperty("effort", option(input, "reasoning_effort", EFFORTS));
        int limit = 1024;
        if (input.has("max_output_tokens")) {
            if (!(input.get("max_output_tokens") instanceof JsonPrimitive number)
                    || !number.isNumber())
                throw new IllegalArgumentException("输出上限须为 16 至 4096 的整数");
            try {
                limit = number.getAsBigDecimal().intValueExact();
            } catch (ArithmeticException | NumberFormatException invalid) {
                throw new IllegalArgumentException("输出上限须为 16 至 4096 的整数");
            }
            if (limit < 16 || limit > 4096)
                throw new IllegalArgumentException("输出上限须为 16 至 4096 的整数");
        }
        var body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("input", prompt);
        body.addProperty("service_tier", tier);
        body.add("reasoning", reasoning);
        body.addProperty("max_output_tokens", limit);
        body.addProperty("store", false);
        return body;
    }

    private static String string(JsonObject input, String field, String fallback) {
        if (!input.has(field)) return fallback;
        if (input.get(field) instanceof JsonPrimitive value && value.isString())
            return value.getAsString();
        throw new IllegalArgumentException(field + " 须为文本");
    }

    private static String option(JsonObject input, String field, List<String> choices) {
        String value = string(input, field, choices.get(0));
        if (!choices.contains(value)) throw new IllegalArgumentException(field + " 不在支持范围内");
        return value;
    }

    static String environmentKey() {
        String secretFile = System.getenv("OPENAI_API_KEY_FILE");
        String value;
        if (secretFile != null && !secretFile.isBlank()) {
            try {
                Path path = Path.of(secretFile);
                if (!path.isAbsolute() || !Files.isRegularFile(path)) throw new IOException();
                try (var input = Files.newInputStream(path)) {
                    byte[] bytes = input.readNBytes(4097);
                    if (bytes.length > 4096) throw new IOException();
                    value = new String(bytes, StandardCharsets.UTF_8).strip();
                }
                if (value.isEmpty()) throw new IOException();
            } catch (IOException | IllegalArgumentException failure) {
                throw new Failure(503, "Manager 无法读取 OpenAI 密钥文件，请检查挂载及权限");
            }
        } else {
            value = System.getenv().getOrDefault("OPENAI_API_KEY", "").strip();
        }
        if (!value.isEmpty() && (value.length() > 4096 || !value.matches("[!-~]+")))
            throw new Failure(503, "Manager 的 OpenAI 密钥格式无效");
        return value;
    }

    private static Proxy proxy() {
        Map<String, String> env = System.getenv();
        String value =
                env.getOrDefault(
                                "WENZHOU_OPENAI_PROXY",
                                env.getOrDefault("WENZHOU_CHATGPT_PROXY", ""))
                        .strip();
        if (value.isEmpty()) return Proxy.NO_PROXY;
        try {
            URI uri = URI.create(value);
            if (!"http".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getPort() < 1
                    || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || !List.of("", "/").contains(uri.getRawPath()))
                throw new IllegalArgumentException();
            return new Proxy(
                    Proxy.Type.HTTP,
                    InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort()));
        } catch (IllegalArgumentException invalid) {
            throw new Failure(503, "Manager 的 OpenAI HTTP 代理地址无效");
        }
    }

    boolean available() {
        try { proxy(); return !credential.effectiveKey().isEmpty(); }
        catch (Failure invalid) { return false; }
    }

    HttpsURLConnection projectConnection() {
        String key = credential.effectiveKey();
        if (key.isEmpty()) throw new Failure(503, "Manager 尚未配置 OpenAI 项目 API Key");
        try { return connection(key,proxy()); }
        catch (IOException unavailable) { throw new Failure(502, "无法连接 OpenAI"); }
    }

    private static HttpsURLConnection connection(String key, Proxy proxy) throws IOException {
        var connection = (HttpsURLConnection) RESPONSES.toURL().openConnection(proxy);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(90000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Bearer " + key);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        return connection;
    }

    private JsonObject send(JsonObject body, String key, Proxy proxy, WzProjectBilling.Attempt attempt) {
        HttpsURLConnection connection = null;
        try {
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            connection = connection(key, proxy);
            connection.setRequestProperty("Accept", "application/json");
            connection.setFixedLengthStreamingMode(data.length);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            if (billing.blocked()) throw new Failure(429, WzProjectBilling.MESSAGE);
            if (!attempt.equals(billing.attempt())) throw new Failure(409, "OpenAI 项目配置已变化，请重新发送");
            try (var out = connection.getOutputStream()) {
                out.write(data);
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                if (WzProjectBilling.httpRefusal(status, connection)) {
                    billing.refuse(attempt);
                    throw new Failure(429, WzProjectBilling.MESSAGE);
                }
                throw upstream(status);
            }
            try (var input = connection.getInputStream()) {
                return result(readBody(input, deadline));
            }
        } catch (SocketTimeoutException timeout) {
            throw new Failure(504, "OpenAI 请求超时，本次调用可能已计量");
        } catch (IOException unavailable) {
            throw new Failure(502, "无法完成与 OpenAI 的连接，请检查 Manager 的网络及代理");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readBody(InputStream input, long deadline) throws IOException {
        var data = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1; ) {
            if (System.nanoTime() - deadline >= 0) throw new SocketTimeoutException();
            if (data.size() + count > MAX_RESPONSE_BYTES) throw new Failure(502, "OpenAI 回包超过大小限制");
            data.write(buffer, 0, count);
        }
        return data.toString(StandardCharsets.UTF_8);
    }

    static JsonObject result(String json) {
        try {
            JsonObject response = JsonParser.parseString(json).getAsJsonObject();
            String status = response.get("status").getAsString();
            if (!Set.of("completed", "incomplete").contains(status))
                throw new Failure(502, "OpenAI 未完成本次请求");
            var result = new JsonObject();
            result.addProperty("status", status);
            result.addProperty("model", response.get("model").getAsString());
            if (response.get("service_tier") instanceof JsonPrimitive tier)
                result.add("service_tier", tier);
            if (response.get("usage") instanceof JsonObject usage) result.add("usage", usage);
            if (response.get("incomplete_details") instanceof JsonObject details)
                result.add("incomplete_details", details);
            var text = new StringBuilder();
            var refusal = new StringBuilder();
            for (var item : response.getAsJsonArray("output")) {
                JsonObject message = item.getAsJsonObject();
                if (!"message".equals(message.get("type").getAsString())) continue;
                if (message.get("phase") instanceof JsonPrimitive phase
                        && "commentary".equals(phase.getAsString())) continue;
                for (var part : message.getAsJsonArray("content")) {
                    JsonObject content = part.getAsJsonObject();
                    switch (content.get("type").getAsString()) {
                        case "output_text" -> text.append(content.get("text").getAsString());
                        case "refusal" -> refusal.append(content.get("refusal").getAsString());
                    }
                }
            }
            if (status.equals("completed") && text.isEmpty() && refusal.isEmpty())
                throw new Failure(502, "OpenAI 未返回文本");
            result.addProperty("text", text.toString());
            if (!refusal.isEmpty()) result.addProperty("refusal", refusal.toString());
            return result;
        } catch (JsonParseException
                | IllegalStateException
                | ClassCastException
                | NullPointerException
                | UnsupportedOperationException malformed) {
            throw new Failure(502, "OpenAI 回包格式无效");
        }
    }

    static Failure upstream(int status) {
        return switch (status) {
            case 401, 403 -> new Failure(502, "OpenAI 拒绝项目凭据或访问权限 (HTTP " + status + ")");
            case 400, 404 -> new Failure(502, "OpenAI 未接受模型或调用参数 (HTTP " + status + ")");
            case 429 -> new Failure(429, "OpenAI 项目达到速率或用量限制 (HTTP 429)");
            default -> new Failure(502, "OpenAI 请求失败 (HTTP " + status + ")");
        };
    }

    static final class Failure extends RuntimeException {
        final int status;

        Failure(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
