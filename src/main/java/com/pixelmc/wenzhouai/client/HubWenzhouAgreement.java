package com.pixelmc.wenzhouai.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pixelmc.hub.client.render.HubDraw;

import net.minecraft.Util;

import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** In-panel reader for the active agreement supplied by the server. */
public final class HubWenzhouAgreement {
    public interface Host {
        void hit(String id, String label, float x, float y, float w, float h, Runnable action);

        void accept(boolean research);

        void close();

        void setResearch(boolean research);

        JsonObject policy();

        boolean researchAllowed();

        default boolean quotaEnabled() { return false; }

        long wenzhouWindow();
    }

    private enum Kind {
        TITLE,
        SUBTITLE,
        BODY,
        BULLET
    }

    private record Block(Kind kind, List<WzParagraph.Run> runs) {}

    private record Measured(Kind kind, WzParagraph flow, float size, float lineHeight, float gap) {}

    private static final Pattern INLINE =
            Pattern.compile("\\*\\*([^*]+)\\*\\*|\\[([^]\\n]+)\\]\\((https://[^\\s)]+)\\)");
    private List<Measured> rules = List.of(), brief = List.of(), details = List.of();
    private List<Measured> quotaRules = List.of();
    private JsonObject cachedPolicy;
    private String version = "";
    private float measuredWidth, scroll, maxScroll, vx, vy, vw, vh;
    private boolean terms, api, optIn, expanded;
    private final WzReadingTime readingTime = new WzReadingTime();
    private float thumbHeight, thumbGrip;
    private boolean dragging;
    private WzDisclosure detailMotion = new WzDisclosure(false);
    private final Map<String, WzDisclosure> checkMotion = new HashMap<>();
    private long frameTime;
    private boolean reducedMotion;
    private float detailClipBottom;

    public void reset() {
        detailMotion = new WzDisclosure(false);
        checkMotion.clear();
        terms = false;
        api = false;
        optIn = false;
        expanded = false;
        scroll = 0;
        maxScroll = 0;
        dragging = false;
        thumbHeight = 0;
        readingTime.reset();
        cachedPolicy = null;
        version = "";
    }

    public boolean scroll(float x, float y, double amount) {
        if (x < vx || x >= vx + vw || y < vy || y >= vy + vh) return false;
        scroll = Math.max(0, Math.min(maxScroll, scroll - (float) amount * 48));
        return true;
    }

    public boolean escape() {
        // Esc is the Web route's Back action, including when the details disclosure is open.
        return false;
    }

    void leaveReader() {
        readingTime.pause();
        dragging = false;
    }

    boolean railAt(float x, float y) {
        return maxScroll > 0
                && thumbHeight > 0
                && x >= vx + vw - 18
                && x < vx + vw
                && y >= vy
                && y < vy + vh;
    }

    boolean pointerDown(float x, float y) {
        if (!railAt(x, y)) return false;
        float top = thumbTop();
        thumbGrip = y >= top && y < top + thumbHeight ? y - top : thumbHeight * .5f;
        dragging = true;
        return pointerDrag(y);
    }

    boolean pointerDrag(float y) {
        if (!dragging) return false;
        float travel = vh - thumbHeight;
        scroll =
                travel <= 0
                        ? 0
                        : Math.max(0, Math.min(1, (y - vy - thumbGrip) / travel)) * maxScroll;
        return true;
    }

    boolean pointerUp() {
        boolean handled = dragging;
        dragging = false;
        return handled;
    }

    private float thumbTop() {
        return vy + scroll / maxScroll * (vh - thumbHeight);
    }

    public void render(
            HubDraw d,
            float x,
            float y,
            float w,
            float h,
            float mx,
            float my,
            boolean present,
            boolean reviewing,
            Host host, long time, boolean reduced) {
        frameTime = time;
        reducedMotion = reduced;
        vx = x;
        vy = y;
        vw = w;
        vh = h;
        JsonObject policy = host.policy();
        if (policy == null) return;
        String activeVersion = HubWenzhouAnswer.string(policy.get("version"));
        if (!Objects.equals(activeVersion, version)) {
            reset();
            version = activeVersion;
        }
        float width = Math.max(80, Math.min(660, w - 48));
        float left = x + (w - width) / 2, base = y + Math.min(35, h * .045f);
        if (cachedPolicy != policy || width != measuredWidth) {
            JsonObject copy = HubWenzhouAnswer.object(policy, "text");
            rules = measure(d, field(copy, "rules"), width - 43);
            brief = measure(d, field(copy, "brief"), width);
            details = measure(d, field(copy, "details"), width);
            quotaRules = measure(d, new com.google.gson.JsonPrimitive("## 当前额度规则\n每期额度从该期首次提问的分钟起计算，七天后重置。之后再次提问时开始新的一期。额度耗尽后，已开始的回答可以继续完成，也可以手动停止。重置后可以再次提问。无限额度的玩家可正常提问。"), width);
            cachedPolicy = policy;
            measuredWidth = width;
        }
        int remaining = readingTime.remaining(present && !reviewing, host.wenzhouWindow());
        int seconds = reviewing ? 0 : remaining;
        boolean unlocked =
                seconds == 0 && !activeVersion.isBlank() && (!brief.isEmpty() || !rules.isEmpty());
        int ink = d.dark() ? 0xffe1e7f4 : 0xff343d54, muted = d.dark() ? 0xffb1bcd1 : 0xff66748e;
        int blue = d.dark() ? 0xffadc3ff : 0xff5365b1, rule = d.dark() ? 0x479badcc : 0x406c7a92;
        int amber = d.dark() ? 0xfff2c582 : 0xff845013;
        d.save();
        d.clipRoundRect(x, y, w, h, 0);
        float pos = base - scroll;
        WzArtwork.boat(d, left, pos + 12, 65);
        d.text("问舟内测使用协议", left + 79, pos + 28, 26, ink, true);
        String label = HubWenzhouAnswer.string(policy.get("versionLabel"));
        d.text(
                "内测协议 · " + (label.isBlank() ? activeVersion : label),
                left + 79,
                pos + 53,
                12,
                muted,
                false);
        pos += 81;
        d.rect(left, pos, width, 42, d.dark() ? 0x40303d55 : 0x66ffffff);
        d.rect(left, pos, 2, 42, rule);
        d.text("提问时，问舟会把本题所需的问题、前文和游戏资料发送至 DeepSeek。", left + 15, pos + 26, 14, muted, false);
        pos += 60;
        float ruleHeight = height(rules) + 32;
        d.roundRect(left, pos, width, ruleHeight, 12, d.dark() ? 0x10ffc675 : 0x38f6d79a);
        d.rect(left, pos, 3, ruleHeight, amber);
        blocks(d, host, rules, left + 23, pos + 16, ink, amber, "rules");
        pos += ruleHeight + 18;
        pos = blocks(d, host, brief, left, pos, ink, ink, "brief") + 18;
        if (host.quotaEnabled()) pos = blocks(d, host, quotaRules, left, pos, ink, ink, "quota") + 12;
        d.line(left, pos, left + width, pos, 1, rule);
        d.text(expanded ? "▾  完整说明" : "▸  完整说明", left, pos + 25, 15, blue, true);
        hit(host, "details", "完整说明", left, pos, width, 46, () -> expanded = !expanded);
        pos += 46;
        detailMotion.set(expanded, time, reduced);
        float reveal = detailMotion.value(time, reduced);
        if (reveal > 0) {
            float visibleHeight = (34 + height(details)) * reveal;
            detailClipBottom = pos + visibleHeight;
            d.save();
            d.clipRoundRect(x, pos, w, visibleHeight, 0);
            blocks(d, host, details, left, pos + 12, ink, ink, "details");
            d.restore();
            pos += visibleHeight;
        }
        d.line(left, pos, left + width, pos, 1, rule);
        pos += 26;
        if (!reviewing) {
            pos =
                    checkbox(
                            d,
                            host,
                            "terms",
                            "我已阅读并同意《问舟内测使用协议》",
                            left,
                            pos,
                            width - 160,
                            terms,
                            unlocked,
                            () -> terms = !terms,
                            ink,
                            rule,
                            blue);
            pos =
                    checkbox(
                            d,
                            host,
                            "api",
                            "我单独同意按上述说明向 DeepSeek 发送回答所需的信息",
                            left,
                            pos,
                            width - 160,
                            api,
                            unlocked,
                            () -> api = !api,
                            ink,
                            rule,
                            blue);
        }
        String opt = "（可选）允许服主用授权期间的新问答原文改进本服问舟";
        pos =
                checkbox(
                        d,
                        host,
                        "research",
                        opt,
                        left,
                        pos,
                        reviewing ? width : width - 160,
                        reviewing ? host.researchAllowed() : optIn,
                        unlocked,
                        () -> {
                            if (reviewing) host.setResearch(!host.researchAllowed());
                            else optIn = !optIn;
                        },
                        ink,
                        rule,
                        blue);
        if (!reviewing) {
            boolean ready = unlocked && terms && api;
            float bx = left + width - 132, by = pos - 59;
            d.roundRect(bx, by, 132, 38, 12, d.dark() ? 0x44374963 : 0x88ffffff);
            d.strokeRoundRect(
                    bx,
                    by,
                    132,
                    38,
                    12,
                    1,
                    ready && mx >= bx && mx < bx + 132 && my >= by && my < by + 38 ? blue : rule);
            d.text("同意并进入问舟", bx + 16, by + 24, 14, ready ? ink : muted, false);
            if (ready) hit(host, "accept", "同意并进入问舟", bx, by, 132, 38, () -> host.accept(optIn));
        }
        String foot =
                reviewing
                        ? "可随时取消勾选，照常使用问舟。"
                        : (seconds > 0 ? "阅读 " + seconds + " 秒后可以勾选。" : "")
                                + "勾选必选项并确认即表示同意。如不同意，可";
        d.text(foot, left, pos + 14, 12, muted, false);
        if (!reviewing) {
            float fx = left + d.textWidth(foot, 12, false);
            d.text("返回手册", fx, pos + 14, 12, ink, false);
            d.line(fx, pos + 16, fx + 48, pos + 16, 1, muted);
            hit(host, "decline", "返回手册", fx, pos, 52, 22, host::close);
        }
        float total = pos + scroll + 50 - y;
        maxScroll = Math.max(0, total - h);
        scroll = Math.min(scroll, maxScroll);
        thumbHeight = maxScroll > 0 ? Math.min(h, Math.max(28, h * h / total)) : 0;
        if (thumbHeight > 0) {
            d.roundRect(
                    x + w - 6, thumbTop(), 4, thumbHeight, 2, d.dark() ? 0x608b9ab6 : 0x5472839e);
        }
        d.restore();
    }

    private static JsonElement field(JsonObject obj, String key) {
        return obj == null ? null : obj.get(key);
    }

    private void hit(
            Host host,
            String id,
            String label,
            float x,
            float y,
            float w,
            float h,
            Runnable action) {
        float top = Math.max(vy, y), bottom = Math.min(vy + vh, y + h);
        if (id.startsWith("details-link-")) bottom = Math.min(bottom, detailClipBottom);
        if (bottom > top) host.hit("agreement-" + id, label, x, top, w, bottom - top, action);
    }

    private float checkbox(
            HubDraw d,
            Host host,
            String id,
            String label,
            float x,
            float y,
            float w,
            boolean checked,
            boolean enabled,
            Runnable action,
            int ink,
            int rule,
            int blue) {
        d.strokeRoundRect(x, y + 4, 14, 14, 3, 1, rule);
        WzDisclosure tick = checkMotion.computeIfAbsent(id, key -> new WzDisclosure(checked));
        tick.set(checked, frameTime, reducedMotion);
        float progress = tick.value(frameTime, reducedMotion);
        if (progress > 0) {
            if (progress < 1) d.saveLayerAlpha(x, y, 16, 22, progress);
            d.roundRect(x, y + 4, 14, 14, 3, blue);
            d.line(x + 3, y + 11, x + 6, y + 14, 1.6f, 0xffffffff);
            d.line(x + 6, y + 14, x + 11, y + 7, 1.6f, 0xffffffff);
            if (progress < 1) d.restore();
        }
        d.text(label, x + 22, y + 16, 13, enabled ? ink : (ink & 0x00ffffff) | 0x88000000, false);
        if (enabled) hit(host, id, label, x, y, w, 22, action);
        return y + 25;
    }

    private static float height(List<Measured> blocks) {
        float sum = 0;
        for (Measured b : blocks) sum += b.gap + b.lineHeight * b.flow.lines.size();
        return sum;
    }

    private float blocks(
            HubDraw d,
            Host host,
            List<Measured> list,
            float x,
            float y,
            int ink,
            int heading,
            String section) {
        int serial = 0;
        for (Measured b : list) {
            y += b.gap;
            for (var line : b.flow.lines) {
                if (y + b.lineHeight >= vy && y < vy + vh) {
                    if (b.kind == Kind.BULLET && line == b.flow.lines.get(0))
                        d.circle(x + 6, y + b.lineHeight * .5f, 2, ink);
                    for (var part : line.parts()) {
                        float left = x + part.x() + (b.kind == Kind.BULLET ? 18 : 0);
                        float baseline = y + (b.lineHeight + b.size * .65f) * .5f;
                        d.text(
                                part.text(),
                                left,
                                baseline,
                                b.size,
                                b.kind == Kind.TITLE ? heading : ink,
                                part.style().bold());
                        String link = part.style().link();
                        if (link != null) {
                            d.line(left, baseline + 2, left + part.width(), baseline + 2, 1, ink);
                            hit(
                                    host,
                                    section + "-link-" + serial,
                                    part.text(),
                                    left,
                                    y,
                                    part.width(),
                                    b.lineHeight,
                                    () -> Util.getPlatform().openUri(URI.create(link)));
                        }
                        serial++;
                    }
                }
                y += b.lineHeight;
            }
        }
        return y;
    }

    private static List<Measured> measure(HubDraw d, JsonElement field, float width) {
        List<Block> blocks = new ArrayList<>();
        if (field != null && field.isJsonObject()) walk(field.getAsJsonObject(), blocks, false);
        else
            for (String line : HubWenzhouAnswer.string(field).split("\n")) {
                String text = line.strip();
                if (text.isEmpty()) continue;
                Kind kind = Kind.BODY;
                if (text.startsWith("## ")) {
                    kind = Kind.SUBTITLE;
                    text = text.substring(3);
                } else if (text.startsWith("# ")) {
                    kind = Kind.TITLE;
                    text = text.substring(2);
                } else if (text.startsWith("- ")) {
                    kind = Kind.BULLET;
                    text = text.substring(2);
                }
                boolean bold = kind == Kind.TITLE || kind == Kind.SUBTITLE;
                Matcher m = INLINE.matcher(text);
                int p = 0;
                List<WzParagraph.Run> runs = new ArrayList<>();
                while (m.find()) {
                    if (p < m.start())
                        runs.add(
                                new WzParagraph.Run(
                                        text.substring(p, m.start()), bold, false, null, null));
                    runs.add(
                            m.group(1) != null
                                    ? new WzParagraph.Run(m.group(1), true, false, null, null)
                                    : new WzParagraph.Run(
                                            m.group(2), bold, false, safeLink(m.group(3)), null));
                    p = m.end();
                }
                if (p < text.length())
                    runs.add(new WzParagraph.Run(text.substring(p), bold, false, null, null));
                blocks.add(new Block(kind, runs));
            }
        List<Measured> result = new ArrayList<>();
        for (Block b : blocks) {
            float size = b.kind == Kind.TITLE ? 18 : 15;
            float gap =
                    result.isEmpty()
                            ? 0
                            : b.kind == Kind.BULLET ? 4 : b.kind == Kind.SUBTITLE ? 16 : 10;
            result.add(
                    new Measured(
                            b.kind,
                            new WzParagraph(
                                    d, width - (b.kind == Kind.BULLET ? 18 : 0), size, b.runs),
                            size,
                            size
                                    * (b.kind == Kind.TITLE
                                            ? 1.5f
                                            : b.kind == Kind.SUBTITLE ? 1.6f : 1.85f),
                            gap));
        }
        return result;
    }

    private static void walk(JsonObject node, List<Block> blocks, boolean bullet) {
        String type = HubWenzhouAnswer.string(node.get("type"));
        if (type.equals("paragraph") || type.equals("heading")) {
            Kind kind = bullet ? Kind.BULLET : Kind.BODY;
            if (type.equals("heading")) {
                JsonObject attrs = HubWenzhouAnswer.object(node, "attrs");
                kind =
                        attrs != null && "3".equals(HubWenzhouAnswer.string(attrs.get("level")))
                                ? Kind.SUBTITLE
                                : Kind.TITLE;
            }
            List<WzParagraph.Run> runs = new ArrayList<>();
            if (node.has("content") && node.get("content").isJsonArray())
                for (JsonElement e : node.getAsJsonArray("content")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject child = e.getAsJsonObject();
                    if ("hardBreak".equals(HubWenzhouAnswer.string(child.get("type")))) {
                        runs.add(new WzParagraph.Run("\n"));
                        continue;
                    }
                    boolean bold = kind == Kind.TITLE || kind == Kind.SUBTITLE;
                    String url = null;
                    if (child.has("marks") && child.get("marks").isJsonArray())
                        for (JsonElement mark : child.getAsJsonArray("marks")) {
                            if (!mark.isJsonObject()) continue;
                            JsonObject m = mark.getAsJsonObject();
                            if ("bold".equals(HubWenzhouAnswer.string(m.get("type")))) bold = true;
                            if ("link".equals(HubWenzhouAnswer.string(m.get("type"))))
                                url =
                                        safeLink(
                                                HubWenzhouAnswer.string(
                                                        field(
                                                                HubWenzhouAnswer.object(m, "attrs"),
                                                                "href")));
                        }
                    runs.add(
                            new WzParagraph.Run(
                                    HubWenzhouAnswer.string(child.get("text")),
                                    bold,
                                    false,
                                    url,
                                    null));
                }
            blocks.add(new Block(kind, runs));
        } else if (node.has("content") && node.get("content").isJsonArray()) {
            for (JsonElement e : node.getAsJsonArray("content"))
                if (e.isJsonObject())
                    walk(e.getAsJsonObject(), blocks, bullet || type.equals("listItem"));
        }
    }

    private static String safeLink(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI u = URI.create(value);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null ? value : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
