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
