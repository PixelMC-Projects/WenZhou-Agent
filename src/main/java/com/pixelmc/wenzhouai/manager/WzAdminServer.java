package com.pixelmc.wenzhouai.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Same-origin administration through Manager's current session, including revocations. */
public final class WzAdminServer implements AutoCloseable {
    public static final String PREFIX = "/extensions/wenzhouai/";
    private static final int MAX_BODY = 262144;
    private final HttpServer listener;
    private final ThreadPoolExecutor executor;
    private final HttpClient manager =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(4))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private final URI managerSession;
    private final String origin;
    private final UUID ownerPlayerUuid;
    private final Backend backend;

    @FunctionalInterface
    public interface Backend {
        JsonObject request(String operation, JsonObject input, UUID operator) throws Exception;
    }

    /** A missing file keeps the private listener disabled. */
    public static WzAdminServer start(Path configDirectory, Backend backend) throws IOException {
        Path file = configDirectory.resolve("wenzhouai-admin.properties");
        if (!Files.isRegularFile(file)) return null;
        Properties config = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            config.load(reader);
        }
        URI origin = root(config.getProperty("publicOrigin"));
        URI manager = root(config.getProperty("managerBase"));
        if (!"https".equalsIgnoreCase(origin.getScheme()))
            throw new IllegalArgumentException("问舟管理页要求使用 HTTPS");
        if ("http".equals(manager.getScheme())
                && !java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(manager.getHost()))
            throw new IllegalArgumentException("非回环 Manager 地址必须使用 HTTPS");
        int port = Integer.parseInt(config.getProperty("listenPort", "18727"));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("问舟管理端口无效");
        String owner = config.getProperty("ownerPlayerUuid", "").strip();
        if (owner.isEmpty()) throw new IllegalArgumentException("问舟管理端缺少服主 UUID 配置");
        UUID ownerId = UUID.fromString(owner);
        if (!ownerId.toString().equalsIgnoreCase(owner))
            throw new IllegalArgumentException("问舟管理端服主 UUID 无效");
        return new WzAdminServer(port, origin, manager, ownerId, backend);
    }

    private WzAdminServer(
            int port, URI publicOrigin, URI managerBase, UUID ownerPlayerUuid, Backend backend)
            throws IOException {
        this.origin = publicOrigin.getScheme() + "://" + publicOrigin.getRawAuthority();
        this.managerSession = managerBase.resolve("/api/me");
        this.ownerPlayerUuid = ownerPlayerUuid;
        this.backend = backend;
        listener =
                HttpServer.create(
                        new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 24);
        executor =
                new ThreadPoolExecutor(
                        4,
                        4,
                        0,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(24),
                        job -> {
                            Thread thread = new Thread(job, "wenzhouai-admin");
                            thread.setDaemon(true);
                            return thread;
                        });
        listener.setExecutor(executor);
        listener.createContext(PREFIX, this::handle);
        listener.start();
    }

    private static URI root(String value) {
        if (value == null) throw new IllegalArgumentException("问舟管理端缺少 URL 配置");
        URI result = URI.create(value.strip());
        if (result.getHost() == null
                || result.getRawUserInfo() != null
                || result.getRawQuery() != null
                || result.getRawFragment() != null
                || (result.getRawPath() != null
                        && !result.getRawPath().isEmpty()
                        && !"/".equals(result.getRawPath()))
                || !("https".equals(result.getScheme()) || "http".equals(result.getScheme())))
            throw new IllegalArgumentException("问舟管理端 URL 必须是 HTTP(S) 根地址");
        return result;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            var headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-store");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("X-Frame-Options", "DENY");
            headers.set(
                    "Content-Security-Policy",
                    "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:;"
                            + " frame-ancestors 'none'; object-src 'none'; base-uri 'none'");
            String rawPath = exchange.getRequestURI().getRawPath();
            if (!rawPath.startsWith(PREFIX)) throw new Failure(404, "页面不存在");
            String path = rawPath.substring(PREFIX.length());
            if (path.startsWith("api/")) {
                api(exchange, path.substring(4));
            } else if ("GET".equals(exchange.getRequestMethod())) {
                legacyPage(exchange, path);
            } else {
                error(exchange, 405, "请求方法不受支持");
            }
        } catch (Failure failure) {
            error(exchange, failure.status, failure.getMessage());
        } catch (com.google.gson.JsonParseException | IllegalArgumentException failure) {
            error(exchange, 400, "请求数据无效");
        } catch (SecurityException failure) {
            error(exchange, 403, "当前权限不允许此操作");
        } catch (IllegalStateException failure) {
            error(exchange, 409, "操作未完成，请刷新后重试");
        } catch (Exception failure) {
            error(exchange, 503, "问舟管理服务暂不可用");
        } finally {
            exchange.close();
        }
    }

    private void api(HttpExchange exchange, String operation) throws Exception {
        if (!"POST".equals(exchange.getRequestMethod())) throw new Failure(405, "请求方法不受支持");
        if (!origin.equals(exchange.getRequestHeaders().getFirst("Origin")))
            throw new Failure(403, "请求来源无效");
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !"application/json".equalsIgnoreCase(type.split(";", 2)[0].strip()))
            throw new Failure(415, "请求必须使用 JSON");
        if (!operation.matches("[a-z][a-z.]{0,31}")) throw new Failure(404, "管理接口不存在");
        JsonObject session = session(exchange);
        UUID operator = UUID.fromString(session.get("player").getAsString());
        if (!ownerPlayerUuid.equals(operator)) throw new Failure(403, "请使用已配置的服主管理账号");
        String csrf = exchange.getRequestHeaders().getFirst("X-CSRF-Token");
        if (csrf == null
                || !MessageDigest.isEqual(
                        csrf.getBytes(StandardCharsets.UTF_8),
                        session.get("csrf").getAsString().getBytes(StandardCharsets.UTF_8)))
            throw new Failure(403, "会话验证失败，请重新登录");
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) throw new Failure(413, "请求内容超出限制");
        var value = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        if (!value.isJsonObject()) throw new Failure(400, "请求数据无效");
        JsonObject response = backend.request(operation, value.getAsJsonObject(), operator);
        send(
                exchange,
                200,
                "application/json; charset=utf-8",
                response.toString().getBytes(StandardCharsets.UTF_8));
    }

    private JsonObject session(HttpExchange exchange) throws Exception {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null || cookie.isBlank()) throw new Failure(401, "请登录 PixelMC Manager");
        // Only the Manager session cookie leaves this handler. Never persist or print it.
        String sessionCookie =
                java.util.Arrays.stream(cookie.split(";"))
                        .map(String::strip)
                        .filter(pair -> pair.matches("MTSESSION_[0-9]+=[A-Za-z0-9_\\-\"=]+"))
                        .reduce((left, right) -> left + "; " + right)
                        .orElseThrow(() -> new Failure(401, "请登录 PixelMC Manager"));
        HttpRequest request =
                HttpRequest.newBuilder(managerSession)
                        .timeout(Duration.ofSeconds(5))
                        .header("Cookie", sessionCookie)
                        .GET()
                        .build();
        var response = manager.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 || response.statusCode() == 403)
            throw new Failure(response.statusCode(), "请重新登录或检查管理授权");
        if (response.statusCode() != 200) throw new Failure(503, "Manager 会话服务暂不可用");
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static void legacyPage(HttpExchange exchange, String path) throws IOException, Failure {
        if (path.equals("assets/sidebar.js")) {
            send(exchange, 200, "text/javascript; charset=utf-8", new byte[0]);
            return;
        }
        if (!path.isEmpty() && !path.equals("index.html")) throw new Failure(404, "页面不存在");

        String destination = "wenzhou-players";
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String parameter : query.split("&")) {
                if (!parameter.startsWith("page=")) continue;
                destination = switch (parameter.substring("page=".length())) {
                    case "chats" -> "wenzhou-chats";
                    case "service" -> "wenzhou-service";
                    default -> "wenzhou-players";
                };
                break;
            }
        }
        exchange.getResponseHeaders().set("Location", "/#/" + destination);
        exchange.sendResponseHeaders(302, -1);
    }

    private static void error(HttpExchange exchange, int status, String message)
            throws IOException {
        JsonObject output = new JsonObject();
        output.addProperty("error", message);
        send(
                exchange,
                status,
                "application/json; charset=utf-8",
                output.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] payload)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
    }

    @Override
    public void close() {
        listener.stop(0);
        executor.shutdownNow();
    }

    private static final class Failure extends Exception {
        private final int status;

        private Failure(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
