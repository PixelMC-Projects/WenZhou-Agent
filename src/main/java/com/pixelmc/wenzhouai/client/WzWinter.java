/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client;

import com.pixelmc.hub.client.render.HubDraw;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PathEffect;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** The winter post desk shares the handbook's light and dark appearance. */
record WzWinter(
        int ink, int muted, int pine, int cover, int paper, int sheet,
        int ruling, int soft, int stitch, int wool, int mail, int mailInk) {
    private static final MethodHandle WINTER = winterMethod();
    private static final WzWinter LIGHT = new WzWinter(
            0xff283f36, 0xff606e62, 0xff345e51, 0xffe1ebe4, 0xfff5f5ed, 0xfffffcf5,
            0xffd5dbcf, 0xfff5f6ef, 0xffbec9c1, 0xff627f78, 0xff996147, 0xff783c2b);
    private static final WzWinter DARK = new WzWinter(
            0xfff0e6cc, 0xffb2b7a5, 0xffa5c7aa, 0xff1c3739, 0xff242f33, 0xff303b3b,
            0xff58615a, 0xff293638, 0xff55685e, 0xff9ab6b9, 0xffdb9a78, 0xffe6b196);

    private static MethodHandle winterMethod() {
        try {
            return MethodHandles.publicLookup().findVirtual(HubDraw.class, "winter",
                    MethodType.methodType(boolean.class));
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
            // Compatible with the earlier 0.5.5-dev handbook that has only the classic style.
            return null;
        }
    }

    static WzWinter forDraw(HubDraw draw) {
        if (WINTER == null) return null;
        try {
            return (boolean) WINTER.invokeExact(draw) ? (draw.dark() ? DARK : LIGHT) : null;
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to read Hub appearance", failure);
        }
    }

    private static String asset(String stem) {
        return "images/winter/" + stem + ".png";
    }

    void art(HubDraw draw, String stem, float x, float y, float w, float h, float opacity) {
        contain(draw, stem, x, y, w, h, opacity, .5f);
    }

    private void contain(HubDraw draw, String stem, float x, float y, float w, float h,
            float opacity, float alignment) {
        String asset = asset(stem);
        var image = draw.imageResource(asset);
        float scale = Math.min(w / image.getWidth(), h / image.getHeight());
        float width = image.getWidth() * scale, height = image.getHeight() * scale;
        draw.image(asset, x + (w - width) * alignment, y + (h - height) * alignment, width, height, opacity);
    }

    private static int alpha(int color, float opacity) {
        return Math.round((color >>> 24) * opacity) << 24 | color & 0xffffff;
    }

    void topbar(HubDraw draw, float width, float height) {
        draw.linearGradientRoundRect(0, 0, width, height, 0, 90,
                new int[] {draw.dark() ? 0xff3d4b4c : 0xfff1f4ec, paper}, new float[] {0, .88f});
    }

    void topbarRule(HubDraw draw, float width, float height) {
        float mid = width / 2;
        draw.line(0, height, mid - 36, height, 1, alpha(wool, .28f));
        draw.line(mid + 36, height, width, height, 1, alpha(wool, .28f));
        art(draw, draw.dark() ? "knit-rose-dark" : "knit-rose-light", mid - 36, height - 8.5f, 72, 17, .56f);
    }

    void popover(HubDraw draw, float x, float y, float w, float h) {
        draw.boxShadow(x, y, w, h, 14, 0, 7, 22, -10, 0x45152720);
        draw.roundRect(x, y, w, h, 14, sheet);
        draw.strokeRoundRect(x + .5f, y + .5f, w - 1, h - 1, 13.5f, 1, stitch);
    }

    void landing(HubDraw draw, float left, float top, float width, float height) {
        draw.radialGradientRoundRect(left, top, width, height, 0,
                left + width * .6f, top + height * .36f, width * .52f, height * .56f,
                new int[] {sheet, paper}, new float[] {0, 1});
        try (Shader fibre = draw.imageResource(asset("paper-fibre"))
                        .makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT);
                Paint paint = new Paint().setShader(fibre)) {
            draw.canvas().drawRect(Rect.makeXYWH(left, top, width, height), paint);
        }
        // The percentages address the inner CSS box. Contain then aligns the image within
        // that box: frost at the top left, fox or lantern at the bottom right.
        float frostWidth = Math.max(64, Math.min(145, width * .12f));
        contain(draw, draw.dark() ? "frost-print-dark" : "frost-print-light",
                left + width * .02f, top + height * .05f, frostWidth, height * .19f, .18f, 0);
        float printWidth = Math.max(84, Math.min(170, width * .17f));
        float printHeight = height * .23f;
        contain(draw, draw.dark() ? "pine-lantern" : "sleeping-fox",
                left + width * .96f - printWidth, top + height * .94f - printHeight,
                printWidth, printHeight, .86f, 1);
    }

    void heading(HubDraw draw, String text, float x, float y, float size, int color) {
        draw.textSerif(text, x, y, size, color, true);
    }

    void stitch(HubDraw draw, float left, float right, float y) {
        try (PathEffect effect = PathEffect.makeDash(new float[] {3.5f, 3.5f}, 0);
                Paint paint = new Paint().setColor(stitch).setStrokeWidth(1).setPathEffect(effect)) {
            draw.canvas().drawLine(left, y, right, y, paint);
        }
    }

    void letterEdge(HubDraw draw, float x, float y, float width) {
        draw.save();
        try {
            draw.clipRoundRect(x, y, width, 3, 0);
            draw.rect(x, y, width, 3, sheet);
            for (float at = x; at < x + width; at += 24) {
                draw.line(at, y + 4, at + 5, y - 1, 5, wool);
                draw.line(at + 12, y + 4, at + 17, y - 1, 5, mail);
            }
        } finally {
            draw.restore();
        }
    }
}
