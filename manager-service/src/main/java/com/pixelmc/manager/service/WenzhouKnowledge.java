package com.pixelmc.manager.service;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Collator;
import java.time.Instant;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Read-only view of gameplay notes in the existing Manager mount. */
final class WenzhouKnowledge {
    private static final int PAGE_SIZE = 50;
    private final Path root;
    private final Map<Path, Archive> archives = new HashMap<>();
    private record Archive(List<?> stamp, List<JsonObject> rows) {}
    private static final Map<String,String> FACETS = Map.of("obtain","获取","uses","用途","operation","操作",
            "conditions","条件","effects","效果","drops","掉落");

    WenzhouKnowledge(Path root) { this.root = root; }

    synchronized JsonObject list(JsonObject input) throws IOException {
        List<JsonObject> all = new ArrayList<>();
        Set<Path> active = new HashSet<>();
        if (!Files.notExists(root, NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(root, NOFOLLOW_LINKS)) throw new IOException("知识目录不可读");
            try (var directories = Files.list(root)) {
                for (Path scope : directories.toList()) {
                    if (!scope.getFileName().toString().matches("[0-9a-f]{64}") || !Files.isDirectory(scope, NOFOLLOW_LINKS)) continue;
                    Path file = scope.resolve("memory.md");
                    if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) continue;
                    active.add(file);
                    var attrs = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
                    var stamp = List.of(attrs.lastModifiedTime(), attrs.size(), Objects.toString(attrs.fileKey()));
                    var cached = archives.get(file);
                    if (cached == null || !cached.stamp.equals(stamp)) {
                        cached = new Archive(stamp, read(file, null));
                        archives.put(file, cached);
                    }
                    all.addAll(cached.rows);
                }
            }
        }
        archives.keySet().retainAll(active);
        int count = all.size();
        String query = value(input,"query").strip().toLowerCase(Locale.ROOT);
        if (query.length() > 256) throw new IllegalArgumentException("搜索内容太长");
        all.removeIf(row -> !row.get("search").getAsString().contains(query));
        String sort = value(input,"sort");
        if (!Set.of("created","updated","name","pack").contains(sort)) sort = "created";
        String key = sort;
        Comparator<JsonObject> order = key.equals("created") || key.equals("updated")
                ? Comparator.comparingLong(row -> row.get(key).getAsLong())
                : Comparator.comparing(row -> value(row,key), Collator.getInstance(Locale.SIMPLIFIED_CHINESE));
        if (!value(input,"direction").equals("asc")) order = order.reversed();
        all.sort(order.thenComparing(row -> value(row,"scope")).thenComparing(row -> value(row,"id")));
        int requested = input.has("page") ? input.get("page").getAsBigDecimal().intValueExact() : 0;
        int page = Math.max(0,Math.min(requested, (all.size() - 1) / PAGE_SIZE));
        JsonArray rows = new JsonArray();
        all.stream().skip((long)page * PAGE_SIZE).limit(PAGE_SIZE).forEach(note -> {
            var copy = note.deepCopy();
            copy.remove("search");
            rows.add(copy);
        });
        var result = new JsonObject();
        result.addProperty("count",count);
        result.addProperty("total",all.size());
        result.addProperty("page",page);
        result.add("rows",rows);
        return result;
    }

    JsonObject detail(JsonObject input) throws IOException {
        String scope = value(input,"scope"), id = value(input,"id");
        if (!scope.matches("[a-f0-9]{64}") || !id.matches("[a-f0-9]{20}"))
            throw new IllegalArgumentException("无效的知识记录");
        Path directory = root.resolve(scope);
        Path file = directory.resolve("memory.md");
        if (Files.isDirectory(root, NOFOLLOW_LINKS) && Files.isDirectory(directory, NOFOLLOW_LINKS)
                && Files.isRegularFile(file, NOFOLLOW_LINKS)) {
            var notes = read(file,id);
            if (!notes.isEmpty()) return notes.get(0);
        }
        throw new IllegalArgumentException("知识记录已不存在");
    }

    private static List<JsonObject> read(Path file, String selected) throws IOException {
        var rows = new ArrayList<JsonObject>();
        try (var reader = Files.newBufferedReader(file)) {
            String header = reader.readLine();
            if (header == null || !header.startsWith("# ")) throw new IOException("知识档案格式无效");
            var parts = JsonParser.parseString(header.substring(2)).getAsJsonArray();
            String pack = (parts.get(2).getAsString() + " " + parts.get(3).getAsString()).strip();
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank()) continue;
                if (!line.startsWith("- ")) throw new IOException("知识记录格式无效");
                var note = JsonParser.parseString(line.substring(2)).getAsJsonObject();
                if (selected != null && !selected.equals(value(note,"id"))) continue;
                var row = new JsonObject();
                row.addProperty("scope",file.getParent().getFileName().toString());
                row.addProperty("id",value(note,"id"));
                row.addProperty("subject",value(note,"subject"));
                row.addProperty("pack",pack);
                String facet = value(note,"facet");
                row.addProperty("facet",FACETS.getOrDefault(facet,facet));
                row.addProperty("summary",value(note,"summary"));
                row.addProperty("name",value(note,"summary"));
                row.addProperty("created",Instant.parse(value(note,"createdAt")).toEpochMilli());
                row.addProperty("updated",Instant.parse(value(note,"updatedAt")).toEpochMilli());
                if (selected == null) {
                    row.addProperty("search",(pack+" "+value(note,"subject")+" "+value(note,"summary")+" "+note.get("keywords")).toLowerCase(Locale.ROOT));
                } else {
                    for (String field : List.of("detail","sources","keywords")) row.add(field,note.get(field));
                }
                rows.add(row);
                if (selected != null) break;
            }
        }
        return rows;
    }

    private static String value(JsonObject row, String key) {
        return row.has(key) ? row.get(key).getAsString() : "";
    }
}
