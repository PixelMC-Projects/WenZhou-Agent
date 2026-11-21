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
    private final List<WzMemoryFacts.Fact> facts = new ArrayList<>();
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
        result.remove("memory_facts");
        if (result.has("personal") && result.get("personal").isJsonObject()) {
            personalGameplayRead = true;
            return;
        }
        if (string(result, "status").equals("installed_code")) {
            if (kind.equals("read_method")) {
                rememberMethod(target, "已读取 " + string(result, "method"));
            } else if (kind.equals("locate_gameplay")) {
                var candidates = WzMemoryFacts.rows(result, "methods");
                if (!candidates.isEmpty()) {
                    var first = candidates.get(0);
                    rememberMethod(string(first, "method_id"), "定位候选 " + target + " · " + string(first, "method"));
                }
            }
        }
        if (personalGameplayRead) return;
        var offered = new JsonArray();
        for (var fact : WzMemoryFacts.from(kind, target, result)) {
            int index = facts.indexOf(fact);
            if (index < 0) {
                if (facts.size() >= 128) break;
                index = facts.size();
                facts.add(fact);
            }
            var row = new JsonObject();
            row.addProperty("id", index + 1);
            row.addProperty("subject", fact.subject());
            row.addProperty("facet", fact.facet());
            row.addProperty("summary", fact.summary());
            row.addProperty("detail", fact.detail());
            offered.add(row);
        }
        if (!offered.isEmpty()) result.add("memory_facts", offered);
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
        return save(memory, scope, args, facts);
    }

    static JsonObject save(WzGameplayMemory memory, WzGameplayMemory.Scope scope, JsonObject args,
                           List<WzMemoryFacts.Fact> available) throws java.io.IOException {
        if (!Set.of("subject", "facet", "facts").containsAll(args.keySet()))
            return WzGameplayMemory.invalid("facts", "请只提交回执中的主题、方面和事实编号。");
        String subject = WzMemoryFacts.subject(WzMemoryFacts.text(args, "subject"));
        if (subject.isEmpty()) return WzGameplayMemory.invalid("subject", "请选择事实回执中的完整主题。");
        String facet = WzMemoryFacts.text(args, "facet");
        if (!WzGameplayMemory.validFacet(facet))
            return WzGameplayMemory.invalid("facet", "请选择事实回执中的方面。");
        var ids = WzMemoryFacts.array(args, "facts");
        if (ids.isEmpty() || ids.size() > 6)
            return WzGameplayMemory.invalid("facts", "需要 1–6 个本轮事实编号。");
        var selected = new LinkedHashSet<WzMemoryFacts.Fact>();
        for (var id : ids) {
            int n;
            try {
                n = id instanceof JsonPrimitive p && p.isNumber() ? p.getAsBigDecimal().intValueExact() : 0;
            } catch (ArithmeticException | NumberFormatException invalid) {
                n = 0;
            }
            if (n <= 0 || n > available.size())
                return WzGameplayMemory.invalid("facts", "事实编号必须来自本轮 memory_facts。");
            var fact = available.get(n - 1);
            if (!subject.equals(fact.subject()))
                return WzGameplayMemory.invalid("subject", "所选事实的主题必须与 subject 完全一致。");
            if (!facet.equals(fact.facet()))
                return WzGameplayMemory.invalid("facet", "所选事实的方面必须与 facet 完全一致。");
            selected.add(fact);
        }
        return memory.save(scope, List.copyOf(selected));
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
