package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pixelmc.wenzhouai.manager.WzDirectory;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.pixelmc.wenzhouai.service.WzData.*;

/** The only route from an authenticated owner connection to personal model preferences. */
final class WzOwnerModels {
    static final String DEEPSEEK = "deepseek";
    private static final List<String> DS_LEVELS = List.of("inherit", "off", "low", "high", "max");
    private static final List<String> GPT_LEVELS = List.of("low", "medium", "high", "xhigh", "max");
    private final UUID owner;
    final WzChatGptAccount account;
    private final WzDirectory directory;

    WzOwnerModels(UUID owner, WzChatGptAccount account, WzDirectory directory) {
        this.owner = owner;
        this.account = account;
        this.directory = directory;
    }

    boolean owns(UUID player) {
        return owner.equals(player);
    }

    record Turn(String model, String effort, long session) {
        boolean project() { return WzProjectModels.TERRA.equals(model); }
        boolean deepseek() { return DEEPSEEK.equals(model); }
        boolean plan() { return !deepseek() && !project(); }
        String selection() { return project() ? model : model + "/" + effort; }
    }

    JsonObject view(String global) throws Exception {
        JsonObject row = directory.read(owner, WzDirectory.MODEL_PREFERENCES).get(4, TimeUnit.SECONDS);
        return viewFrom(object(row, "value"), global);
    }

    private JsonObject viewFrom(JsonObject saved, String global) {
        JsonObject view = new JsonObject();
        JsonArray models = catalog();
        String selected = string(saved, "model");
        if (!DEEPSEEK.equals(selected) && (find(models, selected) == null
                || !account.preferenceSession().equals(string(saved, "sessionTag")))) selected = DEEPSEEK;
        JsonObject stored = object(saved, "efforts");
        JsonObject efforts = new JsonObject();
        String ds = string(stored, DEEPSEEK);
        efforts.addProperty(DEEPSEEK, DS_LEVELS.contains(ds) ? ds : "inherit");
        for (var entry : models) {
            var model = entry.getAsJsonObject();
            String id = string(model, "id");
            var levels = levels(model);
            String effort = string(stored, id);
            efforts.addProperty(id, levels.contains(effort) ? effort
                    : levels.contains("medium") ? "medium" : levels.get(0));
        }
        String effort = string(efforts, selected);
        view.addProperty("connected", account.connected());
        view.addProperty("model", selected);
        view.addProperty("effectiveEffort", "inherit".equals(effort) ? global : effort);
        view.add("efforts", efforts);
        view.add("models", models);
        return view;
    }

    JsonObject change(JsonObject body, String global) throws Exception {
        if (!body.keySet().stream().allMatch(k -> k.equals("model") || k.equals("effort")))
            return error("模型设置参数无效");
        String model = string(body, "model");
        JsonObject row = directory.read(owner, WzDirectory.MODEL_PREFERENCES).get(4, TimeUnit.SECONDS);
        JsonObject saved = object(row, "value").deepCopy();
        String sessionTag = account.preferenceSession();
        JsonObject option = find(catalog(), model);
        if (!DEEPSEEK.equals(model) && option == null) return error("该模型当前不可用，请重新选择");
        if (body.has("effort")) {
            String value = string(body, "effort");
            var supported = DEEPSEEK.equals(model) ? DS_LEVELS : levels(option);
            if (!supported.contains(value)) return error("该模型不支持此思考强度");
            var efforts = object(saved, "efforts");
            efforts.addProperty(model, value);
            saved.add("efforts", efforts);
        }
        if (DEEPSEEK.equals(model)) saved.remove("sessionTag");
        else saved.addProperty("sessionTag", sessionTag);
        saved.remove("projectTerraSelected");
        saved.addProperty("model", model);
        directory.saveModelPreferences(owner, saved, number(row, "revision")).get(4, TimeUnit.SECONDS);
        var result = new JsonObject();
        result.add("modelPicker", viewFrom(saved, global));
        return result;
    }

    // A displayed choice is pinned when Send is pressed. Revalidate catalog membership and
    // identity independently; a stale/disconnected GPT request must never incur a DeepSeek fee.
    Turn resolve(String selection) throws WenzhouChat.Notice {
        int slash = selection.indexOf('/');
        if (slash < 1 || selection.length() > 110) throw new WenzhouChat.Notice("模型选择已失效，请重新选择");
        String model = selection.substring(0, slash);
        String effort = selection.substring(slash + 1);
        if (DEEPSEEK.equals(model) && DS_LEVELS.contains(effort) && !"inherit".equals(effort))
            return new Turn(model, effort, 0);
        long session = account.sessionGeneration();
        JsonObject option = find(catalog(), model);
        if (option == null || !levels(option).contains(effort))
            throw new WenzhouChat.Notice("该模型或思考强度当前不可用，请重新选择");
        return new Turn(model, effort, session);
    }

    private JsonArray catalog() {
        return account.connected() ? account.models() : new JsonArray();
    }

    private static JsonObject find(JsonArray models, String id) {
        for (var entry : models) {
            var model = entry.getAsJsonObject();
            if (string(model, "id").equals(id)) return model;
        }
        return null;
    }

    private static List<String> levels(JsonObject option) {
        var accepted = new java.util.ArrayList<String>();
        var offered = option.getAsJsonArray("efforts");
        if (offered != null)
            for (var level : offered)
                if (level.isJsonPrimitive() && GPT_LEVELS.contains(level.getAsString()))
                    accepted.add(level.getAsString());
        return accepted.isEmpty() ? List.of("auto") : accepted;
    }
}
