package com.pixelmc.wenzhouai.service;

import static com.pixelmc.wenzhouai.service.WzData.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pixelmc.wenzhouai.manager.WzDirectory;
import com.pixelmc.wenzhouai.manager.WzManagerStorage;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** The project model ID is separate from IDs issued by a personal ChatGPT session. */
final class WzProjectModels {
    static final String TERRA = "terra-api";
    private static final String SELECTED = "projectTerraSelected";
    private static final WzDirectory directory = WzManagerStorage.DIRECTORY;

    static JsonObject call(UUID player, String op, JsonObject input) throws Exception {
        JsonObject request = input.deepCopy();
        request.addProperty("op", op);
        request.addProperty("player", player.toString());
        return WzManagerStorage.request(request).get(8, TimeUnit.SECONDS);
    }

    static JsonObject available(UUID player) {
        try {
            var result = call(player, "terra.view", new JsonObject());
            if (flag(result, "granted") && (flag(result, "available") || billingBlocked(result))) return result;
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            // Older Managers keep their existing DeepSeek and personal routes.
        }
        return new JsonObject();
    }

    static boolean billingBlocked(JsonObject terra) {
        return flag(terra, "granted") && !flag(terra, "available") && flag(terra, "billingBlocked");
    }

    static JsonObject view(UUID player, WzOwnerModels owner, String global, JsonObject terra)
            throws Exception {
        JsonObject result = owner == null ? new JsonObject() : owner.view(global);
        if (!flag(terra, "available")) {
            if (owner != null && billingBlocked(terra) && projectSelected(player)) {
                result.addProperty("model", WzOwnerModels.DEEPSEEK);
                String preference = string(object(result, "efforts"), WzOwnerModels.DEEPSEEK);
                result.addProperty("effectiveEffort", "inherit".equals(preference) ? global : preference);
            }
            return result;
        }
        if (owner == null) {
            result.addProperty("managedOnly", true);
            result.addProperty("model", WzOwnerModels.DEEPSEEK);
            result.addProperty("effectiveEffort", global);
            result.add("models", new JsonArray());
            result.add("efforts", new JsonObject());
        }
        var entry = new JsonObject();
        entry.addProperty("id", TERRA);
        entry.addProperty("label", owner == null ? "GPT-6.1 Terra" : "GPT-6.1 Terra · 服务器");
        result.getAsJsonArray("models").add(entry);
        result.add("terraQuota", terra.get("terraQuota"));
        if (projectSelected(player)) {
            result.addProperty("model", TERRA);
            result.add("effectiveEffort", terra.get("effort"));
        }
        return result;
    }

    private static boolean projectSelected(UUID player) throws Exception {
        var row = directory.read(player, WzDirectory.MODEL_PREFERENCES).get(4, TimeUnit.SECONDS);
        return flag(object(row, "value"), SELECTED);
    }

    static JsonObject change(
            UUID player, JsonObject body, WzOwnerModels owner, String global, JsonObject terra)
            throws Exception {
        String id = string(body, "model");
        if (!TERRA.equals(id) && owner != null) {
            var changed = owner.change(body, global);
            if (!changed.has("error") && flag(terra, "available"))
                changed.add("modelPicker", view(player, owner, global, terra));
            return changed;
        }
        if (TERRA.equals(id) && billingBlocked(terra))
            return error("Terra 项目余额不可用，问舟将使用 DeepSeek。");
        if (!body.keySet().equals(Set.of("model"))
                || !flag(terra, "available")
                || !Set.of(TERRA, WzOwnerModels.DEEPSEEK).contains(id))
            return error("无权选择此模型或更改推理设置");
        var row = directory.read(player, WzDirectory.MODEL_PREFERENCES).get(4, TimeUnit.SECONDS);
        var saved = object(row, "value").deepCopy();
        saved.addProperty(SELECTED, TERRA.equals(id));
        directory
                .saveModelPreferences(player, saved, number(row, "revision"))
                .get(4, TimeUnit.SECONDS);
        var result = new JsonObject();
        result.add("modelPicker", view(player, owner, global, terra));
        return result;
    }

    static WzOwnerModels.Turn resolve(
            UUID player,
            String selection,
            WzOwnerModels owner,
            String global,
            boolean authenticated)
            throws Exception {
        if (TERRA.equals(selection)) {
            return resolveTerra(authenticated ? available(player) : new JsonObject(), global);
        }
        if (selection.isEmpty() || WzOwnerModels.DEEPSEEK.equals(selection))
            return new WzOwnerModels.Turn(WzOwnerModels.DEEPSEEK, global, 0);
        if (owner == null) throw new WenzhouChat.Notice("无权指定模型或思考强度");
        return owner.resolve(selection);
    }

    static WzOwnerModels.Turn resolveTerra(JsonObject view, String global) throws WenzhouChat.Notice {
        if (billingBlocked(view)) return new WzOwnerModels.Turn(WzOwnerModels.DEEPSEEK, global, 0);
        if (!flag(view, "granted") || !flag(view, "available"))
            throw new WenzhouChat.Notice("当前没有可用的 Terra 权限或服务");
        if (flag(object(view, "terraQuota"), "exhausted"))
            throw new WenzhouChat.Notice("Terra 额度已用完，可切换至 DeepSeek");
        return new WzOwnerModels.Turn(TERRA, string(view, "effort"), 0);
    }

    private WzProjectModels() {}
}
