package com.pixelmc.wenzhouai.service;

import static com.pixelmc.wenzhouai.service.WzData.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.util.Set;

final class WzProtocolText {
    private static final Set<String> NODES =
            Set.of("doc", "paragraph", "heading", "bulletList", "listItem", "text", "hardBreak");
    private static final Set<String> BLOCKS = Set.of("paragraph", "heading", "bulletList");

    static JsonElement sanitize(JsonElement input) {
        if (input == null || input.isJsonNull()) return new com.google.gson.JsonPrimitive("");
        if (input.isJsonPrimitive() && input.getAsJsonPrimitive().isString()) {
            if (input.getAsString().length() > 12_000) throw invalid();
            return input;
        }
        if (!input.isJsonObject() || !string(input.getAsJsonObject(), "type").equals("doc"))
            throw invalid();
        StringBuilder text = new StringBuilder();
        JsonObject result = sanitizeNode(input, 0, text);
        if (result.toString().length() > 40_000) throw invalid();
        return result;
    }

    static String plain(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) return value.getAsString();
        StringBuilder result = new StringBuilder();
        append(value.getAsJsonObject(), result);
        return result.toString().strip();
    }

    private static void append(JsonObject value, StringBuilder text) {
        text.append(string(value, "text"));
        if (value.has("content"))
            value.getAsJsonArray("content").forEach(child -> append(child.getAsJsonObject(), text));
        if (Set.of("paragraph", "heading", "hardBreak").contains(string(value, "type")))
            text.append('\n');
    }

    private static JsonObject sanitizeNode(JsonElement node, int depth, StringBuilder text) {
        if (depth > 10 || !node.isJsonObject()) throw invalid();
        JsonObject input = node.getAsJsonObject();
        String type = string(input, "type");
        if (!NODES.contains(type)) throw invalid();
        JsonObject result = new JsonObject();
        result.addProperty("type", type);
        if (type.equals("heading")) {
            long level = number(object(input, "attrs"), "level");
            if (level < 1 || level > 6) throw invalid();
            JsonObject attrs = new JsonObject();
            attrs.addProperty("level", level);
            result.add("attrs", attrs);
        }
        if (type.equals("text")) {
            String content = string(input, "text");
            if (content.isEmpty() || text.length() + content.length() > 12_000) throw invalid();
            text.append(content);
            result.addProperty("text", content);
            if (input.has("marks")) result.add("marks", marks(input.getAsJsonArray("marks")));
        }
        if (input.has("content")) {
            JsonArray content = input.getAsJsonArray("content");
            if (content.size() > 256) throw invalid();
            JsonArray children = new JsonArray();
            for (JsonElement child : content) {
                if (!child.isJsonObject()) throw invalid();
                String childType = string(child.getAsJsonObject(), "type");
                boolean allowed =
                        switch (type) {
                            case "doc", "listItem" -> BLOCKS.contains(childType);
                            case "paragraph", "heading" ->
                                    Set.of("text", "hardBreak").contains(childType);
                            case "bulletList" -> childType.equals("listItem");
                            default -> false;
                        };
                if (!allowed) throw invalid();
                children.add(sanitizeNode(child, depth + 1, text));
            }
            result.add("content", children);
        }
        return result;
    }

    private static JsonArray marks(JsonArray list) {
        if (list.size() > 2) throw invalid();
        JsonArray result = new JsonArray();
        for (var input : list) {
            String type = string(input.getAsJsonObject(), "type");
            JsonObject mark = new JsonObject();
            mark.addProperty("type", type);
            if (type.equals("link")) {
                String href = string(object(input.getAsJsonObject(), "attrs"), "href");
                URI uri = URI.create(href);
                if (href.length() > 1000
                        || !"https".equalsIgnoreCase(uri.getScheme())
                        || uri.getHost() == null
                        || uri.getUserInfo() != null) throw invalid();
                JsonObject attrs = new JsonObject();
                attrs.addProperty("href", href);
                mark.add("attrs", attrs);
            } else if (!type.equals("bold")) {
                throw invalid();
            }
            result.add(mark);
        }
        return result;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("协议格式无效");
    }

    private WzProtocolText() {}
}
