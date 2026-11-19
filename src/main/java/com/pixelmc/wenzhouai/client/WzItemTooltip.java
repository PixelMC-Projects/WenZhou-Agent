/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Keeps vanilla/Forge tooltip components and formatting, reserving room for the source item. */
@Mod.EventBusSubscriber(modid = "wenzhouai", value = Dist.CLIENT)
public final class WzItemTooltip {
    record Bounds(int left, int top, int right, int bottom) {}
    private record Source(GuiGraphics graphics, Bounds bounds) {}
    private static Source current;

    static void draw(GuiGraphics graphics, Bounds bounds, Runnable draw) {
        Source previous = current;
        current = new Source(graphics, bounds);
        try {
            draw.run();
        } finally {
            current = previous;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void avoidSource(RenderTooltipEvent.Pre event) {
        if (current == null || current.graphics != event.getGraphics()
                || event.getTooltipPositioner() != DefaultTooltipPositioner.INSTANCE) return;
        int width = 0;
        int height = event.getComponents().size() == 1 ? -2 : 0;
        for (var part : event.getComponents()) {
            width = Math.max(width, part.getWidth(event.getFont()));
            height += part.getHeight();
        }
        Bounds source = current.bounds;
        int screenW = event.getScreenWidth(), screenH = event.getScreenHeight();
        var pos = DefaultTooltipPositioner.INSTANCE.positionTooltip(
                screenW, screenH, event.getX(), event.getY(), width, height);
        if (pos.x() >= source.right + 6 || pos.x() + width <= source.left - 6
                || pos.y() >= source.bottom + 6 || pos.y() + height <= source.top - 6) return;

        // Vanilla offsets the anchor by (+12, -12), then flips or clamps at the screen edge.
        if (source.right + 12 + width + 4 <= screenW) event.setX(source.right);
        else if (source.left >= width + 12) event.setX(source.left - width - 20);
        else if (source.bottom + 8 + height + 4 <= screenH) event.setY(source.bottom + 20);
        else if (source.top >= height + 12) event.setY(source.top - height + 4);
    }

    private WzItemTooltip() {}
}
