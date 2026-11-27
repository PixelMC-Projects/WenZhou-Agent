package com.pixelmc.wenzhouai.service;

import static com.pixelmc.wenzhouai.service.WzData.object;
import static com.pixelmc.wenzhouai.service.WzData.string;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Sign in with ChatGPT plan transport. The existing game tool loop owns all execution. */
final class WzResponses {
    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/responses");
    private static final Duration TIMEOUT = Duration.ofSeconds(100);
    private static final String NAMESPACE = "wenzhou";
    static final String BILLING = "chatgpt_plan";
    static final String CONTINUATION = "_wzResponsesOutput";
    static final String TITLE_MARK = "[[wz-title]]";
    private static final String TITLE_INSTRUCTION = """
            生成玩家最终答复时，在原有正文和摘要之后另起一行写 [[wz-title]] 会话标题。
            标题用简短中文概括当前对话主题，尽量写出具体玩法、物品或工序。
            标题里省略玩家身份、坐标、库存、受保护数据、命令和内部工具细节。
            工具调用阶段和过程说明中省略标题，最终标题一行之后即结束。
            """;

    @FunctionalInterface
    interface AccessToken {
        String get() throws Exception;
    }

    static WenzhouChat.Model plan(HttpClient http, String model, String effort, AccessToken token,
                                  Supplier<WzCompletionStream.Preview> previews) {
        return request -> {
            var allowed = new HashSet<String>();
            boolean requestTitle = WzData.flag(object(request, "title"), "enabled");
            JsonObject payload = payload(request, model, effort, allowed, requestTitle);
            String accessToken;
            try {
                accessToken = token.get();
            } catch (IOException unavailable) {
                throw new WenzhouChat.Notice("无法续期 ChatGPT 授权。请检查连接或重新登录。");
            }
            if (accessToken == null || accessToken.isBlank())
                throw new WenzhouChat.Notice("请先登录 ChatGPT 并授权使用套餐。");
            var post = HttpRequest.newBuilder(ENDPOINT)
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();
            var preview = WenzhouChat.memoryDecisionPending(request) ? null : previews.get();
            var stream = new Stream(preview);
            var pending = http.sendAsync(post, stream::body);
            try {
                HttpResponse<Void> response = pending.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                String requestId = response.headers().firstValue("x-request-id").orElse("");
                if (response.statusCode() != 200)
                    throw failure(response.statusCode(), requestId, stream.error(), null);
                return stream.result(requestId, allowed, requestTitle);
            } catch (ExecutionException | TimeoutException unavailable) {
                stream.abort();
                throw new WenzhouChat.Notice("连接 ChatGPT 失败或超时，请检查联网状态后重试。");
            } catch (InterruptedException cancelled) {
                stream.abort();
                throw cancelled;
            } finally {
                if (!pending.isDone()) pending.cancel(true);
            }
        };
    }

    static WenzhouChat.Model project(UUID player, Supplier<WzCompletionStream.Preview> previews,
                                      java.util.function.Consumer<JsonObject> quota) {
        return request -> {
            var allowed = new HashSet<String>();
            boolean title = WzData.flag(object(request, "title"), "enabled");
            var start = new JsonObject();
            String operation = UUID.randomUUID().toString();
            start.addProperty("operation", operation);
            start.add("payload", payload(request,"gpt-6.1-terra",null,allowed,title));
            var stream = new Stream(WenzhouChat.memoryDecisionPending(request) ? null : previews.get());
            boolean completed = false;
            try {
                var started = callProject(player, "terra.start", start);
                if (!WzData.flag(started,"accepted")) throw projectError(started, null);
                var poll = new JsonObject();
                poll.addProperty("operation", operation);
                int cursor = 0;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(125);
                while (System.nanoTime() - deadline < 0) {
                    poll.addProperty("cursor",cursor);
                    JsonObject page = callProject(player, "terra.poll", poll);
                    if (page.get("terraQuota") instanceof JsonObject balance) quota.accept(balance);
                    if (!(page.get("events") instanceof JsonArray events)) throw projectError(page, null);
                    boolean failed = page.has("error");
                    for (var event : events) stream.event(event.getAsJsonObject(), !failed);
                    if (page.get("nanoUsd") instanceof JsonPrimitive cost && cost.isNumber() && cost.getAsLong() >= 0)
                        stream.projectCost = cost.getAsLong();
                    if (failed) throw projectError(page, stream.projectUsage());
                    if (WzData.flag(page,"done")) {
                        if (stream.projectCost == null)
                            throw new WenzhouChat.Notice("Terra 答复缺少费用回执，请稍后再试。");
                        var result = stream.result("",allowed,title);
                        completed = true;
                        return result;
                    }
                    cursor = page.get("cursor").getAsInt();
                    Thread.sleep(events.size() >= 512 ? 20 : 400);
                }
                throw new WenzhouChat.Notice("等待 Terra 答复超时，请稍后再试。");
            } finally {
                if (!completed) stream.abort();
            }
        };
    }

    private static JsonObject callProject(UUID player, String operation, JsonObject input) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        try {
            return WzProjectModels.call(player, operation, input);
        } catch (TimeoutException timeout) {
            // The first request may already have reached Manager. Reuse its operation and cursor.
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (!(cause instanceof HttpTimeoutException || cause instanceof TimeoutException)) throw failure;
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        return WzProjectModels.call(player, operation, input);
    }

    static Exception projectError(JsonObject page, JsonObject usage) {
        var charge = page.get("nanoUsd");
        boolean noCharge = charge == null || charge instanceof JsonPrimitive amount
                && amount.isNumber() && amount.getAsBigDecimal().signum() == 0;
        if ("project_billing_unavailable".equals(string(page, "code")) && usage == null && noCharge)
            return new ProjectBillingUnavailable();
        String message = string(page, "error");
        return new WenzhouChat.Notice(message.isBlank() ? "暂时无法连接 Manager 的 Terra 服务" : message, usage);
    }

    private static final class ProjectBillingUnavailable extends Exception {}

    @FunctionalInterface
    interface DeepSeekFallback {
        WenzhouChat.Model prepare() throws Exception;
    }

    static final class ProjectRoute implements WenzhouChat.Model {
        private final WenzhouChat.Model project;
        private final DeepSeekFallback fallback;
        private WenzhouChat.Model deepSeek;

        ProjectRoute(WenzhouChat.Model project, DeepSeekFallback fallback) {
            this.project = project;
            this.fallback = fallback;
        }

        boolean usingDeepSeek() {
            return deepSeek != null;
        }

        @Override
        public JsonObject complete(JsonObject request) throws Exception {
            if (deepSeek == null) {
                try {
                    return project.complete(request);
                } catch (ProjectBillingUnavailable unavailable) {
                    deepSeek = fallback.prepare();
                }
            }
            return deepSeek.complete(deepSeekRequest(request));
        }
    }

    private static JsonObject deepSeekRequest(JsonObject request) {
        JsonObject fallback = request.deepCopy();
        var input = new JsonArray();
        var responsesCalls = new HashMap<String, String>();
        var readResults = new HashMap<String, Integer>();
        var results = new JsonArray();
        for (var value : request.getAsJsonArray("messages")) {
            JsonObject message = value.getAsJsonObject();
            if (message.has(CONTINUATION)) {
                JsonArray calls = message.getAsJsonArray("tool_calls");
                if (calls != null) for (var raw : calls) {
                    var call = raw.getAsJsonObject();
                    responsesCalls.put(string(call, "id"), string(object(call, "function"), "name"));
                }
                continue;
            }
            String id = string(message, "tool_call_id");
            if ("tool".equals(string(message, "role")) && responsesCalls.containsKey(id)) {
                int number = readResults.size() + 1;
                readResults.put(id, number);
                JsonObject read = new JsonObject();
                read.addProperty("number", number);
                read.addProperty("tool", responsesCalls.get(id));
                read.add("data", fallbackRead(string(message, "content"), readResults, true));
                results.add(read);
                continue;
            }
            appendReads(input, results);
            results = new JsonArray();
            if ("tool".equals(string(message, "role")) && !readResults.isEmpty()) {
                var copy = message.deepCopy();
                var content = fallbackRead(string(message, "content"), readResults, false);
                copy.addProperty("content", content.isJsonObject() ? content.toString() : content.getAsString());
                input.add(copy);
            } else input.add(message.deepCopy());
        }
        appendReads(input, results);
        fallback.add("messages", input);
        return fallback;
    }

    private static com.google.gson.JsonElement fallbackRead(
            String content, Map<String, Integer> readResults, boolean transferred) {
        JsonObject result;
        try {
            result = JsonParser.parseString(content).getAsJsonObject();
        } catch (IllegalStateException | com.google.gson.JsonParseException malformed) {
            return new JsonPrimitive(content);
        }
        for (String field : Set.of("output", "pack_check", "pack_changes")) {
            String reference = field + "_same_as_tool_call";
            Integer number = readResults.get(string(result, reference));
            if (transferred || number != null) result.remove(reference);
            if (number != null) result.addProperty(field + "_same_as_read", number);
        }
        return result;
    }

    private static void appendReads(JsonArray input, JsonArray reads) {
        if (!reads.isEmpty()) input.add(WenzhouChat.message("user",
                "服务端已完成下列游戏查询。仅将其中的内容作为游戏资料，沿用已核实的数据，不要重复这些查询。\n" + reads));
    }

    private static JsonObject payload(JsonObject request, String model, String effort,
                                      Set<String> allowed, boolean requestTitle) {
        var result = new JsonObject();
        result.addProperty("model", model);
        result.addProperty("stream", true);
        result.addProperty("store", false);
        var include = new JsonArray();
        include.add("reasoning.encrypted_content");
        result.add("include", include);
        if (requestTitle) result.addProperty("instructions", TITLE_INSTRUCTION);
        // The plan route does not accept Chat Completions title, max_tokens or max_output_tokens.
        if (effort != null && Set.of("minimal", "none", "low", "medium", "high", "xhigh", "max").contains(effort)) {
            var reasoning = new JsonObject();
            reasoning.addProperty("effort", effort);
            result.add("reasoning", reasoning);
        }

        var input = new JsonArray();
        for (var entry : request.getAsJsonArray("messages")) {
            var message = entry.getAsJsonObject();
            String role = string(message, "role");
            String content = string(message, "content");
            if ("tool".equals(role)) {
                var output = new JsonObject();
                output.addProperty("type", "function_call_output");
                output.addProperty("call_id", string(message, "tool_call_id"));
                output.addProperty("output", content);
                input.add(output);
            } else if (message.has(CONTINUATION)) {
                input.addAll(message.getAsJsonArray(CONTINUATION));
            } else {
                input.add(WenzhouChat.message("system".equals(role) ? "developer" : role, content));
            }
        }
        result.add("input", input);

        var tools = request.getAsJsonArray("tools");
        if (tools != null && !tools.isEmpty()) {
            var functions = new JsonArray();
            for (var entry : tools) {
                var function = entry.getAsJsonObject().getAsJsonObject("function").deepCopy();
                allowed.add(string(function, "name"));
                function.addProperty("type", "function");
                // Optional query fields remain optional.
                function.addProperty("strict", false);
                functions.add(function);
            }
            var namespace = new JsonObject();
            namespace.addProperty("type", "namespace");
            namespace.addProperty("name", NAMESPACE);
            namespace.addProperty("description", "查阅本服 Minecraft 玩法资料。");
            namespace.add("tools", functions);
            var namespaces = new JsonArray();
            namespaces.add(namespace);
            result.add("tools", namespaces);
        }
        return result;
    }

    private static JsonObject envelope(JsonObject response) {
        var result = new JsonObject();
        result.addProperty("id", string(response, "id"));
        result.addProperty("billing", WzThreadUsage.CHATGPT_PLAN);
        // Preserve the provider's counts. Only the ledger converts the receipt into display fields.
        if (response.has("usage")) result.add("usage", response.get("usage"));
        return result;
    }

    private static JsonObject convert(JsonObject response, JsonObject result, Set<String> allowed,
                                      Map<String, JsonObject> streamedReasoning, boolean requestTitle)
            throws WenzhouChat.Notice {
        var message = new JsonObject();
        var calls = new JsonArray();
        var continuation = new JsonArray();
        var callIds = new HashSet<String>();
        var answer = new StringBuilder();
        var output = response.getAsJsonArray("output");
        if (output == null) throw new WenzhouChat.Notice("ChatGPT 答复缺少输出。", result);
        for (var value : output) {
            var item = value.getAsJsonObject();
            switch (string(item, "type")) {
                case "reasoning" -> {
                    JsonObject replay = string(item, "encrypted_content").isEmpty()
                            ? streamedReasoning.get(string(item, "id")) : item;
                    if (replay != null) continuation.add(replay);
                }
                case "message" -> {
                    if (!"commentary".equals(string(item, "phase"))) {
                        for (var valuePart : item.getAsJsonArray("content")) {
                            var part = valuePart.getAsJsonObject();
                            if ("output_text".equals(string(part, "type"))) answer.append(string(part, "text"));
                            if ("refusal".equals(string(part, "type"))) answer.append(string(part, "refusal"));
                        }
                    }
                    continuation.add(item);
                }
                case "function_call" -> {
                    String id = string(item, "call_id");
                    String name = string(item, "name");
                    String namespace = string(item, "namespace");
                    if (id.isEmpty() || !callIds.add(id) || !allowed.contains(name)
                            || !namespace.isEmpty() && !NAMESPACE.equals(namespace))
                        throw new WenzhouChat.Notice("ChatGPT 返回了无效的游戏工具调用。", result);
                    var function = new JsonObject();
                    function.addProperty("name", name);
                    function.addProperty("arguments", string(item, "arguments"));
                    var call = new JsonObject();
                    call.addProperty("id", id);
                    call.addProperty("type", "function");
                    call.add("function", function);
                    calls.add(call);
                    continuation.add(item);
                }
                default -> throw new WenzhouChat.Notice("ChatGPT 返回了未支持的输出类型。", result);
            }
        }
        writeAnswer(message, answer.toString(), requestTitle && calls.isEmpty());
        if (!calls.isEmpty()) {
            message.add("tool_calls", calls);
            message.add(CONTINUATION, continuation);
        }
        var choice = new JsonObject();
        choice.addProperty("finish_reason", calls.isEmpty() ? "stop" : "tool_calls");
        choice.add("message", message);
        var choices = new JsonArray();
        choices.add(choice);
        result.add("choices", choices);
        return result;
    }

    private static void writeAnswer(JsonObject message, String answer, boolean requestTitle) {
        int marker = answer.indexOf(TITLE_MARK);
        if (marker >= 0) {
            if (requestTitle) {
                String title = WzPublicCaption.clean(answer.substring(marker + TITLE_MARK.length()));
                if (!title.isEmpty()) message.addProperty("title", title);
            }
            answer = answer.substring(0, marker).stripTrailing();
        }
        message.addProperty("content", answer);
    }

    private static WenzhouChat.Notice failure(int status, String requestId, JsonObject error,
                                             JsonObject usageResponse) {
        String code = string(error, "code");
        String text = switch (code) {
            case "subscription_sharing_user_not_eligible" -> "当前 ChatGPT 账号或工作区无法使用套餐推理。";
            case "subscription_sharing_usage_limit_exceeded" -> "ChatGPT 套餐请求已达到用量上限，请查看 ChatGPT 用量。";
            case "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" ->
                    "暂时无法核实 ChatGPT 套餐用量，请稍后重试。";
            case "subscription_sharing_unsupported_capability" -> "当前 ChatGPT 模型或套餐不支持本次请求。";
            case "model_not_found" -> "当前 ChatGPT 账号无法使用所选模型，请重新选择。";
            case "subscription_sharing_invalid_user", "chatpass_v2_scope_not_authorized",
                 "chatpass_v2_invalid_authorization_context" -> "ChatGPT 套餐授权不可用，请检查登录与授权。";
            default -> switch (status) {
                case 401 -> "ChatGPT 套餐授权无效，请检查登录与授权。";
                case 403 -> "ChatGPT 拒绝当前套餐请求，请检查授权和服务资格。";
                case 429 -> "ChatGPT 暂时限流，请稍后再问。";
                case 503 -> "ChatGPT 套餐服务暂不可用，请稍后重试。";
                case 200 -> "ChatGPT 未完成本轮答复。";
                default -> "ChatGPT 请求失败（HTTP " + status + "）。";
            };
        };
        var diagnostic = new JsonObject();
        diagnostic.addProperty("httpStatus", status);
        if (requestId.matches("[A-Za-z0-9_-]{1,120}")) diagnostic.addProperty("providerRequestId", requestId);
        if (code.matches("[a-z0-9_]{1,100}")) diagnostic.addProperty("providerErrorCode", code);
        String param = string(error, "param");
        if (param.matches("[A-Za-z0-9_.\\[\\]-]{1,100}")) diagnostic.addProperty("providerErrorParam", param);
        return new WenzhouChat.Notice(text, usageResponse, diagnostic);
    }

    private static final class Stream implements Flow.Subscriber<String> {
        private final WzCompletionStream.Preview preview;
        private final StringBuilder data = new StringBuilder(), errorBody = new StringBuilder();
        private final Set<Integer> commentary = new HashSet<>();
        private final Map<String, JsonObject> streamedReasoning = new HashMap<>();
        private JsonObject terminal;
        private Long projectCost;
        private String terminalType = "";
        private boolean toolOutput, invalid, errorResponse;

        Stream(WzCompletionStream.Preview preview) {
            this.preview = preview;
        }

        HttpResponse.BodySubscriber<Void> body(HttpResponse.ResponseInfo info) {
            errorResponse = info.statusCode() != 200;
            return HttpResponse.BodySubscribers.fromLineSubscriber(this);
        }

        @Override public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override public synchronized void onError(Throwable error) {
            invalid = true;
        }

        @Override public void onComplete() {}

        @Override public synchronized void onNext(String line) {
            if (errorResponse) {
                if (line.length() < 16_384 - errorBody.length()) errorBody.append(line).append('\n');
                return;
            }
            if (invalid || terminal != null) return;
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.startsWith("data: ") ? line.substring(6) : line.substring(5));
            } else if (line.isEmpty() && !data.isEmpty()) {
                String event = data.toString();
                data.setLength(0);
                if (event.equals("[DONE]")) return;
                try {
                    event(JsonParser.parseString(event).getAsJsonObject(), true);
                } catch (RuntimeException malformed) {
                    invalid = true;
                }
            }
        }

        private void event(JsonObject event, boolean publishPreview) {
            switch (string(event, "type")) {
                case "response.output_text.delta" -> {
                    if (publishPreview && preview != null && !toolOutput && !commentary.contains(event.get("output_index").getAsInt()))
                        preview.append(string(event, "delta"));
                }
                case "response.output_item.added", "response.output_item.done" -> {
                    if (!publishPreview) return;
                    var item = object(event, "item");
                    String type = string(item, "type");
                    if ("reasoning".equals(type) && "response.output_item.done".equals(string(event, "type"))
                            && !string(item, "encrypted_content").isEmpty())
                        streamedReasoning.put(string(item, "id"), item);
                    if ("function_call".equals(type) && !toolOutput) {
                        toolOutput = true;
                        resetPreview();
                    } else if ("commentary".equals(string(item, "phase"))) {
                        commentary.add(event.get("output_index").getAsInt());
                    }
                }
                case "response.completed", "response.failed", "response.incomplete", "error" -> {
                    terminalType = string(event, "type");
                    terminal = event;
                }
                default -> { }
            }
        }

        JsonObject error() {
            if (errorBody.isEmpty()) return new JsonObject();
            try {
                return object(JsonParser.parseString(errorBody.toString()).getAsJsonObject(), "error");
            } catch (RuntimeException malformed) {
                return new JsonObject();
            }
        }

        JsonObject result(String requestId, Set<String> allowed, boolean requestTitle)
                throws WenzhouChat.Notice {
            if (invalid || terminal == null) {
                resetPreview();
                throw new WenzhouChat.Notice("ChatGPT 答复传输中断，请重新提问。");
            }
            var response = object(terminal, "response");
            var receipt = usage(response);
            if (!"response.completed".equals(terminalType)) {
                resetPreview();
                var error = "error".equals(terminalType) ? terminal : object(response, "error");
                if ("response.incomplete".equals(terminalType)) {
                    var diagnostic = new JsonObject();
                    diagnostic.addProperty("httpStatus", 200);
                    diagnostic.addProperty("providerErrorCode", "response_incomplete");
                    if (requestId.matches("[A-Za-z0-9_-]{1,120}"))
                        diagnostic.addProperty("providerRequestId", requestId);
                    throw new WenzhouChat.Notice((projectCost == null ? "ChatGPT" : "Terra") + " 本轮答复不完整，请重新提问。", receipt, diagnostic);
                }
                if (projectCost != null) throw new WenzhouChat.Notice("Terra 没有完成这次回答。", receipt);
                throw failure(200, requestId, error, receipt);
            }
            JsonObject result;
            try {
                result = convert(response, receipt, allowed, streamedReasoning, requestTitle);
            } catch (RuntimeException malformed) {
                resetPreview();
                throw new WenzhouChat.Notice("ChatGPT 返回了无效的答复格式。", receipt);
            } catch (WenzhouChat.Notice rejected) {
                resetPreview();
                throw rejected;
            }
            if (preview != null) {
                if ("stop".equals(string(result.getAsJsonArray("choices").get(0).getAsJsonObject(), "finish_reason")))
                    preview.flush();
                else resetPreview();
            }
            return result;
        }

        private JsonObject usage(JsonObject response) {
            var receipt = envelope(response);
            if (projectCost != null) {
                receipt.addProperty("billing", WzThreadUsage.OPENAI_API);
                receipt.addProperty("nanoUsd", projectCost);
            }
            return receipt;
        }

        private JsonObject projectUsage() {
            return projectCost == null || terminal == null ? null : usage(object(terminal, "response"));
        }

        private synchronized void abort() {
            invalid = true;
            resetPreview();
        }

        private void resetPreview() {
            if (preview != null) preview.reset();
        }
    }

    private WzResponses() {}
}
