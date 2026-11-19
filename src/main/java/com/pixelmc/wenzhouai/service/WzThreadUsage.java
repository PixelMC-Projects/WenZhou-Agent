package com.pixelmc.wenzhouai.service;

import static com.pixelmc.wenzhouai.service.WzData.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;

final class WzThreadUsage {
    private WzThreadUsage() {}

    static JsonObject merge(JsonArray turns, JsonArray current) {
        return merge(turns, current, false);
    }

    static JsonObject merge(JsonArray turns, JsonArray current, boolean liveMissing) {
        var byId = new LinkedHashMap<String, JsonObject>();
        boolean missing = liveMissing;
        for (var entry : turns) {
            JsonObject turn = entry.getAsJsonObject();
            var receipts = turn.getAsJsonArray("usageReceipts");
            missing |= flag(turn, "usageMissing") || receipts == null || receipts.size() == 0;
            if (receipts != null) missing |= !collect(byId, receipts);
        }
        if (current != null) missing |= !collect(byId, current);
        JsonArray receipts = new JsonArray();
        byId.values().forEach(receipts::add);
        JsonObject result = new JsonObject();
        result.add("receipts", receipts);
        result.addProperty("running", current != null);
        result.addProperty("missing", missing);
        return result;
    }

    private static boolean collect(LinkedHashMap<String, JsonObject> byId, JsonArray receipts) {
        boolean complete = true;
        for (var entry : receipts) {
            if (!entry.isJsonObject()) {
                complete = false;
                continue;
            }
            JsonObject receipt = entry.getAsJsonObject();
            String id = string(receipt, "id");
            if (id.isEmpty()) {
                complete = false;
                continue;
            }
            byId.putIfAbsent(id, receipt.deepCopy());
            complete &= receipt.has("nanoYuan") && receipt.has("reasoning");
        }
        return complete;
    }
}
