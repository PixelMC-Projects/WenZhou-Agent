package com.pixelmc.wenzhouai.client;

import com.pixelmc.hub.client.render.HubDraw;
import java.util.ArrayList;
import java.util.List;

/** Keeps completed paragraphs laid out while the last paragraph grows with provider deltas. */
final class WzStreamingReply {
    private static final float SIZE = 15, LINE = SIZE * 1.85f;
    private static final class Paragraph {
        final StringBuilder raw = new StringBuilder();
        WzParagraph layout;
        int characters;
    }

    private final List<Paragraph> paragraphs = new ArrayList<>();
    private int received, count, visible;
    private long lastAdvance;
    private float measuredWidth;
    private HubDraw measuredDraw;

    boolean started() { return received > 0; }

    void append(String delta) {
        if (delta.isEmpty() || received >= 8000) return;
        if (paragraphs.isEmpty()) paragraphs.add(new Paragraph());
        int from = 0;
        int end = Math.min(delta.length(), 8000 - received);
        for (int pos = 0; pos <= end; pos++) {
            if (pos != end && delta.charAt(pos) != '\n') continue;
            Paragraph last = paragraphs.get(paragraphs.size() - 1);
            last.raw.append(delta, from, pos);
            last.layout = null;
            if (pos < end) paragraphs.add(new Paragraph());
            from = pos + 1;
        }
        received += end;
    }

    void reset() {
        paragraphs.clear();
        count = visible = received = 0;
        lastAdvance = 0;
    }

    boolean advance(HubDraw draw, float width, long now, boolean reduced, boolean complete) {
        if (!started()) return false;
        boolean remeasure = measuredDraw != draw || measuredWidth != width;
        measuredDraw = draw;
        measuredWidth = width;
        count = paragraphs.size() - 1;
        for (var paragraph : paragraphs) {
            if (remeasure || paragraph.layout == null) {
                var runs = WzAnswerMarkdown.format(List.of(new WzParagraph.Run(paragraph.raw.toString())));
                paragraph.layout = new WzParagraph(draw, width, SIZE, runs);
                paragraph.characters = runs.stream().mapToInt(r -> r.text().codePointCount(0, r.text().length())).sum();
            }
            count += paragraph.characters;
        }
        if (reduced) visible = count;
        else {
            int backlog = count - visible;
            long step = 1_000_000_000L / Math.max(65, backlog * 4);
            if (lastAdvance == 0) lastAdvance = now - step;
            int add = (int)Math.min(Math.max(0, backlog), Math.max(0L, now - lastAdvance) / step);
            visible = Math.min(count, visible + add);
            lastAdvance = visible == count ? now : lastAdvance + add * step;
        }
        return !complete || visible < count;
    }

    float height() { return paint(null, 0, 0, 0, 0, 0); }

    float paint(HubDraw draw, float x, float y, float top, float bottom, int ink) {
        int remaining = visible;
        float h = 0;
        for (var paragraph : paragraphs) {
            if (remaining <= 0) break;
            float fullHeight = paragraph.layout.lines.size() * LINE;
            if (remaining >= paragraph.characters && (draw == null || y + h + fullHeight < top || y + h > bottom)) {
                h += fullHeight;
                remaining -= paragraph.characters + 1;
                continue;
            }
            for (var line : paragraph.layout.lines) {
                if (remaining <= 0) break;
                boolean onScreen = draw != null && y + h + LINE >= top && y + h <= bottom;
                for (var part : line.parts()) {
                    String text = part.text();
                    int length = text.codePointCount(0, text.length());
                    int show = Math.min(remaining, length);
                    if (onScreen && show > 0) {
                        String shown = show == length ? text : text.substring(0, text.offsetByCodePoints(0, show));
                        draw.text(shown, x + part.x(), y + h + (LINE + SIZE * .65f) / 2, SIZE, ink, part.style().bold());
                    }
                    remaining -= show;
                    if (remaining == 0) break;
                }
                h += LINE;
            }
            remaining--; // newline between paragraphs
        }
        return h + 8;
    }
}
