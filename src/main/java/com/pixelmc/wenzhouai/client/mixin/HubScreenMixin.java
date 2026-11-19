/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client.mixin;

import com.pixelmc.hub.client.ui.HubView;
import com.pixelmc.wenzhouai.client.WzHubAccess;
import com.pixelmc.wenzhouai.client.WzPage;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.pixelmc.hub.client.HubScreen", remap = false)
abstract class HubScreenMixin {
    @Final @Shadow private HubView view;
    @Shadow private boolean failed;
    @Shadow private float panelX;
    @Shadow private float panelY;
    @Shadow private float panelScale;

    @Inject(
            method = {"render", "m_88315_"},
            at = @At("RETURN"))
    private void wenzhouai$items(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (failed || !((Object) view instanceof WzHubAccess access)) return;
        WzPage page = access.wenzhouPage();
        if (!page.active()) return;
        Window window = Minecraft.getInstance().getWindow();
        float sx = (float) window.getGuiScaledWidth() / window.getWidth();
        float sy = (float) window.getGuiScaledHeight() / window.getHeight();
        page.paintItems(
                graphics,
                panelX * sx,
                panelY * sy,
                panelScale * sx,
                panelScale * sy,
                mouseX,
                mouseY);
    }
}
