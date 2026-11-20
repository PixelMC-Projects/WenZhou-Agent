package com.pixelmc.wenzhouai.service;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static com.pixelmc.wenzhouai.service.WzData.string;

/** One directory per Manager scope. Model arguments never become filesystem paths. */
final class WzGameplayMemory {
    record Scope(String server, String archive, String pack, String version) {
        static Scope current(JsonObject scope) {
            if (!"current".equals(string(scope, "metadataStatus"))) return null;
            if (!scope.has("packVersion") || !scope.get("packVersion").isJsonPrimitive()
                    || !scope.getAsJsonPrimitive("packVersion").isString()) return null;
            var result = new Scope(string(scope, "serverId"), string(scope, "archiveId"),
                    string(scope, "packName"), string(scope, "packVersion"));
            // Manager also permits a versioned display name with an empty version column.
            // Preserve both fields verbatim in the key; never infer a version by parsing a name.
            return result.server.isBlank() || result.archive.isBlank() || result.pack.isBlank() ? null : result;
        }
        List<String> parts() { return List.of(server, archive, pack, version); }
    }
    private static final Gson JSON = new Gson();
    private static final Set<String> FACETS = Set.of("obtain", "uses", "operation", "conditions", "effects", "drops");
    private final Path root;

    WzGameplayMemory(Path root) { this.root = root; }

    private Path file(Scope scope) {
        return root.resolve(digest(JSON.toJson(scope.parts()))).resolve("memory.md");
    }

    static String digest(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private Map<String, JsonObject> load(Path path) throws IOException {
        var notes = new LinkedHashMap<String, JsonObject>();
        if (Files.notExists(path)) return notes;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("# ")) continue;
            if (!line.startsWith("- ")) throw new IOException("Unknown gameplay memory format");
            JsonObject note = JsonParser.parseString(line.substring(2)).getAsJsonObject();
            String id = note.get("id").getAsString();
            if (notes.put(id, note) != null) throw new IOException("Duplicate gameplay note");
        }
        return notes;
    }

    synchronized JsonObject search(Scope scope, List<String> keywords, int page) throws IOException {
        var terms = keywords.stream().map(WzGameplayMemory::fold).filter(s -> !s.isBlank()).distinct().toList();
        if (page < 1 || keywords.size() > 6 || (!keywords.isEmpty() && terms.isEmpty()))
            return error("关键词或页码无效。");
        record Match(int score, JsonObject note) {}
        List<Match> matches = new ArrayList<>();
        for (var note : load(file(scope)).values()) {
            String subject = fold(string(note, "subject"));
            String headline = fold(string(note, "summary"));
            String body = fold(string(note, "detail") + " " + note.get("sources") + " " + note.get("keywords"));
            int score = 0;
            for (String term : terms) {
                if (subject.equals(term)) score += 10;
                else if (subject.contains(term) || headline.contains(term)) score += 5;
                else if (body.contains(term)) score += 2;
            }
            if (terms.isEmpty() || score > 0) matches.add(new Match(score, note));
        }
        matches.sort(Comparator.comparingInt(Match::score).reversed()
                .thenComparing(m -> string(m.note, "updatedAt"), Comparator.reverseOrder())
                .thenComparing(m -> string(m.note, "id")));
        JsonArray rows = new JsonArray();
        matches.stream().skip((page - 1L) * 6).limit(6).forEach(m -> {
            JsonObject row = new JsonObject();
            row.add("id", m.note.get("id"));
            row.add("summary", m.note.get("summary"));
            rows.add(row);
        });
        JsonObject result = new JsonObject();
        result.addProperty("total", matches.size());
        result.addProperty("page", page);
        result.add("matches", rows);
        return result;
    }

    synchronized JsonObject read(Scope scope, String id) throws IOException {
        JsonObject note = load(file(scope)).get(id);
        if (note == null) return error("当前整合包档案没有这条笔记。");
        JsonObject result = note.deepCopy();
        result.addProperty("pack", scope.pack);
        result.addProperty("version", scope.version);
        return result;
    }

    synchronized JsonObject save(Scope scope, String subject, String facet, String summary, String detail,
                                 List<String> keywords, List<String> sources) throws IOException {
        if (!FACETS.contains(facet) || !subject.matches("(item|tag|recipe|loot_table)/[a-z0-9_.-]+:[a-z0-9_./-]+")
                || !plain(summary, 48) || !plain(detail, 240) || sources.isEmpty() || sources.size() > 6
                || keywords.size() > 6 || keywords.stream().anyMatch(word -> !plain(word, 48)))
            return error("需要已查实的主题、一句概况、简短正文和本轮证据。");
        Path path = file(scope);
        Map<String, JsonObject> notes = load(path);
        String id = digest(JSON.toJson(scope.parts()) + "\n" + subject + "\n" + facet).substring(0, 20);
        boolean created = !notes.containsKey(id);
        if (created && notes.size() >= 4096) return error("当前档案的笔记已满。");
        var note = new JsonObject();
        String now = Instant.now().toString();
        note.addProperty("id", id);
        note.addProperty("subject", subject);
        note.addProperty("facet", facet);
        note.addProperty("summary", summary.strip());
        note.addProperty("detail", detail.strip());
        // Preserve previous search aliases when refreshing the same fact.
        Set<String> aliases = new LinkedHashSet<>(keywords);
        if (notes.containsKey(id)) notes.get(id).getAsJsonArray("keywords").forEach(k -> aliases.add(k.getAsString()));
        note.add("keywords", JSON.toJsonTree(aliases.stream().limit(16).toList()));
        note.add("sources", JSON.toJsonTree(sources));
        note.addProperty("createdAt", notes.containsKey(id) ? string(notes.get(id), "createdAt") : now);
        note.addProperty("updatedAt", now);
        notes.put(id, note);
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "notes-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                writer.write("# " + JSON.toJson(scope.parts()));
                writer.newLine();
                for (var value : notes.values()) {
                    writer.write("- " + JSON.toJson(value));
                    writer.newLine();
                }
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
        JsonObject result = new JsonObject();
        result.addProperty("saved", id);
        result.addProperty("created", created);
        return result;
    }

    private static String fold(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\s_#-]+", "");
    }
    static boolean plain(String text, int max) {
        return !text.isBlank() && text.length() <= max && text.chars().noneMatch(Character::isISOControl)
                && !text.contains("[[") && !text.contains("://");
    }
    static JsonObject error(String message) {
        return WenzhouReadTools.message("unavailable", message);
    }
}
