package com.pixelmc.wenzhouai.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.lwjgl.glfw.GLFW;

import java.util.concurrent.TimeUnit;

/** Counts visible reading time on the game window, including frames slower than a second. */
@Mod.EventBusSubscriber(modid = "wenzhouai", value = Dist.CLIENT)
public final class WzReadingTime {
    private static final long REQUIRED = TimeUnit.SECONDS.toNanos(30);
    private static WzReadingTime reader;
    private long elapsed;
    private long previous;
    private boolean wasVisible;
    private long window;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (reader != null && event.phase == TickEvent.Phase.END) reader.observeWindow();
    }

    int remaining(boolean reading, long gameWindow) {
        if (reading && elapsed < REQUIRED) {
            window = gameWindow;
            reader = this;
            observeWindow();
        } else {
            pause();
        }
        return (int) Math.ceil((REQUIRED - elapsed) / 1_000_000_000d);
    }

    private void observeWindow() {
        // HubRenderer has switched to its hidden, shared context while drawing the reader.
        boolean visible =
                window != 0
                        && GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_VISIBLE) == GLFW.GLFW_TRUE
                        && GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_FALSE;
        sample(System.nanoTime(), visible);
        if (elapsed == REQUIRED) pause();
    }

    void sample(long now, boolean visible) {
        if (visible && wasVisible)
            elapsed = Math.min(REQUIRED, elapsed + Math.max(0, now - previous));
        previous = now;
        wasVisible = visible;
    }

    void pause() {
        if (reader == this) reader = null;
        wasVisible = false;
    }

    void reset() {
        pause();
        elapsed = 0;
    }
}
