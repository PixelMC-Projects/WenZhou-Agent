package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class WzChatGptProtocol {
    static final String ISSUER = "https://auth.openai.com";
    static final String RESOURCE = "https://api.openai.com/v1";
    static final String DYNAMIC_CLIENT = "dynamic_agent_client";
    private static final URI TOKEN = URI.create(ISSUER + "/api/accounts/oauth/token");
    private static final URI JWKS = URI.create(ISSUER + "/.well-known/jwks.json");
    private static final URI DISCOVERY = URI.create(ISSUER + "/.well-known/openid-configuration");
    private static final String PLAN_SCOPE = "chatgpt.tokens.use.direct";
    private static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");
    private final HttpClient http;
    private JsonArray keys = new JsonArray();
    private long keysUntil;
    private long lastKeyFetch;

    WzChatGptProtocol(String proxy) {
        InetSocketAddress address = null;
        if (!proxy.isBlank()) {
            URI uri = URI.create(proxy);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !Set.of("", "/").contains(uri.getRawPath()))
                throw new IllegalArgumentException("ChatGPT 代理必须是无账号密码的 HTTP 代理地址");
            address = InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort());
        }
        http = HttpClient.newBuilder().proxy(ProxySelector.of(address))
                .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    HttpClient client() {
        return http;
    }

    static String authorize(Map<String, String> params) {
        return ISSUER + "/api/accounts/authorize?" + form(params);
    }

    static boolean issuedClient(String value) {
        return value.matches("oaiapp_[A-Za-z0-9_-]{1,128}");
    }

    JsonObject exchange(String client, String code, String verifier, String redirectUri) throws IOException {
        return token(Map.of("grant_type", "authorization_code", "client_id", client, "code", code,
                "code_verifier", verifier, "redirect_uri", redirectUri, "resource", RESOURCE));
    }

    JsonObject refresh(String client, String refresh) throws IOException {
        return token(Map.of("grant_type", "refresh_token", "client_id", client,
                "refresh_token", refresh, "resource", RESOURCE));
    }

    private JsonObject token(Map<String, String> params) throws IOException {
        var response = send(post(TOKEN, params));
        if (response.statusCode() == 200) return object(response.body());
        String code = "";
        try {
            code = text(object(response.body()), "error");
        } catch (IOException ignored) {
            // The response may be an upstream error page.
        }
        throw new OAuthFailure(response.statusCode(), code);
    }

    boolean revoke(String client, String refresh) {
        if (refresh.isEmpty()) return false;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                var configuration = fetch(DISCOVERY);
                URI endpoint = URI.create(text(configuration, "revocation_endpoint"));
                if (!ISSUER.equals(text(configuration, "issuer")) || !"https".equals(endpoint.getScheme())
                        || !"auth.openai.com".equals(endpoint.getHost()) || endpoint.getPort() != -1
                        || endpoint.getRawUserInfo() != null || endpoint.getRawFragment() != null
                        || endpoint.getRawQuery() != null) return false;
                var response = send(post(endpoint, Map.of("token", refresh,
                        "token_type_hint", "refresh_token", "client_id", client)));
                if (response.statusCode() == 200) return true;
                if (response.statusCode() < 500) return false;
            } catch (IOException | IllegalArgumentException unavailable) {
                if (Thread.currentThread().isInterrupted()) return false;
            }
            if (attempt == 0) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    JsonArray models(String token) throws IOException {
        var req = request(URI.create(RESOURCE + "/models")).header("Authorization", "Bearer " + token).GET();
        var response = send(req.build());
        if (response.statusCode() != 200) throw new IOException("无法读取当前账号的模型列表");
        if (!(object(response.body()).get("models") instanceof JsonArray entries))
            throw new IOException("账号模型列表格式无效");
        JsonArray list = new JsonArray();
        Set<String> seen = new HashSet<>();
        entries.forEach(entry -> {
            if (!(entry instanceof JsonObject model) || !"list".equals(text(model, "visibility"))) return;
            String id = text(model, "slug"), label = text(model, "display_name");
            if (!id.matches("[A-Za-z0-9._-]{1,96}") || "deepseek".equalsIgnoreCase(id)
                    || label.isBlank() || label.length() > 128 || !seen.add(id)) return;
            JsonObject item = new JsonObject();
            item.addProperty("id", id);
            item.addProperty("label", label);
            JsonArray efforts = new JsonArray();
            if (model.get("supported_reasoning_levels") instanceof JsonArray levels) {
                Set<String> added = new HashSet<>();
                for (var level : levels) {
                    String effort = "";
                    if (level instanceof JsonObject levelInfo) effort = text(levelInfo, "effort");
                    else if (level instanceof JsonPrimitive value && value.isString()) effort = value.getAsString();
                    if (EFFORTS.contains(effort) && added.add(effort)) efforts.add(effort);
                }
            }
            item.add("efforts", efforts);
            list.add(item);
        });
        return list;
    }

    synchronized JsonObject verify(String jwt, String client, String nonce) throws IOException {
        try {
            String[] segments = jwt.split("\\.", -1);
            if (segments.length != 3 || jwt.length() > 32768) throw new GeneralSecurityException();
            Base64.Decoder decoder = Base64.getUrlDecoder();
            JsonObject header = object(new String(decoder.decode(segments[0]), StandardCharsets.UTF_8));
            String kid = text(header, "kid");
            if (!"RS256".equals(text(header, "alg")) || kid.isBlank() || header.has("crit"))
                throw new GeneralSecurityException();
            long now = Instant.now().getEpochSecond();
            if (now >= keysUntil) loadKeys(now);
            JsonObject key = key(kid);
            if (key == null && now - lastKeyFetch >= 30) {
                loadKeys(now);
                key = key(kid);
            }
            if (key == null) throw new GeneralSecurityException();
            var modulus = new BigInteger(1, decoder.decode(text(key, "n")));
            var exponent = new BigInteger(1, decoder.decode(text(key, "e")));
            if (modulus.bitLength() < 2048) throw new GeneralSecurityException();
            var publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update((segments[0] + "." + segments[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(decoder.decode(segments[2]))) throw new GeneralSecurityException();
            JsonObject claims = object(new String(decoder.decode(segments[1]), StandardCharsets.UTF_8));
            String subject = text(claims, "sub"), party = text(claims, "azp");
            long expiry = integer(claims, "exp"), issued = integer(claims, "iat");
            if (!ISSUER.equals(text(claims, "iss")) || !audience(claims, client)
                    || (!party.isEmpty() && !client.equals(party)) || subject.isBlank() || subject.length() > 512
                    || expiry < now - 5 || issued <= 0 || issued > now + 5 || issued >= expiry
                    || (claims.has("nbf") && integer(claims, "nbf") > now + 5)
                    || (nonce != null && !nonce.equals(text(claims, "nonce"))))
                throw new GeneralSecurityException();
            return claims;
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new IOException("OpenAI 账号身份验证失败");
        }
    }

    private static boolean audience(JsonObject claims, String client) {
        if (claims.get("aud") instanceof JsonArray list) {
            if (list.size() > 1 && !client.equals(text(claims, "azp"))) return false;
            for (var entry : list) if (entry instanceof JsonPrimitive value && value.isString()
                    && client.equals(value.getAsString())) return true;
            return false;
        }
        return client.equals(text(claims, "aud"));
    }

    private void loadKeys(long now) throws IOException {
        lastKeyFetch = now;
        if (!(fetch(JWKS).get("keys") instanceof JsonArray list)) throw new IOException("无法验证 OpenAI 账号身份");
        keys = list;
        keysUntil = now + 3600;
    }

    private JsonObject key(String kid) throws IOException {
        JsonObject match = null;
        for (var value : keys) {
            if (!(value instanceof JsonObject key) || !kid.equals(text(key, "kid"))
                    || !"RSA".equals(text(key, "kty")) || key.has("alg") && !"RS256".equals(text(key, "alg"))
                    || key.has("use") && !"sig".equals(text(key, "use"))) continue;
            if (match != null) throw new IOException("OpenAI 签名密钥标识重复");
            match = key;
        }
        return match;
    }

    private JsonObject fetch(URI uri) throws IOException {
        var result = send(request(uri).GET().build());
        if (result.statusCode() != 200) throw new IOException("OpenAI 授权服务暂不可用");
        return object(result.body());
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("OpenAI 请求已中止");
        } catch (IOException failure) {
            throw new IOException("暂时无法连接 OpenAI");
        }
    }

    private static HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
    }

    private static HttpRequest post(URI uri, Map<String, String> parameters) {
        return request(uri).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(parameters))).build();
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    static boolean hasPlan(String scope) {
        var granted = new HashSet<>(java.util.Arrays.asList(scope.split("\\s+")));
        return granted.contains(PLAN_SCOPE) && granted.contains("resource.invoke");
    }

    static String text(JsonObject object, String name) {
        return object.get(name) instanceof JsonPrimitive value && value.isString() ? value.getAsString() : "";
    }

    static long integer(JsonObject object, String name) {
        try {
            return object.get(name) instanceof JsonPrimitive value && value.isNumber()
                    ? value.getAsBigDecimal().longValueExact() : 0;
        } catch (ArithmeticException invalid) {
            return 0;
        }
    }

    static JsonObject object(String input) throws IOException {
        if (input.length() <= 1_048_576) try {
            if (JsonParser.parseString(input) instanceof JsonObject object) return object;
        } catch (com.google.gson.JsonParseException invalid) {
            // Outside messages are never included in errors.
        }
        throw new IOException("OpenAI 数据格式无效");
    }

    static final class OAuthFailure extends IOException {
        private final boolean invalidGrant;

        OAuthFailure(int status, String code) {
            super(status == 429 ? "OpenAI 授权服务暂时限流" : "OpenAI 未接受授权请求");
            invalidGrant = "invalid_grant".equals(code);
        }

        boolean invalidGrant() {
            return invalidGrant;
        }
    }
}
