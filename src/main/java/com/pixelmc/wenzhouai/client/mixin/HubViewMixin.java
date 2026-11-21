/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client.mixin;

import com.pixelmc.hub.client.render.HubDraw;
import com.pixelmc.hub.client.ui.HubView;
import com.pixelmc.wenzhouai.client.WzHubAccess;
import com.pixelmc.wenzhouai.client.WzPage;
import com.pixelmc.wenzhouai.client.WzWorldPicker;

import io.github.humbleui.skija.Matrix33;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = HubView.class, remap = false)
abstract class HubViewMixin implements WzHubAccess, WzPage.Host {
    @Final @Shadow private List<?> nextHits;
    @Shadow private HubDraw d;
    @Shadow private Matrix33 logical;
    @Shadow private float mx;
    @Shadow private float my;
    @Shadow private long now;
    @Shadow private boolean dark;
    @Shadow private boolean reducedMotion;
    @Shadow private boolean keyboardFocus;
    @Shadow private String focusId;

    @Shadow
    protected abstract void hit(
            String id, String label, float x, float y, float w, float h, Runnable action);

    @Shadow
    protected abstract void navigate(int page);

    @Shadow
    public abstract void drawExtensionNavigation(
            HubView.NavigationEntry entry, int slot, boolean visible);

    @Shadow
    public abstract float extensionHover(String id, boolean hovered);

    @Override
    public float wenzhouHover(String id, boolean hovered) {
        return extensionHover(id, hovered);
    }

    @Shadow
    public abstract float extensionButtonMove(String id, boolean enabled, boolean hovered);

    @Override
    public float wenzhouButtonMove(String id, boolean enabled, boolean hovered) {
        return extensionButtonMove(id, enabled, hovered);
    }

    @Shadow
    protected abstract void beginAlpha(float alpha, float x, float y, float w, float h);

    @Shadow
    protected abstract void endAlpha();

    @Shadow
    protected abstract void push(Matrix33 transform);

    @Shadow
    protected abstract void pop();

    @Unique private WzPage wenzhouai$page;
    @Unique private boolean wenzhouai$handbookLayer;

    @Override
    public WzPage wenzhouPage() {
        if (wenzhouai$page == null) wenzhouai$page = new WzPage(this);
        return wenzhouai$page;
    }

    @Override
    public void wenzhouAddHit(
            String id, String label, float x, float y, float w, float h, Runnable action) {
        hit(id, label, x, y, w, h, action);
    }

    @Shadow
    public abstract void feedback(String value);

    @Shadow
    public abstract void feedbackSuccess(String value);

    @Shadow
    public abstract void feedbackError(String value);

    @Override
    public void wenzhouFocus(String id) {
        focusId = id;
    }

    @Override
    public void wenzhouBack() {
        navigate(0);
    }

    @Override
    public long wenzhouWindow() {
        return Minecraft.getInstance().getWindow().getWindow();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void wenzhouai$refresh(
            HubDraw draw, float mouseX, float mouseY, long clock, CallbackInfo ci) {
        wenzhouPage().refresh();
    }

    @Inject(method = "sidebar", at = @At("HEAD"), cancellable = true)
    private void wenzhouai$hideSidebar(CallbackInfo ci) {
        if (wenzhouPage().coverHandbook(now, reducedMotion)) ci.cancel();
    }

    @Inject(method = "drawExtraNavigation", at = @At("HEAD"))
    private void wenzhouai$addEntry(int firstSlot, CallbackInfo ci) {
        WzPage page = wenzhouPage();
        drawExtensionNavigation(page.navigationEntry(), firstSlot, page.navigationVisible());
    }

    @Inject(method = "topbar", at = @At("HEAD"), cancellable = true)
    private void wenzhouai$hideTopbar(CallbackInfo ci) {
        if (wenzhouPage().coverHandbook(now, reducedMotion)) ci.cancel();
    }

    @Redirect(
            method = "render",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/pixelmc/hub/client/render/HubDraw;linearGradientRoundRect(FFFFFF[I[F)V"))
    private void wenzhouai$workspace(
            HubDraw draw,
            float x,
            float y,
            float width,
            float height,
            float radius,
            float angle,
            int[] colors,
            float[] stops) {
        float alpha = wenzhouPage().handbookAlpha(now, reducedMotion);
        wenzhouai$handbookLayer = alpha > 0 && alpha < 1;
        if (wenzhouai$handbookLayer) {
            beginAlpha(alpha, 0, 0, 1260, 850);
            push(Matrix33.makeTranslate(-22 * (1 - alpha), 0));
        }
        if (alpha > 0)
            draw.linearGradientRoundRect(x, y, width, height, radius, angle, colors, stops);
    }

    @Inject(
            method = "render",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lcom/pixelmc/hub/client/render/HubDraw;restore()V",
                            ordinal = 0))
    private void wenzhouai$drawPage(
            HubDraw draw, float mouseX, float mouseY, long clock, CallbackInfo ci) {
        WzPage page = wenzhouPage();
        if (wenzhouai$handbookLayer) {
            pop();
            endAlpha();
            wenzhouai$handbookLayer = false;
        }
        if (!page.routeVisible(now, reducedMotion)) return;
        if (page.active()) nextHits.clear();
        page.render(d, logical, mx, my, now, dark, reducedMotion, focusId);
    }

    @Inject(
            method = {"home", "titles", "fortunes", "updates", "leaderboards", "memories"},
            at = @At("HEAD"),
            cancellable = true)
    private void wenzhouai$hideHandbook(CallbackInfo ci) {
        if (wenzhouPage().coverHandbook(now, reducedMotion)) ci.cancel();
    }

    @Inject(method = "panelVisibility", at = @At("HEAD"))
    private void wenzhouai$closePanel(boolean open, CallbackInfo ci) {
        if (open) WzWorldPicker.hubVisible(wenzhouPage());
        else if (wenzhouai$page != null && !WzWorldPicker.containsDraft(wenzhouai$page)) wenzhouai$page.closePanel();
    }

    @Inject(method = "dismissOverlay", at = @At("HEAD"), cancellable = true)
    private void wenzhouai$escape(CallbackInfoReturnable<Boolean> cir) {
        if (wenzhouPage().escape()) cir.setReturnValue(true);
    }

    @Inject(method = "mouseClicked(FFIZ)Z", at = @At("HEAD"), cancellable = true)
    private void wenzhouai$press(
            float x, float y, int button, boolean shift, CallbackInfoReturnable<Boolean> cir) {
        if (button == 0 && wenzhouPage().active() && wenzhouPage().pointerDown(x, y, shift)) {
            keyboardFocus = false;
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void wenzhouai$release(
            float x, float y, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button == 0 && wenzhouPage().active() && wenzhouPage().pointerUp())
            cir.setReturnValue(true);
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void wenzhouai$drag(
            float x, float y, int button, float dx, float dy, CallbackInfoReturnable<Boolean> cir) {
        if (button == 0 && wenzhouPage().active() && wenzhouPage().pointerDrag(x, y))
            cir.setReturnValue(true);
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void wenzhouai$scroll(
            float x, float y, double amount, CallbackInfoReturnable<Boolean> cir) {
        if (wenzhouPage().active()) cir.setReturnValue(wenzhouPage().scroll(x, y, amount));
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void wenzhouai$key(
            int key, int scanCode, int mods, CallbackInfoReturnable<Boolean> cir) {
        if (WzWorldPicker.hubKey(wenzhouPage(), key, scanCode) || wenzhouPage().key(key, mods, focusId))
            cir.setReturnValue(true);
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void wenzhouai$type(char c, int mods, CallbackInfoReturnable<Boolean> cir) {
        if (!wenzhouPage().textFocused(focusId)) return;
        wenzhouPage().type(c, mods);
        cir.setReturnValue(true);
    }

    @Inject(method = "textInputFocused", at = @At("HEAD"), cancellable = true)
    private void wenzhouai$input(CallbackInfoReturnable<Boolean> cir) {
        if (wenzhouPage().active()) cir.setReturnValue(wenzhouPage().textFocused(focusId));
    }

    @Inject(method = "selectedInputText", at = @At("HEAD"), cancellable = true)
    private void wenzhouai$selected(CallbackInfoReturnable<String> cir) {
        if (wenzhouPage().textFocused(focusId)) cir.setReturnValue(wenzhouPage().selection());
    }

    @Inject(method = "cutInputText", at = @At("HEAD"), cancellable = true)
    private void wenzhouai$cut(CallbackInfoReturnable<String> cir) {
        if (wenzhouPage().textFocused(focusId)) cir.setReturnValue(wenzhouPage().cut());
    }

    @Inject(method = "pasteText", at = @At("HEAD"), cancellable = true)
    private void wenzhouai$paste(String value, CallbackInfo ci) {
        if (!wenzhouPage().textFocused(focusId)) return;
        if (value != null) wenzhouPage().paste(value);
        ci.cancel();
    }

    @Inject(method = "pointerCursor", at = @At("HEAD"), cancellable = true)
    private void wenzhouai$pointer(CallbackInfoReturnable<String> cir) {
        if (wenzhouPage().textPointer(mx, my)) cir.setReturnValue("text");
        else if (wenzhouPage().referencePointer(mx, my)) cir.setReturnValue("pointer");
    }
}
