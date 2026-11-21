/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client;

import com.pixelmc.hub.client.render.HubDraw;
import com.pixelmc.wenzhouai.network.WzBlockReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Item and block references each occupy one logical caret step. */
final class WzInlineItems {
    private static final int FIRST = 0xf0000;
    private static final Pattern REFERENCE = Pattern.compile("\\[\\[item:([a-z0-9_.-]+:[a-z0-9_./-]+)\\]\\]");
    private record Reference(ResourceLocation id, WzBlockReference block) {}
    private final Map<Reference, String> codes = new HashMap<>();
    private final List<Reference> items = new ArrayList<>();
    private final Map<ResourceLocation, ItemStack> stacks = new HashMap<>();
    private record Icon(ItemStack stack, float x, float y, float size, float right, float top, float bottom) {}
    private final List<Icon> icons = new ArrayList<>();

    String token(ResourceLocation id) {
        return token(new Reference(id, null));
    }

    String token(WzBlockReference block) {
        return token(new Reference(block.id(), block));
    }

    private String token(Reference ref) {
        return codes.computeIfAbsent(ref, key -> {
            int n = FIRST + items.size();
            if (n > 0xffffd) return marker(ref);
            items.add(key);
            return Character.toString(n);
        });
    }

    String decode(String wire) {
        if (wire.contains("[[item:")) wire = REFERENCE.matcher(wire).replaceAll(m -> {
            ResourceLocation id = ResourceLocation.tryParse(m.group(1));
            return Matcher.quoteReplacement(id != null && ForgeRegistries.ITEMS.containsKey(id) ? token(id) : m.group());
        });
        if (!wire.contains("[[block:")) return wire;
        return WzBlockReference.MARKER.matcher(wire).replaceAll(m -> {
            WzBlockReference ref = WzBlockReference.parse(m);
            return Matcher.quoteReplacement(ref == null ? m.group() : token(ref));
        });
    }

    private Reference item(int cp) {
        int index = cp - FIRST;
        return index < 0 || index >= items.size() ? null : items.get(index);
    }

    private static String marker(Reference ref) {
        return ref.block == null ? "[[item:" + ref.id + "]]" : ref.block.marker(true);
    }

    String encode(String text) {
        return encode(text, false);
    }

    String copy(String text) {
        return encode(text, true);
    }

    private String encode(String text, boolean clipboard) {
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(cp -> {
            Reference ref = item(cp);
            if (ref == null) out.appendCodePoint(cp);
            else out.append(clipboard && ref.block != null ? name(ref, ItemStack.EMPTY) : marker(ref));
        });
        return out.toString();
    }

    private ItemStack stack(Reference ref) {
        if (ref.block == null)
            return stacks.computeIfAbsent(ref.id, key -> new ItemStack(ForgeRegistries.ITEMS.getValue(key)));
        var block = ForgeRegistries.BLOCKS.getValue(ref.id);
        if (block == null) return ItemStack.EMPTY;
        var item = block.asItem();
        return stacks.computeIfAbsent(ForgeRegistries.ITEMS.getKey(item), key -> new ItemStack(item));
    }

    private static String name(Reference ref, ItemStack stack) {
        if (ref.block == null) return net.minecraft.ChatFormatting.stripFormatting(stack.getHoverName().getString());
        var block = ForgeRegistries.BLOCKS.containsKey(ref.id) ? ForgeRegistries.BLOCKS.getValue(ref.id) : null;
        String label = block == null ? ref.id.toString() : block.getName().getString();
        return label + " (" + ref.block.coordinates() + ")";
    }

    void beginFrame() { icons.clear(); }
    void clear() { codes.clear(); items.clear(); stacks.clear(); icons.clear(); }
    void icon(ItemStack stack, float x, float y, float size, float right, float top, float bottom) {
        if (y < bottom && y + size > top) icons.add(new Icon(stack, x, y, size, right, top, bottom));
    }

    float line(HubDraw draw, String text, float size, float x, float baseline, int ink, boolean paint,
               float top, float bottom) {
        float start = x;
        int from = 0;
        for (int i = 0; !items.isEmpty() && i < text.length();) {
            int cp = text.codePointAt(i);
            int end = i + Character.charCount(cp);
            Reference ref = item(cp);
            if (ref != null) {
                String before = text.substring(from, i);
                if (paint && !before.isEmpty()) draw.text(before, x, baseline, size, ink, false);
                x += draw.textWidth(before, size, false);
                ItemStack stack = stack(ref);
                String name = name(ref, stack);
                float width = size + 4 + draw.textWidth(name, size, false);
                if (paint) {
                    int color = (ink & 0xff000000) | (draw.dark() ? 0xc9a7ff : 0x007b91);
                    if (stack.isEmpty() && ref.block != null)
                        WzGlyph.draw(draw, "select-block", x, baseline - size + 2, size, color);
                    else icon(stack, x, baseline - size + 2, size, x + width, top, bottom);
                    draw.text(name, x + size + 3, baseline, size, color, false);
                }
                x += width;
                from = end;
            }
            i = end;
        }
        String tail = text.substring(from);
        if (paint && !tail.isEmpty()) draw.text(tail, x, baseline, size, ink, false);
        return x - start + draw.textWidth(tail, size, false);
    }

    void paint(GuiGraphics g, float ox, float oy, float sx, float sy, int mx, int my) {
        Icon hover = null;
        for (Icon icon : icons) {
            int left = (int)Math.floor(ox + icon.x * sx);
            int right = (int)Math.ceil(ox + icon.right * sx);
            int top = (int)Math.floor(oy + Math.max(icon.y, icon.top) * sy);
            int bottom = (int)Math.ceil(oy + Math.min(icon.y + icon.size, icon.bottom) * sy);
            g.enableScissor(left, top, right, bottom);
            g.pose().pushPose();
            try {
                g.pose().translate(ox + icon.x * sx, oy + icon.y * sy, 0);
                g.pose().scale(icon.size * sx / 16, icon.size * sy / 16, 1);
                g.renderItem(icon.stack, 0, 0);
                g.flush();
            } finally {
                g.pose().popPose();
                g.disableScissor();
            }
            if (mx >= left && mx < right && my >= top && my < bottom) hover = icon;
        }
        if (hover != null) {
            ItemStack shown = hover.stack;
            var bounds = new WzItemTooltip.Bounds((int)(ox + hover.x*sx), (int)(oy + hover.y*sy),
                    (int)(ox + hover.right*sx), (int)(oy + (hover.y + hover.size)*sy));
            WzItemTooltip.draw(g, bounds, () -> g.renderTooltip(Minecraft.getInstance().font, shown, mx, my));
        }
    }
}
