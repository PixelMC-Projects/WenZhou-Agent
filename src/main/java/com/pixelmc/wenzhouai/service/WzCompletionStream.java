package com.pixelmc.wenzhouai.service;

import com.google.gson.*;
import java.net.http.HttpResponse;
import java.util.TreeMap;
import java.util.concurrent.Flow;
import static com.pixelmc.wenzhouai.service.WzData.string;

/** Reassembles the provider response for the existing tool loop while delivering only public content deltas. */
final class WzCompletionStream implements Flow.Subscriber<String> {
    interface Preview {
        void append(String text);
        void flush();
        void reset();
    }

    private static final class Tool {
        String id = "";
        final StringBuilder name = new StringBuilder(), arguments = new StringBuilder();
        JsonObject json() {
            var call = new JsonObject();
            call.addProperty("id", id);
            call.addProperty("type", "function");
            var function = new JsonObject();
            function.addProperty("name", name.toString());
            function.addProperty("arguments", arguments.toString());
            call.add("function", function);
            return call;
        }
    }

    private final Preview preview;
    private final StringBuilder content = new StringBuilder(), reasoning = new StringBuilder(), title = new StringBuilder();
    private final TreeMap<Integer, Tool> tools = new TreeMap<>();
    private final JsonObject result = new JsonObject();
    private String finish = "";
    private boolean done, invalid;

    WzCompletionStream(Preview preview) { this.preview = preview; }

    @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
    @Override public void onError(Throwable error) { invalid = true; }
    @Override public void onComplete() {}

    @Override public void onNext(String line) {
        if (invalid || done || !line.startsWith("data:")) return;
        String data = line.substring(5).strip();
        if (data.equals("[DONE]")) { done = true; return; }
        try {
            var chunk = JsonParser.parseString(data).getAsJsonObject();
            if (chunk.has("error")) { invalid = true; return; }
            if (chunk.has("id")) result.add("id", chunk.get("id"));
            if (chunk.has("usage") && chunk.get("usage").isJsonObject()) result.add("usage", chunk.get("usage"));
            for (var value : chunk.getAsJsonArray("choices")) {
                var choice = value.getAsJsonObject();
                if (choice.has("index") && choice.get("index").getAsInt() != 0) continue;
                var delta = WzData.object(choice, "delta");
                reasoning.append(string(delta, "reasoning_content"));
                title.append(string(delta, "title"));
                if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
                    for (var entry : delta.getAsJsonArray("tool_calls")) {
                        var part = entry.getAsJsonObject();
                        var tool = tools.computeIfAbsent(part.get("index").getAsInt(), ignored -> new Tool());
                        if (!string(part, "id").isEmpty()) tool.id = string(part, "id");
                        var fn = WzData.object(part, "function");
                        tool.name.append(string(fn, "name"));
                        tool.arguments.append(string(fn, "arguments"));
                    }
                    if (preview != null && !tools.isEmpty()) preview.reset();
                }
                String fragment = string(delta, "content");
                content.append(fragment);
                if (preview != null && !fragment.isEmpty() && tools.isEmpty()) preview.append(fragment);
                String reason = string(choice, "finish_reason");
                if (!reason.isEmpty()) {
                    finish = reason;
                    if (preview != null) {
                        if (reason.equals("stop") && tools.isEmpty()) preview.flush();
                        else preview.reset();
                    }
                }
            }
        } catch (RuntimeException malformed) {
            invalid = true;
        }
    }

    JsonObject response() throws WenzhouChat.Notice {
        if (invalid || !done || finish.isEmpty()) throw new WenzhouChat.Notice("模型答复传输中断，请重新提问。");
        var message = new JsonObject();
        message.addProperty("content", content.toString());
        if (!reasoning.isEmpty()) message.addProperty("reasoning_content", reasoning.toString());
        if (!title.isEmpty()) message.addProperty("title", title.toString());
        if (!tools.isEmpty()) {
            var calls = new JsonArray();
            tools.values().forEach(tool -> calls.add(tool.json()));
            message.add("tool_calls", calls);
        }
        var choice = new JsonObject();
        choice.addProperty("finish_reason", finish);
        choice.add("message", message);
        var choices = new JsonArray();
        choices.add(choice);
        result.add("choices", choices);
        return result;
    }

    HttpResponse.BodySubscriber<Void> body(HttpResponse.ResponseInfo info) {
        return info.statusCode() == 200 ? HttpResponse.BodySubscribers.fromLineSubscriber(this)
                : HttpResponse.BodySubscribers.replacing(null);
    }
}
