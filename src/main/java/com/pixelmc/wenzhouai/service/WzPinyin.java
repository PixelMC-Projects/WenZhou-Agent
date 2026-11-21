package com.pixelmc.wenzhouai.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Search readings generated from ICU Han-Latin because the dedicated server omits ICU. */
final class WzPinyin {
    private static final Pattern MARKS = Pattern.compile("\\p{Mn}+");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s'’]+");
    private record Table(Map<Integer, String> single, Map<Long, String> pairs) {}

    private static final class Lookup {
        private static final Table TABLE = load();
    }

    static String of(String value) {
        var text = new StringBuilder(value.length());
        Table readings = Lookup.TABLE;
        for (int offset = 0; offset < value.length(); ) {
            int point = value.codePointAt(offset);
            offset += Character.charCount(point);
            String reading = readings.single().get(point);
            if (reading != null) {
                int nextOffset = offset;
                if (nextOffset < value.length() && value.charAt(nextOffset) == ' ') nextOffset++;
                if (nextOffset < value.length()) {
                    reading = readings.pairs().getOrDefault(pair(point, value.codePointAt(nextOffset)), reading);
                }
                text.append(reading);
            } else {
                text.appendCodePoint(point);
            }
        }
        String nfd = Normalizer.normalize(text, Normalizer.Form.NFD);
        String bare = MARKS.matcher(nfd).replaceAll("");
        return SEPARATORS.matcher(Normalizer.normalize(bare, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static long pair(int current, int next) {
        return ((long) current << 21) | next;
    }

    private static Table load() {
        var source = WzPinyin.class.getResourceAsStream("/wenzhouai/pinyin/han-latin.tsv");
        var single = new HashMap<Integer, String>();
        var pairs = new HashMap<Long, String>();
        try (var reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(source, "Missing Han-Latin search table"), StandardCharsets.UTF_8))) {
            for (String row; (row = reader.readLine()) != null; ) {
                String[] fields = row.split("\t", -1);
                int first = Integer.parseInt(fields[0], 16);
                if (fields.length == 2) {
                    single.put(first, fields[1]);
                } else {
                    pairs.put(pair(first, Integer.parseInt(fields[1], 16)), fields[2]);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Unreadable Han-Latin search table", failure);
        }
        return new Table(Map.copyOf(single), Map.copyOf(pairs));
    }

    private WzPinyin() {}
}
