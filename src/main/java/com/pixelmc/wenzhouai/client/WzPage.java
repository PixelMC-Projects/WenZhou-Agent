/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pixelmc.hub.client.render.HubDraw;
import com.pixelmc.hub.client.ui.HubTextEditor;
import com.pixelmc.hub.client.ui.HubView;

import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.types.RRect;

import net.minecraft.client.gui.GuiGraphics;

import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Native guide surface within the Hub's frame. */
public final class WzPage implements HubWenzhouAgreement.Host {
    public interface Host {
        void wenzhouAddHit(
                String id, String label, float x, float y, float w, float h, Runnable action);

        void wenzhouFocus(String id);

        void wenzhouBack();

        float wenzhouHover(String id, boolean hovered);

        float wenzhouButtonMove(String id, boolean enabled, boolean hovered);

        void feedback(String value);

        void feedbackSuccess(String value);

        void feedbackError(String value);

        long wenzhouWindow();
    }

    private static final String INPUT = "wz-input", COST = "wz-cost", QUOTA = "wz-quota";
    private static final float X = 275, W = 710, END = X + W, TOP = 55, BOTTOM = 849;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M/d");

    private final Host host;
    private final HubView.NavigationEntry navigationEntry =
            new HubView.NavigationEntry(
                    "wz-nav",
                    "问舟",
                    "问舟，内测",
                    "BETA",
                    0xff31d1bd,
                    0xff098b91,
                    (draw, x, y, size) -> WzGlyph.draw(draw, "boat", x, y, size, 0xffffffff),
                    this::openEntry);
    final WzConversation model;
    private final HubWenzhouAgreement agreement = new HubWenzhouAgreement();
    private final HubWenzhouAnswer answer = new HubWenzhouAnswer();
    private final WzUsage.Cache usageCache = new WzUsage.Cache();
    private final HubTextEditor input = new HubTextEditor(true, 1200);
    private final WzInlineItems references = new WzInlineItems();
    private final WzItemSearch picker = new WzItemSearch();
    private float pickerY;
    private static final float SEARCH_H = 41, ROW_H = 42;

    private HubTextEditor wrapper = new HubTextEditor(true, 65536);
    private HubDraw draw;
    private final Map<WzConversation.Turn, TurnLayout> turnLayouts = new IdentityHashMap<>();
    private final Map<String, WzDisclosure> deleteAnimations = new HashMap<>();
    private HubDraw measuredDraw;
    private JsonArray measuredHistory;
    private int measuredHistorySize = -1;
    private HubDraw historyDraw;
    private List<HistoryRow> historyRows = List.of();

    private record HistoryRow(
            String id, String title, String preview, String time, boolean pendingTopic) {}

    private record TimelineRow(
            WzConversation.Activity activity,
            List<HubTextEditor.Line> label,
            List<HubTextEditor.Line> detail,
            float offset,
            float detailX,
            float detailY,
            float height) {}

    private static final class TurnLayout {
        final WzDisclosure disclosure;
        final List<HubTextEditor.Line> question;
        final float questionWidth;
        String topicText, failureText;
        List<HubTextEditor.Line> topic = List.of(), failure = List.of();
        List<TimelineRow> events = List.of();
        long activityRevision = Long.MIN_VALUE;
        boolean running, answered;
        float timelineHeight, start, end, disclosureHeight, replySeconds;

        TurnLayout(List<HubTextEditor.Line> question, float questionWidth, boolean open) {
            disclosure = new WzDisclosure(open);
            this.question = question;
            this.questionWidth = questionWidth;
        }
    }

    private Matrix33 transform = Matrix33.IDENTITY;
    private int ink, muted, blue, rule, paper, solidPaper, caution;
    private float mx, my, scroll, maxScroll, editorX, editorY, editorW, editorH, dockY;
    private float clipTop, clipBottom, railY, railH, railGrip;
    private boolean active, history, reviewing, reduced, pinned = true, usageOpen, usageDismissed;
    private boolean quotaOpen, quotaDismissed;
    private WzQuota measuredQuota;
    private float quotaTipWidth;
    private Boolean lastProtocol;
    private long protocolChanged;
    private boolean wantFocus, drawItems, railDrag, emptyBefore = true;
    private String confirm, focus;
    private long now, opened, closedAt, paneChanged, layoutChanged, displayedEpoch;
    private float guideStart, handbookStart = 1, routeOffset;

    public WzPage(Host host) {
        this(host, new WzConversation());
    }

    WzPage(Host host, WzConversation model) {
        this.host = host;
        this.model = model;
    }

    public void invalidateStatus() {
        model.invalidateStatus();
    }

    public void refresh() {
        model.refresh();
        synchronizeAccess();
    }

    void synchronizeAccess() {
        if (displayedEpoch != model.epoch()) {
            displayedEpoch = model.epoch();
            references.clear();
            picker.open = false;
            input.setText("");
            answer.reset();
            usageCache.clear();
            clearChatLayouts();
            agreement.reset();
            reviewing = false;
        }
        if ((active || closedAt != 0) && !model.entry()) leave();
        if (active && showAgreement() && model.policy == null && model.error.isEmpty())
            model.loadPolicy();
    }

    public boolean active() {
        return active;
    }

    public boolean routeVisible(long time, boolean reducedMotion) {
        return active || !reducedMotion && closedAt != 0 && time - closedAt < 180_000_000L;
    }

    public boolean coverHandbook(long time, boolean reducedMotion) {
        return handbookAlpha(time, reducedMotion) <= 0;
    }

    public float handbookAlpha(long time, boolean reducedMotion) {
        if (reducedMotion) return active ? 0 : 1;
        if (active) return handbookStart * (1 - curve((time - opened) / 180_000_000f));
        return closedAt == 0
                ? 1
                : handbookStart + (1 - handbookStart) * curve((time - closedAt) / 270_000_000f);
    }

    private float guideAlpha(long time, boolean reducedMotion) {
        if (reducedMotion) return active ? 1 : 0;
        if (active) return guideStart + (1 - guideStart) * curve((time - opened) / 270_000_000f);
        return closedAt == 0 ? 0 : guideStart * (1 - curve((time - closedAt) / 180_000_000f));
    }

    private void switchRoute(boolean entering, long time) {
        float guide = guideAlpha(time, reduced);
        handbookStart = handbookAlpha(time, reduced);
        guideStart = guide;
        active = entering;
        if (entering) {
            opened = time;
            closedAt = 0;
        } else {
            closedAt = time;
        }
    }

    public void closePanel() {
        clearChatLayouts();
        leave();
        model.choose(null);
        answer.reset();
        usageCache.clear();
        input.setText("");
        scroll = maxScroll = layoutChanged = 0;
        pinned = emptyBefore = true;
    }

    public void leave() {
        picker.open = false;
        active = history = reviewing = usageOpen = usageDismissed = quotaOpen = quotaDismissed = drawItems = railDrag = false;
        closedAt = 0;
        confirm = null;
        input.pointerUp();
        agreement.reset();
    }

    public HubView.NavigationEntry navigationEntry() {
        return navigationEntry;
    }

    public boolean navigationVisible() {
        return model.entry() || !model.entryNotice.isEmpty();
    }

    private void openEntry() {
        if (!model.entry()) {
            if (!model.entryNotice.isEmpty()) host.feedback(model.entryNotice);
            return;
        }
        if (closedAt != 0) {
            history = reviewing = false;
            confirm = null;
            agreement.reset();
        }
        lastProtocol = null;
        protocolChanged = 0;
        switchRoute(true, System.nanoTime());
        paneChanged = opened;
        wantFocus = true;
        if (model.showProtocol && !model.consented) model.loadPolicy();
    }

    public void render(
            HubDraw d,
            Matrix33 matrix,
            float mouseX,
            float mouseY,
            long time,
            boolean dark,
            boolean reduceMotion,
            String focused) {
        draw = d;
        transform = matrix;
        float route = guideAlpha(time, reduceMotion);
        routeOffset = 22 * (1 - route);
        float[] pointer = local(mouseX, mouseY);
        mx = pointer[0];
        my = pointer[1];
        now = time;
        reduced = reduceMotion;
        focus = focused;
        ink = dark ? 0xffe4e9f5 : 0xff26344f;
        muted = dark ? 0xffaeb9d0 : 0xff6c7891;
        blue = dark ? 0xffa28ae7 : 0xff765bd4;
        rule = dark ? 0x29a5b7e0 : 0xffd9dfee;
        paper = dark ? 0xe0252d44 : 0xe0ffffff;
        solidPaper = dark ? 0xff252d44 : 0xffffffff;
        caution = dark ? 0xfff2c582 : 0xff845013;
        clipTop = 0;
        clipBottom = 850;
        boolean protocol = showAgreement();
        if (lastProtocol != null && lastProtocol != protocol) protocolChanged = time;
        lastProtocol = protocol;
        float paneAlpha = protocolChanged == 0 ? 1 : ease((time - protocolChanged) / 220_000_000f);
        if (active && wantFocus && !protocol && !history && waiting() == null) {
            host.wenzhouFocus(INPUT);
            focus = INPUT;
            wantFocus = false;
        }
        references.beginFrame();
        if (protocol || history || waiting() != null) picker.open = false;
        answer.beginFrame();
        drawItems = false;
        boolean literal = d.literalColors(true);
        if (route < 1) {
            d.saveLayerAlpha(0, 0, 1260, 850, route);
            d.translate(routeOffset, 0);
        }
        try {
            d.linearGradientRoundRect(
                    1,
                    1,
                    1258,
                    848,
                    0,
                    135,
                    dark ? new int[] {0xff252d44, 0xff20283b} : new int[] {0xadffffff, 0x9cf5f6ff},
                    new float[] {0, 1});
            d.rect(1, 1, 1258, 53, dark ? 0x47263149 : 0x36ffffff);
            d.rect(1, 54, 1258, 1, dark ? 0x11b6c9f0 : 0xb8ffffff);
            button(
                    "wz-back",
                    reviewing ? "返回问舟" : "返回手册",
                    "back",
                    28,
                    9,
                    104,
                    36,
                    this::back,
                    false);
            if (paneAlpha < 1) d.saveLayerAlpha(0, 0, 1260, 850, paneAlpha);
            try {
                if (protocol) {
                    if (model.policy == null) {
                        d.text(
                                model.error.isEmpty() ? "正在读取使用协议…" : model.error,
                                X,
                                99,
                                15,
                                muted,
                                false);
                        if (!model.error.isEmpty())
                            button(
                                    "wz-policy-retry",
                                    "重试",
                                    null,
                                    X,
                                    120,
                                    64,
                                    34,
                                    model::loadPolicy,
                                    false);
                    } else {
                        agreement.render(d, 0, TOP, 1260, 850 - TOP, mx, my, active, reviewing, this, time, reduced);
                        if (!model.error.isEmpty())
                            d.text(model.error, X, TOP + 20, 13, caution, false);
                    }
                } else {
                    if (!history && !empty())
                        button(
                                "wz-new",
                                "新对话",
                                "new",
                                1037,
                                9,
                                85,
                                36,
                                model.writing ? null : () -> choose(null),
                                false);
                    button(
                            "wz-history",
                            history ? "返回对话" : "历史",
                            "history",
                            1126,
                            9,
                            106,
                            36,
                            this::toggleHistory,
                            false);
                    if (history) drawHistory();
                    else drawChat();
                }
            } finally {
                if (paneAlpha < 1) d.restore();
            }
        } finally {
            if (route < 1) d.restore();
            d.literalColors(literal);
            draw = null;
        }
        drawItems = active && !protocol && !history && !empty() && route >= 1 && paneAlpha >= 1 && !usageOpen && !quotaOpen;
    }

    private boolean empty() {
        return model.selected == null && model.turns.isEmpty();
    }

    private boolean showAgreement() {
        return model.showProtocol && (!model.consented || reviewing);
    }

    private WzConversation.Turn waiting() {
        if (model.turns.isEmpty()) return null;
        WzConversation.Turn turn = model.turns.get(model.turns.size() - 1);
        return turn.topic.isEmpty() ? null : turn;
    }

    private void drawChat() {
        WzUsage usage = usageCache.get(model.turns);
        String cost = usage.displayCost();
        float costX = END - 62 - (width(cost, 12) + 4);
        editorX = X + 87;
        editorW = costX - editorX - 12;
        HubDraw renderer = draw;
        HubTextEditor.Measurer measure = value -> references.line(renderer, value, 15, 0, 0, 0, false, 0, 0);
        input.layout(measure, editorW, Math.max(28, editorH), 22.5f);
        editorH = clamp(input.contentHeight() + 6, 28, 96);
        input.layout(measure, editorW, editorH, 22.5f);
        float dockH = composerHeight() + 24 + (model.showProtocol ? 21.8f : 0);
        boolean empty = empty();
        if (empty != emptyBefore) {
            emptyBefore = empty;
            paneChanged = layoutChanged = now;
        }
        float bottom = BOTTOM - 8 - dockH, middle = TOP + (BOTTOM - TOP - dockH) / 2;
        float motion = layoutChanged == 0 ? 1 : ease((now - layoutChanged) / 340_000_000f);
        dockY = empty ? mix(bottom, middle, motion) : mix(middle, bottom, motion);
        if (empty) {
            drawLanding();
        } else {
            draw.save();
            clipTop = TOP;
            clipBottom = dockY;
            answer.beginFrame(
                    X,
                    clipTop,
                    W,
                    clipBottom - clipTop,
                    focus,
                    (item, click) ->
                            hit(
                                    item.id(),
                                    item.label(),
                                    item.x(),
                                    item.y(),
                                    item.width(),
                                    item.height(),
                                    click));
            draw.clipRoundRect(0, TOP, 1260, dockY - TOP, 0);
            float transition = 9 * (1 - ease((now - paneChanged) / 200_000_000f));
            float content = measureTurns();
            maxScroll = Math.max(0, content + 32 - (dockY - TOP));
            scroll = pinned ? maxScroll : clamp(scroll, 0, maxScroll);
            float top = TOP + 16 - scroll + transition;
            if (model.loadingHistory) draw.text("正在读取对话…", X, top + 17, 15, muted, false);
            else if (!model.historyError.isEmpty())
                drawRetry("wz-history-retry", model.historyError, top, model::retryHistory);
            else {
                for (int i = 0; i < model.turns.size(); i++) {
                    WzConversation.Turn turn = model.turns.get(i);
                    TurnLayout layout = turnLayouts.get(turn);
                    if (top + layout.end > clipTop && top + layout.start < clipBottom)
                        drawTurn(turn, layout, i, top + layout.start);
                }
            }
            draw.restore();
            drawRail(dockY);
            clipTop = 0;
            clipBottom = 850;
        }
        if (picker.open) references.beginFrame();
        drawComposer(usage, cost, costX);
        if (picker.open) drawPicker();
    }

    private void openPicker() {
        picker.show();
        host.wenzhouFocus(INPUT);
    }

    private void closePicker() {
        picker.open = false;
        host.wenzhouFocus(INPUT);
    }

    private void insertItem(int row) {
        WzItemSearch.Entry item = picker.at(row);
        if (item == null) return;
        input.insert(references.token(item.id()));
        closePicker();
    }

    private void drawPicker() {
        picker.refresh();
        float h = SEARCH_H + WzItemSearch.ROWS * ROW_H + 8;
        pickerY = dockY - h - 8;

        draw.roundRect(X, pickerY, W, h, 15, solidPaper);
        draw.strokeRoundRect(X + .5f, pickerY + .5f, W - 1, h - 1, 15, 1, rule);
        HubTextEditor query = picker.query;
        // Editing between frames still needs the renderer's font metrics.
        HubDraw renderer = draw;
        query.layout(value -> renderer.textWidth(value, 15, false), W - 35, 25, 22);
        float qx = X + 16;
        draw.save();
        draw.clipRoundRect(qx, pickerY + 6, W - 35, 30, 0);
        for (var rect : query.selectionRects())
            draw.rect(qx + rect.x(), pickerY + 9, rect.width(), 20, 0x509685e8);
        draw.text(query.text().isEmpty() ? "搜索物品名称、拼音或 ID" : query.text(),
                qx - query.scrollX(), pickerY + 26, 15, query.text().isEmpty() ? muted : ink, false);
        if (reduced || now / 500_000_000L % 2 == 0)
            draw.rect(qx + query.caretRect().x(), pickerY + 11, 1, 18, ink);
        draw.restore();
        draw.rect(X + 1, pickerY + SEARCH_H, W - 2, 1, rule);
        if (picker.at(0) == null)
            draw.text(picker.loading() ? "正在索引物品…" : "没有匹配的物品", qx, pickerY + 72, 14, muted, false);
        for (int i = 0; i < WzItemSearch.ROWS; i++) {
            int index = picker.first + i;
            WzItemSearch.Entry item = picker.at(index);
            if (item == null) break;
            float y = pickerY + SEARCH_H + 4 + i * ROW_H;
            if (index == picker.selected || inside(X + 4, y, W - 8, ROW_H))
                draw.roundRect(X + 4, y, W - 8, ROW_H, 8, alpha(blue, .13f));
            references.icon(item.item().getDefaultInstance(), X + 15, y + 7, 27, X + 42, y, y + ROW_H);
            draw.text(ellipsis(item.name(), W - 240, 14), X + 54, y + 17, 14, ink, true);
            draw.text(ellipsis(item.id().toString(), W - 240, 12), X + 54, y + 33, 12, muted, false);
            String mod = ellipsis(item.mod(), 166, 12);
            draw.text(mod, END - 16 - width(mod, 12), y + 24, 12, muted, false);
        }
    }

    private void drawLanding() {
        railH = scroll = maxScroll = 0;
        WzArtwork.boat(draw, 560, dockY - 151.6f, 140);
        center("问舟", 630, dockY - 33.3f, 25, ink, true);
    }

    private void clearChatLayouts() {
        turnLayouts.clear();
        deleteAnimations.clear();
        measuredDraw = historyDraw = null;
        measuredHistory = null;
        measuredHistorySize = -1;
        historyRows = List.of();
        wrapper = new HubTextEditor(true, 65536);
    }

    private float measureTurns() {
        if (measuredDraw != draw) {
            measuredDraw = draw;
            turnLayouts.clear();
        }
        float offset = 0;
        for (WzConversation.Turn turn : model.turns) {
            TurnLayout layout = turnLayouts.computeIfAbsent(turn, this::measureQuestion);
            boolean running = model.pending == turn;
            if (layout.activityRevision != turn.activityRevision
                    || layout.running != running
                    || layout.answered != (turn.reply != null)) {
                measureTimeline(turn, layout, running);
            }
            if (!turn.topic.equals(layout.topicText)) {
                layout.topicText = turn.topic;
                layout.topic = turn.topic.isEmpty() ? List.of() : lines(turn.topic, W - 14, 15, 24);
            }
            String failure = turn.error;
            if (turn.stopped) {
                failure = "已停止回答";
            } else if (running || turn.reply != null) {
                failure = running && turn.stopping && turn.activity.isEmpty() ? "正在停止回答…" : "";
            } else if (failure.isEmpty() && turn.topic.isEmpty()) {
                failure = "上次查阅已中断，可以重新提问。";
            }
            if (!failure.equals(layout.failureText)) {
                layout.failureText = failure;
                layout.failure =
                        failure.isEmpty()
                                ? List.of()
                                : lines(failure, turn.topic.isEmpty() ? W : W - 14, 15, 23);
            }

            layout.start = offset;
            offset += bubbleHeight(layout) + 48;
            layout.disclosureHeight = layout.timelineHeight * layout.disclosure.value(now, reduced);
            if (!layout.events.isEmpty()) offset += 42 + layout.disclosureHeight;
            if (!layout.topic.isEmpty()) offset += 55 + 24 * layout.topic.size();
            offset += 23 * layout.failure.size();
            if (turn.reply != null) {
                layout.replySeconds =
                        turn.answered == 0
                                ? Float.POSITIVE_INFINITY
                                : Math.max(0, (now - turn.answered) / 1_000_000_000f);
                offset += answer.height(draw, turn.reply, W, layout.replySeconds, reduced);
            }
            offset += 28;
            layout.end = offset;
        }
        if (turnLayouts.size() > model.turns.size()) {
            turnLayouts.keySet().removeIf(turn -> !model.turns.contains(turn));
        }
        return offset;
    }

    private TurnLayout measureQuestion(WzConversation.Turn turn) {
        String display = references.decode(turn.question);
        float longest = 0;
        for (String line : display.split("\n", -1))
            longest = Math.max(longest, references.line(draw, line, 14, 0, 0, 0, false, 0, 0));
        float questionWidth = clamp(longest, 1, W * .85f - 32);
        HubTextEditor bubble = new HubTextEditor(true, 65536);
        bubble.layout(value -> references.line(draw, value, 14, 0, 0, 0, false, 0, 0), questionWidth, 21.7f);
        bubble.setText(display);
        return new TurnLayout(bubble.lines(), questionWidth, turn.open);
    }

    private static float bubbleHeight(TurnLayout layout) {
        return 22 + layout.question.size() * 21.7f;
    }

    private void measureTimeline(WzConversation.Turn turn, TurnLayout layout, boolean running) {
        float offset = 4;
        List<TimelineRow> rows = new ArrayList<>();
        for (int i = 0; i < turn.activity.size(); i++) {
            WzConversation.Activity row = turn.activity.get(i);
            String label =
                    switch (row.type()) {
                        case "progress" -> {
                            if (row.text().startsWith("思考 · ")) yield row.text();
                            yield turn.reply != null || i < turn.activity.size() - 1
                                    ? "已思考"
                                    : running ? "思考中" : turn.stopped ? "思考已结束" : "思考已中断";
                        }
                        case "tool.start" -> running || row.text().startsWith("运行命令 · ")
                                ? row.text() : turn.stopped ? "查询已停止" : "查询已中断";
                        default -> row.text();
                    };
            String detail = "";
            if (row.type().equals("tool.finish")
                    && (!row.icon().equals("command") || label.startsWith("运行命令 · "))) {
                detail = row.detail();
            } else if (!running && row.type().equals("tool.start") && label.startsWith("运行命令 · ")) {
                detail = turn.stopped ? "查询已停止" : "查询已中断";
            }
            List<HubTextEditor.Line> labels = lines(label, W - 47, 13, 18.85f);
            float labelWidth = labels.size() == 1 ? labels.get(0).width() : W;
            boolean inline = !detail.isEmpty() && labelWidth + 8 + width(detail, 11) <= W - 47;
            List<HubTextEditor.Line> details =
                    detail.isEmpty()
                            ? List.of()
                            : lines(detail, inline ? W - 55 - labelWidth : W - 47, 11, 16);
            float detailX = inline ? X + 55 + labelWidth : X + 47;
            float detailY = inline ? 0 : labels.size() * 18.85f;
            float height = 10 + labels.size() * 18.85f + (inline ? 0 : details.size() * 16);
            rows.add(new TimelineRow(row, labels, details, offset, detailX, detailY, height));
            offset += height;
        }
        layout.events = List.copyOf(rows);
        layout.timelineHeight = rows.isEmpty() ? 0 : offset;
        layout.activityRevision = turn.activityRevision;
        layout.running = running;
        layout.answered = turn.reply != null;
    }

    private void drawTurn(WzConversation.Turn turn, TurnLayout layout, int index, float y) {
        float questionHeight = bubbleHeight(layout);
        if (y + questionHeight + 9 > clipTop && y < clipBottom) {
            boolean fresh = turn == model.pending || turn.answered > 0 && turn.since > paneChanged;
            float progress = fresh ? ease((now - turn.since) / 200_000_000f) : 1;
            float bubbleY = y + 9 * (1 - progress);
            float x = END - layout.questionWidth - 32;
            float w = layout.questionWidth + 32;
            questionCard(x, bubbleY, w, questionHeight, progress);
            for (HubTextEditor.Line line : layout.question)
                references.line(draw, line.text(), 14, x + 16, bubbleY + line.y() + 26,
                        alpha(ink, progress), true, clipTop, clipBottom);
        }
        y += questionHeight + 22;
        if (y + 18 > clipTop && y < clipBottom) {
            WzArtwork.boat(draw, X, y, 30);
            draw.text("问舟", X + 38, y + 14, 14, blue, true);
        }
        y += 26;
        if (!layout.events.isEmpty()) y = drawTimeline(turn, layout, index, y);
        if (!layout.topic.isEmpty()) {
            y += 10;
            float height = 45 + 24 * layout.topic.size();
            draw.line(X, y, X, y + height, 2, rule);
            for (HubTextEditor.Line line : layout.topic)
                draw.text(line.text(), X + 12, y + 17 + line.y(), 15, ink, false);
            float ay = y + 24 * layout.topic.size() + 8;
            button(
                    "wz-topic-continue",
                    "有关，接着聊",
                    null,
                    X + 12,
                    ay,
                    116,
                    33,
                    model.busy() || model.quota.exhausted() ? null : () -> resolveTopic(false),
                    true);
            button(
                    "wz-topic-new",
                    "无关，新开对话",
                    null,
                    X + 136,
                    ay,
                    130,
                    33,
                    model.busy() || model.quota.exhausted() ? null : () -> resolveTopic(true),
                    true);
            y += height;
        } else if (turn.reply != null && y < clipBottom) {
            y += answer.render(draw, turn.reply, X, y, W, mx, my, layout.replySeconds, reduced);
        }
        for (HubTextEditor.Line line : layout.failure)
            draw.text(
                    line.text(),
                    X + (layout.topic.isEmpty() ? 0 : 12),
                    y + 17 + line.y(),
                    15,
                    turn.error.isEmpty() ? muted : caution,
                    false);
    }

    private void questionCard(float x, float y, float w, float h, float opacity) {
        float[] outer = {16, 16, 16, 16, 4, 4, 16, 16};
        float[] inner = {15.5f, 15.5f, 15.5f, 15.5f, 3.5f, 3.5f, 15.5f, 15.5f};
        try (Paint paint = new Paint().setAntiAlias(true).setColor(alpha(paper, opacity))) {
            draw.canvas().drawRRect(RRect.makeComplexXYWH(x, y, w, h, outer), paint);
            paint.setColor(alpha(rule, opacity)).setMode(PaintMode.STROKE).setStrokeWidth(1);
            draw.canvas()
                    .drawRRect(RRect.makeComplexXYWH(x + .5f, y + .5f, w - 1, h - 1, inner), paint);
        }
    }

    private float drawTimeline(WzConversation.Turn turn, TurnLayout layout, int index, float y) {
        String title = layout.running ? (turn.stopping ? "正在停止回答…" : "问舟正在查阅")
                : turn.reply != null || turn.stopped ? "查阅记录" : "未完成的查阅";
        if (y + 25 > clipTop && y < clipBottom) {
            float tw = width(title, 13);
            if (inside(X, y, tw + 47, 25)) draw.roundRect(X - 3, y, tw + 47, 25, 5, paper);
            WzGlyph.draw(draw, "book", X, y + 4, 16, muted);
            draw.text(title, X + 23, y + 17, 13, muted, false);
            WzGlyph.draw(draw, turn.open ? "down" : "chevron", X + 29 + tw, y + 6, 13, muted);
            hit(
                    "wz-timeline-" + index,
                    title,
                    X,
                    y,
                    tw + 46,
                    25,
                    () -> {
                        turn.open = !turn.open;
                        layout.disclosure.set(turn.open, System.nanoTime(), reduced);
                    });
        }
        y += 30;
        float visibleBottom = Math.min(y + layout.disclosureHeight, clipBottom);
        if (visibleBottom > Math.max(y, clipTop)) {
            draw.save();
            draw.clipRoundRect(X, y, W, layout.disclosureHeight, 0);
            draw.line(X + 7, y, X + 7, y + layout.disclosureHeight, 1, rule);
            for (TimelineRow row : layout.events) {
                float at = y + row.offset;
                if (at + row.height <= clipTop || at >= visibleBottom) continue;
                WzConversation.Activity activity = row.activity;
                if (activity.type().equals("progress")) draw.circle(X + 31, at + 9, 2, muted);
                else {
                    boolean doing = layout.running && activity.type().equals("tool.start");
                    float pulse =
                            doing && !reduced
                                    ? .725f
                                            + .275f
                                                    * (float)
                                                            Math.cos(
                                                                    (now - activity.since())
                                                                            * Math.PI
                                                                            / 1_150_000_000)
                                    : 1;
                    WzGlyph.draw(
                            draw,
                            activity.icon().isEmpty() ? "book" : activity.icon(),
                            X + 23,
                            at + 1,
                            16,
                            alpha(doing ? blue : muted, pulse));
                }
                for (HubTextEditor.Line line : row.label)
                    draw.text(line.text(), X + 47, at + 14 + line.y(), 13, muted, false);
                for (HubTextEditor.Line line : row.detail)
                    draw.text(
                            line.text(),
                            row.detailX,
                            at + 14 + row.detailY + line.y(),
                            11,
                            muted,
                            false);
            }
            draw.restore();
        }
        return y + layout.disclosureHeight + 12;
    }

    private static int alpha(int color, float opacity) {
        return Math.round((color >>> 24) * opacity) << 24 | color & 0xffffff;
    }

    private float composerHeight() {
        return Math.max(60, editorH + 22);
    }

    private void drawComposer(WzUsage usage, String cost, float costX) {
        boolean waiting = waiting() != null,
                canSend = !model.busy() && !model.quota.exhausted() && !waiting && !input.text().isBlank();
        float h = composerHeight(), mid = dockY + h / 2;
        float costW = width(cost, 12) + 4;
        draw.boxShadow(X, dockY, W, h, 21, 0, 8, 28, -12, 0x2a203e61);
        boolean focused =
                INPUT.equals(focus) && !waiting || COST.equals(focus)
                        || "wz-send".equals(focus) || "wz-stop".equals(focus);
        draw.roundRect(X, dockY, W, h, 21, paper);
        if (focused) {
            draw.strokeRoundRect(
                    X - 1, dockY - 1, W + 2, h + 2, 22, 2, 0x14000000 | (ink & 0xffffff));
            int border = draw.dark() ? 0xff979eb1 : 0xff798397;
            draw.strokeRoundRect(X + .5f, dockY + .5f, W - 1, h - 1, 20.5f, 1, border);
        } else {
            draw.strokeRoundRect(X + .5f, dockY + .5f, W - 1, h - 1, 20.5f, 1, rule);
        }
        button("wz-pick", "物品", "item", X + 6, mid - 17, 70, 34,
                waiting ? null : () -> { if (picker.open) closePicker(); else openPicker(); }, false);
        editorY = mid - editorH / 2;
        draw.save();
        draw.clipRoundRect(editorX, editorY, editorW, editorH, 0);
        if (input.text().isEmpty() || waiting) {
            String placeholder = model.quota.exhausted() ? "本周额度已用完" : waiting ? "请选择如何继续这次提问" : empty() ? "想在旅途中弄清什么？" : "接着问问舟…";
            draw.text(placeholder, editorX, editorY + 19, 15, muted, false);
        } else {
            if (INPUT.equals(focus))
                for (HubTextEditor.Rect rect : input.selectionRects())
                    draw.rect(
                            editorX + rect.x(),
                            editorY + rect.y(),
                            rect.width(),
                            rect.height(),
                            0x509685e8);
            for (HubTextEditor.Line line : input.lines())
                references.line(draw, line.text(), 15, editorX - input.scrollX(),
                        editorY + line.y() - input.scrollY() + 17, ink, true, editorY, editorY + editorH);
        }
        if (INPUT.equals(focus) && !waiting && (reduced || now / 500_000_000L % 2 == 0)) {
            HubTextEditor.Rect caret = input.caretRect();
            draw.rect(editorX + caret.x(), editorY + caret.y() + 2, 1, caret.height() - 4, ink);
        }
        draw.restore();
        if (!waiting) hit(INPUT, "向问舟提问", editorX, editorY, editorW, editorH, () -> {});
        boolean onCost = inside(costX, mid - 15, costW, 30) || COST.equals(focus);
        boolean onTip = usageOpen && inside(costX + costW - 206, dockY - 191, 210, h / 2 + 201);
        if (!onCost && !onTip) usageDismissed = false;
        usageOpen = !usageDismissed && (onCost || onTip);
        draw.text(cost, costX + 2, mid + 4, 12, onCost ? ink : muted, false);
        hit(COST, usage.costLabel(), costX, mid - 15, costW, 30, () -> usageDismissed = false);
        float sx = END - 50;
        WzConversation.Turn pending = model.visiblePending();
        boolean stop = pending != null;
        String actionId = stop ? "wz-stop" : "wz-send";
        boolean enabled = stop ? !pending.stopping : canSend;
        boolean hovered = enabled && inside(sx, mid - 19, 38, 38);
        float hover = host.wenzhouHover(actionId, hovered);
        float actionY = mid - 19 + host.wenzhouButtonMove(actionId, enabled, hovered);
        if (stop) {
            int fill = draw.dark() ? 0xfffafafa : 0xff202124;
            int glyph = draw.dark() ? 0xff202124 : 0xfffafafa;
            float opacity = enabled ? 1 - .13f * hover : .55f;
            draw.circle(sx + 19, actionY + 19, 18, alpha(fill, opacity));
            draw.roundRect(sx + 12, actionY + 12, 14, 14, 2.5f, glyph);
            if (enabled) hit(actionId, "停止回答", sx, mid - 19, 38, 38, () -> model.stop(host::feedback));
        } else {
            if (canSend) {
                draw.roundRect(sx, actionY, 38, 38, 13, blue);
                if (hover > 0) draw.roundRect(sx, actionY, 38, 38, 13, alpha(0x20ffffff, hover));
            }
            WzGlyph.draw(draw, "send", sx + 8.5f, actionY + 8.5f, 21, canSend ? paper : muted);
            if (canSend) hit(actionId, "发送问题", sx, mid - 19, 38, 38, () -> send(input.text()));
        }
        String bold = "换话题请新建对话。", rest = "经提醒仍反复询问无关问题，可能被限制使用问舟。";
        float hintX = 630 - (draw.textWidth(bold, 12, true) + width(rest, 12)) / 2;
        draw.text(bold, hintX, dockY + h + 16, 12, caution, true);
        draw.text(rest, hintX + draw.textWidth(bold, 12, true), dockY + h + 16, 12, muted, false);
        if (model.showProtocol) {
            String terms = "《问舟内测使用协议》";
            float tw = width(terms, 12), y = dockY + h + 20;
            center(terms, 630, y + 13, 12, muted, false);
            draw.line(630 - tw / 2, y + 15, 630 + tw / 2, y + 15, 1, muted);
            hit(
                    "wz-terms",
                    terms,
                    630 - tw / 2 - 5,
                    y,
                    tw + 10,
                    18,
                    () -> {
                        reviewing = true;
                        agreement.reset();
                        model.loadPolicy();
                    });
        }
        drawQuota(mid);
        if (usageOpen && !quotaOpen) drawUsage(usage, costX + costW - 206, mid - 22);
    }

    private void drawQuota(float mid) {
        WzQuota quota = model.quota;
        if (!quota.enabled()) {
            quotaOpen = quotaDismissed = false;
            return;
        }
        float size = 52, x = X - size - 10, y = mid - size / 2;
        boolean hovered = inside(x, y, size, size) || QUOTA.equals(focus);
        if (measuredQuota != quota) {
            measuredQuota = quota;
            quotaTipWidth = Math.max(151, Math.max(width(quota.resetLabel(), 12),
                    draw.textWidth(quota.label(), 13, true)) + 28);
        }
        float tipWidth = quotaTipWidth;
        boolean showReset = !quota.unlimited();
        float tipHeight = showReset ? 60 : 40, tipY = y - tipHeight - 7;
        boolean onTip = quotaOpen && inside(x, tipY, tipWidth, tipHeight + 7);
        if (!hovered && !onTip) quotaDismissed = false;
        quotaOpen = !quotaDismissed && (hovered || onTip);
        draw.boxShadow(x, y, size, size, 17, 0, 6, 20, -9, 0x2a203e61);
        draw.roundRect(x, y, size, size, 17, paper);
        draw.strokeRoundRect(x + .5f, y + .5f, size - 1, size - 1, 16.5f, 1, hovered ? muted : rule);
        if (quota.unlimited())
            center("∞", x + size / 2, mid + 9, 28, draw.dark() ? 0xffffd275 : 0xffa86608, true);
        else
            WzGlyph.quotaRing(draw, x + size / 2, mid, quota.percent(), rule, ink);
        hit(QUOTA, quota.label(), x, y, size, size, () -> quotaDismissed = false);
        if (!quotaOpen) return;
        draw.boxShadow(x, tipY, tipWidth, tipHeight, 13, 0, 8, 24, -5, 0x28152541);
        draw.roundRect(x, tipY, tipWidth, tipHeight, 13, solidPaper);
        draw.strokeRoundRect(x + .5f, tipY + .5f, tipWidth - 1, tipHeight - 1, 12.5f, 1, rule);
        draw.text(quota.label(), x + 14, tipY + 24, 13, ink, true);
        if (showReset) draw.text(quota.resetLabel(), x + 14, tipY + 45, 12, muted, false);
    }

    private void drawUsage(WzUsage usage, float x, float bottom) {
        float h = usage.note().isEmpty() ? 185.3f : 202.3f, y = bottom - h;
        draw.boxShadow(x, y, 210, h, 14, 0, 8, 24, -5, 0x28152541);
        draw.roundRect(x, y, 210, h, 14, solidPaper);
        draw.strokeRoundRect(x + .5f, y + .5f, 209, h - 1, 13.5f, 1, rule);
        draw.text("本对话用量", x + 15, y + 25, 13, ink, true);
        y += 34;
        for (int i = 0; i < usage.rows().size(); i++) {
            WzUsage.Row row = usage.rows().get(i);
            if (i == 5) {
                y += 5;
                draw.line(x + 15, y - 2, x + 195, y - 2, 1, rule);
                y += 3;
            }
            draw.text(row.label(), x + 15, y + 17, 12, muted, false);
            draw.text(row.value(), x + 195 - width(row.value(), 12), y + 17, 12, ink, false);
            y += 20.4f;
        }
        if (!usage.note().isEmpty()) draw.text(usage.note(), x + 15, y + 14, 11, muted, false);
    }

    private void drawHistory() {
        usageOpen = quotaOpen = false;
        clipTop = TOP;
        draw.save();
        draw.clipRoundRect(0, TOP, 1260, BOTTOM - TOP, 0);
        float translation = 9 * (1 - ease((now - paneChanged) / 200_000_000f));
        float y = TOP + 56 - scroll + translation;
        draw.text("历史对话", X, y, 26, ink, true);
        y += 27;
        if (model.loadingList) {
            draw.text("正在读取历史对话…", X, y + 18, 15, muted, false);
            y += 35;
        } else if (!model.listError.isEmpty()) {
            y = drawRetry("wz-list-retry", model.listError, y, model::list);
        }
        if (historyRows().isEmpty() && !model.loadingList && model.listError.isEmpty())
            draw.text("还没有历史对话。", X, y + 18, 15, muted, false);
        for (HistoryRow row : historyRows) {
            if (y >= clipTop - 50 && y < clipBottom) drawHistoryRow(row, y);
            y += 50;
            WzDisclosure disclosure = deleteAnimations.get(row.id);
            if (disclosure != null) {
                float height = disclosure.value(now, reduced) * 47;
                if (height > 0 && y + height > clipTop && y < clipBottom)
                    drawDeleteConfirmation(row.id, y, height);
                y += height;
                if (height == 0 && !row.id.equals(confirm)) deleteAnimations.remove(row.id);
            }
            if (y >= clipTop && y < clipBottom) draw.line(X, y, END, y, 1, rule);
            y += 1;
        }
        maxScroll = Math.max(0, y + scroll - translation + 36 - BOTTOM);
        scroll = clamp(scroll, 0, maxScroll);
        draw.restore();
        drawRail(BOTTOM);
        clipTop = 0;
    }

    private List<HistoryRow> historyRows() {
        if (measuredHistory == model.conversations
                && measuredHistorySize == model.conversations.size()
                && historyDraw == draw) return historyRows;
        measuredHistory = model.conversations;
        measuredHistorySize = measuredHistory.size();
        historyDraw = draw;
        List<HistoryRow> rows = new ArrayList<>();
        for (JsonElement element : measuredHistory) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            String id = WzConversation.str(row, "id");
            if (id.isEmpty()) continue;
            String title = WzConversation.str(row, "title");
            long update = WzConversation.number(row, "updatedAt", 0);
            String time =
                    update <= 0
                            ? ""
                            : DATE.format(
                                    Instant.ofEpochMilli(update).atZone(ZoneId.systemDefault()));
            boolean pending = WzConversation.flag(row, "pendingTopic");
            rows.add(
                    new HistoryRow(
                            id,
                            title,
                            ellipsis(title, W - (pending ? 240 : 190), 14),
                            time,
                            pending));
        }
        historyRows = List.copyOf(rows);
        deleteAnimations
                .keySet()
                .removeIf(id -> historyRows.stream().noneMatch(row -> row.id.equals(id)));
        return historyRows;
    }

    private void drawHistoryRow(HistoryRow row, float y) {
        boolean enabled = !model.writing;
        String rowId = "wz-history-open-" + row.id;
        float rowHover = host.wenzhouHover(rowId, enabled && inside(X, y, W - 40, 50));
        if (rowHover > 0) draw.roundRect(X, y, W - 40, 50, 10, alpha(paper, rowHover));
        WzGlyph.draw(draw, "book", X + 8, y + 15, 20, muted);
        draw.text(row.preview, X + 40, y + 30, 14, ink, false);
        if (!row.time.isEmpty())
            draw.text(row.time, END - 73 - width(row.time, 12), y + 29, 12, muted, false);
        if (row.pendingTopic)
            draw.text("待选择", END - 140 - width(row.time, 12), y + 29, 12, muted, false);
        WzGlyph.draw(draw, "chevron", END - 64, y + 18, 14, muted);
        if (enabled) hit(rowId, row.title, X, y, W - 40, 50, () -> choose(row.id));
        String deleteId = "wz-history-delete-" + row.id;
        float x = END - 34;
        float hover = host.wenzhouHover(deleteId, enabled && inside(x, y + 8, 34, 34));
        if (hover > 0) draw.roundRect(x, y + 8, 34, 34, 10, alpha(dangerPaper(), hover));
        WzGlyph.draw(draw, "trash", x + 9, y + 16.5f, 17, mixColor(muted, dangerInk(), hover));
        if (enabled)
            hit(deleteId, "删除对话「" + row.title + "」", x, y + 8, 34, 34, () -> openDelete(row.id));
    }

    private void openDelete(String id) {
        if (confirm != null && !confirm.equals(id)) closeDelete(false);
        confirm = id;
        deleteAnimations
                .computeIfAbsent(id, ignored -> new WzDisclosure(false))
                .set(true, System.nanoTime(), reduced);
        host.wenzhouFocus("wz-cancel-delete");
    }

    private void closeDelete(boolean restoreFocus) {
        if (confirm == null) return;
        WzDisclosure animation = deleteAnimations.get(confirm);
        if (animation != null) animation.set(false, System.nanoTime(), reduced);
        if (restoreFocus) host.wenzhouFocus("wz-history-delete-" + confirm);
        confirm = null;
    }

    private void drawDeleteConfirmation(String id, float y, float height) {
        draw.save();
        draw.clipRoundRect(X, y, W, height, 0);
        draw.text("从你的个人历史中移除这段对话？", X + 4, y + 22, 13, muted, false);
        float oldBottom = clipBottom;
        clipBottom = Math.min(clipBottom, y + height);
        boolean chosen = id.equals(confirm) && !model.writing;
        button(
                "wz-cancel-delete",
                "取消",
                null,
                END - 152,
                y + 4,
                55,
                31,
                chosen ? () -> closeDelete(true) : null,
                false);
        float x = END - 90;
        boolean canDelete = chosen;
        float hover = host.wenzhouHover("wz-confirm-delete", canDelete && inside(x, y + 4, 86, 31));
        draw.roundRect(x, y + 4, 86, 31, 8, mixColor(dangerPaper(), 0x30b8465b, hover));
        draw.text("确认删除", x + 10, y + 24.5f, 13, canDelete ? dangerInk() : muted, false);
        if (canDelete)
            hit(
                    "wz-confirm-delete",
                    "确认删除",
                    x,
                    y + 4,
                    86,
                    31,
                    () ->
                            model.remove(
                                    id,
                                    () -> {
                                        closeDelete(false);
                                        host.wenzhouFocus("wz-history");
                                        host.feedbackSuccess("已从历史移除这段对话");
                                    },
                                    host::feedbackError));
        clipBottom = oldBottom;
        draw.restore();
    }

    private int dangerInk() {
        return draw.dark() ? 0xffffb0ba : 0xffa3374b;
    }

    private int dangerPaper() {
        return 0x10b8465b;
    }

    private float drawRetry(String id, String message, float y, Runnable action) {
        draw.text(ellipsis(message, W, 14), X, y + 18, 14, caution, false);
        button(id, "重试", null, X, y + 28, 70, 34, action, true);
        return y + 76;
    }

    private void drawRail(float bottom) {
        if (maxScroll <= 0) {
            railH = 0;
            return;
        }
        float available = bottom - TOP - 8;
        railH = Math.max(26, available * (bottom - TOP) / (maxScroll + bottom - TOP));
        railY = TOP + 4 + (available - railH) * scroll / maxScroll;
        draw.roundRect(1250, railY, 4, railH, 2, rule);
    }

    private void button(
            String id,
            String label,
            String icon,
            float x,
            float y,
            float w,
            float h,
            Runnable action,
            boolean outlined) {
        boolean enabled = action != null;
        boolean hovered = enabled && inside(x, y, w, h);
        float hover = host.wenzhouHover(id, hovered);
        float visualY = y + host.wenzhouButtonMove(id, enabled, hovered);
        if (outlined) card(x, visualY, w, h, 9);
        if (hover > 0)
            draw.roundRect(
                    x, visualY, w, h, outlined ? 9 : 10, alpha(outlined ? rule : paper, hover));
        if (icon != null)
            WzGlyph.draw(draw, icon, x + 10, visualY + (h - 17) / 2, 17, enabled ? ink : muted);
        draw.text(
                label,
                x + (icon == null ? 10 : 35),
                visualY + h / 2 + 5,
                13,
                enabled ? ink : muted,
                false);
        if (enabled) hit(id, label, x, y, w, h, action);
    }

    private static int mixColor(int from, int to, float amount) {
        int a = Math.round(mix(from >>> 24, to >>> 24, amount));
        int r = Math.round(mix((from >>> 16) & 255, (to >>> 16) & 255, amount));
        int g = Math.round(mix((from >>> 8) & 255, (to >>> 8) & 255, amount));
        int b = Math.round(mix(from & 255, to & 255, amount));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void card(float x, float y, float w, float h, float radius) {
        draw.roundRect(x, y, w, h, radius, paper);
        draw.strokeRoundRect(x + .5f, y + .5f, w - 1, h - 1, radius - .5f, 1, rule);
    }

    private List<HubTextEditor.Line> lines(String text, float width, float size, float lineHeight) {
        wrapper.layout(value -> width(value, size), width, lineHeight);
        wrapper.setText(text);

        return wrapper.lines();
    }

    private String ellipsis(String text, float available, float size) {
        if (width(text, size) <= available) return text;
        int end = text.length();
        while (end > 0 && width(text.substring(0, end) + "…", size) > available)
            end = text.offsetByCodePoints(end, -1);
        return end == 0 ? "" : text.substring(0, end) + "…";
    }

    private float width(String value, float size) {
        return draw.textWidth(value, size, false);
    }

    private void center(String text, float x, float y, float size, int color, boolean bold) {
        draw.text(text, x - draw.textWidth(text, size, bold) / 2, y, size, color, bold);
    }

    private boolean inside(float x, float y, float w, float h) {
        return mx >= x && my >= y && mx < x + w && my < y + h && my >= clipTop && my < clipBottom;
    }

    private void send(String text) {
        String question = references.encode(text).strip();
        if (question.length() > 1500) {
            host.feedback("包含物品 ID 后问题超过 1500 字，请缩短后再发送。");
            return;
        }
        if (model.quota.exhausted()) {
            host.feedback("本周额度已用完，可在输入栏左侧查看额度状态。");
            return;
        }
        if (question.isEmpty() || model.busy() || waiting() != null || showAgreement()) return;
        pinned = true;
        input.setText("");
        model.ask(question, false, false);
    }

    private void resolveTopic(boolean startNew) {
        WzConversation.Turn turn = waiting();
        if (turn == null || model.busy() || model.quota.exhausted()) return;
        pinned = wantFocus = true;
        model.ask(turn.question, true, startNew);
    }

    private void choose(String id) {
        if (model.writing) return;
        model.choose(id);
        picker.open = false;
        history = false;
        confirm = null;
        input.setText("");
        scroll = maxScroll = 0;
        paneChanged = System.nanoTime();
        pinned = wantFocus = true;
    }

    private void toggleHistory() {
        picker.open = false;
        history = !history;
        scroll = maxScroll = 0;
        confirm = null;
        deleteAnimations.clear();
        paneChanged = System.nanoTime();
        if (history) model.list();
        else pinned = wantFocus = true;
    }

    private void back() {
        picker.open = false;
        if (reviewing) close();
        else {
            switchRoute(false, System.nanoTime());
            agreement.leaveReader();
            usageOpen = usageDismissed = drawItems = railDrag = false;
            input.pointerUp();
            host.wenzhouBack();
        }
    }

    public boolean escape() {
        if (!active) return false;
        if (picker.open) { closePicker(); return true; }
        if (showAgreement() && agreement.escape()) return true;
        if (answer.escape()) return true;
        if (quotaOpen) {
            quotaOpen = false;
            quotaDismissed = true;
            return true;
        }
        if (usageOpen) {
            usageOpen = false;
            usageDismissed = true;
            return true;
        }
        if (confirm != null) {
            closeDelete(true);
            return true;
        }
        back();
        return true;
    }

    public boolean scroll(float x, float y, double amount) {
        float[] point = local(x, y);
        if (picker.open) { picker.move((int) -Math.signum(amount)); return true; }
        if (showAgreement()) return agreement.scroll(point[0], point[1], amount);
        if (point[0] < 0 || point[0] >= 1260 || point[1] < TOP || point[1] >= 850) return false;
        if (textPointer(x, y)) {
            float before = input.scrollY();
            input.scrollBy(0, (float) (-amount * 22.5));
            if (input.scrollY() != before) return true;
        }
        userScrollTo(scroll - (float) amount * 48);
        return true;
    }

    private void userScrollTo(float position) {
        scroll = clamp(position, 0, maxScroll);
        // Any upward gesture, including a fractional trackpad step, releases follow-to-bottom.
        if (!history) pinned = scroll == maxScroll;
    }

    public boolean pointerDown(float x, float y, boolean shift) {
        float[] p = local(x, y);
        if (picker.open) {
            if (p[0] >= X && p[0] < END && p[1] >= pickerY && p[1] < dockY - 8) {
                float rowY = p[1] - pickerY - SEARCH_H - 4;
                if (rowY >= 0 && rowY < WzItemSearch.ROWS * ROW_H) insertItem(picker.first + (int)(rowY / ROW_H));
                else {
                    picker.query.pointerDown(p[0] - X - 16, 0, shift);
                    picker.query.pointerUp();
                }
                return true;
            }
            closePicker();
            if (p[1] >= dockY && p[1] < dockY + composerHeight() && p[0] >= X && p[0] < editorX) return true;
        }
        if (showAgreement()) return agreement.pointerDown(p[0], p[1]);
        if (!history) answer.dismissOutside(p[0], p[1]);
        if (railH > 0
                && p[0] >= 1242
                && p[0] < 1260
                && p[1] >= TOP
                && p[1] < (history ? BOTTOM : dockY)) {
            railDrag = true;
            railGrip = p[1] >= railY && p[1] < railY + railH ? p[1] - railY : railH / 2;
            dragRail(p[1]);
            return true;
        }
        if (!textPointer(x, y)) return false;
        host.wenzhouFocus(INPUT);
        input.pointerDown(p[0] - editorX, p[1] - editorY, shift);
        return true;
    }

    public boolean pointerDrag(float x, float y) {
        float[] p = local(x, y);
        if (showAgreement()) return agreement.pointerDrag(p[1]);
        if (railDrag) {
            dragRail(p[1]);
            return true;
        }
        if (!input.dragging()) return false;
        input.pointerDrag(p[0] - editorX, p[1] - editorY);
        return true;
    }

    private void dragRail(float y) {
        float track = (history ? BOTTOM : dockY) - TOP - 8 - railH;
        userScrollTo(track <= 0 ? 0 : clamp((y - TOP - 4 - railGrip) / track, 0, 1) * maxScroll);
    }

    public boolean pointerUp() {
        boolean handled = agreement.pointerUp() || railDrag || input.dragging();
        railDrag = false;
        input.pointerUp();
        return handled;
    }

    public boolean textFocused(String focused) {
        return active && !history && !showAgreement() && waiting() == null && INPUT.equals(focused);
    }

    public boolean referencePointer(float x, float y) {
        if (!active || history || showAgreement()) return false;
        float[] p = local(x, y);
        return answer.referencePointer(p[0], p[1]);
    }

    public boolean textPointer(float x, float y) {
        if (!active || history || showAgreement() || waiting() != null) return false;
        float[] p = local(x, y);
        return p[0] >= editorX
                && p[0] < editorX + editorW
                && p[1] >= editorY
                && p[1] < editorY + editorH;
    }

    public boolean key(int key, int mods, String focused) {
        if (!active) return false;
        boolean editing = textFocused(focused);
        if (picker.open && editing) {
            int delta = switch (key) {
                case GLFW.GLFW_KEY_UP -> -1;
                case GLFW.GLFW_KEY_DOWN -> 1;
                case GLFW.GLFW_KEY_PAGE_UP -> -WzItemSearch.ROWS;
                case GLFW.GLFW_KEY_PAGE_DOWN -> WzItemSearch.ROWS;
                default -> 0;
            };
            if (delta != 0) { picker.move(delta); return true; }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                picker.refresh();
                insertItem(picker.selected);
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) { closePicker(); return true; }
        }
        HubTextEditor editor = picker.open ? picker.query : input;
        double steps = switch (key) {
            case GLFW.GLFW_KEY_PAGE_UP -> 10;
            case GLFW.GLFW_KEY_PAGE_DOWN -> -10;
            case GLFW.GLFW_KEY_UP -> editing ? 0 : 1;
            case GLFW.GLFW_KEY_DOWN -> editing ? 0 : -1;
            default -> 0;
        };
        if (steps != 0) {
            if (showAgreement()) return agreement.scroll(630, 420, steps);
            userScrollTo(scroll - (float) steps * 48);
            return true;
        }
        if (!editing) return false;
        boolean ctrl = (mods & GLFW.GLFW_MOD_CONTROL) != 0,
                shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        switch (key) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (shift) input.insert("\n");
                else send(input.text());
            }
            case GLFW.GLFW_KEY_BACKSPACE -> editor.backspace(ctrl);
            case GLFW.GLFW_KEY_DELETE -> editor.deleteForward(ctrl);
            case GLFW.GLFW_KEY_LEFT -> editor.moveLeft(ctrl, shift);
            case GLFW.GLFW_KEY_RIGHT -> editor.moveRight(ctrl, shift);
            case GLFW.GLFW_KEY_HOME -> editor.moveHome(ctrl, shift);
            case GLFW.GLFW_KEY_END -> editor.moveEnd(ctrl, shift);
            case GLFW.GLFW_KEY_UP -> editor.moveVertical(-1, shift);
            case GLFW.GLFW_KEY_DOWN -> editor.moveVertical(1, shift);
            case GLFW.GLFW_KEY_A -> {
                if (ctrl) editor.selectAll();
                else return false;
            }
            case GLFW.GLFW_KEY_SPACE -> {
                // charTyped inserts the space. It is not a button activation.
            }
            default -> {
                return false;
            }
        }
        if (picker.open && (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE)) picker.changed();
        return true;
    }

    public void type(char c, int mods) {
        if ((mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT)) != 0) return;
        if (picker.open) { picker.query.type(c); picker.changed(); }
        else if (c == '@') openPicker();
        else input.type(c);
    }

    public void paste(String value) {
        if (picker.open) { picker.query.insert(value); picker.changed(); }
        else input.insert(references.decode(value));
    }

    public String selection() {
        return picker.open ? picker.query.selectedText() : references.encode(input.selectedText());
    }

    public String cut() {
        if (picker.open) { picker.changed(); return picker.query.cutSelection(); }
        return references.encode(input.cutSelection());
    }

    public void paintItems(
            GuiGraphics graphics, float ox, float oy, float sx, float sy, int mx, int my) {
        if (!active || showAgreement() || history) return;
        float[] m = transform.getMat();
        if (!usageOpen && !quotaOpen && guideAlpha(now, reduced) >= 1)
            references.paint(graphics, ox + sx * m[2], oy + sy * m[5], sx * m[0], sy * m[4], mx, my);
        if (!drawItems || picker.open) return;
        answer.paintItems(
                graphics,
                ox + sx * m[2],
                oy + sy * m[5],
                sx * m[0],
                sy * m[4],
                X,
                TOP,
                W,
                dockY - TOP,
                mx,
                my);
    }

    @Override
    public void hit(String id, String label, float x, float y, float w, float h, Runnable action) {
        float from = Math.max(y, clipTop), to = Math.min(y + h, clipBottom);
        if (active && (reduced || now - opened >= 230_000_000L) && to > from)
            host.wenzhouAddHit(id, label, x + routeOffset, from, w, to - from, action);
    }

    @Override
    public boolean quotaEnabled() {
        return model.quota.enabled();
    }

    @Override
    public JsonObject policy() {
        return model.policy;
    }

    @Override
    public long wenzhouWindow() {
        return host.wenzhouWindow();
    }

    @Override
    public boolean researchAllowed() {
        return model.research;
    }

    @Override
    public void accept(boolean research) {
        model.consent(research);
        wantFocus = true;
    }

    @Override
    public void setResearch(boolean research) {
        model.research(research);
    }

    @Override
    public void close() {
        if (reviewing) {
            reviewing = false;
            agreement.reset();
            host.wenzhouFocus("wz-terms");
        } else back();
    }

    private float[] local(float x, float y) {
        float[] point = HubDraw.inversePoint(transform, x, y);
        point[0] -= routeOffset;
        return point;
    }

    private float ease(float value) {
        return reduced ? 1 : curve(value);
    }

    private static float curve(float value) {
        if (value >= 1) return 1;
        if (value <= 0) return 0;
        float low = 0, high = 1;
        for (int i = 0; i < 12; i++) {
            float at = (low + high) / 2;
            if (cubic(at, .22f, .18f) < value) low = at;
            else high = at;
        }
        return cubic((low + high) / 2, .68f, 1);
    }

    private static float cubic(float t, float a, float b) {
        float q = 1 - t;
        return 3 * q * q * t * a + 3 * q * t * t * b + t * t * t;
    }

    private static float clamp(float value, float lo, float hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
