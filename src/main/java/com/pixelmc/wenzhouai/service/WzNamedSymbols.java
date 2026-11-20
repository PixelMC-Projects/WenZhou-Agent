package com.pixelmc.wenzhouai.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Loaded on first bytecode query. Missing aliases always leave the original operand intact. */
final class WzNamedSymbols {
    private static final Map<String,String> NAMES = load();

    static String display(String owner, String symbol) {
        if (!owner.startsWith("net/minecraft/")) return symbol;
        String name = NAMES.get(symbol);
        if (name == null) return symbol;
        return switch (name) {
            case "getOffhandItem" -> symbol + "[" + name + "·副手物品]";
            case "getMainHandItem" -> symbol + "[" + name + "·主手物品]";
            default -> symbol + "[" + name + "]";
        };
    }

    private static Map<String,String> load() {
        var input = WzNamedSymbols.class.getResourceAsStream("/wenzhouai/mappings/forge-1.20.1.tsv");
        if (input == null) return Map.of();
        var names = new HashMap<String,String>();
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                int tab = line.indexOf('\t');
                if (tab > 0) names.put(line.substring(0, tab), line.substring(tab + 1));
            }
            return names;
        } catch (IOException unreadable) { return Map.of(); }
    }
    private WzNamedSymbols() {}
}
