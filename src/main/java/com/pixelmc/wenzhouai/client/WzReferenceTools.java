/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client;

import com.pixelmc.hub.client.render.HubDraw;

final class WzReferenceTools {
    interface Hits {
        void add(String id, String label, float x, float y, float w, float h, Runnable action);
    }

    record Bounds(float x, float y, float w, float h) {
        boolean contains(float px, float py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }

    private static final String ID = "wz-reference-tools";
    private Bounds trigger = new Bounds(0, 0, 0, 0), menu = trigger;
    private boolean open;

    Bounds trigger() { return trigger; }
    Bounds menu() { return menu; }
    boolean open() { return open; }
    void toggle() { open = !open; }
    void close() { open = false; }
    boolean contains(float x, float y) { return trigger.contains(x, y) || menu.contains(x, y); }

    void drawTrigger(HubDraw draw, float x, float y, boolean enabled, boolean guideOpen,
                     float mx, float my, int blue, int ink, int muted, WzPage.Host host,
                     Hits hits, Runnable action) {
        trigger = new Bounds(x, y, 34, 34);
        menu = new Bounds(x - 3, y - 98, 150, 86);
        boolean hovered = enabled && trigger.contains(mx, my);
        float opacity = open || guideOpen ? .16f : .13f * host.wenzhouHover(ID, hovered);
        WzWinter winter = WzWinter.forDraw(draw);
        int glyph = enabled || guideOpen ? ink : muted;
        if (winter != null) {
            boolean expanded = open || guideOpen || hovered;
            draw.roundRect(x, y, 34, 34, 10, expanded ? winter.cover() : winter.soft());
            glyph = expanded ? winter.ink() : enabled ? winter.pine() : winter.muted();
        } else if (opacity > 0) draw.roundRect(x, y, 34, 34, 10, alpha(blue, opacity));
        WzGlyph.draw(draw, "toolbox", x + 6.5f, y + 6.5f, 21, glyph);
        if (enabled && !guideOpen) hits.add(ID, "工具箱", x, y, 34, 34, action);
    }

    void drawMenu(HubDraw draw, boolean guideOpen, boolean worldAvailable, float mx, float my,
                  int blue, int ink, int rule, int paper, WzPage.Host host, Hits hits,
                  Runnable items, Runnable world) {
        if (!open && !guideOpen) return;
        WzWinter winter = WzWinter.forDraw(draw);
        if (winter != null) winter.popover(draw, menu.x, menu.y, menu.w, menu.h);
        else {
            draw.roundRect(menu.x, menu.y, menu.w, menu.h, 13, paper);
            draw.strokeRoundRect(menu.x + .5f, menu.y + .5f, menu.w - 1, menu.h - 1, 13, 1, rule);
        }
        row(draw, "wz-tools-item", "内联物品", "item", menu.y + 5, true, mx, my, blue, ink, host, hits,
                guideOpen ? null : items);
        row(draw, "wz-tools-block", "世界方块", "select-block", menu.y + 43, worldAvailable || guideOpen,
                mx, my, blue, ink, host, hits, guideOpen || !worldAvailable ? null : world);
    }

    private void row(HubDraw draw, String id, String label, String icon, float y, boolean enabled,
                     float mx, float my, int blue, int ink, WzPage.Host host, Hits hits, Runnable action) {
        Bounds row = new Bounds(menu.x + 5, y, menu.w - 10, 38);
        float hover = host.wenzhouHover(id, action != null && row.contains(mx, my));
        if (hover > 0) draw.roundRect(row.x, row.y, row.w, row.h, 8, alpha(blue, .15f * hover));
        WzGlyph.draw(draw, icon, row.x + 11, y + 10, 18, alpha(ink, enabled ? .7f : .4f));
        draw.text(label, row.x + 39, y + 24, 14, alpha(ink, enabled ? 1 : .44f), false);
        if (action != null) hits.add(id, label, row.x, row.y, row.w, row.h, action);
    }

    private static int alpha(int color, float opacity) {
        return color & 0x00ffffff | Math.round((color >>> 24) * opacity) << 24;
    }
}
