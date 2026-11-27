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
        String topic = "", topicToken = "", error = "", modelSelection = "";
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
                        if (!stack.isEmpty())
                            text =
                                    net.minecraft.ChatFormatting.stripFormatting(
                                            stack.getHoverName().getString());
                    }
                    stream.append(text);
                }
                case "answer.reset" -> stream.reset();
                case "stage.report" -> {
                    if (!text.isBlank()
                            && activity.stream().noneMatch(row -> row.id().equals(id))) {
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
                    if (!id.isEmpty()
                            || activity.isEmpty()
                            || !activity.get(activity.size() - 1).type().equals(type)) {
                        activity.add(new Activity(type, id, "", text, "", System.nanoTime()));
                        activityRevision++;
                    }
                }
                case "tool.start", "tool.finish" -> {
                    activityRevision++;
                    String icon = str(event, "icon");
                    if (java.util.Set.of(
                                    "event-output",
                                    "worldgen",
                                    "food-book",
                                    "store-search",
                                    "reward",
                                    "trade",
                                    "structure-loot")
                            .contains(icon)) text = registryTargetLabel(text);
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

    java.util.function.BiConsumer<String, String> openLink = (kind, id) -> {};
    final java.util.LinkedHashMap<String, String> unread = new java.util.LinkedHashMap<>();
    private final java.util.Set<String> acknowledging = new java.util.HashSet<>();
    private long unreadRevision, reminderRevision, retryReadAt;
    boolean socialEnabled, completionReminder = true, unreadReminder = true, savingReminder;

    String resumeThread() {
        if (pending != null) return pendingThread;
        return unread.isEmpty() ? null : unread.keySet().iterator().next();
    }

    void resumeFresh() {
        if (pending != null && pendingThread == null) {
            pendingSelection = selectionEpoch;
            turns.add(pending);
        }
    }

    void markVisibleRead() {
        String thread = selected, token = unread.get(thread);
        if (token == null
                || loadingHistory
                || turns.isEmpty()
                || turns.stream().noneMatch(turn -> token.equals(turn.choiceId))
                || System.nanoTime() < retryReadAt
                || !acknowledging.add(thread)) return;
        var body = id("id", thread);
        body.addProperty("turn", token);
        request(
                "read",
                body,
                result -> {
                    acknowledging.remove(thread);
                    if (flag(result, "ok")) {
                        unread.remove(thread, token);
                        unreadRevision++;
                    } else retryReadAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                });
    }

    private void preferences(JsonObject value) {
        completionReminder = value == null || !value.has("completion") || flag(value, "completion");
        unreadReminder = value == null || !value.has("unread") || flag(value, "unread");
    }

    void toggleReminder(boolean completion, Consumer<String> errorNotice) {
        if (savingReminder) return;
        var body = id("kind", completion ? "completion" : "unread");
        body.addProperty("enabled", !(completion ? completionReminder : unreadReminder));
        savingReminder = true;
        reminderRevision++;
        request(
                "reminder",
                body,
                result -> {
                    savingReminder = false;
                    reminderRevision++;
                    if (result.has("error")) errorNotice.accept(str(result, "error"));
                    else preferences(result);
                });
    }

    private void completed(JsonObject result, Turn turn) {
        String thread = str(result, "id"), token = str(result, "unreadTurn");
        if (thread.isEmpty()
                || token.isEmpty()
                || !token.equals(str(result, "requestId"))
                || flag(result, "removed")
                || removedThreads.contains(thread)) return;
        var previous = new java.util.LinkedHashMap<>(unread);
        previous.remove(thread);
        unread.clear();
        unread.put(thread, token);
        unread.putAll(previous);
        unreadRevision++;
        var mc = Minecraft.getInstance();
        if (completionReminder
                && obj(result, "reply") != null
                && !result.has("itemChoice")
                && !result.has("error")
                && !flag(result, "stopped")
                && mc.player != null
                && !(mc.screen instanceof com.pixelmc.hub.client.HubScreen)) {
            String title = str(result, "title");
            mc.player.displayClientMessage(
                    com.pixelmc.wenzhouai.network.WzChatLinks.completed(
                            title.isBlank() ? turn.question : title, thread),
                    false);
        }
    }

    final List<Turn> turns = new ArrayList<>();
    JsonArray conversations = new JsonArray();
    WzQuota quota = WzQuota.OFF;
    private WzQuota terraQuota = WzQuota.OFF;
    private boolean projectBillingBlocked;
    private long nextQuotaCheck, terraQuotaRevision;
    private WzModelPicker.State modelPicker;
    private long modelRevision;
    private boolean modelWriting, modelRefreshing;

    WzModelPicker.State modelPicker() {
        return modelPicker;
    }

    boolean modelUpdating() {
        return modelWriting || modelRefreshing;
    }

    boolean chatGptSelected() {
        return modelPicker != null && modelPicker.chatGpt();
    }

    boolean quotaExhausted() {
        return quotaExhausted(null);
    }

    boolean terraSelected() {
        return modelPicker != null && modelPicker.terra();
    }

    WzQuota activeQuota(Turn turn) {
        String id =
                turn == null || turn.modelSelection.isEmpty()
                        ? modelPicker == null ? WzModelPicker.DEEPSEEK : modelPicker.selected().id()
                        : turn.modelSelection.split("/", 2)[0];
        return switch (id) {
            case WzModelPicker.TERRA -> projectBillingBlocked ? quota : terraQuota;
            case WzModelPicker.DEEPSEEK -> quota;
            default -> WzQuota.OFF;
        };
    }

    boolean quotaExhausted(Turn turn) {
        return activeQuota(turn).exhausted();
    }

    private void applyTerraQuota(JsonObject quota) {
        WzQuota updated = WzQuota.terra(quota);
        if (updated.resetAt() != terraQuota.resetAt()) nextQuotaCheck = 0;
        terraQuota = updated;
        terraQuotaRevision++;
    }

    private void applyModelPicker(JsonObject data, long quotaRead) {
        modelPicker = WzModelPicker.State.from(data);
        if (modelPicker == null || !modelPicker.accepts(WzModelPicker.TERRA, null))
            applyTerraQuota(null);
        else if (quotaRead == terraQuotaRevision) applyTerraQuota(obj(data, "terraQuota"));
    }

    void setModel(String id, String effort, Consumer<String> notice) {
        WzModelPicker.State state = modelPicker;
        if (busy() || !granted || state == null || !state.accepts(id, effort)) return;
        if (state.selected().id().equals(id) && (effort == null || effort.equals(state.effort())))
            return;
        JsonObject body = id("model", id);
        if (effort != null) body.addProperty("effort", effort);
        modelWriting = true;
        long revision = ++modelRevision, quotaRead = terraQuotaRevision;
        request(
                "model.set",
                body,
                result -> {
                    modelWriting = false;
                    nextStatus = 0;
                    statusDirty = true;
                    JsonObject confirmed = obj(result, "modelPicker");
                    String message = str(result, "error");
                    if (!message.isEmpty() || confirmed == null) {
                        modelRefreshing = true;
                        notice.accept(message.isEmpty() ? "模型设置未确认，正在重新读取。" : message);
                        return;
                    }
                    if (revision == modelRevision) applyModelPicker(confirmed, quotaRead);
                });
    }

    void applyQuota(JsonObject envelope) {
        WzQuota updated = WzQuota.from(obj(envelope, "quota"));
        if (updated.resetAt() != quota.resetAt()) nextQuotaCheck = 0;
        quota = updated;
    }

    String selected, error = "";
    String listError = "", historyError = "";
    Turn pending;
    private String pendingThread, pendingRequest;
    private String pendingTopicToken = "";
    private boolean pendingWasFresh;
    private String failedThread;
    private Turn unresolvedFailure;
    private final java.util.Set<String> removedThreads = new java.util.HashSet<>();
    private long pendingSelection;
    boolean granted, loadingHistory, loadingList, checking, writing;
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
        request(
                "introSeen",
                new JsonObject(),
                result -> {
                    introWritePending = false;
                    if (flag(result, "introSeen")) introSeen = true;
                    else introRetryAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                });
    }

    String entryNotice = "";
    private Object connection;
    private long entryRequest, nextStatus, nextChannelCheck, epoch, selectionEpoch;
    private int statusFailures;
    private boolean statusDirty = true, listDirty;
    boolean entryConfirmed;

    boolean prefetchDue() {
        return statusDirty
                && !checking
                && statusFailures < 3
                && System.nanoTime() >= Math.max(nextStatus, nextChannelCheck);
    }

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
            if (granted || checking || entryConfirmed) invalidateAccess();
            nextChannelCheck = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            return;
        }
        long now = System.nanoTime();
        if (introShownHere
                && !introSeen
                && !introWritePending
                && introWriteAttempts < 3
                && entry()
                && now >= introRetryAt) acknowledgeIntro();
        long wallTime = System.currentTimeMillis();
        boolean deepSeekReset = quota.resetAt() > 0 && wallTime >= quota.resetAt();
        boolean terraReset = terraQuota.resetAt() > 0 && wallTime >= terraQuota.resetAt();
        if (granted && (deepSeekReset || terraReset) && now >= nextQuotaCheck) {
            statusDirty = true;
            nextQuotaCheck = now + 5_000_000_000L;
        }
        if (checking || !statusDirty || now < nextStatus) return;
        statusDirty = false;
        checking = true;
        long modelRead = modelRevision,
                unreadRead = unreadRevision,
                reminderRead = reminderRevision;
        long quotaRead = terraQuotaRevision;
        WzClient.onEvent(this::onEvent);
        var capabilities = new JsonObject();
        capabilities.addProperty("shareLinks", true);
        entryRequest++;
        if (!granted && entryNotice.isEmpty())
            capabilities.addProperty("entryRequest", entryRequest);
        request(
                "status",
                capabilities,
                result -> {
                    checking = false;
                    entryConfirmed = false;
                    if (!str(result, "error").isEmpty()) {
                        statusFailures++;
                        statusDirty = true;
                        nextStatus = System.nanoTime() + 3_000_000_000L;
                        return;
                    }
                    statusFailures = 0;
                    socialEnabled = number(result, "shareVersion", 0) >= 1;
                    boolean hadGrant = granted;
                    entryNotice = str(result, "entryNotice");
                    granted = flag(result, "granted");
                    introSeen = !granted || !result.has("introSeen") || flag(result, "introSeen");
                    blockReferences = granted && flag(result, "blockReferences");
                    if (granted && !savingReminder && reminderRead == reminderRevision)
                        preferences(obj(result, "reminders"));
                    if (granted && unreadRead == unreadRevision && result.has("unread")) {
                        unread.clear();
                        for (var element : array(result, "unread")) {
                            var row = element.getAsJsonObject();
                            unread.put(str(row, "id"), str(row, "unreadTurn"));
                        }
                    }
                    projectBillingBlocked = granted && flag(result, "projectBillingBlocked");
                    if (granted) applyQuota(result);
                    else quota = WzQuota.OFF;
                    if (hadGrant && !granted) {
                        forgetConversations();
                    } else if (!modelWriting && modelRead == modelRevision) {
                        applyModelPicker(obj(result, "modelPicker"), quotaRead);
                        modelRefreshing = false;
                    }
                });
    }

    void invalidateAccess() {
        entryRequest++;
        statusFailures = 0;
        entryConfirmed = false;
        introSeen = true;
        introShownHere = introWritePending = blockReferences = false;
        introWriteAttempts = 0;
        introRetryAt = 0;
        socialEnabled = false;
        entryNotice = "";
        granted = checking = writing = false;
        nextStatus = nextChannelCheck = nextQuotaCheck = 0;
        quota = WzQuota.OFF;
        projectBillingBlocked = false;
        statusDirty = true;
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
        unread.clear();
        acknowledging.clear();
        unreadRevision++;
        reminderRevision++;
        savingReminder = false;
        preferences(null);
        listDirty = false;
        modelPicker = null;
        applyTerraQuota(null);
        modelWriting = modelRefreshing = false;
        modelRevision++;
        listError = historyError = "";
        loadingHistory = loadingList = writing = checking = false;
        turns.clear();
        conversations = new JsonArray();
    }

    private void onEvent(JsonObject event) {
        Minecraft.getInstance()
                .execute(
                        () -> {
                            if (connection != Minecraft.getInstance().getConnection()) return;
                            switch (str(event, "type")) {
                                case "link.shared", "link.answer" ->
                                        openLink.accept(str(event, "type"), str(event, "id"));
                                case "revoked" -> invalidateAccess();
                                case "status.entry" -> {
                                    if (checking
                                            && connection == Minecraft.getInstance().getConnection()
                                            && number(event, "entryRequest", -1) == entryRequest)
                                        entryConfirmed = flag(event, "visible");
                                }
                                case "quota" -> {
                                    if (granted) applyQuota(event);
                                }
                                case "terra.quota" -> {
                                    if (granted && event.has("terraQuota"))
                                        applyTerraQuota(obj(event, "terraQuota"));
                                }
                                case "changed", "quota.changed" -> {
                                    nextStatus = 0;
                                    statusDirty = true;
                                    refresh();
                                }
                                case "model.changed" -> {
                                    modelRevision++;
                                    modelRefreshing = modelPicker != null;
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
        unread.remove(thread);
        unreadRevision++;
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
        turn.modelSelection = str(row, "modelSelection");
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
        request(
                "dismissItemChoice",
                body,
                ack -> {
                    writing = false;
                    if (epoch != selectionEpoch || !thread.equals(selected)) return;
                    if (!flag(ack, "dismissed")) {
                        errorNotice.accept(str(ack, "error"));
                        return;
                    }
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
        String name =
                item.map(
                                i ->
                                        net.minecraft.client.resources.language.I18n.get(
                                                i.getDescriptionId()))
                        .orElseGet(() -> block.map(b -> b.getName().getString()).orElse(""));
        return name.isBlank() || name.startsWith("item.") || name.startsWith("block.")
                ? label
                : label.substring(0, split + 3)
                        + net.minecraft.ChatFormatting.stripFormatting(name);
    }

    void retryHistory() {
        if (selected != null && !loadingHistory) choose(selected);
    }

    boolean entry() {
        return granted;
    }

    boolean busy() {
        return pending != null
                || writing
                || loadingHistory
                || modelUpdating()
                || !historyError.isEmpty();
    }

    Turn visiblePending() {
        if (pending == null || !turns.contains(pending)) return null;
        boolean sameThread =
                pendingThread == null
                        ? pendingSelection == selectionEpoch
                        : pendingThread.equals(selected);
        return sameThread ? pending : null;
    }

    void stop(Consumer<String> notice) {
        Turn turn = visiblePending();
        if (turn == null || turn.stopping) return;
        turn.stopping = true;
        request(
                "stop",
                id("requestId", pendingRequest),
                ack -> {
                    if (pending != turn) return;
                    String message = str(ack, "error");
                    if (!message.isEmpty() || !ack.has("stopping")) {
                        turn.stopping = false;
                        if (turn == visiblePending())
                            notice.accept(message.isEmpty() ? "停止请求未确认，可以重试。" : message);
                    }
                    // Either stop was accepted, or the ask is already finishing. Its reply is
                    // authoritative.
                });
    }

    void ask(String question, boolean topicConfirmed, boolean startNew) {
        Turn waiting = topicConfirmed && !turns.isEmpty() ? turns.get(turns.size() - 1) : null;
        if (busy()
                || quotaExhausted(waiting)
                || !granted
                || question.isBlank()
                || waiting != null && waiting.topicToken.isEmpty()) return;
        error = "";
        List<Turn> origin = startNew ? new ArrayList<>(turns) : List.of();
        String previous = selected;
        if (waiting != null && !startNew) turns.remove(turns.size() - 1);
        if (startNew) choose(null);
        long current = selectionEpoch;
        Turn turn = new Turn(question);
        if (modelPicker != null)
            turn.modelSelection =
                    waiting != null && !waiting.modelSelection.isEmpty()
                            ? waiting.modelSelection
                            : modelPicker.snapshot();
        if (waiting != null) {
            waiting.receipts.forEach(turn::addReceipt);
            turn.usageMissing = waiting.usageMissing;
        }
        turns.add(turn);
        stageAsk(turn, selected, current);
        if (waiting != null) pendingTopicToken = waiting.topicToken;
        JsonObject payload = id("question", question);
        payload.addProperty("requestId", pendingRequest);
        if (!turn.modelSelection.isEmpty())
            payload.addProperty("modelSelection", turn.modelSelection);
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
                    completed(result, turn);
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
                    String usedModel = str(result, "modelSelection");
                    if (!usedModel.isEmpty()) turn.modelSelection = usedModel;
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

    void request(String action, JsonObject payload, Consumer<JsonObject> then) {
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
