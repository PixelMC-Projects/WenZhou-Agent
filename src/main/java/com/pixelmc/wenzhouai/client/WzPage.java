/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pixelmc.hub.client.render.HubDraw;
import com.pixelmc.hub.client.ui.HubExternalPage;
import com.pixelmc.hub.client.ui.HubPageHost;
import com.pixelmc.hub.client.ui.HubPageRegistry;
import com.pixelmc.hub.client.ui.HubTextEditor;
import com.pixelmc.hub.client.ui.HubView;
import com.pixelmc.wenzhouai.network.WzBlockReference;

import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.types.RRect;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Native guide surface within the Hub's frame. */
public final class WzPage implements HubExternalPage {
    public static final String PAGE_ID = "wenzhouai";

    private static final class HubHostAdapter implements Host {
        private final HubPageHost host;

        private HubHostAdapter(HubPageHost host) {
            this.host = host;
        }

        @Override
        public void wenzhouAddHit(
                String id, String label, float x, float y, float w, float h, Runnable action) {
            host.hit(id, label, x, y, w, h, action);
        }

        @Override
        public void wenzhouFocus(String id) {
            host.focus(id);
        }

        @Override
        public void wenzhouBack() {
            host.navigateBuiltIn(0);
        }

        @Override
        public float wenzhouHover(String id, boolean hovered) {
            return host.extensionHover(id, hovered);
        }

        @Override
        public float wenzhouButtonMove(String id, boolean enabled, boolean hovered) {
            return host.extensionButtonMove(id, enabled, hovered);
        }

        @Override
        public void feedback(String value) {
            host.feedback(value);
        }

        @Override
        public void feedbackSuccess(String value) {
            host.feedbackSuccess(value);
        }

        @Override
        public void feedbackError(String value) {
            host.feedbackError(value);
        }

        @Override
        public long wenzhouWindow() {
            return host.windowHandle();
        }
    }

    @Mod.EventBusSubscriber(
            modid = "wenzhouai",
            value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {}

        @SubscribeEvent
        public static void clientSetup(FMLClientSetupEvent event) {
            HubPageRegistry.registerExternalPage(
                    PAGE_ID, host -> new WzPage(new HubHostAdapter(host)));
        }
    }

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
    private static final String TOOLS = "wz-reference-tools",
            TOOL_ITEM = "wz-tools-item",
            TOOL_BLOCK = "wz-tools-block";
    private static final int INPUT_LIMIT = 1200;
    private static final float X = 275, W = 710, END = X + W, TOP = 55, BOTTOM = 849;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M/d");

    private static final List<String> GREETINGS =
            List.of(
                    "哎，碰到你啦！",
                    "今天的风刚刚好。",
                    "晃悠，晃悠。",
                    "刚跟一条鱼打了个照面。",
                    "嘿，给你留了个位置。",
                    "我刚才漂到哪儿来着？",
                    "扑通！哦，是颗小石子。",
                    "好巧，你也在这儿。");

    private int greetingIndex = ThreadLocalRandom.current().nextInt(GREETINGS.size());
    private final Host host;
    private final HubView.NavigationEntry navigationEntry =
            new HubView.NavigationEntry(
                    "wz-nav",
                    "问舟",
                    "问舟",
                    "",
                    0xff31d1bd,
                    0xff098b91,
                    (draw, x, y, size) -> WzGlyph.draw(draw, "boat", x, y, size, 0xffffffff),
                    this::openEntry);
    final WzConversation model;
    private final WzFirstGuide firstGuide;
    private final HubWenzhouAnswer answer = new HubWenzhouAnswer();
    private final WzUsage.Cache usageCache = new WzUsage.Cache();
    private final HubTextEditor input = new HubTextEditor(true, INPUT_LIMIT);
    private final WzInlineItems references = new WzInlineItems();
    private final WzReferenceTools tools = new WzReferenceTools();
    private final WzItemSearch picker = new WzItemSearch();
    private final WzModelPicker modelPicker;
    private final WzShareView shares;
    private boolean settings, restorePinned;
    private float restoreScroll;
    private long settingsChanged, shareExit;
    private Runnable afterShareExit;
    private float pickerY;
    private static final float SEARCH_H = 41, ROW_H = 42;
    private static final String[] QUICK_ASK_PRESETS = {
        "怎么获取？", "有什么用，怎么使用？", "有哪些主要用途或关联玩法？",
    };
    private static final float QUICK_CONTEXT_H = 40f, QUICK_ROW_H = 34f, QUICK_LIST_PAD = 6f;
    private static final float QUICK_TOP_H =
            QUICK_CONTEXT_H + QUICK_LIST_PAD * 2 + QUICK_ROW_H * QUICK_ASK_PRESETS.length;

    private record QuickAskItem(ItemStack stack, ResourceLocation id, String name) {}

    private QuickAskItem quickAskItem;
    private long suppressQuickAskCharUntil;

    private HubTextEditor wrapper = new HubTextEditor(true, 65536);
    private HubDraw draw;
    private WzWinter winter;
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
        boolean running, answered, streaming;
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
    private boolean openWhenReady;
    private boolean active, history, dark, reduced, pinned = true, usageOpen, usageDismissed;
    private boolean quotaOpen, quotaDismissed;
    private WzQuota measuredQuota;
    private float quotaTipWidth;
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
        shares = new WzShareView(model, host::feedbackError);
        model.openLink = this::chatLink;
        modelPicker = new WzModelPicker(host, model);
        firstGuide = new WzFirstGuide(host, this::guideHit);
    }

    @Override
    public String id() {
        return PAGE_ID;
    }

    public void invalidateStatus() {
        model.invalidateStatus();
    }

    @Override
    public void refresh() {
        model.refresh();
        synchronizeAccess();
    }

    @Override
    public void prefetchIfDue() {
        if (model.prefetchDue()) model.refresh();
    }

    @Override
    public void cancelPendingOpen() {
        openWhenReady = false;
    }

    void synchronizeAccess() {
        if (displayedEpoch != model.epoch()) openWhenReady = false;
        if (openWhenReady && !model.checking) {
            openWhenReady = false;
            if (model.entry()) openEntry();
            else if (!model.entryNotice.isEmpty()) host.feedback(model.entryNotice);
        }
        if (displayedEpoch != model.epoch()) {
            displayedEpoch = model.epoch();
            resetExtras();
            references.clear();
            picker.open = false;
            modelPicker.close();
            tools.close();
            input.setText("");
            answer.reset();
            usageCache.clear();
            clearChatLayouts();
            firstGuide.reset();
        }
        if ((active || closedAt != 0) && !model.entry() && shares.page != WzShareView.Page.EXTERNAL)
            leave();
    }

    @Override
    public boolean active() {
        return active;
    }

    @Override
    public boolean routeVisible(long time, boolean reducedMotion) {
        return active || !reducedMotion && closedAt != 0 && time - closedAt < 180_000_000L;
    }

    @Override
    public boolean coverHandbook(long time, boolean reducedMotion) {
        return handbookAlpha(time, reducedMotion) <= 0;
    }

    @Override
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
        cancelPendingOpen();
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
        resetExtras();
        picker.open = false;
        modelPicker.close();
        tools.close();
        active =
                history =
                        usageOpen =
                                usageDismissed =
                                        quotaOpen = quotaDismissed = drawItems = railDrag = false;
        closedAt = 0;
        confirm = null;
        input.pointerUp();
        firstGuide.reset();
    }

    @Override
    public HubView.NavigationEntry navigationEntry() {
        return navigationEntry;
    }

    @Override
    public boolean navigationVisible() {
        return model.entryConfirmed || model.entry() || !model.entryNotice.isEmpty();
    }

    private void openEntry() {
        if (!model.entry()) {
            if (model.entryConfirmed && model.checking) {
                openWhenReady = true;
                host.feedback("正在读取问舟…");
            } else if (!model.entryNotice.isEmpty()) host.feedback(model.entryNotice);
            return;
        }
        if (closedAt != 0) {
            history = false;
            confirm = null;
        }
        quickAskItem = null;
        String resume = model.resumeThread();
        if (resume != null || !empty()) choose(resume);
        if (resume == null && empty()) model.resumeFresh();
        if (empty()) changeGreeting();
        switchRoute(true, System.nanoTime());
        paneChanged = opened;
        wantFocus = true;
    }

    boolean quickAskAvailable() {
        return model.entry() && !model.writing;
    }

    boolean openQuickAsk(ItemStack stack, ResourceLocation id) {
        if (stack == null || stack.isEmpty() || id == null) return false;
        refresh();
        if (!quickAskAvailable()) return false;
        resetExtras();
        history = false;
        confirm = null;
        picker.open = false;
        tools.close();
        modelPicker.close();
        usageOpen = quotaOpen = false;
        model.choose(null);
        input.setText("");
        scroll = maxScroll = 0;
        String rawName = ChatFormatting.stripFormatting(stack.getHoverName().getString());
        String cleanName = rawName == null || rawName.isBlank() ? id.toString() : rawName.trim();
        quickAskItem = new QuickAskItem(stack.copyWithCount(1), id, cleanName);
        long timestamp = System.nanoTime();
        switchRoute(true, timestamp);
        paneChanged = opened;
        layoutChanged = 0;
        pinned = wantFocus = true;
        suppressQuickAskCharUntil = timestamp + 140_000_000L;
        return true;
    }

    private boolean quickAskActive() {
        return quickAskItem != null
                && empty()
                && !history
                && shares.page == null
                && !firstGuide.active();
    }

    @Override
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
        float route = guideAlpha(time, reduceMotion) * WzWorldPicker.closingOpacity(this, time);
        routeOffset = 22 * (1 - route);
        float[] pointer = local(mouseX, mouseY);
        mx = pointer[0];
        my = pointer[1];
        now = time;
        this.dark = dark;
        reduced = reduceMotion;
        focus = focused;
        winter = WzWinter.forDraw(d);
        ink = dark ? 0xffe4e9f5 : 0xff26344f;
        muted = dark ? 0xffaeb9d0 : 0xff6c7891;
        blue = dark ? 0xffa28ae7 : 0xff765bd4;
        rule = dark ? 0x29a5b7e0 : 0xffd9dfee;
        paper = dark ? 0xe0252d44 : 0xe0ffffff;
        solidPaper = dark ? 0xff252d44 : 0xffffffff;
        caution = dark ? 0xfff2c582 : 0xff845013;
        if (winter != null) {
            ink = winter.ink();
            muted = winter.muted();
            blue = winter.pine();
            rule = winter.ruling();
            paper = solidPaper = winter.sheet();
        }
        answer.appearance(winter);
        clipTop = 0;
        clipBottom = 850;
        if (shareExit != 0 && (reduced || now - shareExit >= 150_000_000L)) {
            var done = afterShareExit;
            shareExit = 0;
            afterShareExit = null;
            if (done != null) done.run();
        }
        long reminderExitAt = firstGuide.reminderExitAt();
        if (!active || shares.page != null) firstGuide.reset();
        else if (firstGuide.finishIfDue(time, reduced)) {
            if (reminderExitAt != 0) settingsChanged = reduced ? 0 : reminderExitAt;
            wantFocus = true;
        } else if (!firstGuide.active() && route > 0 && !model.introSeen()) {
            picker.open = usageOpen = quotaOpen = railDrag = false;
            tools.close();
            input.pointerUp();
            firstGuide.open(time);
            focus = null;
            model.markIntroSeen();
        }
        if (active
                && shares.page == null
                && !settings
                && wantFocus
                && !firstGuide.active()
                && !history
                && waiting() == null) {
            host.wenzhouFocus(INPUT);
            focus = INPUT;
            wantFocus = false;
        }
        firstGuide.beginFrame(model.chatGptSelected(), model.socialEnabled);
        references.beginFrame();
        if (history || waiting() != null) {
            picker.open = false;
            tools.close();
        } else if (tools.open() && !toolFocus(focus)) {
            tools.close();
        }
        if (!active
                || history
                || waiting() != null
                || firstGuide.active()
                || model.modelPicker() == null
                || !modelPicker.focused(focus)
                || (model.busy() && !model.modelUpdating())) modelPicker.close();
        answer.beginFrame();
        drawItems = false;
        boolean literal = d.literalColors(true);
        if (route < 1) {
            d.saveLayerAlpha(0, 0, 1260, 850, route);
            d.translate(routeOffset, 0);
        }
        try {
            if (winter != null) {
                d.rect(0, 0, 1260, 850, winter.paper());
                winter.topbar(d, 1260, TOP);
            } else {
                d.linearGradientRoundRect(
                        1,
                        1,
                        1258,
                        848,
                        0,
                        135,
                        dark
                                ? new int[] {0xff252d44, 0xff20283b}
                                : new int[] {0xadffffff, 0x9cf5f6ff},
                        new float[] {0, 1});
                d.rect(1, 1, 1258, 53, dark ? 0x47263149 : 0x36ffffff);
                d.rect(1, 54, 1258, 1, dark ? 0x11b6c9f0 : 0xb8ffffff);
            }
            if (shares.page != null) {
                drawShares();
                if (winter != null) winter.topbarRule(d, 1260, TOP);
                return;
            }
            button("wz-back", "返回手册", "back", 28, 9, 104, 36, this::back, false);
            if (!history && (!empty() || firstGuide.showingStep(5))) drawNewConversation();
            boolean hasNew = !history && (!empty() || firstGuide.showingStep(5));
            float inboxX = hasNew ? 938 : 1029;
            if (model.socialEnabled)
                button(
                        "wz-inbox",
                        "收到的分享",
                        "inbox",
                        inboxX,
                        9,
                        105,
                        36,
                        () -> openShares(WzShareView.Page.INBOX, "", 0),
                        false);
            if (firstGuide.showingStep(7)) firstGuide.include(inboxX, 9, 105, 36);
            if (firstGuide.showingStep(6)) firstGuide.include(inboxX - 77, 9, 73, 36);
            if (model.socialEnabled
                    && !history
                    && (firstGuide.showingStep(6)
                            || model.selected != null
                                    && model.turns.stream()
                                            .anyMatch(
                                                    t ->
                                                            t.reply != null
                                                                    && !t.stopped
                                                                    && t.itemChoice == null)))
                button(
                        "wz-share",
                        "分享",
                        "share",
                        inboxX - 77,
                        9,
                        73,
                        36,
                        () -> openShares(WzShareView.Page.PREVIEW, model.selected, 0),
                        false);
            if (firstGuide.showingStep(4)) firstGuide.include(1150, 9, 82, 36);
            button(
                    "wz-history",
                    history ? "返回对话" : "历史",
                    "history",
                    1150,
                    9,
                    history ? 86 : 73,
                    36,
                    this::toggleHistory,
                    false);
            if (!history
                    && (model.socialEnabled && firstGuide.showingStep(4)
                            || model.unreadReminder && !model.unread.isEmpty()))
                d.roundRect(1214, 14, 6, 6, 3, unreadColor());
            float homeArrival = firstGuide.homeArrival(now);
            if (homeArrival < 1) {
                d.saveLayerAlpha(0, TOP, 1260, 850 - TOP, .6f + .4f * homeArrival);
                d.translate(0, 7 * (1 - homeArrival));
            }
            try {
                if (history) drawHistory();
                else {
                    drawChat();
                    if ((empty() && !firstGuide.active()
                                    || model.socialEnabled && firstGuide.showingReminders())
                            && !WzWorldPicker.containsDraft(this)) drawSettings();
                    if (active
                            && !firstGuide.active()
                            && net.minecraft.client.Minecraft.getInstance().screen
                                    instanceof com.pixelmc.hub.client.HubScreen
                            && net.minecraft.client.Minecraft.getInstance().isWindowActive())
                        model.markVisibleRead();
                }
            } finally {
                if (homeArrival < 1) d.restore();
            }
            if (winter != null) winter.topbarRule(d, 1260, TOP);
            firstGuide.render(
                    d,
                    time,
                    reduced,
                    dark,
                    mx,
                    my,
                    focus,
                    ink,
                    muted,
                    blue,
                    rule,
                    solidPaper,
                    winter);
        } finally {
            if (route < 1) d.restore();
            d.literalColors(literal);
            draw = null;
        }
        drawItems =
                active
                        && !firstGuide.active()
                        && !history
                        && !empty()
                        && route >= 1
                        && !usageOpen
                        && !quotaOpen
                        && !tools.open()
                        && !modelPicker.open();
    }

    private int unreadColor() {
        return winter != null ? winter.mailInk() : dark ? 0xffb9a5ff : 0xff087d8b;
    }

    private void resetExtras() {
        settings = false;
        quickAskItem = null;
        settingsChanged = shareExit = 0;
        afterShareExit = null;
        shares.close();
    }

    private void toggleSettings() {
        settings = !settings;
        settingsChanged = System.nanoTime();
        tools.close();
        picker.open = false;
        modelPicker.close();
        host.wenzhouFocus(
                settings
                        ? (model.socialEnabled ? "wz-pref-completion" : "wz-pref-quick-global")
                        : "wz-pref-gear");
    }

    private float settingsPanelHeight() {
        return model.socialEnabled ? 250f : 128f;
    }

    private float settingsPanelTop() {
        return 774f - settingsPanelHeight();
    }

    private void drawSettings() {
        String trigger = "wz-pref-gear";
        boolean hover = inside(24, 785, 40, 40);
        float h = host.wenzhouHover(trigger, hover || settings || trigger.equals(focus));
        float y = 785 + host.wenzhouButtonMove(trigger, true, hover);
        if (winter != null) {
            draw.boxShadow(24, y, 40, 40, 20, 0, 2, 4, 0, alpha(ink, .09f));
            draw.circle(44, y + 20, 20, mixColor(winter.soft(), winter.cover(), h));
        } else card(24, y, 40, 40, 20);
        draw.save();
        draw.translate(44, y + 20);
        draw.canvas().rotate(24 * h);
        WzGlyph.draw(
                draw,
                "gear",
                -9.5f,
                -9.5f,
                19,
                winter != null ? mixColor(blue, ink, h) : mixColor(muted, blue, h));
        draw.restore();
        hit(trigger, "问舟设置", 24, 785, 40, 40, this::toggleSettings);
        float progress =
                reduced || settingsChanged == 0
                        ? 1
                        : WzFirstGuide.reminderPanelProgress(now - settingsChanged);
        boolean tutorial = firstGuide.showingReminders();
        float panelH = settingsPanelHeight();
        float panelY = settingsPanelTop();
        if (tutorial) firstGuide.include(24, panelY, 282, 825 - panelY);
        float opacity =
                tutorial
                        ? firstGuide.reminderPreviewOpacity(now, reduced)
                        : settings ? progress : 1 - progress;
        if (opacity <= 0) return;
        draw.saveLayerAlpha(20, panelY - 12, 300, panelH + 24, opacity);
        draw.translate(0, (tutorial || settings ? 6 : 4) * (1 - opacity));
        if (winter != null) winter.popover(draw, 24, panelY, 282, panelH);
        else card(24, panelY, 282, panelH, 16);
        draw.text("问舟设置", 40, panelY + 27, 15, winter != null ? blue : ink, true);
        float cursorY = panelY + 34;
        if (model.socialEnabled) {
            drawSetting(true, cursorY);
            draw.line(40, cursorY + 59, 290, cursorY + 59, 1, rule);
            drawSetting(false, cursorY + 61);
            draw.line(40, cursorY + 120, 290, cursorY + 120, 1, rule);
            cursorY += 124;
        }
        drawQuickAskSetting(cursorY);
        draw.restore();
    }

    private void drawQuickAskSetting(float y) {
        draw.text("物品快捷提问", 40, y + 20, 13, ink, true);
        draw.text("悬停物品时显示快捷提问提示。", 40, y + 38, 12, muted, false);
        float trackX = 40f, trackY = y + 48f, trackW = 250f, trackH = 32f;
        int trackFill = winter != null ? winter.soft() : alpha(ink, .05f);
        draw.roundRect(trackX, trackY, trackW, trackH, 10, trackFill);
        draw.strokeRoundRect(trackX + .5f, trackY + .5f, trackW - 1, trackH - 1, 9.5f, 1, rule);
        WzQuickAskPrefs.Mode current = WzQuickAskPrefs.mode();
        WzQuickAskPrefs.Mode[] modes = WzQuickAskPrefs.Mode.values();
        float gap = 2f;
        float inset = 3f;
        float segW = (trackW - inset * 2 - gap * (modes.length - 1)) / modes.length;
        float segH = trackH - inset * 2;
        for (int i = 0; i < modes.length; i++) {
            WzQuickAskPrefs.Mode mode = modes[i];
            float sx = trackX + inset + i * (segW + gap);
            float sy = trackY + inset;
            boolean activeSeg = current == mode;
            String id = "wz-pref-quick-" + mode.wire;
            boolean hovered = settings && (id.equals(focus) || inside(sx, sy, segW, segH));
            float h = host.wenzhouHover(id, hovered);
            if (activeSeg) {
                if (winter == null) {
                    draw.boxShadow(sx, sy, segW, segH, 7, 0, 2, 8, 0, alpha(blue, .28f));
                }
                draw.roundRect(sx, sy, segW, segH, 7, blue);
            } else if (h > 0) {
                draw.roundRect(sx, sy, segW, segH, 7, alpha(ink, .05f * h));
            }
            int labelColor =
                    activeSeg
                            ? (winter != null ? winter.sheet() : 0xffffffff)
                            : mixColor(muted, ink, h);
            center(mode.label, sx + segW / 2f, sy + 17.5f, 12, labelColor, true);
            if (settings) {
                hit(
                        id,
                        "物品快捷提问：" + mode.label,
                        sx,
                        sy,
                        segW,
                        segH,
                        () -> WzQuickAskPrefs.setMode(mode, host::feedbackError));
            }
        }
    }

    private void drawSetting(boolean completion, float y) {
        String label = completion ? "处理完成提醒" : "历史未读提醒";
        String id = completion ? "wz-pref-completion" : "wz-pref-unread";
        boolean on = completion ? model.completionReminder : model.unreadReminder;
        float hover =
                host.wenzhouHover(id, settings && (id.equals(focus) || inside(35, y, 260, 55)));
        if (hover > 0) draw.roundRect(33, y, 264, 54, 9, alpha(blue, .07f * hover));
        draw.text(label, 40, y + 20, 13, ink, true);
        draw.text(
                completion ? "离开手册后，回答完成时提醒我。" : "在历史入口和未读对话旁显示圆点。", 40, y + 40, 12, muted, false);
        float value = reduced ? (on ? 1 : 0) : host.wenzhouHover(id + "-switch", on);
        draw.roundRect(257, y + 16, 33, 18, 9, mixColor(rule, blue, value));
        draw.roundRect(
                259 + 15 * value, y + 18, 14, 14, 7, winter != null ? winter.sheet() : 0xffffffff);
        if (settings && !model.savingReminder)
            hit(
                    id,
                    label + (on ? "，已开启" : "，已关闭"),
                    35,
                    y,
                    260,
                    55,
                    () -> model.toggleReminder(completion, host::feedbackError));
    }

    private void chatLink(String kind, String id) {
        if (kind.equals("link.answer") && !model.entry()) return;
        if (!(net.minecraft.client.Minecraft.getInstance().screen
                instanceof com.pixelmc.hub.client.HubScreen))
            com.pixelmc.hub.client.ClientBootstrap.open();
        if (!active) switchRoute(true, System.nanoTime());
        if (kind.equals("link.shared")) openShares(WzShareView.Page.EXTERNAL, id, 0);
        else {
            resetExtras();
            choose(id);
        }
    }

    private void openShares(WzShareView.Page target, String id, int index) {
        if (shares.page == null) {
            restoreScroll = scroll;
            restorePinned = pinned;
        }
        settings = usageOpen = quotaOpen = false;
        picker.open = false;
        tools.close();
        modelPicker.close();
        firstGuide.reset();
        answer.reset();
        shares.load(target, id, index);
        paneChanged = System.nanoTime();
        scroll = maxScroll = 0;
        pinned = wantFocus = false;
        host.wenzhouFocus("wz-share-back");
    }

    private void returnFromShare() {
        if (shareExit != 0) return;
        if (!shares.remove.isEmpty()) {
            shares.remove = "";
            return;
        }
        shareExit = System.nanoTime();
        afterShareExit =
                () -> {
                    if (shares.page == WzShareView.Page.DETAIL) {
                        openShares(WzShareView.Page.INBOX, "", shares.index);
                        return;
                    }
                    boolean external = shares.page == WzShareView.Page.EXTERNAL;
                    boolean preview = shares.page == WzShareView.Page.PREVIEW;
                    shares.close();
                    answer.reset();
                    scroll = restoreScroll;
                    pinned = restorePinned;
                    paneChanged = System.nanoTime();
                    if (external) back();
                    else host.wenzhouFocus(preview ? "wz-share" : "wz-inbox");
                };
    }

    private static final DateTimeFormatter RECEIVED =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneId.systemDefault());

    private void drawShares() {
        float opacity =
                shareExit != 0
                        ? 1 - ease((now - shareExit) / 150_000_000f)
                        : ease((now - paneChanged) / 200_000_000f);
        boolean preview = shares.page == WzShareView.Page.PREVIEW;
        boolean inbox = shares.page == WzShareView.Page.INBOX;
        if (opacity < 1) {
            draw.saveLayerAlpha(0, 0, 1260, 850, opacity);
            draw.translate(12 * (1 - opacity), 0);
        }
        try {
            String backLabel =
                    switch (shares.page) {
                        case DETAIL -> "收到的分享";
                        case EXTERNAL -> "返回手册";
                        default -> "返回问舟";
                    };
            button(
                    "wz-share-back",
                    backLabel,
                    "back",
                    28,
                    9,
                    114,
                    36,
                    this::returnFromShare,
                    false);
            if (shares.loading) {
                draw.text("正在读取…", X, 105, 15, muted, false);
                return;
            }
            if (!shares.error.isEmpty()) {
                drawRetry(
                        "wz-share-retry",
                        shares.error,
                        86,
                        () -> openShares(shares.page, shares.source, shares.index));
                return;
            }
            dockY = preview ? BOTTOM - 70 : BOTTOM;
            draw.save();
            clipTop = TOP;
            clipBottom = dockY;
            try {
                draw.clipRoundRect(0, TOP, 1260, dockY - TOP, 0);
                if (inbox) drawInbox();
                else drawSnapshot();
            } finally {
                draw.restore();
                clipTop = 0;
                clipBottom = 850;
            }
            drawRail(dockY);
            if (preview) {
                draw.line(0, dockY, 1260, dockY, 1, rule);
                draw.text(
                        shares.published ? "快照已分享至聊天区" : "公开当前已完成的问答，后续追问不会加入快照。",
                        X,
                        dockY + 38,
                        13,
                        muted,
                        false);
                button(
                        "wz-publish",
                        shares.published ? "已分享" : "分享至聊天区",
                        "share",
                        END - 133,
                        dockY + 16,
                        133,
                        36,
                        shares.published || shares.writing
                                ? null
                                : () -> shares.publish(() -> host.feedbackSuccess("已分享至聊天区")),
                        true);
            }
            drawItems = !inbox && opacity >= 1;
        } finally {
            if (opacity < 1) draw.restore();
        }
    }

    private void drawSnapshot() {
        answer.beginFrame(
                X,
                TOP,
                W,
                dockY - TOP,
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
        float content = measureTurns(shares.turns);
        maxScroll = Math.max(0, content + 180 - (dockY - TOP));
        scroll = clamp(scroll, 0, maxScroll);
        float y = TOP + 32 - scroll;
        if (winter != null) {
            winter.letterEdge(draw, X, y - 18, W);
            winter.art(draw, "winter-post", END - 66, y - 11, 66, 33, 1);
        }
        draw.text(
                shares.page == WzShareView.Page.PREVIEW ? "分享预览" : "只读快照",
                X,
                y,
                13,
                winter != null ? blue : muted,
                false);
        heading(ellipsis(WzConversation.str(shares.data, "title"), W, 25), X, y + 41, 25);
        draw.text(
                ellipsis(
                        WzConversation.str(shares.data, "sender")
                                + " 分享 · "
                                + WzConversation.str(shares.data, "models")
                                + " · "
                                + shares.turns.size()
                                + " 轮问答",
                        W,
                        13),
                X,
                y + 70,
                13,
                muted,
                false);
        draw.line(X, y + 92, END, y + 92, 1, rule);
        y += 118;
        for (int i = 0; i < shares.turns.size(); i++) {
            var turn = shares.turns.get(i);
            var layout = turnLayouts.get(turn);
            if (y + layout.end > TOP && y + layout.start < dockY)
                drawTurn(turn, layout, i, y + layout.start);
        }
    }

    private void drawInbox() {
        var rows = WzConversation.array(shares.data, "rows");
        int last = (int) WzConversation.number(shares.data, "last", 0);
        float content =
                120 + rows.size() * 76 + (!shares.remove.isEmpty() ? 49 : 0) + (last > 0 ? 50 : 0);
        maxScroll = Math.max(0, content - (BOTTOM - TOP));
        scroll = clamp(scroll, 0, maxScroll);
        float y = TOP + 56 - scroll;
        heading("收到的分享", X, y, 25);
        if (winter != null) winter.art(draw, "winter-post", END - 90, y - 29, 90, 38, 1);
        draw.text(
                WzConversation.number(shares.data, "count", 0) + " 条",
                X + 152,
                y,
                13,
                muted,
                false);
        y += 24;
        if (rows.isEmpty()) {
            center("还没有收到的分享", 630, y + 75, 16, ink, true);
            center("点开玩家在聊天里分享的对话，就会保存在这里。", 630, y + 102, 13, muted, false);
        }
        for (var e : rows) {
            var row = e.getAsJsonObject();
            String id = WzConversation.str(row, "id"), title = WzConversation.str(row, "title");
            if (y + 76 > TOP && y < BOTTOM) {
                float hover = host.wenzhouHover("wz-s-" + id, inside(X, y, W - 90, 76));
                draw.roundRect(
                        X, y, W - 90, 76, 10, alpha(winter != null ? winter.soft() : paper, hover));
                if (winter != null) {
                    draw.roundRect(X + 4, y + 18, 34, 39, 5, winter.cover());
                    draw.strokeRoundRect(X + 4.5f, y + 18.5f, 33, 38, 5, 1, winter.stitch());
                    draw.roundRect(X + 4, y + 19, 3, 37, 1.5f, blue);
                    WzGlyph.draw(draw, "book", X + 12, y + 27, 19, blue);
                } else {
                    draw.roundRect(X + 4, y + 18, 39, 39, 11, alpha(blue, .13f));
                    WzGlyph.draw(draw, "book", X + 13, y + 27, 21, blue);
                }
                draw.text(ellipsis(title, W - 270, 15), X + 59, y + 32, 15, ink, true);
                draw.text(
                        ellipsis(
                                WzConversation.str(row, "sender")
                                        + " 分享 · "
                                        + WzConversation.str(row, "models"),
                                W - 270,
                                12),
                        X + 59,
                        y + 52,
                        12,
                        muted,
                        false);
                draw.text(
                        RECEIVED.format(Instant.ofEpochMilli(WzConversation.number(row, "at", 0))),
                        END - 176,
                        y + 42,
                        12,
                        muted,
                        false);
                hit(
                        "wz-s-" + id,
                        title,
                        X,
                        y,
                        W - 90,
                        76,
                        () -> openShares(WzShareView.Page.DETAIL, id, shares.index));
                shareIcon(
                        "pin",
                        id,
                        WzConversation.flag(row, "pinned") ? "取消置顶" : "置顶",
                        END - 76,
                        y + 21,
                        WzConversation.flag(row, "pinned"),
                        () -> shares.change(row, false, () -> {}));
                shareIcon("trash", id, "删除", END - 36, y + 21, false, () -> shares.remove = id);
            }
            y += 76;
            if (shares.remove.equals(id)) {
                if (winter != null)
                    draw.roundRect(X, y - 3, W, 39, 9, mixColor(paper, winter.mail(), .09f));
                draw.text("从你的列表移除这条分享？", X + 10, y + 24, 13, ink, false);
                button(
                        "wz-s-cancel",
                        "取消",
                        null,
                        END - 157,
                        y,
                        57,
                        32,
                        () -> shares.remove = "",
                        false);
                button(
                        "wz-s-delete",
                        "确认删除",
                        null,
                        END - 88,
                        y,
                        87,
                        32,
                        () -> shares.change(row, true, () -> host.feedbackSuccess("已从收到的分享中移除")),
                        true);
                y += 49;
            }
            listRule(y);
        }
        if (last > 0) {
            button(
                    "wz-s-prev",
                    "上一页",
                    null,
                    X,
                    y + 8,
                    70,
                    32,
                    shares.index > 0 ? () -> openShares(shares.page, "", shares.index - 1) : null,
                    false);
            button(
                    "wz-s-next",
                    "下一页",
                    null,
                    END - 70,
                    y + 8,
                    70,
                    32,
                    shares.index < last
                            ? () -> openShares(shares.page, "", shares.index + 1)
                            : null,
                    false);
            y += 50;
        }
        if (winter != null && maxScroll == 0) {
            float artTop = Math.max(BOTTOM - 162, rows.isEmpty() ? y + 120 : y + 12);
            if (BOTTOM - artTop > 48)
                winter.art(draw, "pine-lantern", 1015, artTop, 180, BOTTOM - artTop - 12, .66f);
        }
    }

    private void shareIcon(
            String icon,
            String row,
            String label,
            float x,
            float y,
            boolean selected,
            Runnable click) {
        String id = "wz-s-" + icon + row;
        float h = host.wenzhouHover(id, inside(x, y, 34, 34));
        draw.roundRect(x, y, 34, 34, 9, alpha(selected ? blue : paper, selected ? .15f : h));
        WzGlyph.draw(draw, icon, x + 9, y + 9, 16, selected ? blue : muted);
        if (!shares.writing) hit(id, label, x, y, 34, 34, click);
    }

    private void drawNewConversation() {
        float x = 1053, y = 9, w = 85, h = 36;
        boolean preview = empty();
        float offset = preview ? firstGuide.previewNewOffset(now, reduced) : 0;
        float opacity = preview ? firstGuide.previewNewOpacity(now, reduced) : 1;
        if (firstGuide.showingStep(5)) firstGuide.include(x, y + offset, w, h);
        if (opacity <= 0) return;
        if (opacity < 1) draw.saveLayerAlpha(x, y, w, h + 6, opacity);
        try {
            button(
                    "wz-new",
                    "新对话",
                    "new",
                    x,
                    y + offset,
                    w,
                    h,
                    model.writing ? null : () -> choose(null),
                    false);
        } finally {
            if (opacity < 1) draw.restore();
        }
    }

    private boolean empty() {
        return model.selected == null && model.turns.isEmpty();
    }

    private WzConversation.Turn waiting() {
        if (model.turns.isEmpty()) return null;
        WzConversation.Turn turn = model.turns.get(model.turns.size() - 1);
        return turn.topic.isEmpty() && turn.itemChoice == null ? null : turn;
    }

    private boolean showingChoice(WzConversation.Turn turn) {
        return turn.itemChoice != null && turn == waiting();
    }

    private void drawChat() {
        WzUsage usage = usageCache.get(model.turns, model.terraSelected());
        String cost = usage.displayCost();
        var modelState = model.modelPicker();
        boolean showCost = modelState == null || !modelState.chatGpt();
        float costX = END - 62 - (showCost ? width(cost, 12) + 4 : 0);
        float modelWidth = modelState == null ? 0 : modelPicker.triggerWidth(draw, modelState);
        float pickerX = costX - modelWidth - (modelState != null && showCost ? 9 : 0);
        editorX = X + 53;
        editorW = pickerX - editorX - 12;
        HubDraw renderer = draw;
        HubTextEditor.Measurer measure =
                value -> references.line(renderer, value, 15, 0, 0, 0, false, 0, 0);
        input.layout(measure, editorW, Math.max(22.5f, editorH), 22.5f);
        editorH = clamp(input.contentHeight(), 22.5f, 90);
        input.layout(measure, editorW, editorH, 22.5f);
        var waitingTurn = firstGuide.active() ? null : waiting();
        var choice = waitingTurn == null ? null : waitingTurn.itemChoice;
        boolean quickAsk = quickAskActive() && choice == null;
        float composeH =
                choice == null
                        ? (quickAsk ? QUICK_TOP_H + composerHeight() : composerHeight())
                        : 101 + choice.getAsJsonArray("options").size() * 44;
        float dockH = composeH + 45.8f;
        boolean empty = empty();
        if (empty != emptyBefore) {
            emptyBefore = empty;
            paneChanged = layoutChanged = now;
        }
        float bottom = BOTTOM - 8 - dockH, middle = TOP + (BOTTOM - TOP - dockH) / 2;
        float motion = layoutChanged == 0 ? 1 : ease((now - layoutChanged) / 340_000_000f);
        dockY =
                quickAsk
                        ? bottom
                        : (empty ? mix(bottom, middle, motion) : mix(middle, bottom, motion));
        if (empty) {
            if (winter != null) winter.landing(draw, 1, TOP, 1258, BOTTOM - TOP);
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
            float content = measureTurns(model.turns);
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
        if (choice != null) {
            drawItemChoice(waitingTurn, composeH);
            return;
        }
        if (picker.open) references.beginFrame();
        drawComposer(usage, cost, costX, modelState, pickerX, modelWidth);
        if (picker.open) drawPicker();
        boolean guideTools = firstGuide.showingStep(3);
        tools.drawMenu(
                draw,
                guideTools,
                model.blockReferences,
                firstGuide.active() ? Float.NaN : mx,
                firstGuide.active() ? Float.NaN : my,
                blue,
                ink,
                rule,
                solidPaper,
                host,
                this::hit,
                this::openPicker,
                this::openWorldPicker);
        if (guideTools) {
            var trigger = tools.trigger();
            var menu = tools.menu();
            firstGuide.include(trigger.x(), trigger.y(), trigger.w(), trigger.h());
            firstGuide.include(menu.x(), menu.y(), menu.w(), menu.h());
        }
        if (modelState != null)
            modelPicker.drawMenu(
                    draw, this::hit, modelState, mx, my, focus, ink, muted, blue, rule, solidPaper);
    }

    private void drawItemChoice(WzConversation.Turn turn, float height) {
        var options = turn.itemChoice.getAsJsonArray("options");
        card(X, dockY, W, height, 16);
        draw.text(
                ellipsis(WzConversation.str(turn.itemChoice, "prompt"), W - 36, 16),
                X + 18,
                dockY + 27,
                16,
                ink,
                true);
        for (int i = 0; i < options.size(); i++) {
            var option = options.get(i).getAsJsonObject();
            float y = dockY + 38 + i * 44;
            boolean selected = turn.selectedOption == i;
            String id = WzConversation.str(option, "id");
            String name = WzConversation.str(option, "name");
            String key = "wz-choice-" + i;
            boolean hover = inside(X + 12, y, W - 24, 40) || key.equals(focus);
            draw.roundRect(
                    X + 12,
                    y,
                    W - 24,
                    40,
                    8,
                    selected ? alpha(blue, .16f) : hover ? alpha(rule, .65f) : solidPaper);
            draw.strokeRoundRect(X + 12, y, W - 24, 40, 8, 1, selected ? blue : rule);
            var stack = HubWenzhouAnswer.stack(option);
            if (!stack.isEmpty()) {
                references.icon(stack, X + 25, y + 8, 24, X + 49, y, y + 40);
                name =
                        net.minecraft.ChatFormatting.stripFormatting(
                                stack.getHoverName().getString());
            }
            draw.text(ellipsis(name, W - 160, 14), X + 60, y + 17, 14, ink, true);
            draw.text(
                    ellipsis(WzConversation.str(option, "mod_name") + " · " + id, W - 160, 11),
                    X + 60,
                    y + 32,
                    11,
                    muted,
                    false);
            draw.strokeRoundRect(END - 42, y + 14, 12, 12, 6, 1, selected ? blue : muted);
            if (selected) draw.roundRect(END - 39, y + 17, 6, 6, 3, blue);
            int index = i;
            hit(key, name, X + 12, y, W - 24, 40, () -> turn.selectedOption = index);
        }
        float y = dockY + height - 45;
        button(
                "wz-choice-manual",
                "我来指定物品",
                null,
                END - 192,
                y,
                106,
                32,
                model.busy()
                        ? null
                        : () ->
                                model.dismissItemChoice(
                                        turn, this::openPicker, host::feedbackError),
                false);
        drawQuota(y + 16);
        boolean ready = turn.selectedOption >= 0 && !model.busy() && !model.quotaExhausted();
        draw.roundRect(END - 77, y, 62, 32, 9, alpha(blue, ready ? 1 : .32f));
        center("发送", END - 46, y + 21, 13, winter != null ? winter.sheet() : 0xffffffff, true);
        if (ready)
            hit(
                    "wz-choice-send",
                    "发送",
                    END - 77,
                    y,
                    62,
                    32,
                    () -> {
                        String id =
                                WzConversation.str(
                                        options.get(turn.selectedOption).getAsJsonObject(), "id");
                        pinned = true;
                        model.ask("我指的是 [[item:" + id + "]]，请继续回答上一个问题。", false, false);
                    });
    }

    private boolean toolFocus(String id) {
        return TOOLS.equals(id) || TOOL_ITEM.equals(id) || TOOL_BLOCK.equals(id);
    }

    private void toggleReferenceTools() {
        picker.open = false;
        modelPicker.close();
        tools.toggle();
        host.wenzhouFocus(tools.open() ? TOOL_ITEM : TOOLS);
    }

    private void openPicker() {
        tools.close();
        modelPicker.close();
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

    boolean canPickWorldBlock() {
        return active && worldBlockAvailable();
    }

    private boolean worldBlockAvailable() {
        return shares.page == null
                && !settings
                && !history
                && !firstGuide.active()
                && waiting() == null
                && model.granted
                && model.blockReferences;
    }

    boolean prepareWorldBlockFromGame() {
        refresh();
        if (!worldBlockAvailable()) return false;
        if (!active) openEntry();
        tools.close();
        picker.open = false;
        input.pointerUp();
        return true;
    }

    public void openWorldPicker() {
        if (!canPickWorldBlock()) return;
        tools.close();
        picker.open = false;
        input.pointerUp();
        if (!WzWorldPicker.begin(this, dark, reduced)) {
            host.wenzhouFocus(INPUT);
            worldBlockError("当前无法选择世界方块。");
        }
    }

    void resumeWorldBlock() {
        guideStart = 0;
        opened = System.nanoTime();
        wantFocus = true;
    }

    void finishWorldBlock(WzBlockReference reference) {
        wantFocus = true;
        if (reference == null) return;
        int start = input.selectionStart(), end = input.selectionEnd();
        String draft = input.text().substring(0, start) + input.text().substring(end);
        // A reference occupies one supplementary code point in the editor.
        if (draft.length() > INPUT_LIMIT - 2) {
            host.feedback("输入框已达字数上限，未插入方块。");
        } else if (WzBlockReference.MARKER.matcher(references.encode(draft)).results().count()
                >= WzBlockReference.PER_QUESTION) {
            host.feedback("一条消息最多可以引用 " + WzBlockReference.PER_QUESTION + " 个世界方块。");
        } else {
            input.insert(references.token(reference));
        }
    }

    void worldBlockError(String message) {
        host.feedbackError(message);
    }

    private void drawPicker() {
        picker.refresh();
        float h = SEARCH_H + WzItemSearch.ROWS * ROW_H + 8;
        pickerY = dockY - h - 8;

        if (winter != null) winter.popover(draw, X, pickerY, W, h);
        else {
            draw.roundRect(X, pickerY, W, h, 15, solidPaper);
            draw.strokeRoundRect(X + .5f, pickerY + .5f, W - 1, h - 1, 15, 1, rule);
        }
        HubTextEditor query = picker.query;
        // Editing between frames still needs the renderer's font metrics.
        HubDraw renderer = draw;
        query.layout(value -> renderer.textWidth(value, 15, false), W - 35, 25, 22);
        float qx = X + 16;
        draw.save();
        draw.clipRoundRect(qx, pickerY + 6, W - 35, 30, 0);
        for (var rect : query.selectionRects())
            draw.rect(qx + rect.x(), pickerY + 9, rect.width(), 20, selectionColor());
        draw.text(
                query.text().isEmpty() ? "搜索物品名称、拼音或 ID" : query.text(),
                qx - query.scrollX(),
                pickerY + 26,
                15,
                query.text().isEmpty() ? muted : ink,
                false);
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
            references.icon(
                    item.item().getDefaultInstance(), X + 15, y + 7, 27, X + 42, y, y + ROW_H);
            draw.text(ellipsis(item.name(), W - 240, 14), X + 54, y + 17, 14, ink, true);
            draw.text(
                    ellipsis(item.id().toString(), W - 240, 12), X + 54, y + 33, 12, muted, false);
            String mod = ellipsis(item.mod(), 166, 12);
            draw.text(mod, END - 16 - width(mod, 12), y + 24, 12, muted, false);
        }
    }

    private void changeGreeting() {
        int offset = ThreadLocalRandom.current().nextInt(1, GREETINGS.size());
        greetingIndex = (greetingIndex + offset) % GREETINGS.size();
    }

    private void drawLanding() {
        railH = scroll = maxScroll = 0;
        if (quickAskActive()) {
            float availH = Math.max(150f, dockY - TOP);
            float boatY = TOP + Math.max(24f, (availH - 122f) / 2f + 26f);
            WzArtwork.boat(draw, 560, boatY, 140);
            String message =
                    quickAskItem.name().codePointCount(0, quickAskItem.name().length()) > 8
                            ? "想了解这件物品什么？"
                            : "想了解「" + quickAskItem.name() + "」什么？";
            float y = boatY + 118.3f;
            if (winter != null)
                heading(message, 630 - draw.textWidthSerif(message, 25, true) / 2, y, 25);
            else center(message, 630, y, 25, ink, true);
            return;
        }
        WzArtwork.boat(draw, 560, dockY - 151.6f, 140);
        String message = GREETINGS.get(greetingIndex);
        float y = dockY - 33.3f;
        if (winter != null)
            heading(message, 630 - draw.textWidthSerif(message, 25, true) / 2, y, 25);
        else center(message, 630, y, 25, ink, true);
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

    private float measureTurns(List<WzConversation.Turn> visibleTurns) {
        if (measuredDraw != draw) {
            measuredDraw = draw;
            turnLayouts.clear();
        }
        float offset = 0;
        for (WzConversation.Turn turn : visibleTurns) {
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
            layout.disclosure.set(turn.open, now, reduced);
            layout.disclosureHeight = layout.timelineHeight * layout.disclosure.value(now, reduced);
            if (!layout.events.isEmpty()) offset += 42 + layout.disclosureHeight;
            if (!layout.topic.isEmpty()) offset += 55 + 24 * layout.topic.size();
            offset += 23 * layout.failure.size();
            layout.streaming =
                    !showingChoice(turn)
                            && (running || turn.reply != null)
                            && !turn.stopping
                            && !turn.stopped
                            && turn.stream.advance(draw, W, now, reduced, turn.reply != null);
            if (layout.streaming) {
                offset += turn.stream.height();
            } else if (turn.reply != null && !showingChoice(turn)) {
                if (turn.stream.started()) turn.stream.reset();
                layout.replySeconds =
                        turn.answered == 0
                                ? Float.POSITIVE_INFINITY
                                : Math.max(0, (now - turn.answered) / 1_000_000_000f);
                offset += answer.height(draw, turn.reply, W, layout.replySeconds, reduced);
            }
            offset += 28;
            layout.end = offset;
        }
        if (turnLayouts.size() > visibleTurns.size()) {
            turnLayouts.keySet().removeIf(turn -> !visibleTurns.contains(turn));
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
        bubble.layout(
                value -> references.line(draw, value, 14, 0, 0, 0, false, 0, 0),
                questionWidth,
                21.7f);
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
                        default -> row.text();
                    };
            String detail = "";
            if (row.type().equals("tool.finish")
                    && (!row.icon().equals("command") || label.startsWith("运行命令 · "))) {
                detail = row.detail();
            } else if (!running && row.type().equals("tool.start")) {
                detail = turn.stopped ? "查询已停止" : "查询已中断";
            }
            boolean report = row.type().equals("stage.report");
            List<HubTextEditor.Line> labels = lines(label, W - 47, report ? 14 : 13, 18.85f);
            if (report && labels.size() > 2) labels = labels.subList(0, 2);
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
                references.line(
                        draw,
                        line.text(),
                        14,
                        x + 16,
                        bubbleY + line.y() + 26,
                        alpha(ink, progress),
                        true,
                        clipTop,
                        clipBottom);
        }
        y += questionHeight + 22;
        if (y + 18 > clipTop && y < clipBottom) {
            WzArtwork.boat(draw, X, y, 30);
            draw.text("问舟", X + 38, y + 14, 14, blue, true);
            if (shares.page != null)
                draw.text(
                        WzModelPicker.TERRA.equals(turn.modelSelection)
                                ? WzModelPicker.TERRA_LABEL
                                : turn.modelSelection,
                        X + 76,
                        y + 14,
                        12,
                        muted,
                        false);
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
                    model.busy() || model.quotaExhausted(turn) ? null : () -> resolveTopic(false),
                    true);
            button(
                    "wz-topic-new",
                    "无关，新开对话",
                    null,
                    X + 136,
                    ay,
                    130,
                    33,
                    model.busy() || model.quotaExhausted(turn) ? null : () -> resolveTopic(true),
                    true);
            y += height;
        } else if (layout.streaming && y < clipBottom) {
            y += turn.stream.paint(draw, X, y, clipTop, clipBottom, ink);
        } else if (turn.reply != null && !showingChoice(turn) && y < clipBottom) {
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
        float radius = winter != null ? 13 : 16, corner = winter != null ? 3 : 4;
        float[] outer = {radius, radius, radius, radius, corner, corner, radius, radius};
        radius -= .5f;
        corner -= .5f;
        float[] inner = {radius, radius, radius, radius, corner, corner, radius, radius};
        int fill = winter != null ? winter.soft() : paper;
        try (Paint paint = new Paint().setAntiAlias(true).setColor(alpha(fill, opacity))) {
            draw.canvas().drawRRect(RRect.makeComplexXYWH(x, y, w, h, outer), paint);
            paint.setColor(alpha(rule, opacity)).setMode(PaintMode.STROKE).setStrokeWidth(1);
            draw.canvas()
                    .drawRRect(RRect.makeComplexXYWH(x + .5f, y + .5f, w - 1, h - 1, inner), paint);
        }
    }

    private float drawTimeline(WzConversation.Turn turn, TurnLayout layout, int index, float y) {
        String title =
                layout.running
                        ? (turn.stopping ? "正在停止回答…" : "问舟正在查阅")
                        : turn.reply != null ? "工作流程" : turn.stopped ? "查阅记录" : "未完成的查阅";
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
                else if (!activity.type().equals("stage.report")) {
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
                    draw.text(
                            line.text(),
                            X + 47,
                            at + 14 + line.y(),
                            activity.type().equals("stage.report") ? 14 : 13,
                            activity.type().equals("stage.report") ? ink : muted,
                            false);
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
        return Math.max(60, editorH + 28);
    }

    private void drawComposer(
            WzUsage usage,
            String cost,
            float costX,
            WzModelPicker.State modelState,
            float pickerX,
            float modelWidth) {
        boolean waiting = waiting() != null,
                canSend =
                        !model.busy()
                                && !model.quotaExhausted()
                                && !waiting
                                && !input.text().isBlank();
        boolean quickAsk = quickAskActive();
        float inputH = composerHeight();
        float h = quickAsk ? QUICK_TOP_H + inputH : inputH;
        float inputTop = quickAsk ? dockY + QUICK_TOP_H : dockY;
        float mid = inputTop + inputH / 2;
        boolean showCost = modelState == null || !modelState.chatGpt();
        float costW = showCost ? width(cost, 12) + 4 : 0;
        boolean guideCost = showCost && firstGuide.showingStep(2);
        if (firstGuide.showingStep(1)) firstGuide.include(X, dockY, W, h);
        if (guideCost) firstGuide.include(costX, mid - 15, costW, 30);
        if (!showCost && firstGuide.showingStep(2))
            firstGuide.include(pickerX, mid - 15, modelWidth, 30);
        boolean focused =
                firstGuide.showingStep(1)
                        || INPUT.equals(focus) && !waiting
                        || showCost && COST.equals(focus)
                        || toolFocus(focus)
                        || modelState != null && modelPicker.focused(focus)
                        || "wz-send".equals(focus)
                        || "wz-stop".equals(focus);
        if (winter != null) {
            draw.roundRect(X, dockY + 4, W, h, 14, rule);
            draw.roundRect(X, dockY + 3, W, h, 14, winter.soft());
            draw.roundRect(X, dockY, W, h, 14, paper);
            draw.strokeRoundRect(X + .5f, dockY + .5f, W - 1, h - 1, 13.5f, 1, rule);
        } else {
            draw.boxShadow(X, dockY, W, h, 21, 0, 8, 28, -12, 0x2a203e61);
            draw.roundRect(X, dockY, W, h, 21, paper);
            if (focused)
                draw.strokeRoundRect(
                        X - 1, dockY - 1, W + 2, h + 2, 22, 2, 0x14000000 | (ink & 0xffffff));
            int border = focused ? (dark ? 0xff979eb1 : 0xff798397) : rule;
            draw.strokeRoundRect(X + .5f, dockY + .5f, W - 1, h - 1, 20.5f, 1, border);
        }
        if (quickAsk) {
            drawQuickAskHeaderAndPresets(inputTop);
        }
        tools.drawTrigger(
                draw,
                X + 6,
                mid - 17,
                !waiting,
                firstGuide.showingStep(3),
                firstGuide.active() ? Float.NaN : mx,
                firstGuide.active() ? Float.NaN : my,
                blue,
                ink,
                muted,
                host,
                this::hit,
                this::toggleReferenceTools);
        editorY = mid - editorH / 2;
        draw.save();
        draw.clipRoundRect(editorX, editorY, editorW, editorH, 0);
        if (input.text().isEmpty() || waiting) {
            String placeholder =
                    model.quotaExhausted(waiting())
                            ? (model.activeQuota(waiting()) == model.quota
                                    ? "本周额度已用完"
                                    : "Terra 美元额度已用完")
                            : waiting
                                    ? "请选择如何继续这次提问"
                                    : quickAsk ? "对该物品进行提问..." : empty() ? "想在旅途中弄清什么？" : "接着问问舟…";
            draw.text(placeholder, editorX, editorY + 17, 15, muted, false);
        } else {
            if (INPUT.equals(focus))
                for (HubTextEditor.Rect rect : input.selectionRects())
                    draw.rect(
                            editorX + rect.x(),
                            editorY + rect.y(),
                            rect.width(),
                            rect.height(),
                            selectionColor());
            for (HubTextEditor.Line line : input.lines())
                references.line(
                        draw,
                        line.text(),
                        15,
                        editorX - input.scrollX(),
                        editorY + line.y() - input.scrollY() + 17,
                        ink,
                        true,
                        editorY,
                        editorY + editorH);
        }
        if (INPUT.equals(focus) && !waiting && (reduced || now / 500_000_000L % 2 == 0)) {
            HubTextEditor.Rect caret = input.caretRect();
            draw.rect(editorX + caret.x(), editorY + caret.y() + 2, 1, caret.height() - 4, ink);
        }
        draw.restore();
        if (!waiting) hit(INPUT, "向问舟提问", editorX, editorY - 3, editorW, editorH + 6, () -> {});
        if (modelState != null)
            modelPicker.drawTrigger(
                    draw,
                    this::hit,
                    modelState,
                    pickerX,
                    mid,
                    modelWidth,
                    mx,
                    my,
                    focus,
                    ink,
                    muted,
                    rule,
                    !waiting && !model.busy() && !firstGuide.active(),
                    this::toggleModelPicker);
        if (showCost) {
            boolean onCost = guideCost || inside(costX, mid - 15, costW, 30) || COST.equals(focus);
            float tipReach = usageHeight(usage) + 6;
            boolean onTip =
                    usageOpen
                            && inside(
                                    costX + costW - 206,
                                    inputTop - tipReach,
                                    210,
                                    inputH / 2 + tipReach + 10);
            if (!onCost && !onTip) usageDismissed = false;
            usageOpen =
                    !firstGuide.active()
                            && !modelPicker.open()
                            && !usageDismissed
                            && (onCost || onTip);
            draw.text(cost, costX + 2, mid + 4, 12, onCost ? ink : muted, false);
            hit(COST, usage.costLabel(), costX, mid - 15, costW, 30, () -> usageDismissed = false);
        } else usageOpen = usageDismissed = false;
        float sx = END - 50;
        WzConversation.Turn pending = model.visiblePending();
        boolean stop = pending != null;
        String actionId = stop ? "wz-stop" : "wz-send";
        boolean enabled = stop ? !pending.stopping : canSend;
        boolean hovered = enabled && inside(sx, mid - 19, 38, 38);
        float hover = host.wenzhouHover(actionId, hovered);
        float actionY = mid - 19 + host.wenzhouButtonMove(actionId, enabled, hovered);
        if (stop) {
            int fill = winter != null ? blue : dark ? 0xfffafafa : 0xff202124;
            int glyph = winter != null ? paper : dark ? 0xff202124 : 0xfffafafa;
            float opacity = enabled ? 1 - .13f * hover : .55f;
            draw.circle(sx + 19, actionY + 19, 18, alpha(fill, opacity));
            draw.roundRect(sx + 12, actionY + 12, 14, 14, 2.5f, glyph);
            if (enabled)
                hit(actionId, "停止回答", sx, mid - 19, 38, 38, () -> model.stop(host::feedback));
        } else {
            if (winter != null) {
                draw.circle(
                        sx + 19,
                        actionY + 19,
                        19,
                        canSend ? mixColor(blue, ink, .14f * hover) : alpha(winter.soft(), .74f));
            } else if (canSend) {
                draw.roundRect(sx, actionY, 38, 38, 13, blue);
                if (hover > 0) draw.roundRect(sx, actionY, 38, 38, 13, alpha(0x20ffffff, hover));
            }
            WzGlyph.draw(
                    draw,
                    "send",
                    sx + 8.5f,
                    actionY + 8.5f,
                    21,
                    canSend ? paper : winter != null ? alpha(muted, .74f) : muted);
            if (canSend) hit(actionId, "发送问题", sx, mid - 19, 38, 38, () -> send(input.text()));
        }
        String bold = "换话题请新建对话。", rest = "经提醒仍反复询问无关问题，可能被限制使用问舟。";
        float hintX = 630 - (draw.textWidth(bold, 12, true) + width(rest, 12)) / 2;
        draw.text(bold, hintX, dockY + h + 16, 12, caution, true);
        draw.text(rest, hintX + draw.textWidth(bold, 12, true), dockY + h + 16, 12, muted, false);
        String replay = "重看使用引导";
        float rw = width(replay, 12), linkY = dockY + h + 20;
        float replayX = 630 - rw / 2;
        draw.text(replay, replayX, linkY + 13, 12, muted, false);
        draw.line(replayX, linkY + 15, replayX + rw, linkY + 15, 1, muted);
        if (!model.busy() && waiting() == null)
            hit(
                    "wz-guide-replay",
                    replay,
                    replayX - 3,
                    linkY,
                    rw + 6,
                    18,
                    () -> {
                        settings = picker.open = usageOpen = quotaOpen = false;
                        modelPicker.close();
                        tools.close();
                        firstGuide.open(System.nanoTime());
                    });
        drawQuota(mid);
        if (guideCost || usageOpen && !quotaOpen) drawUsage(usage, costX + costW - 206, mid - 22);
    }

    private void drawQuota(float mid) {
        WzQuota quota = model.activeQuota(waiting());
        if (!quota.visible()) {
            quotaOpen = quotaDismissed = false;
            return;
        }
        float size = 52, x = X - size - 10, y = mid - size / 2;
        boolean hovered = inside(x, y, size, size) || QUOTA.equals(focus);
        if (measuredQuota != quota) {
            measuredQuota = quota;
            quotaTipWidth =
                    Math.max(
                            151,
                            Math.max(
                                            width(quota.resetLabel(), 12),
                                            draw.textWidth(quota.label(), 13, true))
                                    + 28);
        }
        float tipWidth = quotaTipWidth;
        boolean showReset = !quota.unlimited() && !quota.resetLabel().isEmpty();
        float tipHeight = showReset ? 60 : 40, tipY = y - tipHeight - 7;
        boolean onTip = quotaOpen && inside(x, tipY, tipWidth, tipHeight + 7);
        if (!hovered && !onTip) quotaDismissed = false;
        quotaOpen = !quotaDismissed && (hovered || onTip);
        draw.boxShadow(x, y, size, size, 17, 0, 6, 20, -9, 0x2a203e61);
        draw.roundRect(x, y, size, size, 17, paper);
        draw.strokeRoundRect(
                x + .5f,
                y + .5f,
                size - 1,
                size - 1,
                16.5f,
                1,
                winter == null && hovered ? muted : rule);
        if (quota.unlimited())
            center("∞", x + size / 2, mid + 9, 28, draw.dark() ? 0xffffd275 : 0xffa86608, true);
        else WzGlyph.quotaRing(draw, x + size / 2, mid, quota.percent(), rule, ink);
        hit(QUOTA, quota.label(), x, y, size, size, () -> quotaDismissed = false);
        if (!quotaOpen) return;
        if (winter != null) winter.popover(draw, x, tipY, tipWidth, tipHeight);
        else {
            draw.boxShadow(x, tipY, tipWidth, tipHeight, 13, 0, 8, 24, -5, 0x28152541);
            draw.roundRect(x, tipY, tipWidth, tipHeight, 13, solidPaper);
            draw.strokeRoundRect(x + .5f, tipY + .5f, tipWidth - 1, tipHeight - 1, 12.5f, 1, rule);
        }
        draw.text(quota.label(), x + 14, tipY + 24, 13, ink, true);
        if (showReset) draw.text(quota.resetLabel(), x + 14, tipY + 45, 12, muted, false);
    }

    private static float usageHeight(WzUsage usage) {
        return (usage.note().isEmpty() ? 185.3f : 202.3f) + (usage.rows().size() - 6) * 20.4f;
    }

    private void drawUsage(WzUsage usage, float x, float bottom) {
        float h = usageHeight(usage), y = bottom - h;
        if (firstGuide.showingStep(2)) firstGuide.include(x, y, 210, h);
        if (winter != null) winter.popover(draw, x, y, 210, h);
        else {
            draw.boxShadow(x, y, 210, h, 14, 0, 8, 24, -5, 0x28152541);
            draw.roundRect(x, y, 210, h, 14, solidPaper);
            draw.strokeRoundRect(x + .5f, y + .5f, 209, h - 1, 13.5f, 1, rule);
        }
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
        heading("历史对话", X, y, 26);
        if (winter != null) winter.art(draw, "winter-post", END - 90, y - 29, 90, 38, 1);
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
            if (y >= clipTop && y < clipBottom) listRule(y);
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
        if (rowHover > 0)
            draw.roundRect(
                    X, y, W - 40, 50, 10, alpha(winter != null ? winter.soft() : paper, rowHover));
        WzGlyph.draw(draw, "book", X + 8, y + 15, 20, muted);
        boolean unread = model.unreadReminder && model.unread.containsKey(row.id);
        if (unread) draw.roundRect(X + 40, y + 23, 6, 6, 3, unreadColor());
        draw.text(row.preview, X + (unread ? 53 : 40), y + 30, 14, ink, false);
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
        if (winter != null) draw.roundRect(X, y, W, 39, 9, mixColor(paper, winter.mail(), .09f));
        draw.text("从你的个人历史中移除这段对话？", X + (winter != null ? 9 : 4), y + 22, 13, muted, false);
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
        draw.roundRect(
                x,
                y + 4,
                86,
                31,
                8,
                mixColor(
                        dangerPaper(),
                        winter != null ? alpha(winter.mail(), .24f) : 0x30b8465b,
                        hover));
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
        return winter != null ? winter.mailInk() : dark ? 0xffffb0ba : 0xffa3374b;
    }

    private int dangerPaper() {
        return winter != null ? alpha(winter.mail(), .13f) : 0x10b8465b;
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
        boolean publish = winter != null && id.equals("wz-publish") && enabled;
        if (publish) draw.roundRect(x, visualY, w, h, 9, blue);
        else if (outlined) card(x, visualY, w, h, 9);
        if (hover > 0) {
            int hoverColor =
                    winter != null
                            ? (publish ? alpha(paper, .12f) : winter.cover())
                            : outlined ? rule : paper;
            draw.roundRect(x, visualY, w, h, outlined ? 9 : 10, alpha(hoverColor, hover));
        }
        int fg =
                publish
                        ? paper
                        : winter != null && id.equals("wz-s-delete") && enabled
                                ? winter.mailInk()
                                : enabled ? ink : muted;
        if (icon != null) WzGlyph.draw(draw, icon, x + 10, visualY + (h - 17) / 2, 17, fg);
        draw.text(label, x + (icon == null ? 10 : 35), visualY + h / 2 + 5, 13, fg, false);
        if (enabled) hit(id, label, x, y, w, h, action);
    }

    private static int mixColor(int from, int to, float amount) {
        int a = Math.round(mix(from >>> 24, to >>> 24, amount));
        int r = Math.round(mix((from >>> 16) & 255, (to >>> 16) & 255, amount));
        int g = Math.round(mix((from >>> 8) & 255, (to >>> 8) & 255, amount));
        int b = Math.round(mix(from & 255, to & 255, amount));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private int selectionColor() {
        return winter != null ? alpha(blue, .28f) : 0x509685e8;
    }

    private void heading(String text, float x, float y, float size) {
        if (winter != null) winter.heading(draw, text, x, y, size, ink);
        else draw.text(text, x, y, size, ink, true);
    }

    private void listRule(float y) {
        if (winter != null) winter.stitch(draw, X, END, y);
        else draw.line(X, y, END, y, 1, rule);
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
        return !firstGuide.active()
                && mx >= x
                && my >= y
                && mx < x + w
                && my < y + h
                && my >= clipTop
                && my < clipBottom;
    }

    private void toggleModelPicker() {
        if (!active || history || firstGuide.active() || waiting() != null) return;
        picker.open = false;
        tools.close();
        modelPicker.toggle(model.modelPicker());
    }

    private void drawQuickAskHeaderAndPresets(float inputTop) {
        QuickAskItem item = quickAskItem;
        if (item == null) return;
        float topRadius = winter != null ? 13f : 20f;
        float[] topCorners = {topRadius, topRadius, topRadius, topRadius, 0, 0, 0, 0};
        int contextFill = winter != null ? winter.soft() : alpha(ink, .03f);
        try (Paint paint = new Paint().setAntiAlias(true).setColor(contextFill)) {
            draw.canvas()
                    .drawRRect(
                            RRect.makeComplexXYWH(
                                    X + 1, dockY + 1, W - 2, QUICK_CONTEXT_H - 1, topCorners),
                            paint);
        }
        draw.line(
                X + 1,
                dockY + QUICK_CONTEXT_H,
                END - 1,
                dockY + QUICK_CONTEXT_H,
                1,
                winter != null ? rule : alpha(rule, .55f));
        if (!settings) {
            references.icon(item.stack(), X + 18, dockY + 10, 20, X + 38, dockY + 6, dockY + 34);
        }
        String nameLabel = ellipsis(item.name(), W - 220, 13);
        float nameW = draw.textWidth(nameLabel, 13, true);
        draw.text(nameLabel, X + 46, dockY + 25, 13, ink, true);
        float idX = X + 54 + nameW;
        float idAvail = (END - 42) - idX;
        if (idAvail > 24) {
            draw.text(
                    ellipsis(item.id().toString(), idAvail, 11.5f),
                    idX,
                    dockY + 24.5f,
                    11.5f,
                    muted,
                    false);
        }
        boolean popupOpen = tools.open() || modelPicker.open();
        String dismissId = "wz-quick-dismiss";
        boolean dismissHover =
                !popupOpen && (inside(END - 36, dockY + 8, 24, 24) || dismissId.equals(focus));
        float dh = host.wenzhouHover(dismissId, dismissHover);
        if (dh > 0) draw.roundRect(END - 36, dockY + 8, 24, 24, 7, alpha(ink, .08f * dh));
        WzGlyph.draw(draw, "close", END - 31, dockY + 13, 14, mixColor(muted, ink, dh));
        hit(
                dismissId,
                "切回常规提问",
                END - 36,
                dockY + 8,
                24,
                24,
                () -> {
                    quickAskItem = null;
                    paneChanged = layoutChanged = System.nanoTime();
                });

        boolean enabled = !model.busy() && !model.quotaExhausted();
        float rowRadius = winter != null ? 8f : 10f;
        for (int i = 0; i < QUICK_ASK_PRESETS.length; i++) {
            String preset = QUICK_ASK_PRESETS[i];
            float rowY = dockY + QUICK_CONTEXT_H + QUICK_LIST_PAD + i * QUICK_ROW_H;
            String rowId = "wz-quick-preset-" + i;
            boolean hovered =
                    enabled
                            && !popupOpen
                            && (inside(X + 6, rowY, W - 12, QUICK_ROW_H - 1)
                                    || rowId.equals(focus));
            float rh = host.wenzhouHover(rowId, hovered);
            if (rh > 0) {
                draw.roundRect(
                        X + 6, rowY, W - 12, QUICK_ROW_H - 1, rowRadius, alpha(blue, .09f * rh));
            }
            int textColor = enabled ? mixColor(ink, blue, rh) : alpha(muted, .55f);
            draw.text(preset, X + 18, rowY + 21.5f, 13.5f, textColor, false);
            int arrowColor = enabled ? mixColor(alpha(muted, .55f), blue, rh) : alpha(muted, .35f);
            WzGlyph.draw(draw, "arrow-up-right", END - 32 + rh, rowY + 10 - rh, 14, arrowColor);
            if (enabled) {
                hit(
                        rowId,
                        preset,
                        X + 6,
                        rowY,
                        W - 12,
                        QUICK_ROW_H - 1,
                        () -> sendQuickAskPreset(preset));
            }
        }
        draw.line(X + 1, inputTop, END - 1, inputTop, 1, winter != null ? rule : alpha(rule, .75f));
    }

    private void sendQuickAskPreset(String preset) {
        QuickAskItem item = quickAskItem;
        if (item == null) return;
        if (model.quotaExhausted()) {
            host.feedback(
                    model.terraSelected()
                            ? "Terra 美元额度已用完，可以切换到 DeepSeek。"
                            : "本周额度已用完，可在输入栏左侧查看额度状态。");
            return;
        }
        if (shares.page != null || settings || model.busy() || waiting() != null) return;
        String question = "[[item:" + item.id() + "]] " + preset;
        quickAskItem = null;
        pinned = true;
        input.setText("");
        model.ask(question, false, false);
    }

    private void send(String text) {
        String question = references.encode(text).strip();
        QuickAskItem activeQuick = quickAskActive() ? quickAskItem : null;
        if (activeQuick != null && !question.isEmpty()) {
            String marker = "[[item:" + activeQuick.id() + "]]";
            if (!question.contains(marker)) {
                question = marker + " " + question;
            }
        }
        if (question.length() > 1500) {
            host.feedback("包含引用后问题超过 1500 字，请缩短后再发送。");
            return;
        }
        if (WzBlockReference.MARKER.matcher(question).results().count()
                > WzBlockReference.PER_QUESTION) {
            host.feedback("一条消息最多可以引用 " + WzBlockReference.PER_QUESTION + " 个世界方块。");
            return;
        }
        if (model.quotaExhausted()) {
            host.feedback(
                    model.terraSelected()
                            ? "Terra 美元额度已用完，可以切换到 DeepSeek。"
                            : "本周额度已用完，可在输入栏左侧查看额度状态。");
            return;
        }
        if (shares.page != null
                || settings
                || question.isEmpty()
                || model.busy()
                || waiting() != null) return;
        quickAskItem = null;
        pinned = true;
        input.setText("");
        model.ask(question, false, false);
    }

    private void resolveTopic(boolean startNew) {
        WzConversation.Turn turn = waiting();
        if (turn == null || model.busy() || model.quotaExhausted(turn)) return;
        pinned = wantFocus = true;
        model.ask(turn.question, true, startNew);
    }

    private void choose(String id) {
        settings = false;
        if (model.writing) return;
        quickAskItem = null;
        model.choose(id);
        if (id == null) changeGreeting();
        picker.open = false;
        modelPicker.close();
        tools.close();
        history = false;
        confirm = null;
        input.setText("");
        scroll = maxScroll = 0;
        paneChanged = System.nanoTime();
        pinned = wantFocus = true;
    }

    private void toggleHistory() {
        settings = false;
        quickAskItem = null;
        picker.open = false;
        modelPicker.close();
        tools.close();
        history = !history;
        scroll = maxScroll = 0;
        confirm = null;
        deleteAnimations.clear();
        paneChanged = System.nanoTime();
        if (history) model.list();
        else pinned = wantFocus = true;
    }

    private void back() {
        settings = false;
        picker.open = false;
        modelPicker.close();
        tools.close();
        switchRoute(false, System.nanoTime());
        usageOpen = usageDismissed = drawItems = railDrag = false;
        input.pointerUp();
        host.wenzhouBack();
    }

    @Override
    public void onPanelVisibility(boolean open) {
        if (open) WzWorldPicker.hubVisible(this);
        else if (!WzWorldPicker.containsDraft(this)) closePanel();
    }

    @Override
    public boolean escape() {
        if (!active) return false;
        if (shares.page != null) {
            if (!answer.escape()) returnFromShare();
            return true;
        }
        if (settings) {
            toggleSettings();
            return true;
        }
        if (firstGuide.active()) {
            firstGuide.dismiss(System.nanoTime());
            return true;
        }
        if (picker.open) {
            closePicker();
            return true;
        }
        if (modelPicker.open()) {
            modelPicker.close();
            host.wenzhouFocus(WzModelPicker.TRIGGER);
            return true;
        }
        if (tools.open()) {
            tools.close();
            host.wenzhouFocus(TOOLS);
            return true;
        }
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

    @Override
    public boolean scroll(float x, float y, double amount) {
        if (settings || firstGuide.active()) return true;
        float[] point = local(x, y);
        var state = model.modelPicker();
        if (state != null && modelPicker.scroll(point[0], point[1], amount, state)) return true;
        if (picker.open) {
            picker.move((int) -Math.signum(amount));
            return true;
        }
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

    @Override
    public boolean pointerDown(float x, float y, boolean shift) {
        if (settings) {
            float[] point = local(x, y);
            if (!(point[0] >= 24
                            && point[0] <= 306
                            && point[1] >= settingsPanelTop()
                            && point[1] <= 774)
                    && !(point[0] >= 24 && point[0] <= 64 && point[1] >= 785 && point[1] <= 825)) {
                toggleSettings();
                return true;
            }
            return false;
        }
        if (firstGuide.active()) return false; // The Hub handles the guide's buttons.
        float[] p = local(x, y);
        if (modelPicker.open()) {
            if (modelPicker.containsMenu(p[0], p[1])) return !modelPicker.actionable(p[0], p[1]);
            if (!modelPicker.contains(p[0], p[1])) modelPicker.close();
        }
        if (tools.open() && !tools.contains(p[0], p[1])) tools.close();
        if (picker.open) {
            if (p[0] >= X && p[0] < END && p[1] >= pickerY && p[1] < dockY - 8) {
                float rowY = p[1] - pickerY - SEARCH_H - 4;
                if (rowY >= 0 && rowY < WzItemSearch.ROWS * ROW_H)
                    insertItem(picker.first + (int) (rowY / ROW_H));
                else {
                    picker.query.pointerDown(p[0] - X - 16, 0, shift);
                    picker.query.pointerUp();
                }
                return true;
            }
            closePicker();
        }
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

    @Override
    public boolean pointerDrag(float x, float y) {
        if (firstGuide.active()) return false;
        float[] p = local(x, y);
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

    @Override
    public boolean pointerUp() {
        boolean handled = railDrag || input.dragging();
        railDrag = false;
        input.pointerUp();
        return handled;
    }

    @Override
    public boolean textFocused(String focused) {
        return active
                && shares.page == null
                && !settings
                && !firstGuide.active()
                && !history
                && waiting() == null
                && INPUT.equals(focused);
    }

    @Override
    public String pointerCursor(float mx, float my) {
        if (textPointer(mx, my)) return "text";
        if (referencePointer(mx, my)) return "pointer";
        return null;
    }

    public boolean referencePointer(float x, float y) {
        if (!active || settings || firstGuide.active() || shares.page == null && history)
            return false;
        float[] p = local(x, y);
        return !modelPicker.containsMenu(p[0], p[1]) && answer.referencePointer(p[0], p[1]);
    }

    public boolean textPointer(float x, float y) {
        if (!active
                || shares.page != null
                || settings
                || firstGuide.active()
                || history
                || waiting() != null) return false;
        float[] p = local(x, y);
        return !modelPicker.containsMenu(p[0], p[1])
                && p[0] >= editorX
                && p[0] < editorX + editorW
                && p[1] >= editorY - 3
                && p[1] < editorY + editorH + 3;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int mods, String focusId) {
        return WzWorldPicker.hubKey(this, key, scanCode) || key(key, mods, focusId);
    }

    @Override
    public boolean charTyped(char c, int mods, String focusId) {
        if (!textFocused(focusId)) return false;
        type(c, mods);
        return true;
    }

    @Override
    public String selectedText() {
        return selection();
    }

    @Override
    public String cutText() {
        return cut();
    }

    @Override
    public void pasteText(String value) {
        paste(value);
    }

    @Override
    public void paintOverlayItems(
            GuiGraphics graphics,
            float panelX,
            float panelY,
            float scaleX,
            float scaleY,
            int mouseX,
            int mouseY) {
        paintItems(graphics, panelX, panelY, scaleX, scaleY, mouseX, mouseY);
    }

    public boolean key(int key, int mods, String focused) {
        if (!active) return false;
        if (firstGuide.active()) {
            firstGuide.key(key, mods, focused, System.nanoTime());
            return true;
        }
        var state = model.modelPicker();
        if (state != null && modelPicker.open() && modelPicker.focused(focused)) {
            if (key == GLFW.GLFW_KEY_ESCAPE) return escape();
            if (key == GLFW.GLFW_KEY_TAB) {
                modelPicker.close();
                boolean backwards = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
                host.wenzhouFocus(
                        backwards ? INPUT : state.chatGpt() ? WzModelPicker.TRIGGER : COST);
                return true;
            }
            if (modelPicker.key(key, focused, state)) return true;
        } else if (state != null
                && WzModelPicker.TRIGGER.equals(focused)
                && key == GLFW.GLFW_KEY_DOWN
                && !model.busy()
                && waiting() == null) {
            toggleModelPicker();
            return true;
        }
        if (tools.open() && toolFocus(focused)) {
            if (key == GLFW.GLFW_KEY_ESCAPE) return escape();
            if (key == GLFW.GLFW_KEY_UP
                    || key == GLFW.GLFW_KEY_DOWN
                    || key == GLFW.GLFW_KEY_HOME
                    || key == GLFW.GLFW_KEY_END) {
                String target =
                        switch (key) {
                            case GLFW.GLFW_KEY_HOME -> TOOL_ITEM;
                            case GLFW.GLFW_KEY_END -> TOOL_BLOCK;
                            default -> TOOL_ITEM.equals(focused) ? TOOL_BLOCK : TOOL_ITEM;
                        };
                host.wenzhouFocus(model.blockReferences ? target : TOOL_ITEM);
                return true;
            }
        }
        boolean editing = textFocused(focused);
        if (picker.open && editing) {
            int delta =
                    switch (key) {
                        case GLFW.GLFW_KEY_UP -> -1;
                        case GLFW.GLFW_KEY_DOWN -> 1;
                        case GLFW.GLFW_KEY_PAGE_UP -> -WzItemSearch.ROWS;
                        case GLFW.GLFW_KEY_PAGE_DOWN -> WzItemSearch.ROWS;
                        default -> 0;
                    };
            if (delta != 0) {
                picker.move(delta);
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                picker.refresh();
                insertItem(picker.selected);
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                closePicker();
                return true;
            }
        }
        HubTextEditor editor = picker.open ? picker.query : input;
        double steps =
                switch (key) {
                    case GLFW.GLFW_KEY_PAGE_UP -> 10;
                    case GLFW.GLFW_KEY_PAGE_DOWN -> -10;
                    case GLFW.GLFW_KEY_UP -> editing ? 0 : 1;
                    case GLFW.GLFW_KEY_DOWN -> editing ? 0 : -1;
                    default -> 0;
                };
        if (steps != 0) {
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
        if (picker.open && (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE))
            picker.changed();
        return true;
    }

    public void type(char c, int mods) {
        if ((mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT)) != 0) return;
        if (System.nanoTime() < suppressQuickAskCharUntil) return;
        if (picker.open) {
            picker.query.type(c);
            picker.changed();
        } else if (c == '@') openPicker();
        else input.type(c);
    }

    public void paste(String value) {
        if (picker.open) {
            picker.query.insert(value);
            picker.changed();
        } else input.insert(references.decode(value));
    }

    public String selection() {
        return picker.open ? picker.query.selectedText() : references.copy(input.selectedText());
    }

    public String cut() {
        if (picker.open) {
            picker.changed();
            return picker.query.cutSelection();
        }
        return references.copy(input.cutSelection());
    }

    public void paintItems(
            GuiGraphics graphics, float ox, float oy, float sx, float sy, int mx, int my) {
        if (!active
                || firstGuide.active()
                || shares.page == null && history
                || shares.page == WzShareView.Page.INBOX) return;
        float[] m = transform.getMat();
        if (!settings
                && !tools.open()
                && !usageOpen
                && !quotaOpen
                && !modelPicker.open()
                && guideAlpha(now, reduced) >= 1)
            references.paint(
                    graphics, ox + sx * m[2], oy + sy * m[5], sx * m[0], sy * m[4], mx, my);
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

    private void hit(String id, String label, float x, float y, float w, float h, Runnable action) {
        if (firstGuide.active() || shareExit != 0 || settings && !id.startsWith("wz-pref-")) return;
        registerHit(id, label, x, y, w, h, action);
    }

    private void guideHit(
            String id, String label, float x, float y, float w, float h, Runnable action) {
        registerHit(
                id,
                label,
                x,
                y,
                w,
                h,
                () -> {
                    if (active && firstGuide.active()) action.run();
                });
    }

    private void registerHit(
            String id, String label, float x, float y, float w, float h, Runnable action) {
        float from = Math.max(y, clipTop), to = Math.min(y + h, clipBottom);
        if (active
                && !WzWorldPicker.closing(this)
                && (reduced || now - opened >= 230_000_000L)
                && to > from)
            host.wenzhouAddHit(id, label, x + routeOffset, from, w, to - from, action);
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
