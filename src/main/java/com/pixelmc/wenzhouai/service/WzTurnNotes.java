package com.pixelmc.wenzhouai.service;

import com.google.gson.*;
import com.pixelmc.pixelmcmanager.api.PlayerHubApi;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.pixelmc.wenzhouai.service.WzData.string;

/** Per-answer evidence handles cannot be reused by another player or turn. */
final class WzTurnNotes {
    private boolean personalGameplayRead;
    boolean personalGameplayRead() { return personalGameplayRead; }

    private final WzGameplayMemory memory;
    private final UUID player;
    private final Set<String> subjects = new LinkedHashSet<>();
    private final List<String> sources = new ArrayList<>();
    private final Map<String, String> listedSummaries = new HashMap<>();
    private final LinkedHashMap<String,String> methodRefs = new LinkedHashMap<>();
    private final java.util.concurrent.CompletableFuture<WzGameplayMemory.Scope> initialScope;
    private long lastScopeCheck = System.nanoTime();

    WzTurnNotes(WzGameplayMemory memory, UUID player) {
        this.memory = memory;
        this.player = player;
        initialScope = snapshot();
    }

    private java.util.concurrent.CompletableFuture<WzGameplayMemory.Scope> snapshot() {
        return PlayerHubApi.request(player, "snapshot", "{}").thenApply(raw ->
                WzGameplayMemory.Scope.current(JsonParser.parseString(raw).getAsJsonObject().getAsJsonObject("scope")));
    }

    private void source(JsonObject row, String ref) {
        if (sources.size() >= 256 || ref.length() > 640) return;
        int index = sources.indexOf(ref);
        if (index < 0) { index = sources.size(); sources.add(ref); }
        row.addProperty("source", index + 1);
    }
    private void subject(String type, String id) {
        if (WenzhouReadTools.resourceId(id) != null) subjects.add(type + "/" + id);
    }
    private void rememberMethod(String id, String label) {
        if (id.isEmpty()) return;
        methodRefs.remove(id);
        methodRefs.put(id, label);
        if (methodRefs.size() > 3) methodRefs.remove(methodRefs.keySet().iterator().next());
    }

    JsonObject methodRefs() {
        var refs = new JsonObject();
        methodRefs.forEach(refs::addProperty);
        return refs;
    }

    static String followupRefs(List<JsonObject> recent) {
        var kept = new LinkedHashMap<String,String>();
        for (var turn : recent) {
            var refs = turn.get("methodRefs");
            if (refs == null || !refs.isJsonObject()) continue;
            for (var entry : refs.getAsJsonObject().entrySet()) {
                String id = entry.getKey();
                if (!WzMethodEvidence.current(id)) continue;
                kept.remove(id);
                kept.put(id, entry.getValue().getAsString());
            }
        }
        while (kept.size() > 3) kept.remove(kept.keySet().iterator().next());
        var text = new StringBuilder();
        kept.forEach((id, label) -> text.append("\n").append(label).append(" → ").append(id));
        return text.isEmpty() ? "" : "\n\n本会话可复查的代码位置（read_method）：" + text;
    }
    void observe(String kind, String target, JsonObject result) {
        if (result.has("personal") && result.get("personal").isJsonObject()) {
            personalGameplayRead=true;
            return;
        }
        String status = string(result, "status");
        if (kind.equals("calculate") && status.equals("calculated")) {
            source(result, "calculate " + string(result, "expression") + " = " + string(result, "result"));
            return;
        }
        if (kind.equals("command") && status.equals("sandbox_output") && result.has("exit_code")
                && WzData.number(result, "exit_code") == 0 && !WzData.flag(result, "truncated")
                && !WzData.flag(result, "time_limit_reached") && !string(result, "output").isBlank()) {
            // Keep a fingerprint of the queried evidence without copying shell text into shared notes.
            source(result, "shell sha256 " + WzGameplayMemory.digest(target + "\n" + string(result, "output")));
            return;
        }
        if (kind.equals("tag") && result.has("suggestions"))
            observe("search_tags", target, result.getAsJsonObject("suggestions"));
        if (kind.equals("read_method") && status.equals("installed_code")) {
            rememberMethod(target, "已读取 " + string(result, "method"));
            source(result, "method " + string(result, "class") + "." + string(result, "method") + string(result, "descriptor"));
            return;
        }
        if (kind.equals("pack_changes") && status.equals("pack_changes")) {
            for (var element : result.getAsJsonArray("matches")) {
                var row = element.getAsJsonObject();
                source(row, string(row,"evidence") + " " + string(row,"path") + " " + string(row,"match"));
            }
            return;
        }
        if (kind.equals("locate_gameplay") && status.equals("installed_code")) {
            var candidates = result.getAsJsonArray("methods");
            if (!candidates.isEmpty()) {
                var first = candidates.get(0).getAsJsonObject();
                rememberMethod(string(first, "method_id"), "定位候选 " + target + " · " + string(first, "method"));
            }
            for (var entry : result.getAsJsonArray("methods")) {
                var row = entry.getAsJsonObject();
                source(row, "method " + string(row, "class") + "." + string(row, "method")
                        + string(row, "descriptor") + " " + row.get("references"));
            }
            return;
        }
        if (!status.equals("live")) return;
        switch (kind) {
            case "item_overview" -> {
                for (var entry : result.getAsJsonArray("items")) {
                    var row = entry.getAsJsonObject();
                    subject("item", string(row, "item"));
                    if (row.has("detail")) source(row, "recipe " + row.getAsJsonArray("recipe_ids").get(0).getAsString());
                }
            }
            case "structure_generation", "structure_chests", "chest_sources", "trade_offers" -> {
                if (WzData.number(result,"total") > 0) {
                    if (kind.equals("chest_sources")) subject("item",target);
                    for (var value : result.getAsJsonArray("entries")) {
                        var row = value.getAsJsonObject();
                        if (row.has("loot_table")) subject("loot_table",string(row,"loot_table"));
                        for (String side : java.util.List.of("cost","second_cost","gives"))
                            if (row.has(side)) subject("item",string(row.getAsJsonObject(side),"item"));
                    }
                    source(result,kind+" "+target+" "+WzGameplayMemory.digest(result.toString()));
                }
            }
            case "food_book_stats" -> {
                if (result.has("book_total")) {
                    subject("item", target);
                    result.addProperty("memory_subject", "item/" + target);
                    source(result, kind + " " + target + " " + WzGameplayMemory.digest(result.toString()));
                }
            }
            case "worldgen_sources" -> {
                if (result.has("places_block")) subject("item", target);
                if (WzData.number(result, "total") > 0 || result.has("places_block"))
                    source(result, kind + " " + target + " " + WzGameplayMemory.digest(result.toString()));
            }
            case "entity_spawns", "biome_tag", "machine_conversions" -> {
                if (WzData.number(result, "total") > 0) {
                    String type = switch (kind) {
                        case "entity_spawns" -> "entity";
                        case "biome_tag" -> "biome_tag";
                        default -> "item";
                    };
                    subject(type, target);
                    result.addProperty("memory_subject", type + "/" + target);
                    source(result, kind + " " + target + " " + WzGameplayMemory.digest(result.toString()));
                }
            }
            case "find_item" -> {
                subject("item", string(result, "unique_item"));
                if (result.has("recipe_detail")) observe("recipe", result.getAsJsonObject("fixed_output_recipes")
                        .getAsJsonArray("recipe_ids").get(0).getAsString(), result.getAsJsonObject("recipe_detail"));
            }
            case "tag", "food_effects", "recipe", "loot_table" -> {
                subject(kind.equals("food_effects") ? "item" : kind, target);
                source(result, kind + " " + target);
                if (kind.equals("recipe")) {
                    var recipe = result.getAsJsonObject("recipe");
                    if (recipe != null && recipe.has("result")) subject("item", string(recipe.getAsJsonObject("result"), "item"));
                }
            }
            case "search_tags", "item_tags" -> {
                if (kind.equals("item_tags")) subject("item", target);
                for (var entry : result.getAsJsonArray("tags")) {
                    var row = entry.getAsJsonObject();
                    String tag = string(row, "tag");
                    subject("tag", tag);
                    source(row, "tag " + tag);
                }
            }
            case "recipes_for_item", "recipes_using_item", "loot_sources" -> {
                subject("item", target);
                var changes = result.getAsJsonObject("pack_changes");
                if (result.get("total").getAsInt() > 0 || changes != null && WzData.number(changes,"total") > 0)
                    source(result, kind + " " + target);
            }
            default -> { }
        }
    }

    JsonObject execute(String action, JsonObject args) throws InterruptedException {
        if (action.equals("skip_memory")) {
            String reason = switch (string(args, "reason")) {
                case "no_new_fact" -> "没有新增的可复用结论";
                case "insufficient_evidence" -> "证据不足";
                case "not_gameplay" -> "本轮无需记录玩法";
                case "storage_unavailable" -> "本轮未能保存";
                default -> "";
            };
            if (reason.isEmpty() || args.size() != 1) return WzGameplayMemory.error("请选择本轮跳过记忆的原因。");
            var receipt = new JsonObject();
            receipt.addProperty("skipped", reason);
            return receipt;
        }
        try {
            // Manager provides the active archive, pack name and version using the authenticated player.
            var scope = initialScope.get(6, TimeUnit.SECONDS);
            if (scope == null)
                return WzGameplayMemory.error("Manager 尚未确认当前整合包档案。");
            // Manager rate-limits player snapshots to one per half second. Consecutive model
            // tool calls share that observation, and later lookups recheck the scope.
            if (System.nanoTime() - lastScopeCheck >= TimeUnit.MILLISECONDS.toNanos(550)) {
                if (!scope.equals(snapshot().get(6, TimeUnit.SECONDS)))
                    return WzGameplayMemory.error("本次提问期间整合包档案发生了变化。");
                lastScopeCheck = System.nanoTime();
            }
            return switch (action) {
                case "search_memory" -> search(scope, args);
                case "read_memory" -> memory.read(scope, string(args, "id"));
                case "save_memory" -> save(scope, args);
                default -> WzGameplayMemory.error("未知记忆操作。");
            };
        } catch (TimeoutException timeout) {
            var result = WzGameplayMemory.error("等待 Manager 确认整合包档案超时。");
            result.addProperty("time_limit_reached", true);
            return result;
        } catch (ExecutionException unavailable) {
            return WzGameplayMemory.error("Manager 暂未提供当前档案，可继续查询游戏。");
        } catch (java.io.IOException unavailable) {
            return WzGameplayMemory.error("记忆文件读写失败，可继续查询游戏。");
        } catch (RuntimeException invalid) {
            return WzGameplayMemory.error("记忆参数或已有记录格式无效，可继续查询游戏。");
        }
    }

    String listedSummary(String id) {
        return listedSummaries.getOrDefault(id, "");
    }

    private JsonObject search(WzGameplayMemory.Scope scope, JsonObject args) throws java.io.IOException {
        int page = args.has("page") ? args.get("page").getAsBigDecimal().intValueExact() : 1;
        var result = memory.search(scope, strings(args, "keywords"), page);
        if (result.has("matches")) {
            for (var element : result.getAsJsonArray("matches")) {
                var row = element.getAsJsonObject();
                listedSummaries.put(string(row, "id"), string(row, "summary"));
            }
        }
        return result;
    }

    private JsonObject save(WzGameplayMemory.Scope scope, JsonObject args) throws java.io.IOException {
        if (personalGameplayRead) return WzGameplayMemory.error("个人进度不保存到共享知识库。");
        return save(memory, scope, args, subjects, sources);
    }

    static JsonObject save(WzGameplayMemory memory, WzGameplayMemory.Scope scope, JsonObject args,
                           Set<String> subjects, List<String> sources) throws java.io.IOException {
        var issues = new LinkedHashMap<String,String>();
        var values = new HashMap<String,String>();
        for (String field : List.of("subject", "facet", "summary", "detail")) {
            var value = args.get(field);
            if (value instanceof JsonPrimitive p && p.isString()) values.put(field, p.getAsString());
            else { issues.put(field, "需要字符串。"); values.put(field, ""); }
        }
        String topic = values.get("subject");
        if (!subjects.contains(topic)) topic = "item/" + topic;
        if (!subjects.contains(topic)) issues.putIfAbsent("subject", "主题必须是本轮已核对的资源 ID。");
        var refs = new LinkedHashSet<String>();
        if (args.get("sources") instanceof JsonArray ids) {
            if (ids.isEmpty() || ids.size() > 6)
                issues.put("sources", "收到 " + ids.size() + " 个编号，需要 1–6 个，优先选 1–3 个。");
            for (var value : ids) {
                try {
                    int n = value instanceof JsonPrimitive p && p.isNumber() ? p.getAsBigDecimal().intValueExact() : 0;
                    if (n > 0 && n <= sources.size()) refs.add(sources.get(n - 1));
                    else issues.putIfAbsent("sources", "请使用本轮提供的编号 1–" + sources.size() + "。");
                } catch (ArithmeticException | NumberFormatException invalid) {
                    issues.putIfAbsent("sources", "编号需要是有效整数。");
                }
            }
        } else issues.put("sources", "需要编号数组。");
        var keywords = new ArrayList<String>();
        if (args.get("keywords") instanceof JsonArray words) {
            for (var word : words) {
                if (word instanceof JsonPrimitive p && p.isString()) keywords.add(p.getAsString().strip());
                else { issues.put("keywords["+keywords.size()+"]", "需要字符串。"); keywords.add(""); }
            }
        } else issues.put("keywords", "需要关键词数组。");
        WzGameplayMemory.validateText(issues, topic, values.get("facet"), values.get("summary"), values.get("detail"), keywords);
        if (!issues.isEmpty()) return WzGameplayMemory.invalidFields(issues);
        return memory.save(scope, topic, values.get("facet"), values.get("summary"), values.get("detail"),
                keywords, List.copyOf(refs));
    }
    private static List<String> strings(JsonObject args, String key) {
        var values = new ArrayList<String>();
        for (var value : args.getAsJsonArray(key)) {
            String word = value.getAsString().strip();
            if (values.size() >= 6 || !WzGameplayMemory.plain(word, 48)) throw new IllegalArgumentException();
            values.add(word);
        }
        return values;
    }
}
