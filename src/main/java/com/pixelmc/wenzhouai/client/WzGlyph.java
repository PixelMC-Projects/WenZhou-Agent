/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client;

import com.pixelmc.hub.client.render.HubDraw;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.PaintStrokeJoin;
import io.github.humbleui.skija.Path;
import java.util.HashMap;
import java.util.Map;

final class WzGlyph {
    // Only the fixed SVG strings below are cached. Paths live on the CPU across GL context changes.
    private static final Map<String, Path> PATHS = new HashMap<>();
    private static final Paint STROKE =
            new Paint()
                    .setAntiAlias(true)
                    .setMode(PaintMode.STROKE)
                    .setStrokeWidth(1.65f)
                    .setStrokeCap(PaintStrokeCap.ROUND)
                    .setStrokeJoin(PaintStrokeJoin.ROUND);

    private static final Paint RING = new Paint().setAntiAlias(true).setMode(PaintMode.STROKE)
            .setStrokeWidth(2.5f).setStrokeCap(PaintStrokeCap.ROUND);

    private WzGlyph() {}

    static void quotaRing(HubDraw draw, float x, float y, float percent, int track, int color) {
        float radius = 13;
        draw.canvas().drawCircle(x, y, radius, RING.setColor(track));
        if (percent > 0) {
            draw.canvas().drawArc(x - radius, y - radius, x + radius, y + radius,
                    -90, percent * 3.6f, false, RING.setColor(color));
        }
        draw.save();
        draw.translate(x, y);
        draw.canvas().rotate(45);
        draw.roundRect(-3.6f, -3.6f, 7.2f, 7.2f, 1.4f, track);
        draw.restore();
    }

    static void draw(HubDraw draw, String name, float x, float y, float size, int color) {
        String data =
                switch (name) {
                    case "item" -> "M12 2.8l8.4 4.7v9L12 21.2l-8.4-4.7v-9zM3.6 7.5L12 12l8.4-4.5M12 12v9.2";
                    case "toolbox" ->
                            "M8.5 7V5.7c0-.9.7-1.7 1.7-1.7h3.6c1 0 1.7.8 1.7 1.7V7M5.6 7h12.8A2.6 2.6 0 0 1 21 9.6v7.8a2.6 2.6 0 0 1-2.6 2.6H5.6A2.6 2.6 0 0 1 3 17.4V9.6A2.6 2.6 0 0 1 5.6 7ZM3.2 12.2c5.2 1.8 12.4 1.8 17.6 0M10 12.7v2.5h4v-2.5";
                    case "select-block" ->
                            "M3 8V4h4m10 0h4v4M3 16v4h4m10 0h4v-4M7 9l5-2.7L17 9v5.7l-5 2.8-5-2.8Zm0 0 5 2.8L17 9m-5 2.8v5.7";
                    case "block-fields" -> "M5 4h14v16H5ZM8.5 8h.1m2.9 0h4m-7 4h.1m2.9 0h4m-7 4h.1m2.9 0h4";
                    case "block-field" -> "M8 4H4v16h4m8-16h4v16h-4M8 12h8m-4-3v6";
                    case "boat" ->
                            "M2 12.5l9.7 1.9L22 8.9l-3.4 9.3-10.8 1.2ZM5.9 13.2l6.4-9.1 4.6 7.3m-4.6-7.3-.6 10.3";
                    case "barrel" ->
                            "M6 10l1.2 9.7c2.8 1.7 6.8 1.7 9.6 0L18 10M6 10c3.1-1.4 8.9-1.4 12 0-2.8 2-9.2 2-12 0Zm3-.9L7.7 4.4l2.1-.6 1.3 5m1.2-.3.9-6 2.1.3-.8 5.8m.9.3 1.9-4 1.8.9-1.8 3.7M9.4 15l.4 2.8m4.7-2.8-.4 2.8";
                    case "bench" ->
                            "M2.8 7.6l9.1-4.4L21 7.6v10.1l-9.1 4.1-9.1-4.1Zm0 0 9.1 4.4L21 7.6M11.9 12v9.8M6 16l2.5 1.3m6.6-3.3 2.4-1.2v3.4M6.1 6.1l8.9 4.4m-5.4-6 9 4.3m-12.7.3 9-4.5";
                    case "torch" ->
                            "M9.3 12.9l-3.8 7.9 2.8 1.2 4.4-7.6m-4.3-2.1 6.6 3.1 1.5-3.1-6.6-3.1ZM11.5 9.5c-1.4-3 2.5-3.4 2.4-7.3 3.5 2.2 4.9 6.5 2.2 9.3";
                    case "back" -> "M10.2 5.5l-6.4 6.4 6.4 6.6M4.5 12H21";
                    case "history" -> "M4.1 8.3A8.5 8.5 0 1 1 3.5 15M3.5 3.9v5h5M12 7v5.4l3.6 2";
                    case "trash" -> "M4.5 6.5h15m-12.8 0 .8 13h9l.8-13M9 6.5V4h6v2.5M10 10v6m4-6v6";
                    case "send" -> "M12 20V4.5m-6 6.1 6-6.1 6 6.1";
                    case "book" ->
                            "M3 5.8c3.4-.9 6.3-.3 9 1.4 2.7-1.7 5.6-2.3 9-1.4v13c-3.2-.7-6.1-.4-9 1.2-2.9-1.6-5.8-1.9-9-1.2Zm9 1.4v12.6";
                    case "calculator" ->
                            "M6 3h12q1 0 1 1v16q0 1-1 1H6q-1 0-1-1V4q0-1 1-1ZM8 7h8M8 12h2m4 0h2m-8 4h2m4 0h2";
                    case "food-book" -> "M3 5c3-1 6 0 9 2 3-2 6-3 9-2v13c-3-1-6 0-9 2-3-2-6-3-9-2Zm9 2v13M6 13v2m3-5v5m6-3h3m-3 3h2";
                    case "structure" -> "M3 21h18M5 20V8h3V4h3v4h3V4h3v4h2v12m-9 0v-5h4v5";
                    case "structure-loot" -> "M3 10h18v10H3zM3 14h18M10 13h4v3h-4zM6 9V6l6-3 6 3v3";
                    case "trade" -> "M3 8h16l-4-4m6 12H5l4 4";
                    case "quest" -> "M5 3h14v18H5zM8 8l1 1 2-3m2 2h3m-8 6 1 1 2-3m2 2h3";
                    case "reward" -> "M3 9h18v4H3zM5 13v8h14v-8M12 9v12m0-12C4 9 5 2 9 4l3 5c7 0 8-6 4-5z";
                    case "wallet" -> "M3 7l13-4 1 4M3 7h18v13H3zM16 11h5v5h-5z";
                    case "store" -> "M3 10l2-6h14l2 6M3 10c0 3 4 3 5 0 2 3 5 3 7 0 2 3 5 3 6 0M5 13v8h14v-8m-10 8v-6h4v6";
                    case "store-search" -> "M2 9l2-5h12l2 5H2zm2 0v10h6m9-3 3 5m-3-7a3 3 0 1 1-6 0 3 3 0 1 1 6 0";
                    case "quote" -> "M5 2h14v20l-3-2-4 2-4-2-3 2zM8 7h8m-8 4h3m2 0h3m-8 4h8";
                    case "event-output" -> "M3 16l5-5 4 3 6-8m-5 0h5v5M4 19h15";
                    case "worldgen" -> "M3 20h18M8 20v-4H4l4-5H5l5-7 5 7h-3l4 5h-4v4m4-9 2-3 4 6h-3l3 4h-5";
                    case "spawn" -> "M5 14l-2-5 3-1 2 4m1-3-1-5 3-1 1 5m2 1 2-5 3 1-2 5m2 2 2-2 2 2-3 4M7 18q4-11 9-3l2 4q0 3-4 1l-2-1-3 1q-4 2-2-2Z";
                    case "biome" -> "M2 19l7-12 5 8 3-5 5 9ZM6 12l3 1 2-2";
                    case "conversion" -> "M3 8h12v6q0 5-6 5t-6-5ZM2 8h14m-2 4h3q3 0 3-3m-7-5h8m-3-2 3 2-3 2";
                    case "tag" -> "M3 4h8l10 10-7 7L3 10ZM7 7.5h.1";
                    case "pack-changes" -> "M3 7l8-4 8 4-8 4ZM3 7v9l7 4m1-9v5m3-1 2 2 5-6";
                    case "tags" -> "M3 4h7l8 8-6 6-9-9ZM7 7h.1M20 10l2 2-9 9";
                    case "locate" -> "M3 8V4h4m10 0h4v4m0 8v4h-4m-10 0H3v-4m6-7-3 3 3 3m6-6 3 3-3 3";
                    case "food" -> "M4 3v5q0 3 3 3t3-3V3M7 3v18m12 0V3q-5 2-5 10h5";
                    case "loot" -> "M3 10l2-5h14l2 5v9H3Zm0 1h7m4 0h7m-11-2h4v5h-4Z";
                    case "memory-search" -> "M13 4H4v15h5M7 8h4m-4 4h2M18 16l3 4m-2-7a4 4 0 1 1-8 0 4 4 0 1 1 8 0Z";
                    case "memory-read" -> "M5 3h14v18H5ZM9 3v7l2-1.5 2 1.5V3m-5 11h8m-8 3h6";
                    case "memory-write" -> "M5 3h14v18H5ZM8 7h8m-8 4h8m-8 4h5";
                    case "memory-add" -> "M18 9V3H4v18h7M7 7h7m-7 4h4m6 1v9m-4.5-4.5h9";
                    case "memory-edit" -> "M17 7V3H4v18h6M7 7h6m-6 4h4m1 9 1-4 6-6 3 3-6 6Z";
                    case "command" ->
                            "M5 5h14q2 0 2 2v10q0 2-2 2H5q-2 0-2-2V7q0-2 2-2Zm2 4 3 3-3 3m6 0h4";
                    case "chevron" -> "M8.5 5.5l6.2 6.5-6.2 6.5";
                    case "down" -> "M5.5 8.5l6.5 6.2 6.5-6.2";
                    case "new" ->
                            "M11 5H6a2 2 0 0 0-2 2v11a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2v-5M10 14l1-4 7-7 3 3-7 7Z";
                    default -> "M4.5 12.5l4.7 4.8L19.7 6.7";
                };
        Path path = PATHS.computeIfAbsent(data, Path::makeFromSVGString);
        draw.save();
        try {
            draw.translate(x, y);
            draw.canvas().scale(size / 24, size / 24);
            draw.canvas().drawPath(path, STROKE.setColor(color));
        } finally {
            draw.restore();
        }
    }
}
