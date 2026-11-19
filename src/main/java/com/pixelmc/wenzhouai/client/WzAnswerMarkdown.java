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
