/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.pixelmc.hub.client.ClientBootstrap;
import com.pixelmc.hub.client.ClientHubSession;
import com.pixelmc.hub.client.HubScreen;
import com.pixelmc.hub.client.ui.HubView;
import com.pixelmc.wenzhouai.client.selection.Atmosphere;
import com.pixelmc.wenzhouai.client.selection.Outline;
import com.pixelmc.wenzhouai.client.selection.OutlinePath;
import com.pixelmc.wenzhouai.client.selection.Panel;
import com.pixelmc.wenzhouai.client.selection.SelectionMotion;
import com.pixelmc.wenzhouai.client.selection.WzSelectionHud;
import com.pixelmc.wenzhouai.network.WzBlockReference;
import com.pixelmc.wenzhouai.network.WzClient;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.lwjgl.glfw.GLFW;

import java.util.concurrent.TimeUnit;

/** Suspends the Hub while a single block is captured from the player's live view. */
@Mod.EventBusSubscriber(modid = "wenzhouai", value = Dist.CLIENT)
public final class WzWorldPicker {
    private static final long EXIT = 180_000_000L, RECEIPT = 200_000_000L;
    private static final KeyMapping KEY =
            new KeyMapping(
                    "key.wenzhouai.select_block",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_UNKNOWN,
                    "key.categories.wenzhouai");

    private enum Phase {
        CLOSING,
        AIMING,
        CHECKING,
        LEAVING,
        RESTORING
    }

    private record Target(BlockPos pos, BlockState state, OutlinePath path, String name) {}

    private static WzWorldPicker current;
    private final WzPage page;
    private final HubScreen origin;
    private final ClientPacketListener connection;
    private final ClientLevel level;
    private final LocalPlayer player;
    private final long epoch;
    private final boolean dark, reduced;
    private final SelectionMotion<Target> motion = new SelectionMotion<>();
    private WzSelectionHud hudLayout;
    private int hudWidth, hudHeight;
    private Phase phase = Phase.CLOSING;
    private long at = System.nanoTime(), entered, titleAt;
    private boolean suspended, retriedReturn;
    private String oldTitle;
    private String error;
    private WzBlockReference result;

    private WzWorldPicker(
            Minecraft minecraft, HubScreen origin, WzPage page, boolean dark, boolean reduced) {
        this.page = page;
        this.origin = origin;
        this.level = minecraft.level;
        this.player = minecraft.player;
        this.connection = minecraft.getConnection();
        this.epoch = page.model.epoch();
        this.dark = dark;
        this.reduced = reduced;
    }

    static boolean begin(WzPage page, boolean dark, boolean reduced) {
        Minecraft minecraft = Minecraft.getInstance();
        if (current != null
                || !(minecraft.screen instanceof HubScreen origin)
                || minecraft.level == null
                || minecraft.player == null
                || minecraft.player.isSpectator()
                || !WzClient.available()) return false;
        current = new WzWorldPicker(minecraft, origin, page, dark, reduced);
        return true;
    }

    private static void shortcut(Minecraft minecraft, long time) {
        HubView liveView = ClientHubSession.liveView();
        if (liveView == null
                || minecraft.screen != null
                || minecraft.getOverlay() != null
                || minecraft.level == null
                || minecraft.player == null
                || minecraft.player.isSpectator()
                || !WzClient.available()) return;
        WzPage page = liveView.externalPage(WzPage.class);
        if (page == null) return;
        if (!page.prepareWorldBlockFromGame()) {
            minecraft.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("问舟方块引用当前不可用。"), true);
            return;
        }
        current =
                new WzWorldPicker(minecraft, null, page, liveView.dark(), liveView.reducedMotion());
        current.startAiming(time);
        discard(minecraft.options.keyUse);
    }

    public static boolean containsDraft(WzPage page) {
        return current != null && current.page == page;
    }

    static boolean closing(WzPage page) {
        return containsDraft(page) && current.phase == Phase.CLOSING;
    }

    static float closingOpacity(WzPage page, long time) {
        return closing(page)
                ? current.reduced
                        ? 0
                        : 1 - SelectionMotion.smooth((time - current.at) / (double) EXIT)
                : 1;
    }

    public static boolean hubKey(WzPage page, int key, int scan) {
        if (closing(page)) return true;
        if (KEY.isUnbound() || !KEY.matches(key, scan) || !page.canPickWorldBlock()) return false;
        page.openWorldPicker();
        return true;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void mouseShortcut(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof HubScreen screen)) return;
        WzPage page = screen.view().externalPage(WzPage.class);
        if (page == null) return;
        if (closing(page)) {
            event.setCanceled(true);
            return;
        }
        if (KEY.isUnbound() || !KEY.matchesMouse(event.getButton())) return;
        if (!page.canPickWorldBlock()) return;
        page.openWorldPicker();
        event.setCanceled(true);
    }

    public static void hubVisible(WzPage page) {
        if (!containsDraft(page) || current.phase != Phase.RESTORING) return;
        var picker = current;
        current = null;
        if (!picker.valid(Minecraft.getInstance())
                || !page.model.granted
                || !page.model.blockReferences) {
            page.closePanel();
            return;
        }
        page.resumeWorldBlock();
        page.finishWorldBlock(picker.result);
        if (picker.error != null) page.worldBlockError(picker.error);
    }

    private boolean valid(Minecraft minecraft) {
        return minecraft.level == level
                && minecraft.player == player
                && minecraft.getConnection() == connection
                && page.model.epoch() == epoch
                && player.isAlive()
                && !player.isSpectator();
    }

    private boolean inWorld() {
        return phase != Phase.CLOSING
                && phase != Phase.RESTORING
                && !suspended
                && worldScreenReady(Minecraft.getInstance());
    }

    private static boolean worldScreenReady(Minecraft minecraft) {
        return minecraft.screen == null && minecraft.getOverlay() == null;
    }

    private void finish(WzBlockReference reference, String message) {
        if (phase != Phase.AIMING && phase != Phase.CHECKING) return;
        result = reference;
        error = message;
        phase = Phase.LEAVING;
        at = System.nanoTime();
    }

    private static void abandon() {
        if (current == null) return;
        var picker = current;
        current = null;
        if (picker.phase != Phase.CLOSING) picker.page.closePanel();
        Panel.clearTitles();
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        abandon();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft minecraft = Minecraft.getInstance();
        long now = System.nanoTime();
        boolean shortcut = KEY.consumeClick();
        while (KEY.consumeClick()) {
            /* Coalesce repeated clicks to one mode change. */
        }
        var picker = current;
        if (picker == null) {
            if (shortcut) shortcut(minecraft, now);
            return;
        }
        if (!picker.valid(minecraft)) {
            abandon();
            return;
        }
        if (picker.phase == Phase.CLOSING) {
            if (worldScreenReady(minecraft)) {
                picker.startAiming(now);
                discard(minecraft.options.keyUse);
            } else if (minecraft.screen == picker.origin
                    && minecraft.getOverlay() == null
                    && (picker.reduced || now - picker.at >= EXIT)) {
                picker.startAiming(now);
                discard(minecraft.options.keyUse);
                minecraft.setScreen(null);
            }
            return;
        }
        if (picker.phase == Phase.RESTORING) {
            // A transient screen may supersede a delayed Hub open. Keep the draft available
            // on the next normal Hub open and retry the automatic return once.
            if (!picker.retriedReturn
                    && now - picker.at > 5_000_000_000L
                    && worldScreenReady(minecraft)) {
                picker.retriedReturn = true;
                ClientBootstrap.open();
            }
            return;
        }
        if (!worldScreenReady(minecraft)) {
            picker.suspended = true;
            return;
        }
        if (picker.suspended) {
            picker.suspended = false;
            discard(minecraft.options.keyUse);
            if (picker.phase == Phase.AIMING) picker.startAiming(now);
        }
        var keys = minecraft.options;
        for (var slot : keys.keyHotbarSlots) discard(slot);
        discard(keys.keyAttack);
        discard(keys.keyPickItem);
        discard(keys.keyDrop);
        discard(keys.keySwapOffhand);
        discard(keys.keyInventory);
        discard(keys.keySaveHotbarActivator);
        discard(keys.keyLoadHotbarActivator);
        discard(keys.keyChat);
        discard(keys.keyCommand);
        discard(keys.keyAdvancements);
        discard(keys.keySocialInteractions);
        if (picker.phase == Phase.LEAVING && (picker.reduced || now - picker.at >= RECEIPT)) {
            discard(keys.keyUse);
            if (picker.origin == null && picker.result == null && picker.error == null) {
                abandon();
                return;
            }
            picker.phase = Phase.RESTORING;
            picker.at = now;
            Panel.clearTitles();
            ClientBootstrap.open();
        }
    }

    private void startAiming(long now) {
        phase = Phase.AIMING;
        entered = now;
        motion.clear(seconds(now));
    }

    private static void discard(KeyMapping key) {
        while (key.consumeClick()) {
            /* Clear queued actions before vanilla processes them. */
        }
        key.setDown(false);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void opening(ScreenEvent.Opening event) {
        if (current == null || !current.inWorld()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getNewScreen() instanceof PauseScreen
                && minecraft.isWindowActive()
                && InputConstants.isKeyDown(
                        minecraft.getWindow().getWindow(), GLFW.GLFW_KEY_ESCAPE)) {
            event.setCanceled(true);
            current.finish(null, null);
        } else if (event.getNewScreen() instanceof HubScreen) {
            event.setCanceled(true);
        } else if (event.getNewScreen() != null) {
            current.suspended = true;
        }
    }

    @SubscribeEvent
    public static void key(InputEvent.Key event) {
        var picker = current;
        if (picker == null || !picker.inWorld() || event.getAction() != GLFW.GLFW_PRESS) return;
        if (event.getKey() == GLFW.GLFW_KEY_ESCAPE
                || !KEY.isUnbound() && KEY.matches(event.getKey(), event.getScanCode()))
            picker.finish(null, null);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void interact(InputEvent.InteractionKeyMappingTriggered event) {
        var picker = current;
        if (picker == null || !picker.inWorld()) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        if (event.isUseItem()) picker.pick();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void mouse(InputEvent.MouseButton.Pre event) {
        var picker = current;
        if (picker == null || !picker.inWorld()) return;
        int button = event.getButton();
        var keys = Minecraft.getInstance().options;
        boolean choose = button == GLFW.GLFW_MOUSE_BUTTON_RIGHT || keys.keyUse.matchesMouse(button);
        if (choose
                || button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                || button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE
                || keys.keyAttack.matchesMouse(button)
                || keys.keyPickItem.matchesMouse(button)) {
            event.setCanceled(true);
            if (choose && event.getAction() == GLFW.GLFW_PRESS) picker.pick();
        }
    }

    private void pick() {
        if (phase != Phase.AIMING) return;
        BlockHitResult hit = hit();
        if (hit == null) return;
        Target target = target(hit);
        if (target == null) return;
        phase = Phase.CHECKING;
        at = System.nanoTime();
        motion.observe(target, seconds(at));
        JsonObject body = new JsonObject();
        body.addProperty("pos", Long.toString(target.pos.asLong()));
        WzClient.request("block.capture", body)
                .completeOnTimeout(null, 6, TimeUnit.SECONDS)
                .whenComplete(
                        (reply, failed) ->
                                Minecraft.getInstance()
                                        .execute(
                                                () -> {
                                                    if (current != this || phase != Phase.CHECKING)
                                                        return;
                                                    var ref = WzBlockReference.fromResponse(reply);
                                                    String message =
                                                            ref != null
                                                                    ? null
                                                                    : reply == null
                                                                                    || !reply.has(
                                                                                            "error")
                                                                            ? "服务器暂未确认方块，可重新选择。"
                                                                            : reply.get("error")
                                                                                    .getAsString();
                                                    finish(ref, message);
                                                }));
    }

    @SubscribeEvent
    public static void overlay(RenderGuiOverlayEvent.Pre event) {
        if (current != null && current.inWorld()) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void hand(RenderHandEvent event) {
        if (current != null && current.inWorld()) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void scroll(InputEvent.MouseScrollingEvent event) {
        if (current != null && current.inWorld()) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void outline(RenderHighlightEvent.Block event) {
        var picker = current;
        if (picker == null || !picker.inWorld()) return;
        if (Outline.customAvailable() || Minecraft.getInstance().options.hideGui) {
            event.setCanceled(true);
            return;
        }
        long now = System.nanoTime();
        var motion = picker.motion;
        var target = motion.current();
        var outgoing = motion.outgoing();
        float theme = picker.dark ? 1 : 0;
        float fade = picker.fade(now);
        double time = picker.reduced ? 0 : seconds(now - picker.entered);
        if (outgoing != null && !picker.reduced) {
            Outline.drawCompatible(
                    event,
                    outgoing.pos,
                    outgoing.path,
                    theme,
                    motion.oldFill(),
                    fade * motion.oldOpacity(seconds(now)),
                    time);
        }
        if (target != null) {
            Outline.drawCompatible(
                    event,
                    target.pos,
                    target.path,
                    theme,
                    picker.reduced ? 1 : motion.edge(),
                    fade,
                    time);
            event.setCanceled(true);
        }
    }

    private static BlockHitResult hit() {
        return Minecraft.getInstance().hitResult instanceof BlockHitResult hit
                        && hit.getType() == HitResult.Type.BLOCK
                ? hit
                : null;
    }

    private Target target(BlockHitResult hit) {
        if (hit == null) return null;
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        Target target = motion.current();
        if (target != null && pos.equals(target.pos) && state == target.state) return target;
        target = motion.outgoing();
        if (target != null && pos.equals(target.pos) && state == target.state) return target;
        var shape = state.getShape(level, pos, CollisionContext.of(player));
        if (shape.isEmpty()) return null;
        var anchor = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
        return new Target(
                pos.immutable(),
                state,
                OutlinePath.capture(shape, anchor),
                state.getBlock().getName().getString());
    }

    private void observe(long now) {
        Target before = motion.current();
        Target next = phase == Phase.AIMING ? target(hit()) : before;
        motion.observe(next, seconds(now));
        if (next == null || next == before) return;
        String previous =
                oldTitle != null && now - titleAt < 75_000_000L
                        ? oldTitle
                        : before == null ? null : before.name;
        oldTitle = motion.card() > 0 && !next.name.equals(previous) ? previous : null;
        titleAt = now;
    }

    private float fade(long now) {
        return phase == Phase.LEAVING
                ? 1 - SelectionMotion.smooth((now - at) / (double) RECEIPT)
                : 1;
    }

    @SubscribeEvent
    public static void world(RenderLevelStageEvent event) {
        var picker = current;
        var mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
                || picker == null
                || !picker.inWorld()
                || mc.options.hideGui) return;
        long now = System.nanoTime();
        picker.observe(now);
        if (!Outline.customAvailable()) return;
        float fade = picker.fade(now);
        float theme = picker.dark ? 1 : 0;
        double time = picker.reduced ? 0 : seconds(now - picker.entered);
        var motion = picker.motion;
        Target outgoing = motion.outgoing();
        Target target = motion.current();
        if (outgoing != null && !picker.reduced)
            Outline.draw(
                    event,
                    outgoing.pos,
                    outgoing.path,
                    theme,
                    motion.oldFill(),
                    fade * motion.oldOpacity(seconds(now)),
                    time);
        if (target != null)
            Outline.draw(
                    event,
                    target.pos,
                    target.path,
                    theme,
                    picker.reduced ? 1 : motion.edge(),
                    fade,
                    time);
    }

    @SubscribeEvent
    public static void hud(RenderGuiEvent.Post event) {
        var picker = current;
        Minecraft minecraft = Minecraft.getInstance();
        if (picker == null || !picker.inWorld() || minecraft.options.hideGui) return;
        var gui = event.getGuiGraphics();
        var window = minecraft.getWindow();
        int framebufferWidth = window.getWidth(), framebufferHeight = window.getHeight();
        if (framebufferWidth <= 0 || framebufferHeight <= 0) return;
        var layout = picker.hudLayout(framebufferWidth, framebufferHeight);
        long now = System.nanoTime();
        var motion = picker.motion;
        var target = motion.current();
        float entry =
                picker.reduced ? 1 : SelectionMotion.smooth((now - picker.entered) / 240_000_000d);
        float opacity = entry * picker.fade(now);
        float card = picker.reduced && target != null ? 1 : motion.card();
        gui.pose().pushPose();
        gui.pose()
                .scale(
                        layout.guiX(framebufferWidth, window.getGuiScaledWidth()),
                        layout.guiY(framebufferHeight, window.getGuiScaledHeight()),
                        1);
        try {
            Atmosphere.render(
                    gui,
                    layout.width(),
                    layout.height(),
                    layout.connectorX(),
                    layout.connectorY(),
                    picker.reduced && target != null ? 1 : motion.wire(),
                    picker.reduced && target != null ? 1 : motion.edge(),
                    picker.dark ? 1 : 0,
                    opacity,
                    picker.reduced ? 0 : seconds(now - picker.entered));
            if (target != null && card > 0) {
                float swap =
                        picker.reduced || picker.oldTitle == null
                                ? 1
                                : SelectionMotion.smooth((now - picker.titleAt) / 150_000_000d);
                Panel.drawLayers(
                        gui,
                        target.name,
                        picker.oldTitle,
                        swap,
                        picker.phase == Phase.CHECKING,
                        picker.dark,
                        layout.cardX(),
                        layout.cardY(),
                        opacity * card,
                        card);
                if (swap >= 1) picker.oldTitle = null;
            }
        } finally {
            gui.pose().popPose();
        }
    }

    private WzSelectionHud hudLayout(int width, int height) {
        if (hudLayout == null || hudWidth != width || hudHeight != height) {
            hudWidth = width;
            hudHeight = height;
            hudLayout = WzSelectionHud.fit(width, height);
        }
        return hudLayout;
    }

    private static double seconds(long nanos) {
        return nanos / 1_000_000_000d;
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

        @SubscribeEvent
        public static void shaders(RegisterShadersEvent event) {
            Outline.registerShader(event);
        }
    }
}
