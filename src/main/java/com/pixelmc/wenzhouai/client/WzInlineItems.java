/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client;

import com.pixelmc.hub.client.render.HubDraw;
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

/** Each reference occupies one caret step; only registry IDs cross the network or enter history. */
final class WzInlineItems {
    private static final int FIRST = 0xf0000;
    private static final Pattern REFERENCE = Pattern.compile("\\[\\[item:([a-z0-9_.-]+:[a-z0-9_./-]+)\\]\\]");
    private final Map<ResourceLocation, String> codes = new HashMap<>();
    private final List<ResourceLocation> items = new ArrayList<>();
    private final Map<ResourceLocation, ItemStack> stacks = new HashMap<>();
    private record Icon(ItemStack stack, float x, float y, float size, float right, float top, float bottom) {}
    private final List<Icon> icons = new ArrayList<>();

    String token(ResourceLocation id) {
        return codes.computeIfAbsent(id, key -> {
            int n = FIRST + items.size();
            if (n > Character.MAX_CODE_POINT) return "[[item:" + id + "]]";
            items.add(key);
            return Character.toString(n);
        });
    }

    String decode(String wire) {
        if (!wire.contains("[[item:")) return wire;
        return REFERENCE.matcher(wire).replaceAll(m -> {
            ResourceLocation id = ResourceLocation.tryParse(m.group(1));
            return Matcher.quoteReplacement(id != null && ForgeRegistries.ITEMS.containsKey(id) ? token(id) : m.group());
        });
    }

    private ResourceLocation item(int cp) {
        int index = cp - FIRST;
        return index < 0 || index >= items.size() ? null : items.get(index);
    }

    String encode(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(cp -> {
            ResourceLocation id = item(cp);
            if (id == null) out.appendCodePoint(cp);
            else out.append("[[item:").append(id).append("]]");
        });
        return out.toString();
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
            ResourceLocation id = item(cp);
            if (id != null) {
                String before = text.substring(from, i);
                if (paint && !before.isEmpty()) draw.text(before, x, baseline, size, ink, false);
                x += draw.textWidth(before, size, false);
                ItemStack stack = stacks.computeIfAbsent(id, key -> new ItemStack(ForgeRegistries.ITEMS.getValue(key)));
                String name = stack.getHoverName().getString();
                float width = size + 4 + draw.textWidth(name, size, false);
                if (paint) {
                    icon(stack, x, baseline - size + 2, size, x + width, top, bottom);
                    int color = (ink & 0xff000000) | (draw.dark() ? 0xc9a7ff : 0x007b91);
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
