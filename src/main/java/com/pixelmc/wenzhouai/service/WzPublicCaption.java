package com.pixelmc.wenzhouai.service;

import java.util.regex.Pattern;

/** A player-facing label is short prose, never shell input, a path, or provider reasoning. */
final class WzPublicCaption {
    private static final Pattern PROSE = Pattern.compile("[\\p{IsHan}\\p{L}\\p{N} ，。；、（）「」《》·×+-]{1,48}");
    private static final Pattern TECHNICAL = Pattern.compile(
            "(?i)\\b(?:javap|bash|shell|classes|recipe-json|grep|rg|unzip|curl|sudo)\\b|[A-Za-z0-9_-]{24,}|sk-[A-Za-z0-9-]{6,}");

    static String clean(String value) {
        String text = value == null ? "" : value.strip();
        if (!PROSE.matcher(text).matches() || TECHNICAL.matcher(text).find()) return "";
        return text;
    }

    static String command(String purpose, String command) {
        String label = clean(purpose);
        if (!label.isEmpty()) return label;
        if (command.startsWith("recipe-json ")) return "查看配方的材料和工序";
        if (command.startsWith("classes ")) return "定位与玩法有关的实现";
        if (command.startsWith("javap ")) return "查看模组的交互规则";
        return "搜索本服玩法资料";
    }

    private WzPublicCaption() {}
}
