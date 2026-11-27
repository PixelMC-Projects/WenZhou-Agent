package com.pixelmc.manager.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.net.ssl.HttpsURLConnection;

/** Each authorized bridge call is short. Upstream generation and settlement outlive the viewer. */
final class WzTerraGateway implements AutoCloseable {
    private static final int MAX_PAYLOAD = 700_000;
    private static final int MAX_STREAM = 4 * 1024 * 1024;
    private static final int MAX_OUTPUT = 8192;
    private final WzTerraLedger ledger;
    private final WzTerraSettlement settlements;
    private final WzProjectBilling billing;
    private final BooleanSupplier available;
    private final Supplier<HttpsURLConnection> connection;
    private final ExecutorService workers =
            Executors.newFixedThreadPool(
                    4,
                    r -> {
                        Thread thread = new Thread(r, "Manager-Terra");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final Map<UUID, Job> jobs = new HashMap<>();
    private final ScheduledExecutorService retention =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread thread = new Thread(r, "Manager-Terra-Retention");
                        thread.setDaemon(true);
                        return thread;
                    });

    WzTerraGateway(
            WzTerraLedger ledger,
            BooleanSupplier available,
            Supplier<HttpsURLConnection> connection) {
        this(ledger, null, available, connection);
    }

    WzTerraGateway(
            WzTerraLedger ledger,
            Path data,
            BooleanSupplier available,
            Supplier<HttpsURLConnection> connection) {
        this(
                ledger,
                data,
                available,
                connection,
                new WzProjectBilling(data, () -> "initial", () -> {}));
    }

    WzTerraGateway(
            WzTerraLedger ledger,
            Path data,
            BooleanSupplier available,
            Supplier<HttpsURLConnection> connection,
            WzProjectBilling billing) {
        this.ledger = ledger;
        this.billing = billing;
        this.settlements = new WzTerraSettlement(ledger, data);
        this.available = available;
        this.connection = connection;
        retention.scheduleWithFixedDelay(this::maintain, 0, 30, TimeUnit.SECONDS);
    }

    JsonObject bridge(String instance, UUID player, String op, JsonObject in) throws Exception {
        if (op.equals("terra.view")) {
            var account = ledger.account(player);
            var result = new JsonObject();
            boolean granted = account.get("granted").getAsBoolean();
            result.addProperty("granted", granted);
            boolean blocked = granted && billing.blocked();
            result.addProperty("available", granted && !blocked && available.getAsBoolean());
            if (granted) {
                result.addProperty("billingBlocked", blocked);
                result.add("terraQuota", publicQuota(account));
                result.add("effort", ledger.settings().get("effort"));
            }
            return result;
        }
        UUID operation = UUID.fromString(Wire.text(in, "operation", 36));
        if (op.equals("terra.start")) {
            JsonObject body = playerBody(in.getAsJsonObject("payload"));
            synchronized (jobs) {
                var existing = jobs.get(operation);
                if (existing != null && !existing.matches(instance, player)) return error("请求标识无效");
                if (existing == null) {
                    if (billing.blocked()) {
                        if (ledger.hasReceipt(operation)) return error("该 Terra 请求已登记");
                        return billingError();
                    }
                    if (!available.getAsBoolean()) return error("Terra 服务暂不可用，请选择其他模型");
                    jobs.values()
                            .removeIf(
                                    previous ->
                                            previous.delivered
                                                    && previous.matches(instance, player));
                    long active = jobs.values().stream().filter(j -> !j.done).count();
                    if (active >= 4 || jobs.size() >= 128) return error("Terra 服务繁忙，请稍后再试");
                    if (jobs.values().stream().anyMatch(j -> !j.done && j.player.equals(player)))
                        return error("你已有正在处理的 Terra 请求");
                    var job = new Job(instance, player);
                    jobs.put(operation, job);
                    try {
                        workers.execute(() -> run(job, operation, body));
                    } catch (RejectedExecutionException stopped) {
                        jobs.remove(operation);
                        return error("Terra 服务已停止");
                    }
                }
            }
            var result = new JsonObject();
            result.addProperty("accepted", true);
            return result;
        }
        if (!op.equals("terra.poll")) throw new IllegalArgumentException("未知 Terra 操作");
        Job job;
        synchronized (jobs) {
            job = jobs.get(operation);
        }
        if (job == null || !job.matches(instance, player)) return error("本次 Terra 请求已过期");
        int cursor = (int) Wire.number(in, "cursor", 0, 0, 65536);
        return job.poll(cursor);
    }

    private void maintain() {
        synchronized (jobs) {
            long now = System.currentTimeMillis();
            jobs.values().removeIf(job -> job.done && now - job.finishedAt > 90000);
        }
        settlements.replay();
    }

    private static JsonObject publicQuota(JsonObject account) {
        var result = new JsonObject();
        for (String field :
                List.of("enabled", "unlimited", "remainingPercent", "exhausted", "resetAt"))
            result.add(field, account.get(field));
        return result;
    }

    static JsonObject playerBody(JsonObject in) {
        if (in == null
                || !(in.get("input") instanceof JsonArray input)
                || input.isEmpty()
                || input.size() > 1024
                || in.toString().getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD)
            throw new IllegalArgumentException("Terra 上下文超出支持范围，请新开话题");
        // Accept only text turns and artifacts from the local function loop.
        for (var entry : input) {
            JsonObject item = entry.getAsJsonObject();
            String type = item.has("type") ? Wire.text(item, "type", 32) : "message";
            if (!Set.of("message", "reasoning", "function_call", "function_call_output")
                    .contains(type)) throw new IllegalArgumentException("Terra 不支持此输入");
            if (type.equals("message") && item.get("content") instanceof JsonArray parts)
                for (var part : parts) {
                    String kind = Wire.text(part.getAsJsonObject(), "type", 24);
                    if (!Set.of("input_text", "output_text", "refusal").contains(kind))
                        throw new IllegalArgumentException("Terra 仅支持文本");
                }
        }
        var result = new JsonObject();
        result.addProperty("model", "gpt-6.1-terra");
        result.addProperty("stream", true);
        result.addProperty("store", false);
        result.add("input", input);
        if (in.has("instructions"))
            result.addProperty("instructions", Wire.text(in, "instructions", 8192));
        var include = new JsonArray();
        include.add("reasoning.encrypted_content");
        result.add("include", include);
        if (in.has("tools")) {
            var namespaces = in.getAsJsonArray("tools");
            if (namespaces.size() != 1) throw new IllegalArgumentException("无效的问舟工具集");
            var namespace = namespaces.get(0).getAsJsonObject();
            if (!"namespace".equals(Wire.text(namespace, "type", 32))
                    || !"wenzhou".equals(Wire.text(namespace, "name", 32)))
                throw new IllegalArgumentException("无效的问舟工具集");
            var functions = Wire.array(namespace, "tools", 128);
            for (var function : functions)
                if (!"function".equals(Wire.text(function.getAsJsonObject(), "type", 32)))
                    throw new IllegalArgumentException("仅允许问舟本地工具");
            result.add("tools", namespaces);
        }
        return result;
    }

    private void run(Job job, UUID operation, JsonObject body) {
        boolean reserved = false;
        boolean dispatched = false;
        Long knownCost = null;
        HttpsURLConnection http = null;
        String failure = "";
        boolean safeFallback = false;
        JsonObject quota = null;
        try {
            var attempt = billing.attempt();
            if (billing.blocked()) {
                if (ledger.hasReceipt(operation)) throw new IllegalStateException("该 Terra 请求已登记");
                safeFallback = true;
                throw new IllegalStateException(WzProjectBilling.MESSAGE);
            }
            http = connection.get();
            // UTF-8 bytes conservatively bound ordinary text tokens, with headroom for message
            // framing.
            long bound =
                    body.toString().getBytes(StandardCharsets.UTF_8).length
                            + 2048L
                            + 16L * body.getAsJsonArray("input").size();
            var policy = ledger.reserve(job.player, operation, bound, MAX_OUTPUT);
            reserved = true;
            var reason = new JsonObject();
            reason.add("effort", policy.get("effort"));
            body.add("reasoning", reason);
            body.add("service_tier", policy.get("service_tier"));
            body.add("max_output_tokens", policy.get("max_output_tokens"));
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            http.setRequestProperty("Accept", "text/event-stream");
            http.setFixedLengthStreamingMode(bytes.length);
            // A concurrent request can confirm a refusal while this one waits for its reservation.
            if (billing.blocked()) {
                knownCost = 0L;
                safeFallback = true;
                throw new IllegalStateException(WzProjectBilling.MESSAGE);
            }
            if (!billing.attempt().equals(attempt)) {
                knownCost = 0L;
                throw new IllegalStateException("OpenAI 项目配置已变化，请重新发送");
            }
            try (var out = http.getOutputStream()) {
                dispatched = true;
                out.write(bytes);
            }
            int code = http.getResponseCode();
            if (code != 200) {
                if (code >= 400 && code < 500) knownCost = 0L;
                if (WzProjectBilling.httpRefusal(code, http)) {
                    billing.refuse(attempt);
                    safeFallback = true;
                    throw new IllegalStateException(WzProjectBilling.MESSAGE);
                }
                throw WenzhouOpenAi.upstream(code);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(105);
            try (var reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    new LimitedInput(http.getInputStream()),
                                    StandardCharsets.UTF_8))) {
                var data = new StringBuilder();
                for (String line; (line = reader.readLine()) != null; ) {
                    if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0)
                        throw new IOException();
                    if (line.startsWith("data:")) {
                        if (!data.isEmpty()) data.append('\n');
                        data.append(line.substring(5).stripLeading());
                    } else if (line.isEmpty() && !data.isEmpty()) {
                        String raw = data.toString();
                        data.setLength(0);
                        if (raw.equals("[DONE]")) break;
                        var event = JsonParser.parseString(raw).getAsJsonObject();
                        String type = Wire.text(event, "type", 100);
                        JsonObject failedResponse =
                                event.get("response") instanceof JsonObject r ? r : null;
                        JsonObject error = null;
                        if (type.equals("error")) {
                            error = event.get("error") instanceof JsonObject inner ? inner : event;
                        } else if (type.equals("response.failed")
                                && failedResponse != null
                                && failedResponse.get("error") instanceof JsonObject inner) {
                            error = inner;
                        }
                        if (WzProjectBilling.refusal(error)) {
                            billing.refuse(attempt);
                            if (failedResponse != null
                                    && failedResponse.get("usage") instanceof JsonObject usage) {
                                if (count(usage, "input_tokens") == 0
                                        && count(usage, "output_tokens") == 0) {
                                    knownCost = 0L;
                                    safeFallback =
                                            job.events.isEmpty() && emptyOutput(failedResponse);
                                } else {
                                    knownCost = price(failedResponse);
                                }
                            }
                            if (knownCost == null) throw new IOException();
                            if (!safeFallback) {
                                var receipt = new JsonObject();
                                if (failedResponse.has("id"))
                                    receipt.add("id", failedResponse.get("id"));
                                receipt.add("usage", failedResponse.get("usage"));
                                var terminal = new JsonObject();
                                terminal.addProperty("type", "response.failed");
                                terminal.add("response", receipt);
                                job.append(terminal);
                            }
                            throw new IllegalStateException(WzProjectBilling.MESSAGE);
                        }
                        if (Set.of("response.completed", "response.incomplete", "response.failed")
                                .contains(type)) {
                            var response = event.getAsJsonObject("response");
                            knownCost = price(response);
                            job.append(event);
                            break;
                        }
                        if (type.equals("error")) throw new IOException();
                        if (Set.of(
                                        "response.output_text.delta",
                                        "response.output_item.added",
                                        "response.output_item.done")
                                .contains(type)) job.append(event);
                    }
                }
            }
            if (knownCost == null) throw new IOException();
        } catch (WenzhouOpenAi.Failure | IllegalStateException | SecurityException expected) {
            failure = expected.getMessage();
        } catch (Exception interrupted) {
            failure =
                    dispatched && knownCost == null
                            ? "Terra 答复中断，用量尚未确认，本次额度暂时保留"
                            : "Terra 答复中断，请稍后重试";
        } finally {
            if (http != null) http.disconnect();
            if (reserved && (knownCost != null || !dispatched)) {
                try {
                    settlements.settle(job.player, operation, knownCost == null ? 0 : knownCost);
                } catch (Exception failed) {
                    safeFallback = false;
                    failure = "Terra 用量结算待核实，本次额度已保留";
                }
            }
            try {
                quota = publicQuota(ledger.account(job.player));
            } catch (Exception unavailable) {
                /* next status refresh */
            }
            job.finish(failure, knownCost, quota, safeFallback);
        }
    }

    private static boolean emptyOutput(JsonObject response) {
        return !response.has("output")
                || (response.get("output") instanceof JsonArray output && output.isEmpty());
    }

    static long price(JsonObject response) {
        var usage = response.getAsJsonObject("usage");
        var details = usage.getAsJsonObject("input_tokens_details");
        String tier = Wire.text(response, "service_tier", 24);
        if (!Set.of("default", "fast", "priority").contains(tier))
            throw new IllegalArgumentException("未知的实际服务档位");
        return WzTerraLedger.price(
                count(usage, "input_tokens"),
                count(details, "cached_tokens"),
                count(details, "cache_write_tokens"),
                count(usage, "output_tokens"),
                !tier.equals("default"));
    }

    private static long count(JsonObject data, String field) {
        if (data == null || !(data.get(field) instanceof JsonPrimitive n) || !n.isNumber())
            throw new IllegalArgumentException("Terra 用量回执缺少计量项");
        return n.getAsBigDecimal().longValueExact();
    }

    private static JsonObject error(String message) {
        var result = new JsonObject();
        result.addProperty("error", message);
        return result;
    }

    private static JsonObject billingError() {
        var result = error(WzProjectBilling.MESSAGE);
        result.addProperty("code", WzProjectBilling.CODE);
        return result;
    }

    // Bound the decoded stream even for an upstream line with no terminator.
    private static final class LimitedInput extends FilterInputStream {
        private int remaining = MAX_STREAM;

        LimitedInput(InputStream input) {
            super(input);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, Math.min(len, remaining + 1));
            if (n > remaining) throw new IOException();
            if (n > 0) remaining -= n;
            return n;
        }
    }

    private static final class Job {
        final String instance;
        final UUID player;
        final List<JsonObject> events = new ArrayList<>();
        volatile boolean done;
        volatile boolean delivered;
        long finishedAt;
        String error;
        boolean safeFallback;
        Long cost;
        JsonObject quota;

        Job(String instance, UUID player) {
            this.instance = instance;
            this.player = player;
        }

        boolean matches(String instance, UUID player) {
            return this.instance.equals(instance) && this.player.equals(player);
        }

        synchronized void append(JsonObject event) {
            if (events.size() >= 65535) throw new IllegalArgumentException("Terra 回包过大");
            events.add(event);
        }

        synchronized void finish(
                String failure, Long cost, JsonObject quota, boolean safeFallback) {
            this.safeFallback = safeFallback;
            this.error = failure == null ? "Terra 服务暂不可用" : failure;
            this.cost = cost;
            this.quota = quota;
            finishedAt = System.currentTimeMillis();
            done = true;
        }

        synchronized JsonObject poll(int cursor) {
            if (cursor > events.size()) return WzTerraGateway.error("Terra 接收进度已失效");
            var result = new JsonObject();
            int end = Math.min(events.size(), cursor + 512);
            var page = new JsonArray();
            for (int i = cursor; i < end; i++) page.add(events.get(i));
            result.add("events", page);
            result.addProperty("cursor", end);
            boolean complete = done && end == events.size();
            result.addProperty("done", complete);
            if (complete) {
                delivered = true;
                if (!error.isEmpty()) result.addProperty("error", error);
                if (safeFallback) result.addProperty("code", WzProjectBilling.CODE);
                if (cost != null) result.addProperty("nanoUsd", cost);
                if (quota != null) result.add("terraQuota", quota);
            }
            return result;
        }
    }

    public void close() {
        retention.shutdownNow();
        workers.shutdown();
    }
}
