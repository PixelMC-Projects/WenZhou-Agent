package com.pixelmc.wenzhouai.client;

import com.google.gson.*;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.pixelmc.hub.client.render.HubDraw;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * Wraps Web-format rich answers, then draws their real item models after Skija restores the game
 * framebuffer.
 */
public final class HubWenzhouAnswer {
    private record Paragraph(WzParagraph value, boolean note) {}

    private record Card(
            JsonObject items,
            JsonArray slots,
            int columns,
            String output,
            int count,
            boolean shapeless,
            JsonObject alternatives) {}

    private record Layout(
            int id, float width, Paragraph body, List<Card> cards, Paragraph note, int steps) {}

    public record ItemFocus(String id, String label, float x, float y, float width, float height) {
        boolean contains(float px, float py) {
            return px >= x && py >= y && px < x + width && py < y + height;
        }
    }

    private record ItemAt(
            JsonObject data,
            List<JsonObject> variants,
            float x,
            float y,
            float size,
            ItemFocus focus) {}

    private final IdentityHashMap<JsonObject, Layout> layouts = new IdentityHashMap<>();
    private final Map<JsonObject, ItemStack> stacks = new IdentityHashMap<>();
    private final List<ItemAt> icons = new ArrayList<>();
    private final boolean hasRegistry = registryAvailable();
    private Language measuredLanguage;
    private float clipX, clipY, clipW, clipH, revealBottom = Float.MAX_VALUE;
    private float pointerX, pointerY, renderLeft, renderRight = Float.MAX_VALUE;
    private int nextLayout;
    private String focusId, pinnedId, dismissedId, completePrefix;
    private BiConsumer<ItemFocus, Runnable> register;

    public void beginFrame(
            float x,
            float y,
            float w,
            float h,
            String focusId,
            BiConsumer<ItemFocus, Runnable> register) {
        beginFrame();
        this.clipX = x;
        this.clipY = y;
        this.clipW = w;
        this.clipH = h;
        if (focusId != null && focusId.startsWith("wz-item-") && !focusId.equals(this.focusId))
            pinnedId = null;
        this.focusId = focusId;
        finishFocusedReveal(focusId);
        this.register = register;
    }

    public void beginFrame() {
        clipX = clipY = 0;
        clipW = clipH = Float.MAX_VALUE;
        register = null;
        icons.clear();
    }

    public void reset() {
        pinnedId = dismissedId = completePrefix = null;
        icons.clear();
        layouts.clear();
        stacks.clear();
    }

    public float render(
            HubDraw d,
            JsonObject reply,
            float x,
            float y,
            float width,
            float mx,
            float my,
            float visibleSeconds,
            boolean reduced) {
        if (reply == null || width <= 0) return 0;
        Layout l = arrange(d, reply, width);
        float height = reveal(l, visibleSeconds, reduced);
        pointerX = mx;
        pointerY = my;
        revealBottom = y + height;
        renderLeft = x;
        renderRight = x + width;
        d.save();
        try {
            d.clipRoundRect(x, y, width, height, 0);
            float bottom = paragraph(d, l.body, l.id + "-body", x, y + 8);
            for (int i = 0; i < l.cards.size() && bottom < revealBottom; i++) {
                float gap = i == 0 ? 4 : 8;
                bottom = recipe(d, l.cards.get(i), l.id + "-recipe-" + i, x, bottom + gap, width);
            }
            if (l.note != null && bottom < revealBottom)
                paragraph(d, l.note, l.id + "-note", x, bottom + 8);
        } finally {
            revealBottom = Float.MAX_VALUE;
            d.restore();
        }
        return height;
    }

    public float height(
            HubDraw d, JsonObject reply, float width, float visibleSeconds, boolean reduced) {
        return reply == null || width <= 0
                ? 0
                : reveal(arrange(d, reply, width), visibleSeconds, reduced);
    }

    private Layout arrange(HubDraw d, JsonObject reply, float width) {
        Language language = hasRegistry ? Language.getInstance() : null;
        if (language != measuredLanguage) {
            measuredLanguage = language;
            layouts.clear();
        }
        Layout l = layouts.get(reply);
        if (l == null || l.width != width) {
            if (layouts.size() >= 512) {
                layouts.clear();
                stacks.clear();
            }
            l = layout(d, reply, width, l == null ? nextLayout++ : l.id);
            layouts.put(reply, l);
        }
        return l;
    }

    private void finishFocusedReveal(String id) {
        if (id == null || !id.startsWith("wz-item-")) return;
        int suffix = id.indexOf('-', 8);
        if (suffix > 8) completePrefix = id.substring(0, suffix + 1);
    }

    private float reveal(Layout layout, float seconds, boolean reduced) {
        if (completePrefix != null && completePrefix.equals("wz-item-" + layout.id + "-"))
            return stepHeight(layout, layout.steps);
        if (reduced || seconds < 0 || seconds >= 4.5f) return stepHeight(layout, layout.steps);
        int stride = Math.max(1, (layout.steps + 23) / 24);
        int step = (int) (seconds / .18f);
        int previous = Math.min(layout.steps, step * stride);
        int next = Math.min(layout.steps, previous + stride);
        float linear = Math.min(1, (seconds - step * .18f) / .15f);
        float eased = 1 - (1 - linear) * (1 - linear) * (1 - linear);
        return stepHeight(layout, previous)
                + (stepHeight(layout, next) - stepHeight(layout, previous)) * eased;
    }

    private static float stepHeight(Layout layout, int steps) {
        int lines = Math.min(steps, layout.body.value.lines.size());
        float height = 8 + lines * 15 * 1.85f;
        int remaining = steps - lines;
        for (int i = 0; i < layout.cards.size() && remaining-- > 0; i++)
            height += (i == 0 ? 4 : 8) + cardHeight(layout.cards.get(i));
        if (layout.note != null && remaining > 0)
            height += 8 + Math.min(remaining, layout.note.value.lines.size()) * 13 * 1.85f;
        return height;
    }

    private static float cardHeight(Card card) {
        int rows = (card.slots.size() + card.columns - 1) / card.columns;
        return rows * 29 - 2 + 26 + (card.shapeless ? 17 : 0);
    }

    private static Card parseCard(JsonObject recipe, JsonObject items) {
        if (recipe == null
                || items == null
                || !recipe.has("slots")
                || !recipe.get("slots").isJsonArray()) return null;
        JsonArray slots = recipe.getAsJsonArray("slots");
        int columns = number(recipe, "columns", 3);
        if (columns <= 0 || columns > 3 || slots.isEmpty() || slots.size() > 9) return null;
        return new Card(
                items,
                slots,
                columns,
                string(recipe.get("output")),
                number(recipe, "count", 1),
                recipe.has("shapeless") && recipe.get("shapeless").getAsBoolean(),
                object(recipe, "slotAlternatives"));
    }

    private Layout layout(HubDraw d, JsonObject reply, float width, int id) {
        JsonObject items = object(reply, "items");
        Paragraph body = paragraph(d, reply.get("text"), items, width, false);
        Paragraph note =
                reply.has("note") && !reply.get("note").isJsonNull()
                        ? paragraph(d, reply.get("note"), items, width, true)
                        : null;
        List<Card> cards = new ArrayList<>();
        if (reply.has("recipes") && reply.get("recipes").isJsonArray()) {
            for (JsonElement element : reply.getAsJsonArray("recipes")) {
                if (!element.isJsonObject()) continue;
                Card card = parseCard(element.getAsJsonObject(), items);
                if (card != null) cards.add(card);
            }
        }
        if (cards.isEmpty()) {
            Card single = parseCard(object(reply, "recipe"), items);
            if (single != null) cards.add(single);
        }
        return new Layout(
                id,
                width,
                body,
                List.copyOf(cards),
                note,
                body.value.lines.size()
                        + cards.size()
                        + (note == null ? 0 : note.value.lines.size()));
    }

    private Paragraph paragraph(
            HubDraw d, JsonElement value, JsonObject items, float w, boolean note) {
        List<WzParagraph.Run> runs = new ArrayList<>();
        if (value != null && value.isJsonArray()) {
            for (JsonElement part : value.getAsJsonArray()) {
                if (!part.isJsonObject()) {
                    addLocalized(runs, string(part));
                    continue;
                }
                JsonObject ref = part.getAsJsonObject();
                String key = string(ref.get("item"));
                JsonObject data = object(items, key);
                String label = data == null ? string(ref.get("label")) : displayName(data);
                if (data != null && !runs.isEmpty()) {
                    int last = runs.size() - 1;
                    WzParagraph.Run previous = runs.get(last);
                    if (previous.item() == null && !previous.muted()) {
                        String prefix =
                                WzAnswerMarkdown.beforeItem(
                                        previous.text(), label, string(data.get("name")));
                        if (!prefix.equals(previous.text()))
                            runs.set(last, new WzParagraph.Run(prefix));
                    }
                }
                runs.add(
                        new WzParagraph.Run(
                                label.isBlank() ? key : label, false, false, null, data));
                String explanation = string(ref.get("explanation"));
                if (!explanation.isEmpty())
                    runs.add(new WzParagraph.Run("（" + explanation + "）", false, true, null, null));
            }
        } else addLocalized(runs, string(value));
        return new Paragraph(WzAnswerMarkdown.layout(d, w, note ? 13 : 15, runs), note);
    }

    private static final java.util.regex.Pattern ITEM_KEY =
            java.util.regex.Pattern.compile(
                    "(?<![a-z0-9_.])(?:item|block)\\.([a-z0-9_-]+)\\.([a-z0-9_/.-]*[a-z0-9_-])");

    private void addLocalized(List<WzParagraph.Run> runs, String text) {
        var keys = ITEM_KEY.matcher(text);
        int end = 0;
        while (hasRegistry && keys.find()) {
            var id = ResourceLocation.tryParse(keys.group(1) + ":" + keys.group(2));
            if (id == null || !ForgeRegistries.ITEMS.containsKey(id)) continue;
            var stack = new ItemStack(ForgeRegistries.ITEMS.getValue(id));
            if (!stack.getDescriptionId().equals(keys.group())) continue;
            if (keys.start() > end)
                runs.add(new WzParagraph.Run(text.substring(end, keys.start())));
            var item = new JsonObject();
            item.addProperty("id", id.toString());
            runs.add(new WzParagraph.Run(displayName(item), false, false, null, item));
            end = keys.end();
        }
        if (end < text.length()) runs.add(new WzParagraph.Run(text.substring(end)));
    }

    private WzWinter winter;

    void appearance(WzWinter value) {
        winter = value;
    }

    private float paragraph(HubDraw d, Paragraph p, String key, float x, float y) {
        float size = p.note ? 13 : 15, lineHeight = size * 1.85f;
        int ink = winter != null ? winter.ink() : d.dark() ? 0xffe2e8f5 : 0xff343d54;
        int muted = winter != null ? winter.muted() : d.dark() ? 0xffadb9d2 : 0xff677591;
        for (int i = 0; i < p.value.lines.size(); i++) {
            float top = y + i * lineHeight;
            if (top >= revealBottom) break;
            if (top + lineHeight <= clipY || top >= clipY + clipH) continue;
            List<WzParagraph.Part> parts = p.value.lines.get(i).parts();
            for (int j = 0; j < parts.size(); j++) {
                WzParagraph.Part part = parts.get(j);
                float left = x + part.x(), baseline = top + (lineHeight + size * .65f) / 2;
                if (part.style().item() != null) {
                    JsonObject item = part.style().item();
                    ItemFocus target =
                            addItem(
                                    item,
                                    List.of(),
                                    key + "-" + i + "-" + j,
                                    left,
                                    baseline - 14,
                                    17.7f,
                                    left,
                                    top,
                                    part.width(),
                                    lineHeight);
                    float right = left + part.width();
                    left += 22;
                    if (target != null
                            && (target.contains(pointerX, pointerY) || target.id.equals(focusId))) {
                        d.line(left, baseline + 3, right, baseline + 3, 1, muted);
                    } else {
                        for (float xx = left; xx < right; xx += 3)
                            d.circle(xx, baseline + 3, .6f, muted);
                    }
                }
                d.text(
                        part.text(),
                        left,
                        baseline,
                        size,
                        p.note || part.style().muted() ? muted : ink,
                        part.style().bold());
            }
        }
        return y + p.value.lines.size() * lineHeight;
    }

    private ItemFocus addItem(
            JsonObject item,
            List<JsonObject> variants,
            String key,
            float x,
            float y,
            float size,
            float hitX,
            float hitY,
            float w,
            float h) {
        float left = Math.max(Math.max(clipX, renderLeft), hitX), top = Math.max(clipY, hitY);
        float right = Math.min(Math.min(clipX + clipW, renderRight), hitX + w);
        float bottom = Math.min(Math.min(clipY + clipH, revealBottom), hitY + h);
        if (bottom - top < 2 || right - left < 2) return null;
        String id = "wz-item-" + key;
        ItemFocus focus =
                new ItemFocus(id, displayName(item), left, top, right - left, bottom - top);
        icons.add(new ItemAt(item, variants, x, y, size, focus));
        if (register != null)
            register.accept(
                    focus,
                    () -> {
                        dismissedId = null;
                        pinnedId = id;
                        finishFocusedReveal(id);
                    });
        return focus;
    }

    private float recipe(HubDraw d, Card card, String key, float x, float y, float width) {
        float cell = 27, gap = 2;
        int rows = (card.slots.size() + card.columns - 1) / card.columns;
        float gridWidth = card.columns * (cell + gap) - gap, gridHeight = rows * (cell + gap) - gap;
        float outX = x + 16 + gridWidth + 45;
        JsonObject output = object(card.items, card.output);
        String count = "×" + card.count;
        float countWidth = d.textWidth(count, 13, false);
        String label =
                output == null
                        ? ""
                        : shorten(
                                d,
                                displayName(output),
                                Math.max(0, width - (outX - x + 50 + countWidth)),
                                13);
        float labelWidth = d.textWidth(label, 13, false);
        float w = Math.min(width, outX - x + 50 + labelWidth + countWidth), h = cardHeight(card);
        int rule = winter != null ? winter.ruling() : d.dark() ? 0x4095abc9 : 0x366a7c9e;
        int ink = winter != null ? winter.ink() : d.dark() ? 0xffdfe7f4 : 0xff46536e;
        float topInset = card.shapeless ? 30 : 13;
        if (y >= clipY + clipH || y + h <= clipY) return y + h;
        d.strokeRoundRect(x, y, w, h, 12, 1, rule);
        if (card.shapeless) d.text("无序配方", x + 16, y + 19, 11, ink, false);
        for (int i = 0; i < card.slots.size(); i++) {
            float cx = x + 16 + (i % card.columns) * (cell + gap);
            float cy = y + topInset + (i / card.columns) * (cell + gap);
            d.strokeRoundRect(cx, cy, cell, cell, 3, 1, rule);
            JsonObject item = object(card.items, string(card.slots.get(i)));
            if (item == null) continue;
            List<JsonObject> choices = variants(card, i);
            addItem(item, choices, key + "-" + i, cx + 4, cy + 4, 19, cx, cy, cell, cell);
            if (!resolved(item)) slotFallback(d, displayName(item), cx, cy, cell - 4, ink);
            if (choices.size() > 1) d.text("+", cx + 19, cy + 8, 8, ink, true);
        }
        float mid = y + topInset + gridHeight / 2;
        float cx = outX - 23;
        d.line(cx - 2, mid - 4, cx + 2, mid, 1.4f, ink);
        d.line(cx + 2, mid, cx - 2, mid + 4, 1.4f, ink);
        if (output != null) {
            addItem(
                    output,
                    List.of(),
                    key + "-out",
                    outX,
                    mid - 11.5f,
                    23,
                    outX,
                    mid - 12,
                    33 + labelWidth,
                    24);
            d.text(label, outX + 29, mid + 5, 13, ink, false);
        }
        d.text(count, outX + 33 + labelWidth, mid + 5, 13, ink, false);
        return y + h;
    }

    private static List<JsonObject> variants(Card card, int slot) {
        JsonElement choices =
                card.alternatives == null ? null : card.alternatives.get(Integer.toString(slot));
        if (choices == null || !choices.isJsonArray()) return List.of();
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement key : choices.getAsJsonArray()) {
            JsonObject choice = object(card.items, string(key));
            if (choice != null && !result.contains(choice)) result.add(choice);
            if (result.size() == 12) break;
        }
        return result;
    }

    private static void slotFallback(
            HubDraw d, String name, float x, float y, float width, int ink) {
        int split = name.length();
        while (split > 0 && d.textWidth(name.substring(0, split), 9, false) > width)
            split = name.offsetByCodePoints(split, -1);
        if (split == 0) return;
        String first = name.substring(0, split);
        if (split == name.length()) {
            d.text(first, x + 2, y + 17, 9, ink, false);
        } else {
            d.text(first, x + 2, y + 12, 9, ink, false);
            d.text(shorten(d, name.substring(split), width, 9), x + 2, y + 22, 9, ink, false);
        }
    }

    private static String shorten(HubDraw d, String name, float width, float size) {
        if (d.textWidth(name, size, false) <= width) return name;
        if (d.textWidth("…", size, false) > width) return "";
        int end = name.length();
        while (end > 0) {
            end = name.offsetByCodePoints(end, -1);
            String label = name.substring(0, end) + "…";
            if (d.textWidth(label, size, false) <= width) return label;
        }
        return "…";
    }

    public boolean referencePointer(float x, float y) {
        return pointed(x, y) != null;
    }

    public void dismissOutside(float x, float y) {
        if (!referencePointer(x, y)) escape();
    }

    public boolean escape() {
        ItemAt active = tooltipAt(pointerX, pointerY);
        if (active == null) return false;
        dismissedId = active.focus.id;
        pinnedId = null;
        return true;
    }

    private ItemAt pointed(float x, float y) {
        for (ItemAt candidate : icons) if (candidate.focus.contains(x, y)) return candidate;
        return null;
    }

    private ItemAt identified(String id) {
        if (id != null)
            for (ItemAt candidate : icons) if (id.equals(candidate.focus.id)) return candidate;
        return null;
    }

    private ItemAt tooltipAt(float x, float y) {
        ItemAt item = identified(pinnedId);
        if (item == null) {
            pinnedId = null;
            item = identified(focusId);
        }
        if (item == null) item = pointed(x, y);
        if (item != null && item.focus.id.equals(dismissedId)) return null;
        dismissedId = null;
        return item;
    }

    public void paintItems(
            GuiGraphics g,
            float originX,
            float originY,
            float sx,
            float sy,
            float clipX,
            float clipY,
            float clipW,
            float clipH,
            int mx,
            int my) {
        if (icons.isEmpty() || sx <= 0 || sy <= 0 || clipW <= 0 || clipH <= 0) return;
        int left = (int) Math.floor(originX + clipX * sx),
                top = (int) Math.floor(originY + clipY * sy);
        int right = (int) Math.ceil(originX + (clipX + clipW) * sx),
                bottom = (int) Math.ceil(originY + (clipY + clipH) * sy);
        ItemAt shown = tooltipAt((mx - originX) / sx, (my - originY) / sy);
        g.enableScissor(left, top, right, bottom);
        try {
            for (ItemAt pos : icons) {
                float x = originX + pos.x * sx, y = originY + pos.y * sy;
                if (y + pos.size * sy <= top
                        || y >= bottom
                        || x + pos.size * sx <= left
                        || x >= right) continue;
                ItemStack stack = itemStack(pos.data);
                if (stack.isEmpty()) continue;
                float itemBottom = originY + (pos.focus.y + pos.focus.height) * sy;
                g.enableScissor(left, top, right, Math.min(bottom, (int) Math.ceil(itemBottom)));
                g.pose().pushPose();
                try {
                    g.pose().translate(x, y, 0);
                    g.pose().scale(pos.size * sx / 16, pos.size * sy / 16, 1);
                    g.renderItem(stack, 0, 0);
                    g.flush();
                } finally {
                    g.pose().popPose();
                    g.disableScissor();
                }
            }
        } finally {
            g.disableScissor();
        }
        if (shown == null) return;
        ItemFocus target = shown.focus;
        var bounds =
                new WzItemTooltip.Bounds(
                        (int) Math.floor(originX + target.x * sx),
                        (int) Math.floor(originY + target.y * sy),
                        (int) Math.ceil(originX + (target.x + target.width) * sx),
                        (int) Math.ceil(originY + (target.y + target.height) * sy));
        boolean hovering = target.contains((mx - originX) / sx, (my - originY) / sy);
        int tooltipX = hovering ? mx : bounds.right();
        int tooltipY = hovering ? my : (bounds.top() + bounds.bottom()) / 2;
        WzItemTooltip.draw(g, bounds, () -> drawTooltip(g, shown, tooltipX, tooltipY));
    }

    private void drawTooltip(GuiGraphics g, ItemAt shown, int tooltipX, int tooltipY) {
        Minecraft minecraft = Minecraft.getInstance();
        ItemStack stack = itemStack(shown.data);
        if (!stack.isEmpty() && shown.variants.size() <= 1) {
            g.renderTooltip(minecraft.font, stack, tooltipX, tooltipY);
        } else {
            List<Component> lines =
                    stack.isEmpty()
                            ? new ArrayList<>(List.of(Component.literal(displayName(shown.data))))
                            : new ArrayList<>(
                                    stack.getTooltipLines(
                                            minecraft.player, TooltipFlag.Default.NORMAL));
            if (shown.variants.size() > 1) {
                lines.add(Component.literal("也可使用："));
                for (JsonObject option : shown.variants) {
                    if (!option.equals(shown.data))
                        lines.add(Component.literal(displayName(option)));
                }
            }
            g.renderComponentTooltip(minecraft.font, lines, tooltipX, tooltipY, stack);
        }
    }

    private ItemStack itemStack(JsonObject data) {
        return stacks.computeIfAbsent(data, HubWenzhouAnswer::stack);
    }

    private boolean resolved(JsonObject data) {
        return hasRegistry && !itemStack(data).isEmpty();
    }

    private String displayName(JsonObject data) {
        String value = itemName(data);
        return value.isBlank() ? "未知物品" : value;
    }

    private static boolean registryAvailable() {
        try {
            Bootstrap.checkBootstrapCalled(() -> "WenZhou item names");
            return true;
        } catch (IllegalArgumentException uninitialized) {
            return false;
        }
    }

    private String itemName(JsonObject data) {
        if (measuredLanguage != null) {
            ItemStack item = stacks.computeIfAbsent(data, HubWenzhouAnswer::stack);
            if (!item.isEmpty()) {
                String localized =
                        net.minecraft.ChatFormatting.stripFormatting(
                                item.getHoverName().getString());
                if (!localized.isBlank() && !localized.equals(item.getDescriptionId()))
                    return localized;
            }
        }
        String name = net.minecraft.ChatFormatting.stripFormatting(string(data.get("name")));
        return name.isBlank() || ITEM_KEY.matcher(name).matches() ? string(data.get("id")) : name;
    }

    static ItemStack stack(JsonObject data) {
        ResourceLocation id = ResourceLocation.tryParse(string(data.get("id")));
        if (id == null || !ForgeRegistries.ITEMS.containsKey(id)) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(id)));
        String nbt = string(data.get("snbt"));
        if (!nbt.isEmpty()) {
            if (nbt.length() > 32767) return ItemStack.EMPTY;
            try {
                stack.setTag(TagParser.parseTag(nbt));
            } catch (CommandSyntaxException invalid) {
                return ItemStack.EMPTY;
            }
        }
        return stack;
    }

    static String string(JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    static JsonObject object(JsonObject src, String key) {
        return src != null && src.has(key) && src.get(key).isJsonObject()
                ? src.getAsJsonObject(key)
                : null;
    }

    private static int number(JsonObject src, String key, int fallback) {
        try {
            return src.has(key) ? src.get(key).getAsInt() : fallback;
        } catch (RuntimeException invalid) {
            return fallback;
        }
    }
}
