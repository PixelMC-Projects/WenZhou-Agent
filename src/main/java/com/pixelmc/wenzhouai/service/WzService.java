package com.pixelmc.wenzhouai.service;

import static com.pixelmc.wenzhouai.service.WzData.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pixelmc.wenzhouai.manager.WzAdminBackend;
import com.pixelmc.wenzhouai.manager.WzDirectory;

import static com.pixelmc.wenzhouai.manager.WzManagerStorage.DIRECTORY;
import com.pixelmc.wenzhouai.network.WzNetwork;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class WzService {
    private static final Set<String> ACTIONS =
            Set.of(
                    "status",
                    "introSeen", "dismissItemChoice",
                    "policy",
                    "consent",
                    "research",
                    "list",
                    "history",
                    "remove",
                    "usage",
                    "stop",
                    "ask");
    private static final Path SETTINGS =
            FMLPaths.CONFIGDIR.get().resolve("wenzhouai").resolve("service.json");
    private static volatile Runtime active;

    private WzService() {}

    public static void start(MinecraftServer server) {
        active = null;
        try {
            active = new Runtime(server);
        } catch (RuntimeException unavailable) {
            com.mojang.logging.LogUtils.getLogger()
                    .error("WenZhou data is unavailable; gameplay will continue without WenZhou");
        }
    }

    public static void stop(MinecraftServer server) {
        Runtime current = active;
        if (current == null || current.server != server) return;
        active = null;
        current.jobs.keySet().forEach(id -> current.cancel(id, false));
        current.worker.shutdownNow();
        current.controls.shutdownNow();
        current.maintenance.shutdownNow();
    }

    public static long generation(UUID player) {
        Runtime current = active;
        return current == null ? -1 : current.generation(player);
    }

    public static void disconnect(UUID player) {
        Runtime current = active;
        if (current != null) current.cancel(player, false);
    }

    public static void invalidate(UUID player) {
        Runtime current = active;
        if (current != null) current.cancel(player, true);
    }

    public static void quotaChanged(UUID player) {
        Runtime current = active;
        if (current != null)
            WzNetwork.quotaChanged(current.server, player);
    }

    public static CompletableFuture<Boolean> blockReferenceAccess(UUID player, long generation) {
        Runtime current = active;
        if (current == null) return CompletableFuture.completedFuture(false);
        var reply = new CompletableFuture<Boolean>();
        try {
            current.controls.execute(() -> {
                try {
                    current.requireAccess(player, generation);
                    reply.complete(true);
                } catch (Exception unavailable) {
                    reply.complete(false);
                }
            });
        } catch (RejectedExecutionException busy) {
            reply.complete(false);
        }
        return reply.completeOnTimeout(false, 5, TimeUnit.SECONDS);
    }

    public static CompletableFuture<JsonObject> request(
            UUID player, String action, JsonObject body) {
        return request(player, action, body, null);
    }

    public static CompletableFuture<JsonObject> request(
            UUID player, String action, JsonObject body, WzWorldReferences.Ask world) {
        Runtime current = active;
        if (current == null || !ACTIONS.contains(action))
            return CompletableFuture.completedFuture(error("问舟操作不可用"));
        long generation = current.generation(player);
        if (action.equals("stop")) return CompletableFuture.completedFuture(current.stopAsk(player, body));
        if (action.equals("ask")) return current.ask(player, body, generation, world)
                .thenCompose(message -> localizeLists(current.server, message));
        CompletableFuture<JsonObject> reply = new CompletableFuture<>();
        try {
            current.controls.execute(
                    () -> {
                        try {
                            reply.complete(current.request(player, action, body, generation));
                        } catch (Exception unavailable) {
                            reply.complete(error("问舟暂不可用，请稍后重试"));
                        }
                    });
        } catch (RejectedExecutionException busy) {
            reply.complete(error("请求过于频繁，请稍后重试"));
        }
        return action.equals("history") ? reply.thenCompose(message -> localizeLists(current.server, message)) : reply;
    }

    private static CompletableFuture<JsonObject> localizeLists(MinecraftServer server, JsonObject message) {
        var labels = WzReply.listLabels(message);
        if (labels.isEmpty()) return CompletableFuture.completedFuture(message);
        return WzRegisteredItems.index(server).thenApplyAsync(catalog -> {
            labels.forEach(label -> label.localize(catalog.index()));
            return message;
        }).exceptionally(unavailable -> message);
    }

    public static WzAdminBackend.Data adminData() {
        return new WzAdminBackend.Data() {
            public JsonObject settings(String operation, JsonObject input) {
                return adminSettings(operation, input);
            }
            public int sessionCount(UUID player) {
                return WzService.sessionCount(player);
            }
            public WzAdminBackend.SessionLists sessions(UUID player, boolean research) throws Exception {
                return sessionSummaries(player, research);
            }
            public JsonObject thread(UUID player, String id, boolean research) {
                return adminThread(player, id, research);
            }
            public JsonObject usage(UUID player, String id, boolean research) {
                return threadUsage(player, id, research);
            }
            public void quotaChanged(UUID player) {
                WzService.quotaChanged(player);
            }
            public void accessChanged(UUID player) {
                invalidate(player);
            }
        };
    }

    public static JsonObject adminSettings(String operation, JsonObject input) {
        Runtime current = requireRuntime();
        JsonObject result = current.settings.admin(operation, input);
        if (Set.of("policy.publish", "credential.set", "credential.remove").contains(operation))
            current.generations.keySet().forEach(player -> current.cancel(player, true));
        if (operation.equals("policy.visibility"))
            current.generations.keySet().forEach(current::changed);
        return result;
    }

    public static int sessionCount(UUID player) {
        Runtime current = requireRuntime();
        synchronized (current.history) {
            JsonArray rows = current.history.index(player, -1).all();
            LiveUsage running = current.liveFor(player, null, 0);
            if (running != null) {
                for (var row : rows)
                    if (running.conversation.equals(string(row.getAsJsonObject(), "id"))) return rows.size();
                return rows.size() + 1;
            }
            return rows.size();
        }
    }

    public static WzAdminBackend.SessionLists sessionSummaries(UUID player, boolean includeResearch) throws Exception {
        Runtime current = requireRuntime();
        var owners = new java.util.LinkedHashSet<UUID>();
        if (player == null) {
            // Capture live owners first so a session completing during enumeration still has an owner.
            owners.addAll(current.live.keySet());
            owners.addAll(current.history.players());
        } else owners.add(player);

        JsonArray all = new JsonArray();
        JsonArray research = new JsonArray();
        JsonObject access = includeResearch && current.server.isDedicatedServer()
                ? DIRECTORY.accessRows(owners.stream().map(UUID::toString).toList(), true)
                : new JsonObject();
        for (UUID owner : owners) {
            long since = -1;
            if (includeResearch) {
                if (current.server.isDedicatedServer()) {
                    JsonObject fields = object(access, owner.toString());
                    since = current.researchSinceFields(fields);
                } else since = current.researchSince(owner);
            }
            synchronized (current.history) {
                WzHistory.SummaryIndex index = current.history.index(owner, since);
                LiveUsage running = current.liveFor(owner, null, 0);
                appendSessions(all, index.all(), owner, running, false);
                if (since > 0) {
                    appendSessions(research, index.research(), owner,
                            running != null && running.createdAt >= since ? running : null, true);
                }
            }
        }
        return new WzAdminBackend.SessionLists(all, research);
    }

    private static void appendSessions(JsonArray rows, JsonArray saved, UUID owner, LiveUsage running, boolean research) {
        if (running == null) {
            rows.addAll(saved);
            return;
        }
        boolean listed = false;
        for (var record : saved) {
            JsonObject row = record.getAsJsonObject();
            if (running.conversation.equals(string(row, "id"))) {
                row = row.deepCopy();
                row.addProperty("running", true);
                row.addProperty("updated", running.createdAt);
                row.addProperty("updatedAt", running.createdAt);
                listed = true;
            }
            rows.add(row);
        }
        if (!listed) {
            JsonObject row = new JsonObject();
            row.addProperty("id", running.conversation);
            row.addProperty("playerId", owner.toString());
            row.addProperty("title", WzHistory.title(running.question));
            row.addProperty("updated", running.createdAt);
            row.addProperty("updatedAt", running.createdAt);
            row.addProperty("count", 0);
            row.addProperty("research", research);
            row.addProperty("running", true);
            rows.add(row);
        }
    }

    public static JsonObject adminThread(UUID player, String id, boolean research) {
        Runtime current = requireRuntime();
        long since = research ? current.researchSince(player) : 0;
        if (since < 0) throw new IllegalArgumentException("问舟会话不可用");
        synchronized (current.history) {
            JsonObject thread = object(current.history.load(player), id);
            LiveUsage running = current.liveFor(player, id, since);
            return WzStoredAdmin.thread(player, id, thread, since,
                    running == null ? null : running.question, running == null ? 0 : running.createdAt,
                    running == null ? null : running.receipts(), running != null && running.missing);
        }
    }

    public static JsonObject threadUsage(UUID player, String session, boolean research) {
        Runtime current = requireRuntime();
        long since = research ? current.researchSince(player) : 0;
        if (since < 0) throw new IllegalArgumentException("问舟会话不可用");
        synchronized (current.history) {
            var saved = current.history.savedThread(player, session, since);
            LiveUsage running = current.liveFor(player, session, since);
            return WzStoredAdmin.usage(
                    saved, since,
                    running == null ? null : running.receipts(),
                    running != null && running.missing);
        }
    }

    private static Runtime requireRuntime() {
        Runtime current = active;
        if (current == null) throw new IllegalStateException("问舟服务尚未启动");
        return current;
    }

    private record Access(boolean granted, JsonObject receipt, long revision) {}

    private record Job(FutureTask<Void> task, CompletableFuture<JsonObject> reply, WzRunControl run) {}

    private record PolicyRead(int version, long startedAt) {}

    private record TopicChoice(String threadId, JsonObject turn, boolean completed) {}

    private static final class LiveUsage {
        final long generation;
        final String conversation;
        final String question;
        final String requestId;
        final long createdAt;
        volatile boolean missing;
        volatile boolean hidden;
        private final JsonArray receipts = new JsonArray();

        LiveUsage(
                long generation,
                String conversation,
                String question,
                long createdAt,
                String requestId) {
            this.generation = generation;
            this.conversation = conversation;
            this.question = question;
            this.createdAt = createdAt;
            this.requestId = requestId;
        }

        synchronized void add(JsonObject receipt) {
            receipts.add(receipt.deepCopy());
        }

        synchronized JsonArray receipts() {
            return receipts.deepCopy();
        }
    }

    private static final class Runtime {
        private final WzGameplayMemory gameplayMemory = new WzGameplayMemory(SETTINGS.getParent().resolve("memory"));
        final MinecraftServer server;
        final WzSettings settings = new WzSettings(SETTINGS);
        final WzHistory history;
        final WzChargeLedger charges;
        final WzQuotaLedger quotaLedger;
        final ThreadPoolExecutor worker = pool("model", 4, 16);
        final ThreadPoolExecutor controls = pool("control", 4, 48);
        final ScheduledThreadPoolExecutor maintenance =
                new ScheduledThreadPoolExecutor(1, runnable -> daemon("retention", runnable));
        final ConcurrentHashMap<UUID, Long> generations = new ConcurrentHashMap<>();
        final ConcurrentHashMap<UUID, Job> jobs = new ConcurrentHashMap<>();
        final ConcurrentHashMap<UUID, LiveUsage> live = new ConcurrentHashMap<>();
        final Set<UUID> trialIntros = ConcurrentHashMap.newKeySet();
        final ConcurrentHashMap<UUID, JsonObject> trialReceipts = new ConcurrentHashMap<>();
        final ConcurrentHashMap<UUID, PolicyRead> policyReads = new ConcurrentHashMap<>();

        Runtime(MinecraftServer server) {
            this.server = server;
            Path directory = SETTINGS.getParent();
            history =
                    new WzHistory(
                            directory.resolve(
                                    server.isDedicatedServer()
                                            ? "conversations"
                                            : "trial-conversations"));
            quotaLedger = server.isDedicatedServer() ? new WzQuotaLedger(directory, DIRECTORY) : null;
            charges =
                    new WzChargeLedger(
                            directory.resolve(
                                    server.isDedicatedServer()
                                            ? "usage.json"
                                            : "trial-usage.json"));
            if (quotaLedger != null) {
                WenzhouBilling.loadCached(directory);
                maintenance.scheduleWithFixedDelay(this::maintainQuotas, 0, 1, TimeUnit.MINUTES);
                maintenance.scheduleWithFixedDelay(
                        () -> WenzhouBilling.refresh(directory), 0, 1, TimeUnit.HOURS);
            }
            maintenance.scheduleWithFixedDelay(
                    () -> {
                        try {
                            charges.sweep();
                            for (UUID owner : history.players()) {
                                try {
                                    history.load(owner);
                                } catch (RuntimeException failed) {
                                    com.mojang.logging.LogUtils.getLogger()
                                            .warn(
                                                    "Wenzhou history retention sweep failed for one record");
                                }
                            }
                        } catch (RuntimeException failed) {
                            com.mojang.logging.LogUtils.getLogger()
                                    .warn("Wenzhou history retention sweep failed");
                        }
                    },
                    0,
                    1,
                    TimeUnit.HOURS);
        }

        private void maintainQuotas() {
            try {
                // The Manager expiry index also includes players who were offline at restart.
                DIRECTORY.sweepQuota().get(8, TimeUnit.SECONDS);
            } catch (Exception unavailable) {
                com.mojang.logging.LogUtils.getLogger().warn("WenZhou quota expiry check deferred");
            }
            try {
                quotaLedger.retry();
            } catch (RuntimeException unavailable) {
                com.mojang.logging.LogUtils.getLogger().warn("WenZhou pending usage sync deferred");
            }
        }

        private static JsonObject playerQuota(JsonObject account) {
            JsonObject result = new JsonObject();
            result.addProperty("enabled", flag(account, "enabled"));
            result.addProperty("unlimited", flag(account, "unlimited"));
            long limit = number(account, "limitNanoYuan");
            long remaining = number(account, "remainingNanoYuan");
            double percent = limit > 0 ? 100.0 * remaining / limit : 0;
            result.addProperty("remainingPercent", Math.max(0, Math.min(100, percent)));
            result.addProperty("resetAt", number(account, "resetAt"));
            result.addProperty("exhausted", flag(account, "enabled") && !flag(account, "unlimited") && remaining <= 0);
            return result;
        }

        private void sendQuota(UUID player, long generation, JsonObject account) {
            JsonObject update = event("quota", "");
            update.add("quota", playerQuota(account));
            WzNetwork.event(server, player, generation, update);
        }

        private boolean trialEnabled() {
            return !server.isDedicatedServer()
                    && !server.isPublished()
                    && Boolean.getBoolean("pixelmc.hub.wenzhouTrial");
        }

        private boolean trial(UUID player) throws Exception {
            if (!trialEnabled()) return false;
            return server.submit(
                            () -> {
                                ServerPlayer connection = server.getPlayerList().getPlayer(player);
                                return trialEnabled()
                                        && connection != null
                                        && server.isSingleplayerOwner(connection.getGameProfile());
                            })
                    .get(4, TimeUnit.SECONDS);
        }

        private String key() {
            if (trialEnabled()) {
                String key = System.getenv("PIXELMC_WENZHOU_TRIAL_KEY");
                return key == null ? "" : key.strip();
            }
            return server.isDedicatedServer() ? settings.key() : "";
        }

        private int version() {
            return trialEnabled() && settings.version() == 0 ? 1 : settings.version();
        }

        private JsonObject policy() {
            JsonObject result = settings.policy();
            if (trialEnabled() && settings.version() == 0) {
                JsonObject fields = new JsonObject();
                fields.addProperty("rules", "本机单人试用");
                fields.addProperty("brief", "问舟通过 DeepSeek API 回答游戏玩法问题。");
                fields.addProperty("details", "发送后，问题、相关前文和游戏查询资料会交给 DeepSeek 处理。只提交适合发给第三方的问题。");
                result.addProperty("version", 1);
                result.addProperty("protocol", fields.get("details").getAsString());
                result.add("text", fields);
            }
            return result;
        }

        Access access(UUID player) throws Exception {
            if (!server.isDedicatedServer()) {
                return new Access(
                        trial(player), trialReceipts.getOrDefault(player, new JsonObject()), 0);
            }
            var grant = DIRECTORY.granted(player);
            var receipt = DIRECTORY.read(player, WzDirectory.RECEIPT);
            if (!grant.get(4, TimeUnit.SECONDS)) return new Access(false, new JsonObject(), 0);
            JsonObject row = receipt.get(4, TimeUnit.SECONDS);
            return new Access(true, object(row, "value"), number(row, "revision"));
        }

        boolean admitted(Access access) {
            return access.granted
                    && version() > 0
                    && number(access.receipt, "version") == version();
        }

        long generation(UUID player) {
            return generations.computeIfAbsent(player, ignored -> 0L);
        }

        void changed(UUID player) {
            cancel(player, false);
            WzNetwork.event(server, player, generation(player), event("changed", "问舟服务状态已更新"));
        }

        void cancel(UUID player, boolean notify) {
            long newGeneration = generations.merge(player, 1L, Long::sum);
            live.computeIfPresent(
                    player, (id, entry) -> entry.generation < newGeneration ? null : entry);
            policyReads.remove(player);
            Job job = jobs.remove(player);
            if (job != null) {
                job.task.cancel(true);
                worker.remove(job.task);
                job.reply.complete(error("问舟权限或连接已变更"));
            }
            if (notify)
                WzNetwork.event(server, player, generation(player), event("revoked", "问舟准入已变更"));
        }

        void check(UUID player, long expected) throws InterruptedException {
            if (active != this
                    || generation(player) != expected
                    || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        }

        LiveUsage liveFor(UUID player, String session, long since) {
            LiveUsage entry = live.get(player);
            return entry != null
                            && entry.generation == generation(player)
                            && entry.createdAt >= since
                            && (session == null || session.equals(entry.conversation))
                    ? entry
                    : null;
        }

        long researchSince(UUID player) {
            try {
                if (server.isDedicatedServer()) {
                    JsonObject rows = DIRECTORY.accessRows(Set.of(player.toString()), true);
                    JsonObject fields = object(rows, player.toString());
                    return researchSinceFields(fields);
                }
                Access access = access(player);
                return access.granted ? researchSince(access.receipt) : -1;
            } catch (Exception unavailable) {
                return -1;
            }
        }

        long researchSinceFields(JsonObject fields) {
            if (!flag(object(fields, WzDirectory.GRANT), "value")) return -1;
            return researchSince(object(object(fields, WzDirectory.RECEIPT), "value"));
        }

        long researchSince(JsonObject receipt) {
            return WzStoredAdmin.researchSince(receipt, version());
        }

        JsonObject request(UUID player, String action, JsonObject body, long generation)
                throws Exception {
            Access access = access(player);
            check(player, generation);
            if (action.equals("status")) {
                JsonObject status = policy();
                boolean admitted = admitted(access);
                String entryNotice = "";
                if (access.granted) {
                    if (version() == 0) entryNotice = "问舟尚未发布使用协议，请联系服主。";
                    else if (key().isEmpty()) entryNotice = "问舟尚未配置模型服务，请联系服主。";
                    else if (!admitted && !flag(status, "showProtocol"))
                        entryNotice = "需要确认当前使用协议，请联系服主开启协议展示。";
                }
                status.addProperty("granted", access.granted && entryNotice.isEmpty());
                if (!entryNotice.isEmpty()) status.addProperty("entryNotice", entryNotice);
                if (access.granted && entryNotice.isEmpty()) {
                    boolean seen = server.isDedicatedServer()
                            ? DIRECTORY.read(player, WzDirectory.INTRO_SEEN).get(4, TimeUnit.SECONDS).get("value").getAsBoolean()
                            : trialIntros.contains(player);
                    status.addProperty("introSeen", seen);
                    status.addProperty("blockReferences", true);
                }
                if (access.granted && quotaLedger != null)
                    status.add("quota", playerQuota(DIRECTORY.quota(player).get(8, TimeUnit.SECONDS)));
                status.addProperty("consented", admitted);
                status.addProperty("research", admitted && flag(access.receipt, "research"));
                if (!access.granted) {
                    status.remove("protocol");
                    status.remove("text");
                }
                return status;
            }
            if (!access.granted || key().isEmpty() || version() == 0) return error("问舟尚未开放");
            if (action.equals("policy")) {
                if (!admitted(access))
                    policyReads.compute(
                            player,
                            (id, read) ->
                                    read != null && read.version == version()
                                            ? read
                                            : new PolicyRead(version(), System.nanoTime()));
                return policy();
            }
            if (action.equals("consent") || action.equals("research")) {
                int version = version();
                var submitted = body.get("version");
                if (action.equals("consent")
                                && (submitted == null
                                        || !submitted.isJsonPrimitive()
                                        || !Integer.toString(version)
                                                .equals(submitted.getAsString()))
                        || action.equals("research") && !admitted(access))
                    return error("请阅读当前版本协议");
                if (action.equals("consent") && !admitted(access)) {
                    PolicyRead read = policyReads.get(player);
                    if (read == null
                            || read.version != version
                            || System.nanoTime() - read.startedAt < TimeUnit.SECONDS.toNanos(30)
                            || !flag(body, "rulesAccepted")
                            || !flag(body, "privacyAccepted"))
                        return error("请阅读满 30 秒并确认使用规范和隐私协议");
                }
                JsonObject receipt = new JsonObject();
                receipt.addProperty("version", version);
                receipt.addProperty(
                        "acceptedAt",
                        admitted(access)
                                ? number(access.receipt, "acceptedAt")
                                : System.currentTimeMillis());
                boolean research =
                        flag(body, action.equals("consent") ? "research" : "enabled")
                                && server.isDedicatedServer();
                receipt.addProperty("research", research);
                receipt.addProperty("researchChangedAt", System.currentTimeMillis());
                receipt.addProperty(
                        "researchSince",
                        research
                                ? admitted(access) && flag(access.receipt, "research")
                                        ? number(access.receipt, "researchSince")
                                        : System.currentTimeMillis()
                                : 0);
                check(player, generation);
                if (server.isDedicatedServer())
                    DIRECTORY.saveReceipt(player, receipt, access.revision)
                            .get(4, TimeUnit.SECONDS);
                else trialReceipts.put(player, receipt);
                policyReads.remove(player);
                check(player, generation);
                JsonObject result = policy();
                result.addProperty("granted", true);
                result.addProperty("consented", true);
                result.addProperty("research", research);
                return result;
            }
            if (!admitted(access)) return error("请先确认当前版本协议");
            if (action.equals("introSeen")) {
                if (!body.entrySet().isEmpty()) return error("引导确认参数无效");
                check(player, generation);
                if (server.isDedicatedServer()) DIRECTORY.markIntroSeen(player).get(8, TimeUnit.SECONDS);
                else trialIntros.add(player);
                check(player, generation);
                JsonObject result = new JsonObject();
                result.addProperty("introSeen", true);
                return result;
            }
            if (action.equals("usage")) return charges.account(player);
            synchronized (history) {
                check(player, generation);
                JsonObject all = history.load(player);
                JsonObject result = new JsonObject();
                if (action.equals("list")) {
                    JsonArray saved = WzHistory.summaries(player, all, false, 0);
                    LiveUsage running = liveFor(player, null, 0);
                    if (running != null && !running.hidden && !all.has(running.conversation)) {
                        JsonArray includingRunning = new JsonArray();
                        JsonObject row = playerPendingRow(running);
                        includingRunning.add(row);
                        includingRunning.addAll(saved);
                        saved = includingRunning;
                    }
                    result.add("conversations", saved);
                    return result;
                }
                String id = string(body, "id");
                JsonObject thread = object(all, id);
                LiveUsage running = liveFor(player, id, 0);
                if (action.equals("dismissItemChoice")) {
                    if (flag(thread, "removed") || !thread.has("messages")) return error("未找到这段会话");
                    var turns = thread.getAsJsonArray("messages");
                    if (turns.isEmpty()) return error("物品选择已失效");
                    var last = turns.get(turns.size() - 1).getAsJsonObject();
                    if (!body.has("choiceId") || !string(body, "choiceId").equals(string(last, "requestId")))
                        return error("物品选择已失效");
                    if (last.remove("itemChoice") != null) history.save(player, all);
                    result.addProperty("dismissed", true);
                    return result;
                }
                if (action.equals("remove")) {
                    if (!WzHistory.removeForPlayer(
                            all, id, running != null, System.currentTimeMillis()))
                        return error("未找到这段会话");
                    history.save(player, all);
                    if (running != null) running.hidden = true;
                    result.addProperty("id", id);
                    return result;
                }
                if (thread.size() == 0 && running != null && !running.hidden) {
                    result.addProperty("id", id);
                    result.addProperty("running", true);
                    result.add("messages", new JsonArray());
                    return result;
                }
                if (thread.size() == 0 || flag(thread, "removed")) return error("未找到这段会话");
                result.addProperty("id", id);
                JsonArray messages = WzHistory.turns(thread, 0);
                for (var entry : messages) {
                    JsonObject turn = entry.getAsJsonObject();
                    turn.remove("answerText");
                    turn.remove("methodRefs");
                    turn.remove("topicResolvedToken");
                    turn.remove("topicMoved");
                }
                result.add("messages", messages);
                return result;
            }
        }

        private JsonObject playerPendingRow(LiveUsage running) {
            JsonObject row = new JsonObject();
            row.addProperty("id", running.conversation);
            row.addProperty("title", WzHistory.title(running.question));
            row.addProperty("updatedAt", running.createdAt);
            row.addProperty("count", 0);
            row.addProperty("running", true);
            return row;
        }

        JsonObject stopAsk(UUID player, JsonObject body) {
            Job job = jobs.get(player);
            String requestId = string(body, "requestId");
            boolean stopping = job != null && job.run.stop(requestId);
            if (stopping && worker.remove(job.task)) {
                job.task.cancel(false);
                jobs.remove(player, job);
                job.reply.complete(stoppedResult());
            }
            JsonObject ack = new JsonObject();
            ack.addProperty("stopping", stopping);
            return ack;
        }

        private JsonObject stoppedResult() {
            JsonObject result = new JsonObject();
            result.addProperty("stopped", true);
            result.addProperty("failure", "已停止回答");
            return result;
        }

        CompletableFuture<JsonObject> ask(UUID player, JsonObject body, long generation, WzWorldReferences.Ask world) {
            long receivedAt = System.currentTimeMillis();
            WzRunControl run = new WzRunControl(string(body, "requestId"));
            CompletableFuture<JsonObject> reply = new CompletableFuture<>();
            FutureTask<Void> task =
                    new FutureTask<>(
                            () -> {
                                JsonObject result;
                                try {
                                    run.begin();
                                    result = answer(player, body, generation, run, receivedAt, world);
                                } catch (InterruptedException cancelled) {
                                    Thread.currentThread().interrupt();
                                    result = run.stopped() ? stoppedResult() : error("问舟请求已结束");
                                } catch (WenzhouChat.Notice notice) {
                                    result = error(notice.getMessage());
                                } catch (Exception failed) {
                                    result = error("此次查询未完成，请稍后重试");
                                } finally {
                                    run.finish();
                                    live.computeIfPresent(
                                            player,
                                            (id, entry) ->
                                                    entry.generation == generation ? null : entry);
                                }
                                jobs.computeIfPresent(
                                        player, (id, job) -> job.reply == reply ? null : job);
                                reply.complete(result);
                                return null;
                            });
            Job job = new Job(task, reply, run);
            if (jobs.putIfAbsent(player, job) != null)
                return CompletableFuture.completedFuture(error("问舟正在处理上一个问题"));
            try {
                worker.execute(task);
            } catch (RejectedExecutionException busy) {
                jobs.remove(player, job);
                reply.complete(error("问舟正忙，请稍后重试"));
            }
            return reply;
        }

        void requireAccess(UUID player, long generation) throws Exception {
            check(player, generation);
            if (!admitted(access(player)) || key().isEmpty())
                throw new WenzhouChat.Notice("问舟准入或协议已变更");
            check(player, generation);
        }

        JsonObject answer(UUID player, JsonObject body, long generation, WzRunControl run, long receivedAt,
                          WzWorldReferences.Ask world) throws Exception {
            String question = string(body, "question").strip();
            if (question.isEmpty() || question.length() > 1500) return error("请输入 1 至 1500 字的有效问题");
            requireAccess(player, generation);
            int version = version();
            String requestedId = string(body, "id");
            boolean fresh = requestedId.isEmpty();
            String conversation = fresh ? UUID.randomUUID().toString() : requestedId;
            JsonObject snapshot = history.load(player);
            String token = string(body, "topicToken");
            boolean confirmed = flag(body, "topicConfirmed");
            TopicChoice choice = confirmed ? findTopic(snapshot, token) : null;
            if (confirmed
                    && (choice == null
                            || !question.equals(string(choice.turn, "question"))
                            || !fresh && !requestedId.equals(choice.threadId)))
                return error("话题确认已失效，请重新提问");
            if (choice != null && choice.completed) {
                if (fresh != flag(choice.turn, "topicMoved")) return error("话题已经处理");
                return answerResult(choice.threadId, choice.turn);
            }
            long createdAt = System.currentTimeMillis();
            JsonObject previous = object(snapshot, conversation);
            if (!fresh && (previous.size() == 0 || flag(previous, "removed")))
                return error("未找到这段会话");
            JsonArray context = new JsonArray();
            JsonArray messages = WzHistory.turns(previous, 0);
            if (!confirmed && !messages.isEmpty()) {
                JsonObject last = messages.get(messages.size() - 1).getAsJsonObject();
                if (last.has("topicToken")
                        && !last.has("reply")
                        && question.equals(string(last, "question")))
                    return answerResult(conversation, last);
            }
            if (messages.size() >= 36 && choice == null) return error("这段会话已达上限，请开始新对话");
            var completedTurns = new java.util.ArrayList<JsonObject>();
            messages.forEach(
                    element -> {
                        JsonObject turn = element.getAsJsonObject();
                        if (turn.has("reply")) completedTurns.add(turn);
                    });
            var recent = completedTurns.subList(Math.max(0, completedTurns.size() - 4), completedTurns.size());
            boolean privateHistory=recent.stream().anyMatch(t->flag(t,"personalFoodRead")||flag(t,"personalGameplayRead"));
            for (var turn : recent) {
                context.add(message("user", string(turn, "question")));
                context.add(message("assistant", string(turn, "answerText")));
            }
            if (!recent.isEmpty()) {
                var lastAnswer = context.get(context.size() - 1).getAsJsonObject();
                lastAnswer.addProperty("content", string(lastAnswer, "content") + WzTurnNotes.followupRefs(recent));
            }
            final JsonArray blockEvidence;
            if (world != null) {
                try {
                    blockEvidence = server.submit(world.evidence()::get).get(5, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException failure) {
                    if (failure.getCause() instanceof WzWorldReferences.Rejected rejected) return error(rejected.getMessage());
                    throw failure;
                }
                requireAccess(player, generation);
            } else {
                blockEvidence = new JsonArray();
            }
            var blockTools = new WzBlockTools(blockEvidence);
            String apiKey = key();
            long questionTime = choice == null ? createdAt : number(choice.turn, "createdAt");
            // A generated title can describe the oldest completed turn supplied to the model.
            long titleSince = questionTime;
            if (!completedTurns.isEmpty()) {
                JsonObject oldest = completedTurns.get(Math.max(0, completedTurns.size() - 4));
                titleSince = Math.min(titleSince, number(oldest, "createdAt"));
            }
            String requestId = string(body, "requestId");
            if (!requestId.isEmpty() && !requestId.matches("[0-9a-f-]{36}")) return error("请求标识无效");
            LiveUsage session =
                    new LiveUsage(generation, conversation, question, questionTime, requestId);
            synchronized (history) {
                check(player, generation);
                if (!fresh && flag(object(history.load(player), conversation), "removed"))
                    return error("这段会话已移除");
                live.put(player, session);
            }
            if (quotaLedger != null) {
                JsonObject account;
                try {
                    quotaLedger.flush(player);
                    account = DIRECTORY.admitQuota(player, receivedAt).get(8, TimeUnit.SECONDS);
                } catch (Exception unavailable) {
                    return error("暂时无法核实问舟额度，请稍后重试");
                }
                sendQuota(player, generation, account);
                if (!flag(account, "allowed")) {
                    JsonObject denied = error("本周问舟额度已用完，请在额度重置后再提问");
                    denied.add("quota", playerQuota(account));
                    return denied;
                }
            }
            WenzhouChat.Usage usage =
                    new WenzhouChat.Usage(
                            receipt -> {
                                if (quotaLedger != null) {
                                    try {
                                        JsonObject account = quotaLedger.record(player, receivedAt, receipt);
                                        if (account != null) sendQuota(player, generation, account);
                                    } catch (RuntimeException failed) {
                                        com.mojang.logging.LogUtils.getLogger()
                                                .error("WenZhou quota receipt could not be synchronized", failed);
                                    }
                                }
                                charges.record(player, receipt);
                                if (liveFor(player, conversation, 0) != session) return;
                                session.add(receipt);
                                if (session.hidden) return;
                                JsonObject event = event("usage", "");
                                event.addProperty("conversationId", conversation);
                                event.addProperty("requestId", requestId);
                                event.add("receipt", receipt);
                                WzNetwork.event(server, player, generation, event);
                            },
                            () -> {
                                if (liveFor(player, conversation, 0) != session) return;
                                session.missing = true;
                                if (session.hidden) return;
                                JsonObject event = event("usage", "");
                                event.addProperty("conversationId", conversation);
                                event.addProperty("requestId", requestId);
                                event.addProperty("missing", true);
                                WzNetwork.event(server, player, generation, event);
                            });
            var packContext = new WzPackContext(question);
            var workbench = blockEvidence.isEmpty() ? WzReply.WorkbenchRequest.parse(question) : null;
            var names = workbench == null ? CompletableFuture.<WzRegisteredItems.Catalog>completedFuture(null)
                    : WzRegisteredItems.index(server).exceptionally(unavailable -> null);
            WzReply rich = new WzReply(id -> {
                var catalog = names.getNow(null);
                String localized = catalog == null ? null : catalog.index().localizedName(id);
                return localized == null ? WenzhouReadTools.itemDisplayName(id) : localized;
            }, workbench);
            java.util.function.Consumer<JsonObject> sendDelta = update -> {
                if (run.stopped() || session.hidden || liveFor(player, conversation, 0) != session) return;
                if ("answer.delta".equals(string(update, "type")) && rich.hasDeterministicCard()) return;
                update.addProperty("conversationId", conversation);
                update.addProperty("requestId", requestId);
                WzNetwork.event(server, player, generation, update);
            };
            var model = new WenzhouChat.TitledModel(WenzhouChat.official(apiKey, () ->
                    new WzLiveText(rich::answerText, (type, part) -> sendDelta.accept(event(type, part)), marker -> {
                        var item = rich.streamItem(marker);
                        var update = event("answer.delta", item == null ? rich.answerText(marker) : string(item, "name"));
                        if (item != null) update.add("item", item);
                        sendDelta.accept(update);
                    })), !WzHistory.hasGeneratedTitle(previous));
            WzTurnNotes notes = new WzTurnNotes(gameplayMemory, player);
            JsonArray events = new JsonArray();
            String effort = settings.reasoning();
            String thoughtId = UUID.randomUUID().toString();
            boolean thinking = !"off".equals(effort);
            if (thinking)
                publicEvent(player, generation, events, conversation, thoughtId, "progress", "思考中", "", "");
            WenzhouChat.Result completed = null;
            boolean topic = false;
            String failure = "";
            try {
                completed =
                        WenzhouChat.ask(
                                context,
                                question,
                                request -> {
                                    requireAccess(player, generation);
                                    if (version() != version) throw new InterruptedException();
                                    WzSettings.applyReasoning(request, effort);
                                    if (session.hidden) {
                                        usage.skipUnsentCall();
                                        stopHidden(session);
                                    }
                                    return model.complete(request);
                                },
                                (mode, kind, target, page, purpose) -> {
                                    requireAccess(player, generation);
                                    stopHidden(session);
                                    String lookupId = UUID.randomUUID().toString();
                                    boolean calculation = kind.equals("calculate");
                                    JsonObject memoryArgs = "memory".equals(mode)
                                            ? com.google.gson.JsonParser.parseString(target).getAsJsonObject() : null;
                                    String summaryHint = "read_memory".equals(kind)
                                            ? notes.listedSummary(string(memoryArgs, "id")) : "";
                                    JsonObject blockInput = "block".equals(mode)
                                            ? com.google.gson.JsonParser.parseString(target).getAsJsonObject() : null;
                                    var activity = blockInput == null
                                            ? WzToolActivity.start(mode, kind, target, purpose, memoryArgs, summaryHint)
                                            : WzBlockToolActivity.start(kind, blockInput, blockEvidence);
                                    publicEvent(
                                                player,
                                                generation,
                                                events,
                                                conversation,
                                                lookupId,
                                                "tool.start",
                                                activity.label(),
                                                activity.icon(),
                                                "");
                                    JsonObject evidence = "memory".equals(mode)
                                            ? notes.execute(kind, memoryArgs)
                                            : calculation
                                            ? WzCalculator.calculate(target)
                                            : server.submit(
                                                            () -> {
                                                                if (run.stopped() || session.hidden
                                                                        || active != this
                                                                        || generation(player)
                                                                                != generation)
                                                                    return CompletableFuture
                                                                            .completedFuture(
                                                                                    new JsonObject());
                                                                if (blockInput != null) {
                                                                    var result = world.inspect(blockInput, WzBlockTools.READ.equals(kind));
                                                                    return CompletableFuture.completedFuture(result);
                                                                }
                                                                return WenzhouReadTools.query(
                                                                        server, mode, kind, target,
                                                                        page, purpose, player, packContext);
                                                            })
                                                    .thenCompose(result -> result)
                                                    .get(20, TimeUnit.SECONDS);
                                    check(player, generation);
                                    stopHidden(session);
                                    if (blockInput != null) evidence = blockTools.visibleResult(evidence);
                                    else {
                                        rich.collect(mode, kind, target, evidence);
                                        notes.observe(kind, target, evidence);
                                    }
                                    WzEvidenceSummary summary = blockInput == null
                                            ? WzEvidenceSummary.from(mode, kind, evidence)
                                            : WzBlockToolActivity.detail(kind, blockInput, evidence);
                                    var finished = activity.finished(kind, evidence);
                                    publicEvent(
                                                player,
                                                generation,
                                                events,
                                                conversation,
                                                lookupId,
                                                "tool.finish",
                                                finished.label(),
                                                finished.icon(),
                                                kind.equals("command") ? summary.outcome() : summary.detail());
                                    return evidence;
                                },
                                usage,
                                !confirmed && !context.isEmpty() && !(question.contains("[[item:") && !recent.isEmpty()
                                        && recent.get(recent.size() - 1).has("itemChoice")), blockEvidence.isEmpty() && !privateHistory,
                                new WzDiagnostics(conversation, requestId), blockTools,
                                answer -> {
                                    String invalid = rich.unverifiedReferences(rich.completeAnswer(answer));
                                    if (!invalid.isEmpty()) sendDelta.accept(event("answer.reset", ""));
                                    return invalid;
                                }, text -> {
                                    if (!run.stopped() && !session.hidden && liveFor(player, conversation, 0) == session)
                                        publicEvent(player, generation, events, conversation, "stage.report", text);
                                });
            } catch (WenzhouChat.TopicConfirmation requested) {
                topic = true;
            } catch (InterruptedException cancelled) {
                if (!run.stopped()) throw cancelled;
            } catch (Exception failed) {
                failure =
                        failed instanceof WenzhouChat.Notice notice
                                ? notice.getMessage()
                                : "此次查询未完成，请稍后重试";
            }
            boolean stopped = run.finish();
            if (stopped) {
                completed = null;
                topic = false;
                failure = "已停止回答";
                for (var entry : events.deepCopy()) {
                    JsonObject row = entry.getAsJsonObject();
                    if ("tool.start".equals(string(row, "type"))) {
                        publicEvent(player, generation, events, conversation, string(row, "id"),
                                "tool.finish", string(row, "text"), string(row, "icon"), "已停止查阅");
                    }
                }
                if (thinking) publicEvent(player, generation, events, conversation, thoughtId,
                        "progress", failure, "", "");
            }
            requireAccess(player, generation);
            if (version() != version) throw new InterruptedException();
            boolean keepWorkbench = rich.hasDeterministicCard()
                    && !stopped && !topic && !session.hidden && blockTools.denial() == null;
            if (keepWorkbench && !names.isDone()) {
                try {
                    names.get(2, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException unavailable) {
                    // The Minecraft client can still localize registered item stacks.
                }
            }
            if (keepWorkbench && completed == null)
                completed = new WenzhouChat.Result(new JsonArray(),
                        "本轮回答未完整生成。下方是已核实的工作台配方。", "已核对工作台配方");
            JsonObject turn = new JsonObject();
            turn.addProperty("question", question);
            if (notes.personalGameplayRead() || privateHistory || !blockEvidence.isEmpty()) turn.addProperty("personalGameplayRead",true);
            if (completed != null) {
                if (thinking) {
                    String summary = completed.summary();
                    publicEvent(player, generation, events, conversation, thoughtId, "progress",
                            summary.isEmpty() ? "已思考" : "思考 · " + summary, "", "");
                }
                if (completed.itemChoice() != null) turn.add("itemChoice", completed.itemChoice());
                String finalAnswer = rich.completeAnswer(completed.answer());
                turn.addProperty("answerText", rich.answerText(finalAnswer));
                var methodRefs = notes.methodRefs();
                if (methodRefs.size() > 0) turn.add("methodRefs", methodRefs);
                turn.add("reply", rich.render(finalAnswer));
                if (choice != null) {
                    turn.addProperty("topicResolvedToken", token);
                    turn.addProperty("topicMoved", fresh);
                }
            } else if (topic) {
                turn.addProperty("topicToken", UUID.randomUUID().toString());
                String prompt = "这个问题像是新话题。要继续当前对话，还是另开一段？";
                turn.addProperty("topicPrompt", prompt);
                publicEvent(player, generation, events, conversation, "topic.confirm", prompt);
            } else {
                if (stopped) turn.addProperty("stopped", true);
                else if (usage.calls == 0 && !session.hidden) return error(failure);
                turn.addProperty("failure", failure);
            }
            JsonArray receipts = new JsonArray();
            if (choice != null && choice.turn.has("usageReceipts"))
                receipts.addAll(choice.turn.getAsJsonArray("usageReceipts"));
            receipts.addAll(usage.receipts);
            turn.addProperty(
                    "createdAt", choice == null ? createdAt : number(choice.turn, "createdAt"));
            turn.addProperty("usage", usage.display());
            turn.add("usageReceipts", receipts);
            turn.addProperty(
                    "usageMissing",
                    usage.calls != usage.reported
                            || (choice != null && flag(choice.turn, "usageMissing")));
            turn.add("events", events);
            if (!requestId.isEmpty()) turn.addProperty("requestId", requestId);
            synchronized (history) {
                check(player, generation);
                JsonObject all = history.load(player);
                if (flag(object(all, conversation), "removed")) {
                    turn.remove("topicToken");
                    turn.remove("topicPrompt");
                    if (choice != null) turn.add("usageReceipts", usage.receipts.deepCopy());
                    WzHistory.appendRemoved(all, conversation, turn);
                    if (completed != null) WzHistory.setGeneratedTitle(object(all, conversation), model.title(), titleSince);
                    history.save(player, all);
                    live.remove(player, session);
                    JsonObject removed = new JsonObject();
                    removed.addProperty("id", conversation);
                    removed.addProperty("removed", true);
                    return removed;
                }
                if (choice != null) {
                    TopicChoice pending = findTopic(all, token, true);
                    if (pending == null || pending.completed) return error("话题确认已失效，请重新提问");
                    JsonArray original = object(all, pending.threadId).getAsJsonArray("messages");
                    if (completed == null) {
                        if (stopped) {
                            pending.turn.addProperty("stopped", true);
                        } else {
                            pending.turn.remove("stopped");
                        }
                        pending.turn.addProperty("failure", failure);
                        pending.turn.add("usageReceipts", receipts);
                        pending.turn.addProperty(
                                "usageMissing",
                                flag(pending.turn, "usageMissing")
                                        || usage.calls != usage.reported);
                        JsonArray priorEvents = pending.turn.getAsJsonArray("events");
                        JsonArray combined = new JsonArray();
                        if (priorEvents != null) {
                            for (int index = Math.max(0, priorEvents.size() - 24);
                                    index < priorEvents.size();
                                    index++) combined.add(priorEvents.get(index));
                        }
                        combined.addAll(events);
                        pending.turn.add("events", combined);
                        if (!fresh && !requestId.isEmpty())
                            pending.turn.addProperty("requestId", requestId);
                        history.save(player, all);
                        live.remove(player, session);
                        JsonObject result = answerResult(pending.threadId, pending.turn);
                        if (stopped) {
                            result.addProperty("stopped", true);
                            result.addProperty("failure", failure);
                        } else result.addProperty("error", failure);
                        return result;
                    }
                    original.remove(pending.turn);
                }
                JsonObject thread = object(all, conversation);
                if (!fresh && (thread.size() == 0 || flag(thread, "removed")))
                    return error("这段会话已移除");
                if (fresh) {
                    thread.addProperty("id", conversation);
                    thread.addProperty("title", WzHistory.title(question));
                    thread.add("messages", new JsonArray());
                    all.add(conversation, thread);
                }
                JsonArray turns = thread.getAsJsonArray("messages");
                if (turns.size() >= 36) return error("这段会话已达上限，请开始新对话");
                thread.addProperty("updatedAt", createdAt);
                turns.add(turn);
                if (completed != null) WzHistory.setGeneratedTitle(thread, model.title(), titleSince);
                history.save(player, all);
                live.remove(player, session);
            }
            return answerResult(conversation, turn);
        }

        private TopicChoice findTopic(JsonObject history, String token) {
            return findTopic(history, token, false);
        }

        private TopicChoice findTopic(JsonObject history, String token, boolean includeRemoved) {
            if (token.isEmpty()) return null;
            for (var entry : history.entrySet()) {
                JsonObject thread = entry.getValue().getAsJsonObject();
                if (!includeRemoved && flag(thread, "removed")) continue;
                for (var message : thread.getAsJsonArray("messages")) {
                    JsonObject turn = message.getAsJsonObject();
                    if (token.equals(string(turn, "topicToken")))
                        return new TopicChoice(entry.getKey(), turn, false);
                    if (token.equals(string(turn, "topicResolvedToken")))
                        return new TopicChoice(entry.getKey(), turn, true);
                }
            }
            return null;
        }

        private JsonObject answerResult(String id, JsonObject turn) {
            JsonObject result = turn.deepCopy();
            result.remove("question");
            result.remove("answerText");
            result.remove("methodRefs");
            result.remove("topicResolvedToken");
            result.remove("topicMoved");
            if (turn.has("failure") && !flag(turn, "stopped")) result.addProperty("error", string(turn, "failure"));
            result.addProperty("id", id);
            return result;
        }

        void publicEvent(
                UUID player,
                long generation,
                JsonArray events,
                String id,
                String type,
                String text) {
            publicEvent(
                    player,
                    generation,
                    events,
                    id,
                    UUID.randomUUID().toString(),
                    type,
                    text,
                    "",
                    "");
        }

        void publicEvent(
                UUID player,
                long generation,
                JsonArray events,
                String id,
                String eventId,
                String type,
                String text,
                String icon,
                String detail) {
            JsonObject event = event(type, text);
            event.addProperty("conversationId", id);
            event.addProperty("id", eventId);
            if (!icon.isEmpty()) event.addProperty("icon", icon);
            if (!detail.isEmpty()) event.addProperty("detail", detail);
            int position = events.size();
            for (int i = 0; i < events.size(); i++) {
                if (eventId.equals(string(events.get(i).getAsJsonObject(), "id"))) {
                    position = i;
                    break;
                }
            }
            if (position == events.size()) events.add(event);
            else events.set(position, event);
            LiveUsage session = liveFor(player, id, 0);
            if (session != null && !session.hidden && session.generation == generation) {
                JsonObject outbound = event.deepCopy();
                outbound.addProperty("requestId", session.requestId);
                WzNetwork.event(server, player, generation, outbound);
            }
        }
    }

    private static void stopHidden(LiveUsage session) throws WenzhouChat.Notice {
        if (session.hidden) throw new WenzhouChat.Notice("对话已移除，已停止后续查阅");
    }

    private static JsonObject message(String role, String content) {
        JsonObject value = new JsonObject();
        value.addProperty("role", role);
        value.addProperty("content", content);
        return value;
    }

    private static JsonObject event(String type, String text) {
        JsonObject result = new JsonObject();
        result.addProperty("type", type);
        result.addProperty("stage", type);
        result.addProperty("text", text);
        return result;
    }

    private static ThreadPoolExecutor pool(String name, int threads, int queue) {
        return new ThreadPoolExecutor(
                threads,
                threads,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue),
                runnable -> daemon(name, runnable));
    }

    private static Thread daemon(String name, Runnable work) {
        Thread thread = new Thread(work, "wenzhouai-" + name);
        thread.setDaemon(true);
        return thread;
    }
}
