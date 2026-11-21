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
        result.addProperty("evidenceBound", WzData.flag(note, "evidenceBound"));
        result.addProperty("pack", scope.pack);
        result.addProperty("version", scope.version);
        return result;
    }

    synchronized JsonObject save(Scope scope, List<WzMemoryFacts.Fact> facts) throws IOException {
        if (facts.isEmpty() || facts.size() > 6)
            return invalid("facts", "需要 1–6 个事实。");
        var first = facts.get(0);
        String subject = first.subject();
        String facet = first.facet();
        if (WzMemoryFacts.subject(subject).isEmpty() || !validFacet(facet))
            return invalid("facts", "事实主题或方面无效。");
        if (facts.stream().anyMatch(fact -> !subject.equals(fact.subject()) || !facet.equals(fact.facet())
                || !plain(fact.summary(), 48) || !plain(fact.detail(), 240) || !plain(fact.provenance(), 640)))
            return invalid("facts", "所选事实须属于同一主题和方面。");
        String summary = first.summary();
        String detail = String.join(" ", facts.stream().map(WzMemoryFacts.Fact::detail).distinct().toList());
        if (!plain(detail, 240)) return invalid("facts", "事实正文合计超过 240 字，请减少选中的事实。");
        List<String> sources = facts.stream().map(WzMemoryFacts.Fact::provenance).distinct().toList();
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
        Set<String> aliases = new LinkedHashSet<>();
        String resource = subject.substring(subject.indexOf('/') + 1);
        if (plain(resource, 48)) aliases.add(resource);
        if (subject.startsWith("item/")) {
            String name = WenzhouReadTools.itemDisplayName(resource);
            if (name != null && plain(name, 48)) aliases.add(name);
        }
        var previous = notes.get(id);
        if (previous != null && WzData.flag(previous, "evidenceBound"))
            WzMemoryFacts.array(previous, "keywords").forEach(k -> {
                if (k instanceof JsonPrimitive p && p.isString() && plain(p.getAsString(), 48)) aliases.add(p.getAsString());
            });
        note.add("keywords", JSON.toJsonTree(aliases.stream().limit(16).toList()));
        note.add("sources", JSON.toJsonTree(sources));
        note.addProperty("evidenceBound", true);
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
        // The Manager snapshot only stats this directory. Notes themselves are replaced in a scope subdirectory.
        try {
            Files.setLastModifiedTime(root, java.nio.file.attribute.FileTime.from(Instant.now()));
        } catch (IOException ignored) {
            // The note is already committed. The next explicit list read still sees its current file.
        }
        result.addProperty("saved", id);
        result.addProperty("created", created);
        result.addProperty("summary", summary);
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
    static boolean validFacet(String facet) { return FACETS.contains(facet); }

    static JsonObject invalidFields(Map<String,String> issues) {
        var first = issues.entrySet().iterator().next();
        var result = invalid(first.getKey(), first.getValue());
        result.add("errors", JSON.toJsonTree(issues));
        var all = new StringJoiner(" ");
        issues.forEach((key, reason) -> all.add(key + "：" + reason));
        result.addProperty("message", all.toString());
        return result;
    }

    static JsonObject invalid(String field, String reason) {
        var result = WenzhouReadTools.message("invalid_request",field+"："+reason);
        result.addProperty("field",field);
        return result;
    }
    static JsonObject error(String message) {
        return WenzhouReadTools.message("unavailable", message);
    }
}
