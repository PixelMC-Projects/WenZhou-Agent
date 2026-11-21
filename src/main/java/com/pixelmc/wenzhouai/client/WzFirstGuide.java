/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client;

import com.pixelmc.hub.client.render.HubDraw;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.Path;
import io.github.humbleui.types.RRect;
import java.util.List;
import org.lwjgl.glfw.GLFW;

/** Draws the first-visit welcome and lessons above the real guide page. */
final class WzFirstGuide {
    private static final float WIDTH = 1260, HEIGHT = 850, CARD = 310;
    private static final long STEP_MOTION = 250_000_000L, EXIT_MOTION = 320_000_000L;
    private static final int WELCOME = 0, HIDDEN = -1;

    private record Lesson(String title, String body) {}

    private static final Lesson[] LESSONS = {
        new Lesson("把问题写在这里", "写下想弄清的事，按 Enter 或点击箭头发送。Shift + Enter 可以换行。"),
        new Lesson("这段对话花了多少？", "金额会随整段对话累加。把鼠标放上去，就能看到输入、输出和缓存命中的明细。"),
        new Lesson("把要问的东西带上", "用「内联物品」插入物品，或选「世界方块」引用眼前的方块和坐标。一次选一块，一条消息可以放好几块。"),
        new Lesson("接着上次的话聊", "在历史里选一段对话，就能看回之前的回答，沿着同一个问题继续追问。"),
        new Lesson("换了话题，就开新对话", "相关的问题留在一起。问另一件事时，请先点「新对话」，避免旧上下文干扰回答、增加费用。原来的对话仍在历史里。")
    };

    @FunctionalInterface
    interface Hits {
        void add(String id, String label, float x, float y, float w, float h, Runnable action);
    }

    private enum Action {
        DIRECT("wz-intro-direct", "直接体验"), TUTORIAL("wz-intro-tutorial", "使用教程"),
        EXIT("wz-intro-exit", "退出教程"), PREVIOUS("wz-intro-previous", "上一步"),
        NEXT("wz-intro-next", "下一步"), FINISH("wz-intro-finish", "开始使用");

        final String id, label;

        Action(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private record Box(float x, float y, float w, float h) {
        private float right() { return x + w; }
        private float bottom() { return y + h; }

        private Box union(Box other) {
            float l = Math.min(x, other.x), t = Math.min(y, other.y);
            return new Box(l, t, Math.max(right(), other.right()) - l, Math.max(bottom(), other.bottom()) - t);
        }

        private Box expand() {
            float l = clamp(x - 6, 2, WIDTH - 2), t = clamp(y - 6, 2, HEIGHT - 2);
            return new Box(l, t, clamp(right() + 6, l, WIDTH - 2) - l, clamp(bottom() + 6, t, HEIGHT - 2) - t);
        }

        private Box interpolate(Box to, float p) {
            return new Box(mix(x, to.x, p), mix(y, to.y, p), mix(w, to.w, p), mix(h, to.h, p));
        }
    }

    private static final class Wake {
        static final Path PATH = Path.makeFromSVGString("M6 13c24-13 45 7 70 1s45-10 77-4");
    }

    private final WzPage.Host host;
    private final Hits hits;
    private int step = HIDDEN;
    private long changedAt, leavingAt;
    private Box target, cutout, card, fromCutout, fromCard;
    private WzParagraph paragraph;
    private HubDraw paragraphDraw;

    WzFirstGuide(WzPage.Host host, Hits hits) {
        this.host = host;
        this.hits = hits;
    }

    boolean active() { return step != HIDDEN; }

    boolean showingStep(int number) { return step == number; }

    void open(long now) { changeStep(WELCOME, now); }

    void reset() {
        step = HIDDEN;
        leavingAt = 0;
        target = cutout = card = fromCutout = fromCard = null;
        paragraph = null;
        paragraphDraw = null;
    }

    boolean finishIfDue(long now, boolean reduced) {
        if (leavingAt == 0 || !reduced && now - leavingAt < EXIT_MOTION) return false;
        reset();
        return true;
    }

    private void changeStep(int next, long now) {
        fromCutout = cutout;
        fromCard = card;
        step = next;
        changedAt = now;
        paragraph = null;
        host.wenzhouFocus(null);
    }

    void dismiss(long now) {
        if (leavingAt == 0) leavingAt = now;
    }

    private void act(Action action, long now) {
        if (leavingAt != 0) return;
        switch (action) {
            case DIRECT, EXIT, FINISH -> dismiss(now);
            case TUTORIAL, NEXT -> changeStep(step + 1, now);
            case PREVIOUS -> changeStep(step - 1, now);
        }
    }

    private Action[] controls() {
        if (step == WELCOME) return new Action[] {Action.DIRECT, Action.TUTORIAL};
        if (step == 1) return new Action[] {Action.EXIT, Action.NEXT};
        Action next = step == LESSONS.length ? Action.FINISH : Action.NEXT;
        return new Action[] {Action.EXIT, Action.PREVIOUS, next};
    }

    void key(int key, int mods, String focus, long now) {
        if (leavingAt != 0) return;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            dismiss(now);
            return;
        }
        if (key != GLFW.GLFW_KEY_TAB && key != GLFW.GLFW_KEY_ENTER && key != GLFW.GLFW_KEY_KP_ENTER && key != GLFW.GLFW_KEY_SPACE) return;
        Action[] controls = controls();
        int index = -1;
        for (int i = 0; i < controls.length; i++) {
            if (controls[i].id.equals(focus)) index = i;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
            int next = shift ? (index <= 0 ? controls.length - 1 : index - 1) : (index + 1) % controls.length;
            host.wenzhouFocus(controls[next].id);
        } else if (index >= 0) {
            act(controls[index], now);
        }
    }

    void beginFrame() { target = null; }

    void include(float x, float y, float w, float h) {
        Box box = new Box(x, y, w, h);
        target = target == null ? box : target.union(box);
    }

    float homeArrival(long now) {
        return step == WELCOME && leavingAt != 0
                ? cubicProgress((float) (now - leavingAt) / EXIT_MOTION, .22f, .75f, .28f, 1)
                : 1;
    }

    float previewNewOpacity(long now, boolean reduced) {
        if (reduced) return 1;
        if (leavingAt == 0) return stepProgress(now);
        float fade = cubicProgress((float) (now - leavingAt) / STEP_MOTION, 0, 0, .58f, 1);
        return stepProgress(leavingAt) * (1 - fade);
    }

    float previewNewOffset(long now, boolean reduced) {
        return reduced ? 0 : 6 * (1 - stepProgress(leavingAt == 0 ? now : leavingAt));
    }

    private float stepProgress(long now) {
        return ease((float) (now - changedAt) / STEP_MOTION);
    }

    void render(HubDraw draw, long now, boolean reduced, boolean dark, float mx, float my, String focus,
            int ink, int muted, int blue, int rule, int surface) {
        if (!active()) return;
        float opacity = leavingAt == 0 ? 1 : 1 - ease((float) (now - leavingAt) / EXIT_MOTION);
        if (opacity < 1) draw.saveLayerAlpha(0, 0, WIDTH, HEIGHT, opacity);
        try {
            if (step == WELCOME) drawWelcome(draw, now, reduced, dark, mx, my, focus, ink, muted, blue, rule, surface);
            else drawLesson(draw, now, reduced, mx, my, focus, ink, muted, blue, rule, surface);
        } finally {
            if (opacity < 1) draw.restore();
        }
    }

    private void drawWelcome(HubDraw draw, long now, boolean reduced, boolean dark, float mx, float my, String focus,
            int ink, int muted, int blue, int rule, int surface) {
        draw.rect(0, 0, WIDTH, HEIGHT, dark ? 0xff1d2230 : 0xfff5f7fc);
        gradient(draw, .84f, .85f, .55f, dark ? 0xff39304c : 0xffeae2fa);
        gradient(draw, .24f, .18f, .56f, dark ? 0xff283c51 : 0xffd9edf7);
        long age = now - changedAt;
        float departing = leavingAt == 0 ? 0 : cubicProgress((now - leavingAt) / 250_000_000f, .42f, 0, 1, 1);
        if (departing >= 1) return;
        if (departing > 0) {
            draw.saveLayerAlpha(0, 307, WIDTH, 213, 1 - departing);
            draw.translate(0, -6 * departing);
        }
        try {
            float boat = reduced ? 1 : cubicProgress(age / 900_000_000f, .18f, .7f, .22f, 1);
            if (boat > 0) {
                if (boat < 1) {
                    draw.saveLayerAlpha(540, 306, 180, 98, boat);
                    draw.translate(630 - 22 * (1 - boat), 356 + 9 * (1 - boat));
                    draw.canvas().rotate(-7 * (1 - boat));
                    draw.translate(-630, -356);
                }
                try {
                    WzArtwork.boat(draw, 565, 319, 130);
                } finally {
                    if (boat < 1) draw.restore();
                }
            }
            float wake = reduced ? 1 : cubicProgress(age / 800_000_000f, 0, 0, .58f, 1);
            if (wake > 0) drawWake(draw, wake, blue);
            float heading = rise(age, 250, 360, reduced);
            float headingX = 630 - (draw.textWidth("问舟", 32, true) + 3.84f) / 2;
            float headingY = 449 + 6 * (1 - heading);
            draw.text("问", headingX, headingY, 32, alpha(ink, heading), true);
            draw.text("舟", headingX + draw.textWidth("问", 32, true) + 3.84f, headingY, 32, alpha(ink, heading), true);
            float intro = rise(age, 360, 320, reduced);
            center(draw, "玩法有疑问？我可以帮你查模组代码和整合包改动。", 630, 482 + 6 * (1 - intro),
                    14, alpha(muted, intro), false);
            float buttons = rise(age, 520, 350, reduced), y = 496 + 6 * (1 - buttons);
            button(draw, Action.DIRECT, 525, y, 100, 40, false, false, buttons, mx, my, focus, ink, muted, blue, rule, surface);
            button(draw, Action.TUTORIAL, 635, y, 100, 40, true, false, buttons, mx, my, focus, ink, muted, blue, rule, surface);
        } finally {
            if (departing > 0) draw.restore();
        }
    }

    private static void gradient(HubDraw draw, float x, float y, float stop, int color) {
        float rx = WIDTH * Math.max(x, 1 - x), ry = HEIGHT * Math.max(y, 1 - y);
        float radius = stop * (float) Math.sqrt(2);
        draw.radialGradientRoundRect(0, 0, WIDTH, HEIGHT, 0, x * WIDTH, y * HEIGHT, rx * radius, ry * radius,
                new int[] {color, color & 0xffffff}, new float[] {0, 1});
    }

    private static void drawWake(HubDraw draw, float progress, int blue) {
        draw.save();
        try (Paint paint = new Paint().setAntiAlias(true).setMode(PaintMode.STROKE).setColor(alpha(blue, .48f))
                .setStrokeWidth(1.5f).setStrokeCap(PaintStrokeCap.ROUND)) {
            draw.translate(557.5f, 387);
            draw.canvas().scale(145 / 160f, 145 / 160f);
            draw.clipRoundRect(0, 0, 160 * progress, 22, 0);
            draw.canvas().drawPath(Wake.PATH, paint);
        } finally {
            draw.restore();
        }
    }

    private void drawLesson(HubDraw draw, long now, boolean reduced, float mx, float my, String focus,
            int ink, int muted, int blue, int rule, int surface) {
        if (target == null) return;
        Lesson lesson = LESSONS[step - 1];
        if (paragraph == null || paragraphDraw != draw) {
            paragraph = new WzParagraph(draw, CARD - 38, 13, List.of(new WzParagraph.Run(lesson.body())));
            paragraphDraw = draw;
        }
        Box nextCutout = target.expand(), nextCard = locate(nextCutout, 118 + 20.8f * paragraph.lines.size());
        long motionTime = leavingAt == 0 ? now : leavingAt;
        float motion = reduced ? 1 : stepProgress(motionTime);
        cutout = fromCutout == null ? nextCutout : fromCutout.interpolate(nextCutout, motion);
        card = fromCard == null ? nextCard
                : new Box(mix(fromCard.x, nextCard.x, motion), mix(fromCard.y, nextCard.y, motion), nextCard.w, nextCard.h);
        draw.save();
        try {
            draw.canvas().clipRRect(RRect.makeXYWH(cutout.x, cutout.y, cutout.w, cutout.h, 13, 13), ClipMode.DIFFERENCE, true);
            draw.rect(0, 0, WIDTH, HEIGHT, 0x691a2332);
        } finally {
            draw.restore();
        }
        draw.strokeRoundRect(cutout.x + .75f, cutout.y + .75f, cutout.w - 1.5f, cutout.h - 1.5f, 12.25f, 1.5f, blue);
        draw.roundRect(card.x, card.y, card.w, card.h, 16, surface);
        draw.strokeRoundRect(card.x + .5f, card.y + .5f, card.w - 1, card.h - 1, 15.5f, 1, rule);

        float x = card.x + 18, y = card.y + 16 + 6 * (1 - motion);
        WzGlyph.draw(draw, "boat", x, y, 18, alpha(muted, motion));
        draw.text("使用教程", x + 24, y + 13, 12, alpha(muted, motion), false);
        String progress = step + " / " + LESSONS.length;
        draw.text(progress, card.right() - 18 - draw.textWidth(progress, 11, false), y + 13, 11, alpha(muted, motion), false);
        draw.text(lesson.title(), x, y + 42, 18, alpha(ink, motion), true);
        float bodyY = y + 63;
        for (WzParagraph.Line line : paragraph.lines) {
            for (WzParagraph.Part part : line.parts())
                draw.text(part.text(), x + part.x(), bodyY, 13, alpha(muted, motion), false);
            bodyY += 20.8f;
        }

        float buttonY = card.bottom() - 37;
        button(draw, Action.EXIT, x, buttonY, 55, 24, false, true, 1, mx, my, focus, ink, muted, blue, rule, surface);
        if (step > 1)
            button(draw, Action.PREVIOUS, card.right() - 135, buttonY, 52, 24, false, false, 1, mx, my, focus, ink, muted, blue, rule, surface);
        Action next = step == LESSONS.length ? Action.FINISH : Action.NEXT;
        button(draw, next, card.right() - 78, buttonY, 60, 24, true, false, 1, mx, my, focus, ink, muted, blue, rule, surface);
    }

    private static Box locate(Box target, float h) {
        float cx = (target.x + target.right() - CARD) / 2, cy = (target.y + target.bottom() - h) / 2;
        float gap = 19;
        float[][] candidates = {
            {cx, target.bottom() + gap}, {cx, target.y - gap - h},
            {target.x - gap - CARD, cy}, {target.right() + gap, cy}
        };
        Box best = null;
        float score = Float.POSITIVE_INFINITY;
        for (float[] position : candidates) {
            float x = clamp(position[0], 10, WIDTH - CARD - 10), y = clamp(position[1], 10, HEIGHT - h - 10);
            float overlap = Math.max(0, Math.min(x + CARD, target.right()) - Math.max(x, target.x))
                    * Math.max(0, Math.min(y + h, target.bottom()) - Math.max(y, target.y));
            float rank = overlap * 1000 + Math.abs(x - position[0]) + Math.abs(y - position[1]);
            if (rank < score) {
                score = rank;
                best = new Box(x, y, CARD, h);
            }
        }
        return best;
    }

    private void button(HubDraw draw, Action action, float x, float y, float w, float h, boolean primary,
            boolean plain, float opacity, float mx, float my, String focus, int ink, int muted, int blue, int rule,
            int surface) {
        boolean hover = leavingAt == 0 && mx >= x && mx < x + w && my >= y && my < y + h;
        boolean focused = action.id.equals(focus);
        if (!plain) {
            int fill = primary ? blue : surface;
            draw.roundRect(x, y, w, h, 10, alpha(fill, opacity));
            if (!primary) draw.strokeRoundRect(x + .5f, y + .5f, w - 1, h - 1, 9.5f, 1, alpha(rule, opacity));
            if (hover) draw.roundRect(x, y, w, h, 10, alpha(0x0d000000, opacity));
        }
        if (focused) draw.strokeRoundRect(x - 2, y - 2, w + 4, h + 4, 12, 1.5f, alpha(blue, opacity));
        boolean welcome = step == WELCOME;
        float baseline = y + h / 2 + (welcome ? 5 : 4);
        if (plain) draw.text(action.label, x, baseline, 12, alpha(muted, opacity), false);
        else center(draw, action.label, x + w / 2, baseline, welcome ? 14 : 12, alpha(primary ? surface : ink, opacity), welcome);
        if (leavingAt == 0) {
            int expected = step;
            hits.add(action.id, action.label, x, y, w, h, () -> {
                if (step == expected) act(action, System.nanoTime());
            });
        }
    }

    private static void center(HubDraw draw, String text, float x, float y, float size, int color, boolean bold) {
        draw.text(text, x - draw.textWidth(text, size, bold) / 2, y, size, color, bold);
    }

    private static int alpha(int color, float opacity) {
        return Math.round((color >>> 24) * opacity) << 24 | color & 0xffffff;
    }

    private static float rise(long age, int delayMs, int durationMs, boolean reduced) {
        return reduced ? 1 : ease((age / 1_000_000f - delayMs) / durationMs);
    }

    private static float ease(float t) { return cubicProgress(t, .25f, .1f, .25f, 1); }

    private static float cubicProgress(float t, float x1, float y1, float x2, float y2) {
        if (t <= 0 || t >= 1) return clamp(t, 0, 1);
        float low = 0, high = 1;
        for (int i = 0; i < 12; i++) {
            float at = (low + high) / 2;
            if (cubic(at, x1, x2) < t) low = at;
            else high = at;
        }
        return cubic((low + high) / 2, y1, y2);
    }

    private static float cubic(float t, float a, float b) {
        float q = 1 - t;
        return 3 * q * q * t * a + 3 * q * t * t * b + t * t * t;
    }

    private static float clamp(float n, float min, float max) { return Math.max(min, Math.min(max, n)); }

    private static float mix(float a, float b, float t) { return a + (b - a) * t; }
}
