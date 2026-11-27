package com.pixelmc.wenzhouai.service;

import static com.pixelmc.wenzhouai.service.WzChatGptProtocol.integer;
import static com.pixelmc.wenzhouai.service.WzChatGptProtocol.text;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One ChatGPT registration in a runtime controlled by the bound player. */
public final class WzChatGptAccount implements AutoCloseable {
    private static final String SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private static final String CALLBACK = "/auth/callback";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    private final UUID owner;
    private final ScheduledThreadPoolExecutor tasks = new ScheduledThreadPoolExecutor(2, job -> {
        Thread thread = new Thread(job, "wenzhouai-chatgpt");
        thread.setDaemon(true);
        return thread;
    });
    private final int port;
    private final WzChatGptVault vault;
    private final WzChatGptProtocol protocol;
    private Runnable onChange = () -> {};
    private JsonObject saved = new JsonObject();
    private Pending pending;
    private long session;
    private long transaction;
    private long refreshRetryAt;
    private boolean closed;
    private String retryClient = "";
    private String phase = "idle";
    private String message = "";

    public WzChatGptAccount(Path dataDir, UUID owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
        tasks.setRemoveOnCancelPolicy(true);
        String key = System.getenv().getOrDefault("WENZHOU_CHATGPT_STORAGE_KEY", "").strip();
        int callbackPort = 1455;
        WzChatGptVault storage = null;
        WzChatGptProtocol transport = null;
        if (key.isEmpty()) {
            phase = "disabled";
            message = "当前运行环境未启用 ChatGPT 登录";
        } else {
            try {
                callbackPort = Integer.parseInt(System.getenv().getOrDefault("WENZHOU_CHATGPT_CALLBACK_PORT", "1455"));
                if (callbackPort < 1024 || callbackPort > 65535) throw new IllegalArgumentException();
                storage = new WzChatGptVault(dataDir, owner, key);
                transport = new WzChatGptProtocol(System.getenv().getOrDefault("WENZHOU_CHATGPT_PROXY", "").strip());
                saved = storage.read();
                if (saved.size() > 0 && !text(saved, "host").matches("urn:uuid:[0-9a-f-]{36}")) throw new IOException();
                if (saved.has("client") && !WzChatGptProtocol.issuedClient(text(saved, "client"))) throw new IOException();
                phase = restingPhase();
            } catch (IOException | IllegalArgumentException failure) {
                storage = null;
                transport = null;
                saved = new JsonObject();
                phase = "disabled";
                message = "无法打开 ChatGPT 凭据存储，请检查本机配置";
            }
        }
        port = callbackPort;
        vault = storage;
        protocol = transport;
    }

    public synchronized void setOnChange(Runnable callback) {
        onChange = java.util.Objects.requireNonNull(callback);
    }

    public boolean isOwner(UUID player) {
        return owner.equals(player);
    }

    public HttpClient httpClient() {
        if (protocol == null) throw new IllegalStateException("ChatGPT 登录未启用");
        return protocol.client();
    }

    public synchronized long sessionGeneration() {
        return session;
    }

    public synchronized String preferenceSession() {
        return connected() ? text(saved, "sessionTag") : "";
    }

    public synchronized boolean connected() {
        return !closed && !text(saved, "access").isBlank()
                && WzChatGptProtocol.hasPlan(text(saved, "scope"))
                && (integer(saved, "expiry") > now() || !text(saved, "refresh").isEmpty());
    }

    public synchronized JsonArray models() {
        return connected() && saved.get("models") instanceof JsonArray models ? models.deepCopy() : new JsonArray();
    }

    public synchronized String accessToken(UUID player) throws IOException {
        return accessToken(player, session);
    }

    public synchronized String accessToken(UUID player, long expectedSession) throws IOException {
        if (!isOwner(player)) throw new SecurityException("ChatGPT 登录仅授权给绑定的服主");
        if (!connected() || session != expectedSession) throw new IOException("ChatGPT 授权不可用，请在管理页检查登录状态");
        long now = now();
        String token = text(saved, "access"), refresh = text(saved, "refresh");
        long expiry = integer(saved, "expiry");
        if (expiry - now > 90 || (now < refreshRetryAt && now < expiry)
                || (refresh.isEmpty() && now < expiry) || (now < integer(saved, "earliestRefresh") && now < expiry)) return token;
        if (now < refreshRetryAt) throw new IOException("ChatGPT 授权暂时不能刷新");
        JsonObject response;
        try {
            response = protocol.refresh(text(saved, "client"), refresh);
        } catch (WzChatGptProtocol.OAuthFailure failure) {
            if (failure.invalidGrant()) {
                invalidateSession("ChatGPT 会话已过期或撤销，请重新登录");
                throw new IOException("ChatGPT 会话已过期或撤销，请重新登录");
            }
            refreshRetryAt = now + 20;
            if (now() < expiry - 5) return token;
            throw failure;
        } catch (IOException failure) {
            refreshRetryAt = now + 20;
            if (now() < expiry - 5) return token;
            throw failure;
        }
        JsonObject next = saved.deepCopy();
        try {
            String idToken = text(response, "id_token");
            if (!idToken.isEmpty()) {
                JsonObject identity = protocol.verify(idToken, text(saved, "client"), null);
                if (!text(saved, "subject").equals(text(identity, "sub"))) throw new IOException();
            }
            applyTokens(response, next, text(saved, "scope"));
            if (!WzChatGptProtocol.hasPlan(text(next, "scope")) || text(next, "refresh").isEmpty())
                throw new IOException();
        } catch (IOException invalid) {
            invalidateSession("ChatGPT 授权已变更，请重新登录");
            throw new IOException("ChatGPT 授权已变更，请重新登录");
        }
        // The previous refresh token may already have been invalidated upstream.
        saved = next;
        refreshRetryAt = 0;
        vault.write(next);
        return text(saved, "access");
    }

    public synchronized JsonObject admin(String operation, JsonObject input) throws IOException {
        if (operation.equals("chatgpt.status")) return status();
        if (closed || vault == null) throw new IllegalStateException("ChatGPT 登录未启用");
        return switch (operation) {
            case "chatgpt.login" -> startLogin();
            case "chatgpt.cancel" -> {
                endLogin();
                update(restingPhase(), "已取消 ChatGPT 登录");
                yield status();
            }
            case "chatgpt.logout" -> logout();
            case "chatgpt.models" -> {
                try {
                    loadModels();
                    message = "";
                } catch (IOException failure) {
                    message = "无法更新 ChatGPT 模型列表，请重试";
                }
                changed();
                yield status();
            }
            default -> throw new IllegalArgumentException("未知 ChatGPT 管理操作");
        };
    }

    private JsonObject startLogin() throws IOException {
        if (phase.equals("verifying") || phase.equals("storage_error") || pending != null) return status();
        if (!saved.has("host")) {
            JsonObject host = new JsonObject();
            host.addProperty("host", "urn:uuid:" + UUID.randomUUID());
            vault.write(host);
            saved = host;
        }
        HttpServer listener;
        try {
            listener = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 8);
        } catch (IOException unavailable) {
            update("error", "登录回环端口无法监听，请检查端口占用");
            return status();
        }
        String client = text(saved, "client");
        if (client.isEmpty()) client = retryClient;
        boolean registration = client.isEmpty();
        String verifier = random(), state = random(), nonce = random();
        String redirect = "http://127.0.0.1:" + port + CALLBACK;
        Map<String, String> query = new HashMap<>();
        query.put("client_id", registration ? WzChatGptProtocol.DYNAMIC_CLIENT : client);
        query.put("ext_agent_host_id", text(saved, "host"));
        if (registration) query.put("agent_name_hint", "WenZhou Agent");
        else if (saved.has("planDenied")) query.put("prompt", "consent");
        query.put("response_type", "code");
        query.put("redirect_uri", redirect);
        query.put("scope", SCOPE);
        query.put("resource", WzChatGptProtocol.RESOURCE);
        query.put("state", state);
        query.put("nonce", nonce);
        query.put("code_challenge_method", "S256");
        query.put("code_challenge", challenge(verifier));
        Pending login = new Pending(++transaction, client, text(saved, "subject"), redirect, verifier, state, nonce, listener);
        listener.createContext(CALLBACK, exchange -> callback(exchange, login));
        pending = login;
        listener.start();
        login.deadline = tasks.schedule(() -> expire(login), 5, TimeUnit.MINUTES);
        update("awaiting", "等待在 ChatGPT 完成授权");
        JsonObject result = status();
        result.addProperty("authorizationUrl", WzChatGptProtocol.authorize(query));
        return result;
    }

    private synchronized void expire(Pending login) {
        if (pending != login) return;
        endLogin();
        update("error", "ChatGPT 登录已超时，请重新发起");
    }

    private void callback(HttpExchange exchange, Pending login) throws IOException {
        boolean consumed = false;
        try {
            if (!"GET".equals(exchange.getRequestMethod()) || !CALLBACK.equals(exchange.getRequestURI().getRawPath())
                    || !exchange.getRemoteAddress().getAddress().isLoopbackAddress()
                    || !("127.0.0.1:" + port).equals(exchange.getRequestHeaders().getFirst("Host"))) {
                reply(exchange, 400, "无效的登录回调");
                return;
            }
            String rawQuery = exchange.getRequestURI().getRawQuery();
            Map<String, String> query = callbackQuery(rawQuery);
            String state = query.getOrDefault("state", "");
            synchronized (this) {
                if (pending == login && MessageDigest.isEqual(login.state.getBytes(StandardCharsets.US_ASCII),
                        state.getBytes(StandardCharsets.UTF_8))) {
                    pending = null;
                    login.deadline.cancel(false);
                    consumed = true;
                    update("verifying", "正在核对 ChatGPT 授权");
                }
            }
            if (!consumed) {
                reply(exchange, 400, "登录请求无效或已过期");
                return;
            }
            if (query.containsKey("error")) {
                finishError(login.id, "access_denied".equals(query.get("error")) ? "ChatGPT 授权已取消" : "OpenAI 未完成授权");
                reply(exchange, 200, "授权已结束，可以返回问舟管理页。");
                return;
            }
            String client = query.getOrDefault("client_id", login.client);
            String code = query.getOrDefault("code", "");
            if (!WzChatGptProtocol.issuedClient(client) || (!login.client.isEmpty() && !login.client.equals(client))
                    || (query.containsKey("iss") && !WzChatGptProtocol.ISSUER.equals(query.get("iss")))
                    || code.isBlank() || code.length() > 4096) {
                finishError(login.id, "OpenAI 授权回调无效");
                reply(exchange, 400, "OpenAI 授权回调无效");
                return;
            }
            synchronized (this) {
                if (transaction == login.id && !closed) {
                    tasks.execute(() -> complete(login, client, code));
                }
            }
            reply(exchange, 200, "已收到 ChatGPT 回调，可以返回问舟管理页查看结果。");
        } catch (IllegalArgumentException badQuery) {
            reply(exchange, 400, "无效的登录回调");
        } finally {
            exchange.close();
            if (consumed) login.listener.stop(0);
        }
    }

    private void complete(Pending login, String client, String code) {
        try {
            JsonObject response = protocol.exchange(client, code, login.verifier, login.redirect);
            JsonObject identity = protocol.verify(text(response, "id_token"), client, login.nonce);
            String subject = text(identity, "sub");
            if (!login.subject.isEmpty() && !login.subject.equals(subject)) throw new IOException();
            synchronized (this) {
                if (transaction != login.id || closed) return;
                JsonObject next = new JsonObject();
                next.addProperty("host", text(saved, "host"));
                next.addProperty("client", client);
                next.addProperty("subject", subject);
                if (identity.get("email_verified") instanceof com.google.gson.JsonPrimitive verified
                        && verified.isBoolean() && verified.getAsBoolean()) {
                    String email = text(identity, "email");
                    if (!email.isBlank() && email.length() <= 254) next.addProperty("email", email);
                }
                boolean allowed = WzChatGptProtocol.hasPlan(text(response, "scope"));
                if (allowed) {
                    applyTokens(response, next, "");
                    next.addProperty("sessionTag", UUID.randomUUID().toString());
                } else next.addProperty("planDenied", true);
                vault.write(next);
                saved = next;
                retryClient = "";
                session++;
                refreshRetryAt = 0;
                update(restingPhase(), allowed ? "" : "已登录 ChatGPT，但未授予方案额度权限");
            }
            JsonArray catalog;
            synchronized (this) {
                if (!connected() || transaction != login.id) return;
            }
            try {
                catalog = protocol.models(text(response, "access_token"));
                synchronized (this) {
                    if (transaction == login.id && connected()) {
                        storeModels(catalog);
                        changed();
                    }
                }
            } catch (IOException unavailable) {
                synchronized (this) {
                    if (transaction == login.id && connected()) update(restingPhase(), "已登录，暂时无法读取模型列表");
                }
            }
        } catch (WzChatGptProtocol.OAuthFailure failure) {
            synchronized (this) {
                if (transaction == login.id && !closed && failure.invalidGrant()) retryClient = client;
            }
            finishError(login.id, "OpenAI 未接受授权请求，请重新登录");
        } catch (IOException | RuntimeException failure) {
            finishError(login.id, "ChatGPT 授权校验未完成，请重新登录");
        }
    }

    private synchronized void finishError(long id, String detail) {
        if (transaction == id && !closed) update("error", detail);
    }

    private void loadModels() throws IOException {
        storeModels(protocol.models(accessToken(owner)));
    }

    private void storeModels(JsonArray catalog) throws IOException {
        JsonObject next = saved.deepCopy();
        next.add("models", catalog);
        vault.write(next);
        saved = next;
    }

    private JsonObject logout() {
        endLogin();
        String refresh = text(saved, "refresh"), client = text(saved, "client");
        boolean cleared = true;
        try {
            invalidateSession("");
        } catch (IOException unavailable) {
            cleared = false;
        }
        boolean revoked = protocol.revoke(client, refresh);
        if (!cleared) {
            try {
                vault.write(saved);
                cleared = true;
            } catch (IOException unavailable) {
                // Keep the in-memory session closed and expose the storage failure in Settings.
            }
        }
        String detail = refresh.isEmpty() || revoked ? "已断开 ChatGPT"
                : "本机已断开，OpenAI 撤销未确认。可在 ChatGPT 设置内断开应用。";
        if (!cleared) detail = "当前会话已停止，但凭据文件无法更新。请修复存储并再次断开。";
        update(cleared ? restingPhase() : "storage_error", detail);
        JsonObject result = status();
        result.addProperty("remoteRevoked", revoked && cleared);
        return result;
    }

    private void invalidateSession(String detail) throws IOException {
        JsonObject next = saved.deepCopy();
        for (String name : new String[]{"access", "refresh", "scope", "expiry", "earliestRefresh", "models", "planDenied", "sessionTag"})
            next.remove(name);
        saved = next;
        session++;
        refreshRetryAt = 0;
        update(restingPhase(), detail);
        if (next.has("host")) vault.write(next);
    }

    private JsonObject status() {
        JsonObject result = new JsonObject();
        result.addProperty("enabled", vault != null && !closed);
        result.addProperty("connected", connected());
        result.addProperty("status", phase.equals("connected") ? restingPhase() : phase);
        result.addProperty("message", message);
        result.addProperty("email", text(saved, "email"));
        result.addProperty("expiresAt", integer(saved, "expiry") * 1000);
        result.addProperty("callbackPort", port);
        result.add("models", models());
        return result;
    }

    private String restingPhase() {
        if (WzChatGptProtocol.hasPlan(text(saved, "scope")) && connected()) return "connected";
        if (saved.has("planDenied")) return "plan_denied";
        return saved.has("client") ? "signed_out" : "idle";
    }

    private void update(String phase, String detail) {
        this.phase = phase;
        message = detail;
        changed();
    }

    private void changed() {
        try {
            onChange.run();
        } catch (RuntimeException ignored) {
            // A notification never invalidates a committed account state change.
        }
    }

    private void endLogin() {
        transaction++;
        Pending previous = pending;
        pending = null;
        if (previous != null) {
            previous.deadline.cancel(false);
            previous.listener.stop(0);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        endLogin();
        session++;
        saved = new JsonObject();
        tasks.shutdownNow();
    }

    private static void applyTokens(JsonObject response, JsonObject output, String retainedScope) throws IOException {
        String access = text(response, "access_token"), refresh = text(response, "refresh_token");
        String scope = response.has("scope") ? text(response, "scope") : retainedScope;
        long lifetime = integer(response, "expires_in");
        if (!"Bearer".equalsIgnoreCase(text(response, "token_type")) || !access.matches("[A-Za-z0-9._~+/=-]{1,32768}")
                || refresh.length() > 32768 || lifetime < 1 || lifetime > 86400 || scope.length() > 4096) throw new IOException();
        output.addProperty("access", access);
        output.addProperty("refresh", refresh);
        output.addProperty("scope", scope);
        output.addProperty("expiry", now() + lifetime);
        output.addProperty("earliestRefresh", integer(response, "earliest_refresh_at"));
    }

    private static Map<String, String> callbackQuery(String raw) {
        if (raw == null || raw.length() > 16384) throw new IllegalArgumentException();
        Map<String, String> result = new HashMap<>();
        for (String pair : raw.split("&")) {
            int index = pair.indexOf('=');
            if (index < 1 || result.putIfAbsent(URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8)) != null) throw new IllegalArgumentException();
        }
        return result;
    }

    private static void reply(HttpExchange exchange, int status, String detail) throws IOException {
        var response = exchange.getResponseHeaders();
        response.set("Content-Type", "text/plain; charset=utf-8");
        response.set("Cache-Control", "no-store");
        response.set("Referrer-Policy", "no-referrer");
        response.set("X-Content-Type-Options", "nosniff");
        response.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        byte[] body = detail.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return BASE64URL.encodeToString(bytes);
    }

    private static String challenge(String verifier) {
        try {
            return BASE64URL.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static final class Pending {
        private final long id;
        private final String client, subject, redirect, verifier, state, nonce;
        private final HttpServer listener;
        private ScheduledFuture<?> deadline;

        private Pending(long id, String client, String subject, String redirect, String verifier, String state, String nonce, HttpServer listener) {
            this.id = id;
            this.client = client;
            this.subject = subject;
            this.redirect = redirect;
            this.verifier = verifier;
            this.state = state;
            this.nonce = nonce;
            this.listener = listener;
        }
    }
}
