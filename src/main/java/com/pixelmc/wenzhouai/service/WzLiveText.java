package com.pixelmc.wenzhouai.service;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Filters protocol markers even when split across provider chunks and batches Minecraft packets. */
final class WzLiveText implements WzCompletionStream.Preview {
    private static final Pattern INTERNAL = Pattern.compile("<\\s*/?\\s*[|｜]");
    private final Function<String, String> itemName;
    private final BiConsumer<String, String> send;
    private final StringBuilder pending = new StringBuilder(), ready = new StringBuilder();
    private long lastSend;
    private boolean hidden, sent;
    private int rawBytes;
    private String highSurrogate = "";

    WzLiveText(Function<String, String> itemName, BiConsumer<String, String> send) {
        this.itemName = itemName;
        this.send = send;
    }

    @Override public void append(String fragment) {
        if (hidden) return;
        String raw = highSurrogate + fragment;
        highSurrogate = "";
        int end = 0;
        while (end < raw.length()) {
            int cp = raw.codePointAt(end);
            if (Character.isHighSurrogate(raw.charAt(end)) && end + 1 == raw.length()) {
                highSurrogate = raw.substring(end);
                break;
            }
            int bytes = cp < 128 ? 1 : cp < 2048 ? 2 : cp < 65536 ? 3 : 4;
            if (rawBytes + bytes > 8000) { rawBytes = 8000; break; }
            rawBytes += bytes;
            end += Character.charCount(cp);
        }
        pending.append(raw, 0, end);
        int i = 0;
        while (i < pending.length()) {
            char c = pending.charAt(i);
            if (c == '[') {
                if (i + 1 == pending.length()) break;
                if (pending.charAt(i + 1) == '[') {
                    int tokenEnd = pending.indexOf("]]", i + 2);
                    if (tokenEnd < 0) break;
                    String token = pending.substring(i, tokenEnd + 2);
                    if (token.equals("[[wz-summary]]")) { hidden = true; break; }
                    if (token.startsWith("[[item:") || token.startsWith("[[stack:"))
                        ready.append(itemName.apply(token));
                    i = tokenEnd + 2;
                    continue;
                }
            } else if (c == '<') {
                var marker = INTERNAL.matcher(pending).region(i, pending.length());
                if (marker.lookingAt()) { reset(); hidden = true; return; }
                if (marker.hitEnd()) break;
            }
            ready.append(c);
            i++;
        }
        if (hidden) pending.setLength(0);
        else pending.delete(0, i);
        if (System.nanoTime() - lastSend >= TimeUnit.MILLISECONDS.toNanos(45)) flushReady();
    }

    private void flushReady() {
        if (ready.isEmpty()) return;
        send.accept("answer.delta", ready.toString());
        sent = true;
        lastSend = System.nanoTime();
        ready.setLength(0);
    }

    @Override public void flush() {
        if (!hidden && pending.toString().equals("[")) ready.append('[');
        pending.setLength(0);
        flushReady();
    }

    @Override public void reset() {
        if (sent) send.accept("answer.reset", "");
        pending.setLength(0);
        ready.setLength(0);
        sent = false;
        highSurrogate = "";
        rawBytes = 0;
    }
}
