/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** The model can only address the short block references handed to this turn by the server. */
final class WzBlockTools {
    static final String LIST = "list_block_fields", READ = "read_block_field";
    private static final int MAX_PAGE_BYTES = 6 * 1024, MAX_TURN_BYTES = 24 * 1024;
    private static final Set<String> KEYS = Set.of("ref", "path", "offset", "limit");
    private static final JsonObject LIST_TOOL = definition(LIST, "列出引用方块指定路径下的可读字段、类型和大小。缺省根路径。可分页。");
    private static final JsonObject READ_TOOL = definition(READ, "读取某个已列出的字段或一页库存；返回范围之外的槽位不能推断为空。查询外置存储时也从服务端列出的库存入口读取。");
    private final Set<String> references = new HashSet<>();
    final JsonArray identities;
    private JsonObject firstDenial;
    private int remaining = MAX_TURN_BYTES;

    WzBlockTools(JsonArray selected) {
        identities = new JsonArray();
        for (var entry : selected) {
            var row = entry.getAsJsonObject();
            var identity = new JsonObject();
            for (String key : Set.of("ref", "reference", "dimension")) identity.addProperty(key, WzData.string(row, key));
            references.add(WzData.string(identity, "ref"));
            identities.add(identity);
            if (firstDenial == null && row.has("denied") && row.get("denied").isJsonObject()) {
                noticeDenial(identity, row.getAsJsonObject("denied"));
            }
        }
    }

    JsonObject denial() { return firstDenial; }

    private void noticeDenial(JsonObject identity, JsonObject receipt) {
        if (firstDenial != null || !"forbidden".equals(WzData.string(receipt, "status"))) return;
        firstDenial = identity.deepCopy();
        firstDenial.addProperty("status", "forbidden");
        firstDenial.addProperty("reasonCode", WzData.string(receipt, "reasonCode"));
        firstDenial.addProperty("message", WzData.string(receipt, "message"));
    }

    private static JsonObject definition(String name, String description) {
        var schema = JsonParser.parseString("""
            {"type":"object","properties":{
            "ref":{"type":"string","pattern":"^b[1-8]$","description":"本轮选中方块的短编号，如 b1。"},
            "path":{"type":"string","maxLength":256,"description":"返回的 JSON Pointer 路径，空串表示根。"},
            "offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":32}},
            "additionalProperties":false}
            """).getAsJsonObject();
        var required = new JsonArray();
        required.add("ref");
        if (READ.equals(name)) required.add("path");
        schema.add("required", required);
        var function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", schema);
        var result = new JsonObject();
        result.addProperty("type", "function");
        result.add("function", function);
        return result;
    }

    void offer(JsonArray tools) {
        if (firstDenial == null && !references.isEmpty() && remaining >= 256) {
            tools.add(LIST_TOOL.deepCopy());
            tools.add(READ_TOOL.deepCopy());
        }
    }

    JsonObject execute(String name, String input, WenzhouChat.DescribedLookup lookup) throws Exception {
        if (references.isEmpty()) return error("unavailable", "本轮没有可访问的方块引用，请玩家重新选择方块。");
        if (remaining < 256) return error("limit_reached", "本轮方块数据读取量已达上限，请用已有资料回答并说明未核实部分。");
        JsonObject args;
        try {
            if (input.length() > 1536) throw new IllegalArgumentException();
            args = JsonParser.parseString(input).getAsJsonObject();
            if (!KEYS.containsAll(args.keySet()) || !references.contains(text(args, "ref"))) throw new IllegalArgumentException();
            String path = args.has("path") ? text(args, "path") : "";
            if (path.length() > 256 || path.chars().anyMatch(Character::isISOControl)
                    || path.chars().filter(c -> c == '/').count() > 12
                    || (!path.isEmpty() && !path.startsWith("/")) || path.matches(".*~(?![01]).*")
                    || (READ.equals(name) && path.isEmpty())) throw new IllegalArgumentException();
            int offset = number(args, "offset", 0), limit = number(args, "limit", 12);
            if (offset < 0 || limit < 1 || limit > 32) throw new IllegalArgumentException();
            args.addProperty("path", path);
            args.addProperty("offset", offset);
            args.addProperty("limit", limit);
        } catch (RuntimeException invalid) {
            return charged(error("invalid_request", "请使用本轮给出的 ref、有效字段路径和分页参数。"));
        }
        JsonObject receipt = lookup.query("block", name, args.toString(), 1, "");
        if ("forbidden".equals(WzData.string(receipt, "status"))) {
            String ref = WzData.string(args, "ref");
            for (var element : identities) {
                var identity = element.getAsJsonObject();
                if (ref.equals(WzData.string(identity, "ref"))) noticeDenial(identity, receipt);
            }
        }
        return charged(visibleResult(receipt));
    }

    /** Apply the same projection for the player receipt and the model; charge exactly once on delivery. */
    JsonObject visibleResult(JsonObject result) {
        if ("forbidden".equals(WzData.string(result, "status"))) return result;
        int bytes = size(result);
        if (bytes > MAX_PAGE_BYTES) return error("limit_reached", "本页过大，请选更具体的路径或减小 limit。");
        if (bytes > remaining) {
            remaining = 0;
            return error("limit_reached", "剩余读取量不足以容纳这页，请用已核实的资料回答。");
        }
        return result;
    }

    private static String text(JsonObject args, String key) {
        var value = args.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
        return value.getAsString();
    }

    private static int number(JsonObject args, String key, int fallback) {
        if (!args.has(key)) return fallback;
        var value = args.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
        return value.getAsBigDecimal().intValueExact();
    }

    private static int size(JsonObject value) { return value.toString().getBytes(StandardCharsets.UTF_8).length; }

    private JsonObject charged(JsonObject result) {
        remaining -= size(result);
        return result;
    }

    private static JsonObject error(String status, String message) {
        var result = new JsonObject();
        result.addProperty("status", status);
        result.addProperty("message", message);
        return result;
    }
}
