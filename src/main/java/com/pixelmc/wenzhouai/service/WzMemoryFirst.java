package com.pixelmc.wenzhouai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

final class WzMemoryFirst {
    private final Set<String> terms = new LinkedHashSet<>();
    private boolean checked;

    WzMemoryFirst(boolean enabled, Collection<String> references) {
        checked = !enabled;
        references.forEach(this::add);
    }

    void add(String term) {
        if (!term.isBlank() && term.length() <= 240 && terms.size() < 6) terms.add(term);
    }

    void explicitSearch() { checked = true; }

    JsonObject search(WenzhouChat.DescribedLookup lookup, WzDiagnostics trace) throws Exception {
        if (checked || terms.isEmpty()) return null;
        checked = true;
        var args = new JsonObject();
        var keywords = new JsonArray();
        terms.forEach(keywords::add);
        args.add("keywords", keywords);
        long start = System.nanoTime();
        try {
            var result = lookup.query("memory", "search_memory", args.toString(), 1, "");
            trace.tool("search_memory", args.toString(), start, result, null);
            return result;
        } catch (Exception failure) {
            trace.tool("search_memory", args.toString(), start, null, failure);
            throw failure;
        }
    }

    JsonObject before(String tool, JsonObject function, String question, WenzhouChat.DescribedLookup lookup,
                      WzDiagnostics trace) throws Exception {
        if (checked || tool.endsWith("_memory") || tool.equals("find_item") || tool.equals("calculate")) return null;
        var args = JsonParser.parseString(WzData.string(function, "arguments")).getAsJsonObject();
        add(WzData.string(args, "id"));
        if (terms.isEmpty()) add(question.replaceAll("[？?。！!]$", "").strip());
        if (terms.isEmpty()) add(WzData.string(args, "purpose"));
        return search(lookup, trace);
    }
}
