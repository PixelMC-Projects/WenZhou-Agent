/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pixelmc.wenzhouai.network.WzClient;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class WzConversation {
    record Activity(String type, String id, String icon, String text, String detail, long since) {}

    static final class Turn {
        final String question;
        final List<Activity> activity = new ArrayList<>();
        final JsonArray receipts = new JsonArray();
        final WzStreamingReply stream = new WzStreamingReply();
        final long since = System.nanoTime();
        JsonObject reply, itemChoice;
        String choiceId = "";
        int selectedOption = -1;
        String topic = "", topicToken = "", error = "";
        boolean usageMissing;
        boolean persisted, stopping, stopped;
        long answered;
        long activityRevision;
        boolean open = true;
        float disclosure = 1;

        Turn(String question) {
            this.question = question;
        }

        void apply(JsonObject event) {
            String type = str(event, "type"), id = str(event, "id"), text = str(event, "text");
            switch (type) {
                case "answer.delta" -> {
                    if (stopping || stopped || reply != null) return;
                    if (event.has("item") && event.get("item").isJsonObject()) {
                        var stack = HubWenzhouAnswer.stack(event.getAsJsonObject("item"));
                        if (!stack.isEmpty()) text = net.minecraft.ChatFormatting.stripFormatting(stack.getHoverName().getString());
                    }
                    stream.append(text);
                }
                case "answer.reset" -> stream.reset();
                case "stage.report" -> {
                    if (!text.isBlank() && activity.stream().noneMatch(row -> row.id().equals(id))) {
                        activity.add(new Activity(type, id, "", text, "", System.nanoTime()));
                        activityRevision++;
                    }
                }
                case "progress" -> {
                    for (int i = 0; !id.isEmpty() && i < activity.size(); i++) {
                        Activity old = activity.get(i);
                        if (id.equals(old.id())) {
                            activity.set(i, new Activity(type, id, "", text, "", old.since()));
                            activityRevision++;
                            return;
                        }
                    }
                    if (!id.isEmpty() || activity.isEmpty()
                            || !activity.get(activity.size() - 1).type().equals(type)) {
                        activity.add(new Activity(type, id, "", text, "", System.nanoTime()));
                        activityRevision++;
                    }
                }
                case "tool.start", "tool.finish" -> {
                    activityRevision++;
                    String icon = str(event, "icon");
                    if (java.util.Set.of("event-output","worldgen","food-book","store-search","reward","trade","structure-loot").contains(icon)) text = registryTargetLabel(text);
                    for (int i = 0; !id.isEmpty() && i < activity.size(); i++) {
                        Activity row = activity.get(i);
                        if (!id.equals(row.id())) continue;
                        if (type.equals("tool.finish"))
                            activity.set(
                                    i,
                                    new Activity(
                                            type,
                                            id,
                                            icon.isEmpty() ? row.icon() : icon,
                                            text,
                                            str(event, "detail"),
                                            row.since()));
                        return;
                    }
                    if (type.equals("tool.finish")) {
                        for (int i = activity.size() - 1; i >= 0; i--) {
                            Activity row = activity.get(i);
                            if (!row.type().equals("tool.start")) continue;
                            activity.set(
                                    i,
                                    new Activity(
                                            type,
                                            row.id(),
                                            icon.isEmpty() ? row.icon() : icon,
                                            text,
                                            str(event, "detail"),
                                            row.since()));
                            return;
                        }
                    }
                    activity.add(
                            new Activity(
                                    type, id, icon, text, str(event, "detail"), System.nanoTime()));
                }
                case "topic.confirm" -> topic = text.isEmpty() ? str(event, "topicPrompt") : text;
                case "usage" -> {
                    JsonObject receipt = obj(event, "receipt");
                    if (receipt != null) addReceipt(receipt);
                    usageMissing |= flag(event, "missing");
                }
                default -> {}
            }
        }

        void addReceipt(JsonElement receipt) {
            if (!receipt.isJsonObject()) return;
            String key = str(receipt.getAsJsonObject(), "id");
            if (key.isEmpty()) {
                usageMissing = true;
                return;
            }
            for (JsonElement prior : receipts)
                if (key.equals(str(prior.getAsJsonObject(), "id"))) return;
            receipts.add(receipt);
        }
    }

    final List<Turn> turns = new ArrayList<>();
    JsonArray conversations = new JsonArray();
    JsonObject policy;
    WzQuota quota = WzQuota.OFF;
    private long nextQuotaCheck;

    void applyQuota(JsonObject envelope) {
        WzQuota updated = WzQuota.from(obj(envelope, "quota"));
        if (updated.resetAt() != quota.resetAt()) nextQuotaCheck = 0;
        quota = updated;
    }
    String selected, error = "", version = "";
    String listError = "", historyError = "";
    Turn pending;
    private String pendingThread, pendingRequest;
    private String pendingTopicToken = "";
    private boolean pendingWasFresh;
    private String failedThread;
    private Turn unresolvedFailure;
    private final java.util.Set<String> removedThreads = new java.util.HashSet<>();
    private long pendingSelection;
    boolean granted,
            consented,
            showProtocol,
            research,
            loadingHistory,
            loadingList,
            checking,
            writing;
    boolean blockReferences;
    // A missing field is an old server or a pending login. Only an explicit false opens the guide.
    private boolean introSeen = true, introShownHere, introWritePending;
    private int introWriteAttempts;
    private long introRetryAt;

    boolean introSeen() {
        return introSeen || introShownHere;
    }

    void markIntroSeen() {
        if (introSeen() || !entry()) return;
        introShownHere = true;
        acknowledgeIntro();
    }

    private void acknowledgeIntro() {
        introWritePending = true;
        introWriteAttempts++;
        request("introSeen", new JsonObject(), result -> {
            introWritePending = false;
            if (flag(result, "introSeen")) introSeen = true;
            else introRetryAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        });
    }

    String entryNotice = "";
    private Object connection;
    private long nextStatus, epoch, selectionEpoch;
    private boolean statusDirty = true, loadingPolicy, listDirty;

    // Network replies are delivered on the client thread by WzClient or marshalled here.
    void invalidateStatus() {
        statusDirty = true;
    }

    long epoch() {
        return epoch;
    }

    void refresh() {
        Minecraft mc = Minecraft.getInstance();
        Object current = mc.getConnection();
        if (current != connection) {
            connection = current;
            invalidateAccess();
        }
        if (!WzClient.available()) {
            if (granted || checking) invalidateAccess();
            return;
        }
        long now = System.nanoTime();
        if (introShownHere && !introSeen && !introWritePending && introWriteAttempts < 3
                && entry() && now >= introRetryAt) acknowledgeIntro();
        if (granted && quota.enabled() && quota.resetAt() > 0
                && System.currentTimeMillis() >= quota.resetAt() && now >= nextQuotaCheck) {
            statusDirty = true;
            nextQuotaCheck = now + 5_000_000_000L;
        }
        if (checking || !statusDirty || now < nextStatus) return;
        statusDirty = false;
        checking = true;
        WzClient.onEvent(this::onEvent);
        request(
                "status",
                new JsonObject(),
                result -> {
                    checking = false;
                    if (!str(result, "error").isEmpty()) {
                        statusDirty = true;
                        nextStatus = System.nanoTime() + 3_000_000_000L;
                        return;
                    }
                    boolean hadAccess = granted && (!showProtocol || consented);
                    entryNotice = str(result, "entryNotice");
                    granted = flag(result, "granted");
                    introSeen = !granted || !result.has("introSeen") || flag(result, "introSeen");
                    blockReferences = granted && flag(result, "blockReferences");
                    if (granted) applyQuota(result);
                    else quota = WzQuota.OFF;
                    showProtocol = flag(result, "showProtocol");
                    consented = flag(result, "consented");
                    research = flag(result, "research");
                    String nextVersion = version(result);
                    if (!version.equals(nextVersion)) {
                        version = nextVersion;
                        policy = null;
                        error = "";
                    }
                    if (!granted || hadAccess && showProtocol && !consented) forgetConversations();
                });
    }

    void invalidateAccess() {
        introSeen = true;
        introShownHere = introWritePending = blockReferences = false;
        introWriteAttempts = 0;
        introRetryAt = 0;
        entryNotice = "";
        granted = consented = showProtocol = research = checking = writing = false;
        nextStatus = nextQuotaCheck = 0;
        quota = WzQuota.OFF;
        statusDirty = true;
        version = "";
        policy = null;
        error = "";
        forgetConversations();
    }

    private void forgetConversations() {
        epoch++;
        selectionEpoch++;
        selected = null;
        pending = null;
        pendingThread = pendingRequest = null;
        pendingTopicToken = "";
        pendingWasFresh = false;
        failedThread = null;
        unresolvedFailure = null;
        removedThreads.clear();
        listDirty = false;
        listError = historyError = "";
        loadingHistory = loadingList = writing = checking = loadingPolicy = false;
        turns.clear();
        conversations = new JsonArray();
    }

    private void onEvent(JsonObject event) {
        Minecraft.getInstance()
                .execute(
                        () -> {
                            switch (str(event, "type")) {
                                case "revoked" -> invalidateAccess();
                                case "quota" -> {
                                    if (granted) applyQuota(event);
                                }
                                case "changed", "quota.changed" -> {
                                    nextStatus = 0;
                                    statusDirty = true;
                                    refresh();
                                }
                                default -> applyPendingEvent(event);
                            }
                        });
    }

    void applyPendingEvent(JsonObject event) {
        if (pending == null
                || pendingRequest == null
                || !pendingRequest.equals(str(event, "requestId"))) return;
        String thread = str(event, "conversationId");
        if (removedThreads.contains(thread) || removedThreads.contains(pendingThread)) return;
        if (pendingThread == null && !thread.isEmpty()) {
            pendingThread = thread;
            if (pendingSelection == selectionEpoch && selected == null) selected = thread;
            addPendingRow();
        } else if (!thread.isEmpty() && !thread.equals(pendingThread)) {
            return;
        }
        pending.apply(event);
    }

    private void addPendingRow() {
        if (pending == null || pendingThread == null || removedThreads.contains(pendingThread))
            return;
        for (JsonElement entry : conversations)
            if (entry.isJsonObject() && pendingThread.equals(str(entry.getAsJsonObject(), "id")))
                return;
        JsonObject row = id("id", pendingThread);
        int length = Math.min(44, pending.question.codePointCount(0, pending.question.length()));
        row.addProperty(
                "title",
                pending.question.substring(0, pending.question.offsetByCodePoints(0, length)));
        row.addProperty("updatedAt", System.currentTimeMillis());
        row.addProperty("count", 0);
        row.addProperty("running", true);
        JsonArray combined = new JsonArray();
        combined.add(row);
        combined.addAll(conversations);
        conversations = combined;
    }

    private void removePendingRow(String thread) {
        if (thread == null) return;
        for (int index = 0; index < conversations.size(); index++) {
            JsonElement entry = conversations.get(index);
            if (entry.isJsonObject()
                    && flag(entry.getAsJsonObject(), "running")
                    && thread.equals(str(entry.getAsJsonObject(), "id"))) {
                conversations.remove(index);
                return;
            }
        }
    }

    void loadPolicy() {
        if (loadingPolicy) return;
        loadingPolicy = true;
        error = "";
        request(
                "policy",
                new JsonObject(),
                result -> {
                    loadingPolicy = false;
                    if (!failed(result)) policy = result;
                });
    }

    void consent(boolean value) {
        if (writing || version.isEmpty()) return;
        writing = true;
        JsonObject payload = id("version", version);
        payload.addProperty("rulesAccepted", true);
        payload.addProperty("privacyAccepted", true);
        payload.addProperty("research", value);
        request(
                "consent",
                payload,
                result -> {
                    writing = false;
                    if (failed(result)) return;
                    consented = flag(result, "consented");
                    research = flag(result, "research");
                    nextStatus = 0;
                    statusDirty = true;
                });
    }

    void research(boolean value) {
        if (writing) return;
        writing = true;
        JsonObject payload = new JsonObject();
        payload.addProperty("enabled", value);
        request(
                "research",
                payload,
                result -> {
                    writing = false;
                    if (!failed(result)) research = flag(result, "research");
                });
    }

    void list() {
        if (loadingList) {
            listDirty = true;
            return;
        }
        loadingList = true;
        listError = "";
        request(
                "list",
                new JsonObject(),
                result -> {
                    loadingList = false;
                    listError = str(result, "error");
                    if (listError.isEmpty()) {
                        conversations = new JsonArray();
                        for (JsonElement entry : array(result, "conversations")) {
                            if (!entry.isJsonObject()) continue;
                            JsonObject row = entry.getAsJsonObject();
                            String id = str(row, "id");
                            if (!removedThreads.contains(id)
                                    && (!flag(row, "running") || id.equals(pendingThread)))
                                conversations.add(row);
                        }
                        addPendingRow();
                    }
                    if (listDirty) {
                        listDirty = false;
                        list();
                    }
                });
    }

    void remove(String id, Runnable done, Consumer<String> failed) {
        if (writing) return;
        writing = true;
        request(
                "remove",
                id("id", id),
                result -> {
                    writing = false;
                    String error = str(result, "error");
                    if (!error.isEmpty()) {
                        failed.accept(error);
                        return;
                    }
                    markRemoved(id);
                    list();
                    done.run();
                });
    }

    void markRemoved(String thread) {
        removedThreads.add(thread);
        for (int index = conversations.size() - 1; index >= 0; index--) {
            JsonElement entry = conversations.get(index);
            if (entry.isJsonObject() && thread.equals(str(entry.getAsJsonObject(), "id")))
                conversations.remove(index);
        }
        if (thread.equals(failedThread)) {
            failedThread = null;
            unresolvedFailure = null;
        }
        if (thread.equals(selected)) beginSelection(null);
    }

    void choose(String id) {
        if (id != null && removedThreads.contains(id)) return;
        long current = beginSelection(id);
        if (id == null) return;
        request("history", id("id", id), result -> applyHistory(id, current, result));
    }

    long beginSelection(String id) {
        long current = ++selectionEpoch;
        selected = id;
        turns.clear();
        historyError = error = "";
        loadingHistory = id != null;
        return current;
    }

    void applyHistory(String id, long current, JsonObject result) {
        if (current != selectionEpoch || !id.equals(selected)) return;
        loadingHistory = false;
        historyError = str(result, "error");
        if (!historyError.isEmpty()) return;
        JsonArray messages = array(result, "messages");
        if (messages.isEmpty() && (pending == null || !id.equals(pendingThread))) {
            historyError = "这段对话没有可读取的历史消息";
            return;
        }
        Turn visiblePending = id.equals(pendingThread) ? pending : null;
        boolean pendingIncluded = false;
        for (JsonElement element : messages) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            boolean sameRequest =
                    visiblePending != null && pendingRequest.equals(str(row, "requestId"));
            if (sameRequest && pendingIncluded) continue;
            if (!sameRequest
                    && visiblePending != null
                    && !pendingTopicToken.isEmpty()
                    && pendingTopicToken.equals(str(row, "topicToken"))) continue;
            Turn turn = sameRequest ? visiblePending : new Turn(str(row, "question"));
            applyTurnRecord(turn, row);
            turn.persisted = true;
            if (sameRequest) pendingIncluded = true;
            turns.add(turn);
        }
        if (visiblePending != null && !pendingIncluded) turns.add(visiblePending);
        if (id.equals(failedThread) && unresolvedFailure != null) {
            turns.add(unresolvedFailure);
            error = unresolvedFailure.error;
        }
    }

    private void applyTurnRecord(Turn turn, JsonObject row) {
        turn.reply = obj(row, "reply");
        if (turn.reply != null) turn.open = false;
        turn.itemChoice = obj(row, "itemChoice");
        turn.choiceId = str(row, "requestId");
        turn.stopped = flag(row, "stopped");
        turn.error = turn.stopped ? "" : str(row, "failure");
        turn.topic = str(row, "topicPrompt");
        turn.topicToken = str(row, "topicToken");
        turn.usageMissing |= flag(row, "usageMissing");
        array(row, "usageReceipts").forEach(turn::addReceipt);
        JsonArray events = array(row, "events");
        if (!events.isEmpty() && !turn.activity.isEmpty()) {
            turn.activity.clear();
            turn.activityRevision++;
        }
        for (JsonElement event : events)
            if (event.isJsonObject()) turn.apply(event.getAsJsonObject());
    }

    void dismissItemChoice(Turn turn, Runnable openPicker, Consumer<String> errorNotice) {
        if (busy() || selected == null || turn.itemChoice == null) return;
        String thread = selected;
        long epoch = selectionEpoch;
        var body = id("id", thread);
        body.addProperty("choiceId", turn.choiceId);
        writing = true;
        request("dismissItemChoice", body, ack -> {
            writing = false;
            if (epoch != selectionEpoch || !thread.equals(selected)) return;
            if (!flag(ack, "dismissed")) { errorNotice.accept(str(ack, "error")); return; }
            turn.itemChoice = null;
            openPicker.run();
        });
    }

    private static String registryTargetLabel(String label) {
        int split = label.lastIndexOf(" · ");
        if (split < 0) return label;
        var id = net.minecraft.resources.ResourceLocation.tryParse(label.substring(split + 3));
        if (id == null) return label;
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id);
        var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(id);
        String name = item.map(i -> net.minecraft.client.resources.language.I18n.get(i.getDescriptionId()))
                .orElseGet(() -> block.map(b -> b.getName().getString()).orElse(""));
        return name.isBlank() || name.startsWith("item.") || name.startsWith("block.") ? label
                : label.substring(0, split + 3) + net.minecraft.ChatFormatting.stripFormatting(name);
    }

    void retryHistory() {
        if (selected != null && !loadingHistory) choose(selected);
    }

    boolean entry() {
        return granted && (showProtocol || consented);
    }

    boolean busy() {
        return pending != null || writing || loadingHistory || !historyError.isEmpty();
    }

    Turn visiblePending() {
        if (pending == null || !turns.contains(pending)) return null;
        boolean sameThread = pendingThread == null
                ? pendingSelection == selectionEpoch
                : pendingThread.equals(selected);
        return sameThread ? pending : null;
    }

    void stop(Consumer<String> notice) {
        Turn turn = visiblePending();
        if (turn == null || turn.stopping) return;
        turn.stopping = true;
        request("stop", id("requestId", pendingRequest), ack -> {
            if (pending != turn) return;
            String message = str(ack, "error");
            if (!message.isEmpty() || !ack.has("stopping")) {
                turn.stopping = false;
                if (turn == visiblePending())
                    notice.accept(message.isEmpty() ? "停止请求未确认，可以重试。" : message);
            }
            // Either stop was accepted, or the ask is already finishing. Its reply is authoritative.
        });
    }

    void ask(String question, boolean topicConfirmed, boolean startNew) {
        if (busy() || quota.exhausted() || !granted || !consented || question.isBlank()) return;
        error = "";
        Turn waiting = topicConfirmed && !turns.isEmpty() ? turns.get(turns.size() - 1) : null;
        if (waiting != null && waiting.topicToken.isEmpty()) return;
        List<Turn> origin = startNew ? new ArrayList<>(turns) : List.of();
        String previous = selected;
        if (waiting != null && !startNew) turns.remove(turns.size() - 1);
        if (startNew) choose(null);
        long current = selectionEpoch;
        Turn turn = new Turn(question);
        if (waiting != null) {
            waiting.receipts.forEach(turn::addReceipt);
            turn.usageMissing = waiting.usageMissing;
        }
        turns.add(turn);
        stageAsk(turn, selected, current);
        if (waiting != null) pendingTopicToken = waiting.topicToken;
        JsonObject payload = id("question", question);
        payload.addProperty("requestId", pendingRequest);
        if (selected != null) payload.addProperty("id", selected);
        if (waiting != null) {
            payload.addProperty("topicConfirmed", true);
            payload.addProperty("topicToken", waiting.topicToken);
        }
        request(
                "ask",
                payload,
                result -> {
                    if (pending != turn) return;
                    if (obj(result, "quota") != null) applyQuota(result);
                    String original = pendingThread;
                    boolean visible = settleResult(turn, result);
                    if (original != null || !str(result, "id").isEmpty()) list();
                    if (!visible) return;
                    if (loadingHistory || !turns.contains(turn)) {
                        if (loadingHistory && original != null && original.equals(failedThread))
                            return;
                        if (original != null) choose(original);
                        return;
                    }
                    array(result, "usageReceipts").forEach(turn::addReceipt);
                    turn.usageMissing |= flag(result, "usageMissing");
                    if (failed(result)) {
                        if (waiting != null && startNew) {
                            selected = previous;
                            turns.clear();
                            turns.addAll(origin);
                            turn.receipts.forEach(waiting::addReceipt);
                            waiting.usageMissing |= turn.usageMissing;
                            waiting.error = error;
                            return;
                        }
                        turn.error = error;
                    }
                    String id = str(result, "id");
                    if (!id.isEmpty()) selected = id;
                    turn.stopped = flag(result, "stopped");
                    turn.reply = obj(result, "reply");
                    if (turn.reply != null) turn.open = false;
                    turn.itemChoice = obj(result, "itemChoice");
                    turn.choiceId = str(result, "requestId");
                    turn.answered = turn.stream.started() ? 0 : System.nanoTime();
                    JsonArray activity = array(result, "events");
                    if (!activity.isEmpty()) {
                        turn.activity.clear();
                        turn.activityRevision++;
                    }
                    for (JsonElement event : activity)
                        if (event.isJsonObject()) turn.apply(event.getAsJsonObject());
                    turn.topicToken = str(result, "topicToken");
                    if (!str(result, "topicPrompt").isEmpty())
                        turn.topic = str(result, "topicPrompt");
                });
    }

    String stageAsk(Turn turn, String thread, long selection) {
        pending = turn;
        pendingThread = thread;
        pendingWasFresh = thread == null;
        pendingSelection = selection;
        pendingTopicToken = "";
        pendingRequest = UUID.randomUUID().toString();
        return pendingRequest;
    }

    boolean settle(Turn turn, long startedSelection) {
        if (pending != turn) return false;
        boolean visible =
                pendingThread == null
                        ? startedSelection == selectionEpoch
                        : pendingThread.equals(selected);
        pending = null;
        pendingThread = pendingRequest = null;
        pendingTopicToken = "";
        pendingWasFresh = false;
        return visible;
    }

    boolean settleResult(Turn turn, JsonObject result) {
        if (pending != turn) return false;
        String thread = pendingThread;
        boolean wasFresh = pendingWasFresh;
        String message = str(result, "error");
        boolean withoutRecord = !message.isEmpty() && str(result, "id").isEmpty();
        boolean visible = settle(turn, pendingSelection);
        removePendingRow(thread);
        if (flag(result, "removed")) {
            String id = str(result, "id");
            if (!id.isEmpty()) markRemoved(id);
            return false;
        }
        if (thread != null && removedThreads.contains(thread)) return false;
        if (!withoutRecord) return visible;
        if (turn.persisted) return false;
        turn.error = message;
        turn.answered = System.nanoTime();
        if (wasFresh && visible) {
            selected = null;
            loadingHistory = false;
            if (!turns.contains(turn)) turns.add(turn);
        } else if (thread != null && !wasFresh) {
            failedThread = thread;
            unresolvedFailure = turn;
        }
        if (visible) error = message;
        return visible;
    }

    private boolean failed(JsonObject value) {
        String message = str(value, "error");
        if (message.isEmpty()) return false;
        error = message;
        return true;
    }

    private void request(String action, JsonObject payload, Consumer<JsonObject> then) {
        long stamp = epoch;
        Object current = connection;
        CompletableFuture<JsonObject> request = WzClient.request(action, payload);
        request.whenComplete(
                (result, failure) ->
                        Minecraft.getInstance()
                                .execute(
                                        () -> {
                                            if (stamp != epoch
                                                    || current
                                                            != Minecraft.getInstance()
                                                                    .getConnection()) return;
                                            then.accept(
                                                    failure == null && result != null
                                                            ? result
                                                            : id("error", "问舟连接中断，请稍后重试。"));
                                        }));
    }

    static JsonObject id(String field, String value) {
        JsonObject result = new JsonObject();
        result.addProperty(field, value);
        return result;
    }

    static String str(JsonObject value, String key) {
        JsonElement v = value == null ? null : value.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()
                ? v.getAsString()
                : "";
    }

    private static String version(JsonObject value) {
        JsonElement element = value.get("version");
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    static boolean flag(JsonObject value, String key) {
        JsonElement v = value.get(key);
        return v != null
                && v.isJsonPrimitive()
                && v.getAsJsonPrimitive().isBoolean()
                && v.getAsBoolean();
    }

    static long number(JsonObject value, String key, long fallback) {
        JsonElement v = value.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber())
            return fallback;
        try {
            return v.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException ignored) {
            return fallback;
        }
    }

    static JsonObject obj(JsonObject value, String key) {
        JsonElement v = value.get(key);
        return v != null && v.isJsonObject() ? v.getAsJsonObject() : null;
    }

    static JsonArray array(JsonObject value, String key) {
        JsonElement v = value.get(key);
        return v != null && v.isJsonArray() ? v.getAsJsonArray() : new JsonArray();
    }
}
