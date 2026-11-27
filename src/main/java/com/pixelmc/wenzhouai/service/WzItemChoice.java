package com.pixelmc.wenzhouai.service;

import com.google.gson.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class WzItemChoice {
    static JsonObject confirmTool() {
        return JsonParser.parseString("""
                {"type":"function","function":{"name":"confirm_item","description":"仅在对象差异会改变答案且上下文和工具都无法消歧时，单独暂停并询问玩家。使用本轮 find_item 的未决查询原词。","parameters":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"],"additionalProperties":false}}}
                """).getAsJsonObject();
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
        String answer = "我还没能确定「" + WzData.string(match, "query") + "」指哪件物品。请在工具箱里选择「内联物品」，选中它再发送，我会接着回答。";
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
                answer = "「" + WzData.string(match, "query") + "」找到了 " + total + " 件可能的物品。请用工具箱里的「内联物品」指定你问的那件，我会接着回答。";
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
