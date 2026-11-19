/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client;

import com.ibm.icu.text.Transliterator;
import com.pixelmc.hub.client.ui.HubTextEditor;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class WzItemSearch {
    record Entry(ResourceLocation id, String name, String mod, Item item) {}
    private record Indexed(Entry entry, String pinyin, String initials) {
        int rank(String query) {
            String name = entry.name.toLowerCase(Locale.ROOT);
            String id = entry.id.toString();
            if (query.equals(name) || query.equals(id) || query.equals(entry.id.getPath())
                    || query.equals(pinyin) || query.equals(initials)) return 0;
            if (name.startsWith(query) || entry.id.getPath().startsWith(query)
                    || pinyin.startsWith(query) || initials.startsWith(query)) return 1;
            if (name.contains(query) || id.contains(query) || pinyin.contains(query)
                    || entry.mod.toLowerCase(Locale.ROOT).contains(query)) return 2;
            return 3;
        }
    }
    final HubTextEditor query = new HubTextEditor(false, 120);
    static final int ROWS = 5;
    boolean open;
    int first, selected;
    private Language language;
    private Iterator<Item> remaining;
    private final List<Entry> names = new ArrayList<>();
    private final Map<String, String> mods = new HashMap<>();
    private CompletableFuture<List<Indexed>> pending;
    private List<Indexed> indexed = List.of();
    private List<Entry> matches = List.of();
    private String lastQuery;

    void show() {
        open = true;
        first = selected = 0;
        query.setText("");
        if (language != Language.getInstance()) {
            language = Language.getInstance();
            remaining = ForgeRegistries.ITEMS.iterator();
            names.clear();
            indexed = List.of();
            pending = null;
        }
        lastQuery = null;
    }

    // Read game objects on the render thread in slices. Only immutable labels reach ICU.
    void refresh() {
        if (remaining != null) {
            long until = System.nanoTime() + 1_500_000;
            do {
                if (!remaining.hasNext()) {
                    remaining = null;
                    List<Entry> snapshot = List.copyOf(names);
                    pending = CompletableFuture.supplyAsync(() -> index(snapshot)).exceptionally(error -> List.of());
                    break;
                }
                Item item = remaining.next();
                if (item == Items.AIR) continue;
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                if (id == null) continue;
                String mod = mods.computeIfAbsent(id.getNamespace(), ns -> ModList.get()
                        .getModContainerById(ns).map(m -> m.getModInfo().getDisplayName()).orElse(ns));
                names.add(new Entry(id, item.getDescription().getString(), mod, item));
            } while (System.nanoTime() < until);
            lastQuery = null;
        }
        if (pending != null && pending.isDone()) {
            indexed = pending.join();
            pending = null;
            lastQuery = null;
        }
        String key = query.text().toLowerCase(Locale.ROOT).strip().replace(" ", "");
        if (key.equals(lastQuery)) return;
        lastQuery = key;
        if (key.isEmpty()) matches = names;
        else {
            List<Indexed> source = indexed;
            if (source.isEmpty()) source = names.stream().map(e -> new Indexed(e, "", "")).toList();
            List<List<Entry>> groups = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            for (Indexed row : source) {
                int score = row.rank(key);
                if (score < groups.size()) groups.get(score).add(row.entry);
            }
            matches = groups.stream().flatMap(List::stream).toList();
        }
        selected = Math.min(selected, Math.max(0, matches.size() - 1));
        first = Math.min(first, selected);
    }

    private static List<Indexed> index(List<Entry> entries) {
        Transliterator latin = Transliterator.getInstance("Han-Latin; NFD; [:Nonspacing Mark:] Remove; NFC; Lower");
        Map<Integer, String> readings = new HashMap<>();
        List<Indexed> result = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            StringBuilder full = new StringBuilder(), initials = new StringBuilder();
            entry.name.codePoints().forEach(cp -> {
                if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) {
                    full.appendCodePoint(Character.toLowerCase(cp));
                    return;
                }
                String syllable = readings.computeIfAbsent(cp, n -> latin.transliterate(Character.toString(n)));
                full.append(syllable);
                if (!syllable.isEmpty()) initials.append(syllable.charAt(0));
            });
            result.add(new Indexed(entry, full.toString(), initials.toString()));
        }
        return result;
    }

    void move(int delta) {
        selected = Math.max(0, Math.min(matches.size() - 1, selected + delta));
        first = Math.max(0, Math.min(selected, Math.max(first, selected - ROWS + 1)));
    }
    void changed() { selected = first = 0; }
    boolean loading() { return remaining != null || pending != null; }
    Entry at(int index) { return index >= 0 && index < matches.size() ? matches.get(index) : null; }
}
