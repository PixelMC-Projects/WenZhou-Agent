/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client.selection;

/** The accepted UHD composition is the reference for all selection-overlay coordinates. */
public record WzSelectionHud(float width, float height, float scale) {
    private static final float REFERENCE_WIDTH = 3840, REFERENCE_HEIGHT = 2160;
    public static final int FRAME_WIDTH = 560, FRAME_HEIGHT = 259;
    private static final float OFFSET_X = 215, OFFSET_Y = 74;
    private static final float RIGHT_MARGIN = 10, BOTTOM_MARGIN = 12;
    private static final float CONNECTOR_Y = 93;

    public static WzSelectionHud fit(int framebufferWidth, int framebufferHeight) {
        float scale = Math.min(framebufferWidth / REFERENCE_WIDTH, framebufferHeight / REFERENCE_HEIGHT);
        return new WzSelectionHud(framebufferWidth / scale, framebufferHeight / scale, scale);
    }

    public float cardX() {
        return Math.min(width / 2 + OFFSET_X, width - FRAME_WIDTH - RIGHT_MARGIN);
    }

    public float cardY() {
        return Math.min(height / 2 + OFFSET_Y, height - FRAME_HEIGHT - BOTTOM_MARGIN);
    }

    public float connectorX() {
        return cardX() - 1;
    }

    public float connectorY() {
        return cardY() + CONNECTOR_Y;
    }

    public float guiX(int framebufferWidth, int guiWidth) {
        return scale * guiWidth / framebufferWidth;
    }

    public float guiY(int framebufferHeight, int guiHeight) {
        return scale * guiHeight / framebufferHeight;
    }
}
