/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.pixelmc.hub.client.ClientBootstrap;
import com.pixelmc.hub.client.ClientHubSession;
import com.pixelmc.hub.client.HubScreen;
import com.pixelmc.hub.client.ui.HubView;
import com.pixelmc.wenzhouai.network.WzClient;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import org.joml.Matrix4f;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * Renders the blue-white gradient quick-ask hint capsule beneath hovered item tooltips and opens
 * Wenzhou's dedicated item quick-ask stage when the bound hotkey (default G) is pressed.
 *
 * <p>When ModernUI tooltips are active, matches ModernUI's exact sub-pixel positioning and
 * delegates box + text rendering to ModernUI's TooltipRenderer so border radius, width, gap, motion
 * smoothness and HarfBuzz typography match the main item tooltip seamlessly.
 */
@Mod.EventBusSubscriber(modid = "wenzhouai", value = Dist.CLIENT)
public final class WzItemQuickAsk {
    static final KeyMapping KEY =
            new KeyMapping(
                    "key.wenzhouai.quick_ask_item",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_G,
                    "key.categories.wenzhouai");

    private static final int BOX_BG = 0xF0090A0C;
    private static final int BOX_BORDER = 0xD1E0E4EC;
    private static final int GRAD_LEFT = 0xFF74C4FF;
    private static final int GRAD_MID = 0xFFC8E9FF;
    private static final int GRAD_RIGHT = 0xFFFFFFFF;
    // ModernUI draws 4px inner padding + ~0.67px outer half-stroke per side (~9.34px combined
    // between top inner bottom and bottom inner top), so 12.0px yields a crisp ~2.7px visual gap.
    private static final float MODERN_UI_INNER_GAP = 12.0f;
    private static final int FALLBACK_VISUAL_GAP = 3;
    private static final long SNAPSHOT_TTL_NANOS = 150_000_000L;

    private record HoverSnapshot(
            ItemStack rawStack,
            ItemStack stackCopy,
            ResourceLocation id,
            float tooltipX,
            float tooltipY,
            int contentW,
            int contentH,
            int screenW,
            int screenH,
            boolean modernUiActive,
            boolean jeiSource,
            Screen screen,
            long capturedAt) {}

    private static HoverSnapshot frameSnapshot;
    private static HoverSnapshot lastValidSnapshot;

    private WzItemQuickAsk() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        frameSnapshot = null;
    }

    /**
     * Captures hovered item tooltip geometry at NORMAL priority so tooltip replacement mods such as
     * ModernUI (which renders at HIGH and cancels RenderTooltipEvent.Pre at LOW to suppress vanilla
     * rendering) do not hide the event from quick-ask.
     */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onTooltipPre(RenderTooltipEvent.Pre event) {
        if (event.isCanceled()) return;
        WzQuickAskPrefs.Mode mode = WzQuickAskPrefs.mode();
        if (mode == WzQuickAskPrefs.Mode.OFF || KEY.isUnbound()) return;
        ItemStack stack = event.getItemStack();
        if (stack == null || stack.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen == null || screen instanceof HubScreen) return;
        if (!availablePage()) return;

        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return;

        List<ClientTooltipComponent> components = event.getComponents();
        if (components.isEmpty()) return;

        int contentW = 0;
        int contentH = components.size() == 1 ? -2 : 0;
        for (var component : components) {
            contentW = Math.max(contentW, component.getWidth(event.getFont()));
            contentH += component.getHeight();
        }
        int screenW = event.getScreenWidth();
        int screenH = event.getScreenHeight();

        boolean modernUi = ModernUiBridge.isTooltipActive();
        float tooltipX;
        float tooltipY;
        if (modernUi
                && ModernUiBridge.isExactPositioning()
                && event.getTooltipPositioner() instanceof DefaultTooltipPositioner) {
            // Mirror ModernUI UIManager.drawExtTooltip + TooltipRenderer.drawTooltip exactly.
            Window window = mc.getWindow();
            double cursorX =
                    mc.mouseHandler.xpos()
                            * (double) window.getGuiScaledWidth()
                            / (double) window.getScreenWidth();
            double cursorY =
                    mc.mouseHandler.ypos()
                            * (double) window.getGuiScaledHeight()
                            / (double) window.getScreenHeight();
            int mouseX = (int) cursorX;
            int mouseY = (int) cursorY;
            float partialX = 0f;
            float partialY = 0f;
            if (event.getX() == mouseX && event.getY() == mouseY) {
                partialX = (float) (cursorX - (double) mouseX);
                partialY = (float) (cursorY - (double) mouseY);
            }
            tooltipX = event.getX() + 12.0f + partialX;
            if (tooltipX - partialX + (float) contentW + 4.0f > (float) screenW) {
                tooltipX -= (28.0f + (float) contentW);
            }
            tooltipY = event.getY() - 12.0f + partialY;
            if (tooltipY + (float) contentH + 6.0f > (float) screenH) {
                tooltipY = (float) (screenH - contentH - 6);
            }
            if (tooltipY < 6.0f) {
                tooltipY = 6.0f;
            }
        } else {
            // Vanilla GuiGraphics adds +2 after the first component when multiple lines exist.
            int vanillaH = components.size() > 1 ? contentH + 2 : contentH;
            Vector2ic pos =
                    event.getTooltipPositioner()
                            .positionTooltip(
                                    screenW,
                                    screenH,
                                    event.getX(),
                                    event.getY(),
                                    contentW,
                                    vanillaH);
            tooltipX = pos.x();
            tooltipY = pos.y();
            contentH = vanillaH;
        }

        boolean jeiSource = isJeiTooltip(screen, stack, event.getX(), event.getY());
        HoverSnapshot snap =
                new HoverSnapshot(
                        stack,
                        stack.copyWithCount(1),
                        id,
                        tooltipX,
                        tooltipY,
                        contentW,
                        contentH,
                        screenW,
                        screenH,
                        modernUi,
                        jeiSource,
                        screen,
                        System.nanoTime());
        frameSnapshot = snap;
        if (matchesMode(mode, jeiSource)) {
            lastValidSnapshot = snap;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        HoverSnapshot snap = frameSnapshot;
        if (snap == null || snap.screen != event.getScreen()) return;
        WzQuickAskPrefs.Mode mode = WzQuickAskPrefs.mode();
        if (!matchesMode(mode, snap.jeiSource) || KEY.isUnbound()) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        boolean typing = isTypingInScreen(event.getScreen());
        String label = "按 [" + keyDisplayName() + "] 向问舟提问...";
        String typingNote = typing ? "  输入中已暂停" : "";

        MutableComponent textComponent = buildHintComponent(label, typingNote, typing);
        FormattedCharSequence visualText = textComponent.getVisualOrderText();
        int textW = font.width(visualText);
        int subInnerW = Math.max(snap.contentW, textW);
        int subInnerH = font.lineHeight;

        GuiGraphics graphics = event.getGuiGraphics();
        if (snap.modernUiActive) {
            float subX =
                    Math.max(
                            6.0f, Math.min(snap.tooltipX, (float) snap.screenW - subInnerW - 6.0f));
            float subY = snap.tooltipY + (float) snap.contentH + MODERN_UI_INNER_GAP;
            if (subY + (float) subInnerH + 6.0f > (float) snap.screenH) {
                subY = Math.max(6.0f, snap.tooltipY - (float) subInnerH - MODERN_UI_INNER_GAP);
            }
            if (ModernUiBridge.drawCapsule(
                    snap.rawStack,
                    graphics,
                    font,
                    visualText,
                    subInnerW,
                    subX,
                    subY,
                    snap.screenW,
                    snap.screenH)) {
                return;
            }
        }

        // Vanilla / non-ModernUI fallback using the same single-run gradient typography.
        int boxW = subInnerW + 8;
        int boxH = subInnerH + 8;
        float boxX = Math.max(4.0f, Math.min(snap.tooltipX - 4.0f, snap.screenW - boxW - 4.0f));
        float boxY = snap.tooltipY + snap.contentH + 4.0f + FALLBACK_VISUAL_GAP;
        if (boxY + boxH > snap.screenH - 4.0f) {
            boxY = Math.max(4.0f, snap.tooltipY - 4.0f - FALLBACK_VISUAL_GAP - boxH);
        }
        int intX = (int) Math.floor(boxX);
        int intY = (int) Math.floor(boxY);
        float fracX = boxX - intX;
        float fracY = boxY - intY;

        graphics.pose().pushPose();
        graphics.pose().translate(fracX, fracY, 500.0f);
        RenderSystem.enableBlend();
        try {
            drawRoundedTooltipBox(graphics, intX, intY, boxW, boxH, typing);
            graphics.drawString(font, visualText, intX + 4, intY + 4, 0xFFFFFFFF, true);
        } finally {
            graphics.pose().popPose();
        }
    }

    /**
     * Runs at LOW priority so JEI's own NORMAL-priority search-box handler consumes and cancels key
     * presses while the player is typing in JEI's filter field.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (event.isCanceled()) return;
        WzQuickAskPrefs.Mode mode = WzQuickAskPrefs.mode();
        if (mode == WzQuickAskPrefs.Mode.OFF || KEY.isUnbound()) return;
        if (Screen.hasControlDown() || Screen.hasAltDown()) return;
        if (!KEY.matches(event.getKeyCode(), event.getScanCode())) return;

        if (triggerQuickAsk(event.getScreen(), mode)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onScreenMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.isCanceled()) return;
        WzQuickAskPrefs.Mode mode = WzQuickAskPrefs.mode();
        if (mode == WzQuickAskPrefs.Mode.OFF || KEY.isUnbound()) return;
        if (!KEY.matchesMouse(event.getButton())) return;

        if (triggerQuickAsk(event.getScreen(), mode)) {
            event.setCanceled(true);
        }
    }

    private static boolean triggerQuickAsk(Screen screen, WzQuickAskPrefs.Mode mode) {
        if (screen == null || screen instanceof HubScreen) return false;
        if (isTypingInScreen(screen)) return false;

        HoverSnapshot snap = lastValidSnapshot;
        if (snap == null || snap.screen != screen) return false;
        if (System.nanoTime() - snap.capturedAt > SNAPSHOT_TTL_NANOS) return false;
        if (!matchesMode(mode, snap.jeiSource)) return false;

        WzPage page = liveWenzhouPage();
        if (page == null || !page.quickAskAvailable()) return false;

        ItemStack stack = snap.stackCopy.copyWithCount(1);
        ResourceLocation id = snap.id;
        frameSnapshot = null;
        lastValidSnapshot = null;
        ClientBootstrap.openForExternal(
                view -> {
                    WzPage target = view.externalPage(WzPage.class);
                    if (target != null) target.openQuickAsk(stack, id);
                });
        return true;
    }

    @SubscribeEvent
    public static void onScreenClose(ScreenEvent.Closing event) {
        if (frameSnapshot != null && frameSnapshot.screen == event.getScreen()) {
            frameSnapshot = null;
        }
        if (lastValidSnapshot != null && lastValidSnapshot.screen == event.getScreen()) {
            lastValidSnapshot = null;
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        frameSnapshot = null;
        lastValidSnapshot = null;
    }

    static String keyDisplayName() {
        String raw = KEY.getTranslatedKeyMessage().getString();
        if (raw == null || raw.isBlank()) return "G";
        String trimmed = raw.trim();
        return trimmed.length() == 1 ? trimmed.toUpperCase(Locale.ROOT) : trimmed;
    }

    private static boolean matchesMode(WzQuickAskPrefs.Mode mode, boolean jeiSource) {
        return mode == WzQuickAskPrefs.Mode.GLOBAL
                || (mode == WzQuickAskPrefs.Mode.JEI && jeiSource);
    }

    private static boolean availablePage() {
        WzPage page = liveWenzhouPage();
        return page != null && page.quickAskAvailable();
    }

    private static WzPage liveWenzhouPage() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !WzClient.available()) return null;
        HubView liveView = ClientHubSession.liveView();
        return liveView == null ? null : liveView.externalPage(WzPage.class);
    }

    private static boolean isJeiTooltip(Screen screen, ItemStack stack, int mouseX, int mouseY) {
        String className = screen.getClass().getName();
        if (className.startsWith("mezz.jei.")) return true;
        if (!ModList.get().isLoaded("jei")) return false;
        if (screen instanceof AbstractContainerScreen<?> container) {
            Slot underMouse = container.getSlotUnderMouse();
            if (underMouse != null
                    && !underMouse.getItem().isEmpty()
                    && ItemStack.isSameItemSameTags(underMouse.getItem(), stack)) {
                return false;
            }
            if (screen instanceof RecipeUpdateListener recipeScreen
                    && recipeScreen.getRecipeBookComponent().isVisible()) {
                int bookRight = container.getGuiLeft();
                int bookLeft = bookRight - 147;
                int bookTop = container.getGuiTop();
                int bookBottom = bookTop + 166;
                if (mouseX >= bookLeft
                        && mouseX <= bookRight
                        && mouseY >= bookTop
                        && mouseY <= bookBottom) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean isTypingInScreen(Screen screen) {
        if (isTypingListener(screen.getFocused())) return true;
        return containsFocusedTextInput(screen, 0);
    }

    private static boolean containsFocusedTextInput(ContainerEventHandler container, int depth) {
        if (container == null || depth > 4) return false;
        for (GuiEventListener child : container.children()) {
            if (isTypingListener(child)) return true;
            if (child instanceof ContainerEventHandler nested
                    && containsFocusedTextInput(nested, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTypingListener(GuiEventListener listener) {
        if (listener == null) return false;
        if (listener instanceof EditBox editBox) return editBox.canConsumeInput();
        if (listener.isFocused()) {
            String name = listener.getClass().getName().toLowerCase(Locale.ROOT);
            if (name.contains("editbox")
                    || name.contains("textfield")
                    || name.contains("searchfield")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds a single styled MutableComponent with per-codepoint blue-white gradient colors so
     * ModernUI's HarfBuzz text shaper lays out the entire run with crisp native kerning.
     */
    private static MutableComponent buildHintComponent(
            String label, String typingNote, boolean muted) {
        MutableComponent root = Component.empty();
        int cpCount = label.codePointCount(0, label.length());
        int idx = 0;
        for (int offset = 0; offset < label.length(); ) {
            int cp = label.codePointAt(offset);
            int charCount = Character.charCount(cp);
            String glyph = label.substring(offset, offset + charCount);
            float t = cpCount <= 1 ? 0.5f : (float) idx / (float) (cpCount - 1);
            int rgb = sampleBlueWhiteGradient(t) & 0xFFFFFF;
            if (muted) rgb = dimRgb(rgb, 0.65f);
            root.append(Component.literal(glyph).withStyle(Style.EMPTY.withColor(rgb)));
            idx++;
            offset += charCount;
        }
        if (!typingNote.isEmpty()) {
            root.append(Component.literal(typingNote).withStyle(Style.EMPTY.withColor(0x93A1B8)));
        }
        return root;
    }

    private static int dimRgb(int rgb, float factor) {
        int r = Math.round(((rgb >> 16) & 0xFF) * factor);
        int g = Math.round(((rgb >> 8) & 0xFF) * factor);
        int b = Math.round((rgb & 0xFF) * factor);
        return (r << 16) | (g << 8) | b;
    }

    /** Draws a 2px-beveled rounded dark card for non-ModernUI clients. */
    private static void drawRoundedTooltipBox(
            GuiGraphics g, int x, int y, int w, int h, boolean muted) {
        int bg = muted ? 0xB8090A0C : BOX_BG;
        int border = muted ? 0x90E0E4EC : BOX_BORDER;
        g.fill(x + 2, y + 1, x + w - 2, y + h - 1, bg);
        g.fill(x + 1, y + 2, x + w - 1, y + h - 2, bg);

        g.fill(x + 2, y, x + w - 2, y + 1, border);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, border);
        g.fill(x, y + 2, x + 1, y + h - 2, border);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, border);

        g.fill(x + 1, y + 1, x + 2, y + 2, border);
        g.fill(x + w - 2, y + 1, x + w - 1, y + 2, border);
        g.fill(x + 1, y + h - 2, x + 2, y + h - 1, border);
        g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, border);
    }

    private static int sampleBlueWhiteGradient(float t) {
        if (t <= 0.5f) {
            return lerpArgb(GRAD_LEFT, GRAD_MID, t * 2f);
        }
        return lerpArgb(GRAD_MID, GRAD_RIGHT, (t - 0.5f) * 2f);
    }

    private static int lerpArgb(int a, int b, float t) {
        int aa = (a >>> 24) & 0xFF, ba = (b >>> 24) & 0xFF;
        int ar = (a >>> 16) & 0xFF, br = (b >>> 16) & 0xFF;
        int ag = (a >>> 8) & 0xFF, bg = (b >>> 8) & 0xFF;
        int ab = a & 0xFF, bb = b & 0xFF;
        int ca = Math.round(aa + (ba - aa) * t);
        int cr = Math.round(ar + (br - ar) * t);
        int cg = Math.round(ag + (bg - ag) * t);
        int cb = Math.round(ab + (bb - ab) * t);
        return (ca << 24) | (cr << 16) | (cg << 8) | cb;
    }

    /**
     * Custom ClientTooltipComponent (intentionally not ClientTextTooltip so ModernUI never adds a
     * title separator bar or centers it) padded to the exact width of the main tooltip above.
     */
    private record QuickAskTooltipComponent(
            FormattedCharSequence text, int targetWidth, int lineHeight)
            implements ClientTooltipComponent {
        @Override
        public int getHeight() {
            // ModernUI subtracts 2 from height when rendering a single-component tooltip.
            return lineHeight + 2;
        }

        @Override
        public int getWidth(Font font) {
            return targetWidth;
        }

        @Override
        public void renderText(
                Font font, int x, int y, Matrix4f pose, MultiBufferSource.BufferSource buffer) {
            font.drawInBatch(
                    text,
                    (float) x,
                    (float) y,
                    0xFFFFFFFF,
                    true,
                    pose,
                    buffer,
                    Font.DisplayMode.NORMAL,
                    0,
                    0xF000F0);
        }
    }

    /** Optional reflection bridge to ModernUI's TooltipRenderer (zero hard dependency). */
    private static final class ModernUiBridge {
        private static volatile boolean initialized;
        private static volatile boolean available;
        private static Field sTooltipField;
        private static Field sExactPositioningField;
        private static Method getInstanceMethod;
        private static Method drawExtTooltipMethod;

        private static void ensureInit() {
            if (initialized) return;
            synchronized (ModernUiBridge.class) {
                if (initialized) return;
                try {
                    Class<?> tooltipCls = Class.forName("icyllis.modernui.mc.TooltipRenderer");
                    Class<?> uiManagerCls = Class.forName("icyllis.modernui.mc.UIManager");
                    sTooltipField = tooltipCls.getField("sTooltip");
                    sExactPositioningField = tooltipCls.getField("sExactPositioning");
                    getInstanceMethod = uiManagerCls.getMethod("getInstance");
                    drawExtTooltipMethod =
                            uiManagerCls.getMethod(
                                    "drawExtTooltip",
                                    ItemStack.class,
                                    GuiGraphics.class,
                                    List.class,
                                    int.class,
                                    int.class,
                                    Font.class,
                                    int.class,
                                    int.class,
                                    ClientTooltipPositioner.class);
                    available = true;
                } catch (Throwable ignored) {
                    available = false;
                } finally {
                    initialized = true;
                }
            }
        }

        static boolean isTooltipActive() {
            ensureInit();
            if (!available) return false;
            try {
                return sTooltipField.getBoolean(null);
            } catch (Throwable ignored) {
                return false;
            }
        }

        static boolean isExactPositioning() {
            ensureInit();
            if (!available) return false;
            try {
                return sExactPositioningField.getBoolean(null);
            } catch (Throwable ignored) {
                return false;
            }
        }

        static boolean drawCapsule(
                ItemStack stack,
                GuiGraphics graphics,
                Font font,
                FormattedCharSequence text,
                int targetWidth,
                float subX,
                float subY,
                int screenW,
                int screenH) {
            ensureInit();
            if (!available) return false;
            try {
                Object manager = getInstanceMethod.invoke(null);
                if (manager == null) return false;
                int intX = (int) Math.floor(subX);
                int intY = (int) Math.floor(subY);
                float fracX = subX - intX;
                float fracY = subY - intY;
                ClientTooltipPositioner fixedPos =
                        (sw, sh, mx, my, w, h) -> new Vector2i(intX, intY);
                List<ClientTooltipComponent> list =
                        List.of(new QuickAskTooltipComponent(text, targetWidth, font.lineHeight));
                graphics.pose().pushPose();
                graphics.pose().translate(fracX, fracY, 10.0f);
                try {
                    drawExtTooltipMethod.invoke(
                            manager, stack, graphics, list, intX, intY, font, screenW, screenH,
                            fixedPos);
                } finally {
                    graphics.pose().popPose();
                }
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    @Mod.EventBusSubscriber(
            modid = "wenzhouai",
            value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent event) {
            event.register(KEY);
        }
    }
}
