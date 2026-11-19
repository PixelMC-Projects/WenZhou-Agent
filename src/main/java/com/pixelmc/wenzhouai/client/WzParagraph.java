package com.pixelmc.wenzhouai.client;

import com.google.gson.JsonObject;
import com.pixelmc.hub.client.render.HubDraw;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Measures formatted paragraphs once in Hub canvas units. Positions are relative to the paragraph. */
final class WzParagraph {
    record Run(String text, boolean bold, boolean muted, String link, JsonObject item) {
        Run(String text) {
            this(text, false, false, null, null);
        }
    }

    record Part(Run style, String text, float x, float width) {}

    record Line(List<Part> parts) {}

    final List<Line> lines = new ArrayList<>();
    private List<Part> row = new ArrayList<>();
    private float cursor;

    WzParagraph(HubDraw draw, float width, float size, List<Run> runs) {
        width = Math.max(1, width);
        for (Run run : runs) {
            if (run.item != null) {
                float itemWidth = 22 + draw.textWidth(run.text, size, run.bold);
                if (cursor > 0 && cursor + itemWidth > width) nextLine();
                add(run, run.text, itemWidth);
                continue;
            }
            String[] paragraphs = run.text.replace("\r\n", "\n").split("\n", -1);
            for (int i = 0; i < paragraphs.length; i++) {
                if (i > 0) nextLine();
                wrap(draw, width, size, run, paragraphs[i]);
            }
        }
        nextLine();
    }

    private void wrap(HubDraw draw, float width, float size, Run run, String value) {
        BreakIterator words = BreakIterator.getLineInstance(Locale.SIMPLIFIED_CHINESE);
        words.setText(value);
        int start = words.first();
        // Buffer each run on a line so a long Chinese paragraph is drawn in lines, not characters.
        StringBuilder line = new StringBuilder();
        float measured = 0;
        for (int end = words.next(); end != BreakIterator.DONE; start = end, end = words.next()) {
            String word = value.substring(start, end);
            float span = draw.textWidth(word, size, run.bold);
            if (cursor + measured > 0 && cursor + measured + span > width) {
                add(run, line.toString(), measured);
                nextLine();
                line.setLength(0);
                measured = 0;
            }
            if (span <= width) {
                line.append(word);
                measured += span;
            } else {
                for (int offset = 0; offset < word.length(); ) {
                    int last = word.offsetByCodePoints(offset, 1);
                    String glyph = word.substring(offset, last);
                    float advance = draw.textWidth(glyph, size, run.bold);
                    if (line.length() > 0 && measured + advance > width) {
                        add(run, line.toString(), measured);
                        nextLine();
                        line.setLength(0);
                        measured = 0;
                    }
                    line.append(glyph);
                    measured += advance;
                    offset = last;
                }
            }
        }
        add(run, line.toString(), measured);
    }

    private void add(Run style, String text, float width) {
        if (text.isEmpty() && style.item == null) return;
        row.add(new Part(style, text, cursor, width));
        cursor += width;
    }

    private void nextLine() {
        lines.add(new Line(List.copyOf(row)));
        row = new ArrayList<>();
        cursor = 0;
    }
}
