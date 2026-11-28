/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client;

import com.mojang.blaze3d.platform.InputConstants;
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
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.resources.ResourceLocation;
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

import org.joml.Vector2ic;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * Renders the JEI-styled blue-white gradient quick-ask hint bar beneath hovered item tooltips and
 * opens Wenzhou's dedicated item quick-ask stage when the bound hotkey (default G) is pressed.
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
    private static final int GAP = 6;
    private static final int SUB_HEIGHT = 20;
    private static final long SNAPSHOT_TTL_NANOS = 150_000_000L;

    private record HoverSnapshot(
            ItemStack stack,
            ResourceLocation id,
            int boxX,
            int boxY,
            int boxW,
            int boxH,
            int screenW,
            int screenH,
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

        int contentW = 0;
        int contentH = event.getComponents().size() == 1 ? -2 : 0;
        for (var component : event.getComponents()) {
            contentW = Math.max(contentW, component.getWidth(event.getFont()));
            contentH += component.getHeight();
        }
        int screenW = event.getScreenWidth();
        int screenH = event.getScreenHeight();
        Vector2ic pos =
                event.getTooltipPositioner()
                        .positionTooltip(
                                screenW, screenH, event.getX(), event.getY(), contentW, contentH);
        int boxX = pos.x() - 4;
        int boxY = pos.y() - 4;
        int boxW = contentW + 8;
        int boxH = contentH + 8;
        boolean jeiSource = isJeiTooltip(screen, stack, event.getX(), event.getY());

        HoverSnapshot snap =
                new HoverSnapshot(
                        stack.copyWithCount(1),
                        id,
                        boxX,
                        boxY,
                        boxW,
                        boxH,
                        screenW,
                        screenH,
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
        int textW = font.width(label) + (typing ? font.width(typingNote) : 0);
        int subW = Math.max(snap.boxW, textW + 18);
        int subH = SUB_HEIGHT;

        int subX = Math.max(6, Math.min(snap.boxX, snap.screenW - subW - 6));
        int subY = snap.boxY + snap.boxH + GAP;
        if (subY + subH > snap.screenH - 6) {
            subY = Math.max(6, snap.boxY - GAP - subH);
        }

        GuiGraphics graphics = event.getGuiGraphics();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 500);
        RenderSystem.enableBlend();
        try {
            drawRoundedTooltipBox(graphics, subX, subY, subW, subH, typing);
            int textX = subX + 9;
            int textY = subY + (subH - font.lineHeight) / 2 + 1;
            drawBlueWhiteGradientText(graphics, font, label, textX, textY, typing ? 0.65f : 1f);
            if (typing) {
                graphics.drawString(
                        font, typingNote, textX + font.width(label), textY, 0xFF93A1B8, true);
            }
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

        ItemStack stack = snap.stack.copyWithCount(1);
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

    /** Draws a 2px-beveled rounded dark card matching JEI / Modern UI tooltip styling. */
    private static void drawRoundedTooltipBox(
            GuiGraphics g, int x, int y, int w, int h, boolean muted) {
        int bg = muted ? 0xB8090A0C : BOX_BG;
        int border = muted ? 0x90E0E4EC : BOX_BORDER;
        // Fill interior with rounded 2px corners
        g.fill(x + 2, y + 1, x + w - 2, y + h - 1, bg);
        g.fill(x + 1, y + 2, x + w - 1, y + h - 2, bg);

        // Crisp 1px light-gray border matching JEI / Modern UI tooltip outline
        g.fill(x + 2, y, x + w - 2, y + 1, border);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, border);
        g.fill(x, y + 2, x + 1, y + h - 2, border);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, border);

        // 4 corner pixels for clean rounded silhouette
        g.fill(x + 1, y + 1, x + 2, y + 2, border);
        g.fill(x + w - 2, y + 1, x + w - 1, y + 2, border);
        g.fill(x + 1, y + h - 2, x + 2, y + h - 1, border);
        g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, border);
    }

    private static void drawBlueWhiteGradientText(
            GuiGraphics g, Font font, String text, int x, int y, float alphaScale) {
        int totalWidth = Math.max(1, font.width(text));
        int cursor = x;
        for (int offset = 0; offset < text.length(); ) {
            int cp = text.codePointAt(offset);
            int charCount = Character.charCount(cp);
            String glyph = text.substring(offset, offset + charCount);
            int glyphWidth = font.width(glyph);
            float t = Math.min(1f, Math.max(0f, ((cursor - x) + glyphWidth * 0.5f) / totalWidth));
            int color = scaleAlpha(sampleBlueWhiteGradient(t), alphaScale);
            g.drawString(font, glyph, cursor, y, color, true);
            cursor += glyphWidth;
            offset += charCount;
        }
    }

    private static int scaleAlpha(int argb, float scale) {
        if (scale >= 0.999f) return argb;
        int a = Math.max(8, Math.min(255, Math.round(((argb >>> 24) & 0xFF) * scale)));
        return (a << 24) | (argb & 0xFFFFFF);
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
