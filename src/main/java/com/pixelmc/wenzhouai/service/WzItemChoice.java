package com.pixelmc.wenzhouai.service;

import com.google.gson.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class WzItemChoice {
    private static final String SUBJECTS = "player_item_names";

    static boolean verbatim(String question, String name) {
        return !name.isBlank() && question.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT));
    }

    static void requireScope(JsonArray tools) {
        for (var tool : tools) {
            var function = tool.getAsJsonObject().getAsJsonObject("function");
            String name = WzData.string(function, "name");
            if (name.equals("find_item") || name.equals("confirm_new_topic")) continue;
            var schema = function.getAsJsonObject("parameters");
            var field = JsonParser.parseString("""
                    {"type":"array","items":{"type":"string"},"maxItems":8,"description":"本轮玩家新提到且需要定位的具体物品，逐个原样填写完整名称。通用机制、追问已确定物品或寒暄填空数组。"}
                    """);
            schema.getAsJsonObject("properties").add(SUBJECTS, field);
            schema.getAsJsonArray("required").add(SUBJECTS);
        }
    }

    static List<String> takeScope(JsonObject function) {
        if (WzData.string(function, "name").equals("find_item")) return List.of();
        var args = JsonParser.parseString(WzData.string(function, "arguments")).getAsJsonObject();
        var subjects = args.remove(SUBJECTS);
        if (subjects == null) return List.of();
        var names = new ArrayList<String>();
        for (var value : subjects.getAsJsonArray()) {
            String subject = value.getAsString().strip();
            if (!WzItemIndex.valid(subject) || names.size() >= 8) throw new IllegalArgumentException("物品名称无效");
            if (!names.contains(subject)) names.add(subject);
        }
        function.addProperty("arguments", args.toString());
        return names;
    }

    static List<JsonElement> ordered(JsonArray calls) {
        var ordered = new ArrayList<JsonElement>();
        calls.forEach(ordered::add);
        ordered.sort(Comparator.comparingInt(call -> switch (WzData.string(call.getAsJsonObject().getAsJsonObject("function"), "name")) {
            case WzBlockTools.LIST, WzBlockTools.READ -> 0;
            case "find_item" -> 1;
            default -> 2;
        }));
        return ordered;
    }

    static WenzhouChat.Result finish(JsonArray history, String question, JsonObject match) {
        String answer = "我还没能确定你问的是哪件物品。请在工具箱里选择「内联物品」，选中它再发送，我会接着回答。";
        JsonObject choice = null;
        if (match != null && "live".equals(WzData.string(match,"status"))) {
            long total = WzData.number(match, "total");
            var rows = match.getAsJsonArray("candidates");
            if (total >= 1 && total <= 3 && rows != null && rows.size() == total) {
                String query = WzData.string(match, "query");
                answer = "你说的「" + query + "」是哪件物品？";
                choice = new JsonObject();
                choice.addProperty("prompt", answer);
                choice.add("options", rows.deepCopy());
            } else if (total > 3) {
                answer = "找到了 " + total + " 件可能的物品。请用工具箱里的「内联物品」指定你问的那件，我会接着回答。";
            }
        }
        if (match != null && "solcarrot".equals(WzData.string(match, "missing_mod")))
            answer = WzFoodBook.MISSING;
        var saved = history.deepCopy();
        saved.add(WenzhouChat.message("user", question));
        saved.add(WenzhouChat.message("assistant", answer));
        return new WenzhouChat.Result(saved, answer, "", choice);
    }

    private WzItemChoice() {}
}
