package com.pixelmc.wenzhouai.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Parses the display subset after projection, so emphasis can span a real item reference. */
final class WzAnswerMarkdown {
    private static final Pattern TOKEN = Pattern.compile(
            "(?m)^(#{1,6})[ \\t]+([^\\n]*)|^([ \\t]*)[-*+][ \\t]+|\\*\\*([^*\\n]+)\\*\\*|__([^_\\n]+)__|\u0060([^\u0060\\n]+)\u0060");

    // Older replies sometimes label both the list entry and the immediately following item.
    static String beforeItem(String text, String localName, String savedName) {
        String end = text.stripTrailing();
        if (text.substring(end.length()).contains("\n")) return text;
        for (String name : List.of(localName, savedName)) {
            if (name.isBlank() || !end.endsWith(name)) continue;
            String prefix = end.substring(0, end.length() - name.length());
            String boundary = prefix.stripTrailing();
            if (boundary.isEmpty() || prefix.endsWith("\n") || boundary.endsWith("、")
                    || boundary.endsWith("，") || boundary.endsWith(",")
                    || boundary.equals("-") || boundary.endsWith("\n-")
                    || boundary.equals("•") || boundary.endsWith("\n•")) return prefix;
        }
        return text;
    }

    static List<WzParagraph.Run> format(List<WzParagraph.Run> source) {
        StringBuilder text = new StringBuilder();
        Map<Integer, WzParagraph.Run> references = new HashMap<>();
        for (var run : source) {
            if (run.item() != null || run.muted()) {
                references.put(text.length(), run);
                text.append('\ufffc');
            } else text.append(run.text());
        }
        var result = new ArrayList<WzParagraph.Run>();
        parse(text, references, result, 0, text.length(), false);
        return result;
    }

    private record Span(int start, int end) {}

    // Lay out tables on the same measured lines that drive scrolling, reveal and item hit testing.
    static WzParagraph layout(com.pixelmc.hub.client.render.HubDraw draw, float width, float size,
                              List<WzParagraph.Run> source) {
        StringBuilder text = new StringBuilder();
        Map<Integer, WzParagraph.Run> refs = new HashMap<>();
        for (var run : source) {
            if (run.item() == null && !run.muted()) text.append(run.text().replace("\r\n", "\n"));
            else { refs.put(text.length(), run); text.append('\ufffc'); }
        }
        String[] lines = text.toString().split("\n", -1);
        var result = new ArrayList<WzParagraph.Line>();
        int pos = 0;
        for (int i = 0; i < lines.length; i++) {
            var cells = cells(lines[i], pos);
            var divider = i + 1 < lines.length ? cells(lines[i+1], pos + lines[i].length()+1) : List.<Span>of();
            boolean table = !cells.isEmpty() && cells.size() == divider.size()
                    && divider.stream().allMatch(v -> text.substring(v.start, v.end).matches(":?-{3,}:?"));
            if (!table) {
                var runs = new ArrayList<WzParagraph.Run>();
                parse(text, refs, runs, pos, pos + lines[i].length(), false);
                result.addAll(new WzParagraph(draw,width,size,runs).lines);
                pos += lines[i].length()+1;
                continue;
            }
            var rows = new ArrayList<List<Span>>();
            rows.add(cells);
            pos += lines[i].length() + lines[++i].length() + 2;
            while (i+1 < lines.length) {
                var row = cells(lines[i+1], pos);
                if (row.size() != cells.size()) break;
                rows.add(row);
                pos += lines[++i].length()+1;
            }
            int cols = cells.size();
            float colWidth = width / cols;
            for (var row : rows) {
                var columns = new ArrayList<WzParagraph>();
                int height = 1;
                for (var cell : row) {
                    var runs = new ArrayList<WzParagraph.Run>();
                    parse(text, refs, runs, cell.start, cell.end, row == rows.get(0));
                    for (int j = 0; j < runs.size(); j++) {
                        var run = runs.get(j);
                        if (run.item() == null) continue;
                        String label = run.text();
                        while (!label.isEmpty() && 22 + draw.textWidth(label+"…",size,run.bold()) > colWidth-6)
                            label = label.substring(0,label.offsetByCodePoints(label.length(),-1));
                        if (!label.equals(run.text()))
                            runs.set(j,new WzParagraph.Run(label+"…",run.bold(),run.muted(),run.link(),run.item()));
                    }
                    var column = new WzParagraph(draw,Math.max(1,colWidth-6),size,runs);
                    columns.add(column);
                    height = Math.max(height,column.lines.size());
                }
                for (int line = 0; line < height; line++) {
                    var parts = new ArrayList<WzParagraph.Part>();
                    for (int col = 0; col < cols; col++) {
                        var column = columns.get(col);
                        if (line >= column.lines.size()) continue;
                        for (var part : column.lines.get(line).parts())
                            parts.add(new WzParagraph.Part(part.style(),part.text(),col*colWidth+part.x(),part.width()));
                    }
                    result.add(new WzParagraph.Line(List.copyOf(parts)));
                }
            }
        }
        return new WzParagraph(result);
    }

    private static List<Span> cells(String line, int offset) {
        if (line.indexOf('|') < 0) return List.of();
        int first = 0, end = line.length();
        while (first < end && Character.isWhitespace(line.charAt(first))) first++;
        while (end > first && Character.isWhitespace(line.charAt(end-1))) end--;
        if (first < end && line.charAt(first) == '|') first++;
        if (end > first && line.charAt(end-1) == '|' && (end < 2 || line.charAt(end-2) != '\\')) end--;
        var result = new ArrayList<Span>();
        for (int i = first; i <= end; i++) {
            if (i != end && (line.charAt(i) != '|' || i > first && line.charAt(i-1) == '\\')) continue;
            int left = first, right = i;
            while (left < right && Character.isWhitespace(line.charAt(left))) left++;
            while (right > left && Character.isWhitespace(line.charAt(right-1))) right--;
            result.add(new Span(offset+left,offset+right));
            first = i+1;
        }
        return result;
    }

    private static void parse(CharSequence text, Map<Integer, WzParagraph.Run> refs,
            List<WzParagraph.Run> out, int start, int end, boolean heading) {
        var match = TOKEN.matcher(text).region(start, end).useAnchoringBounds(false);
        int pos = start;
        while (match.find()) {
            emit(text, refs, out, pos, match.start(), heading);
            if (match.group(1) != null) {
                parse(text, refs, out, match.start(2), match.end(2), true);
            } else if (match.group(3) != null) {
                out.add(new WzParagraph.Run(match.group(3) + "• "));
            } else {
                int value = match.start(4) >= 0 ? 4 : match.start(5) >= 0 ? 5 : 6;
                emit(text, refs, out, match.start(value), match.end(value), heading || value != 6);
            }
            pos = match.end();
        }
        emit(text, refs, out, pos, end, heading);
    }

    private static void emit(CharSequence text, Map<Integer, WzParagraph.Run> refs,
            List<WzParagraph.Run> out, int from, int end, boolean bold) {
        int start = from;
        for (int i = from; i < end; i++) {
            if (text.charAt(i) != '\ufffc') continue;
            var ref = refs.get(i);
            if (ref == null) continue;
            plain(text, out, start, i, bold);
            out.add(new WzParagraph.Run(ref.text(), bold, ref.muted(), ref.link(), ref.item()));
            start = i + 1;
        }
        plain(text, out, start, end, bold);
    }

    private static void plain(CharSequence text, List<WzParagraph.Run> out, int start, int end, boolean bold) {
        if (end > start) out.add(new WzParagraph.Run(text.subSequence(start, end).toString(), bold, false, null, null));
    }

    private WzAnswerMarkdown() {}
}
