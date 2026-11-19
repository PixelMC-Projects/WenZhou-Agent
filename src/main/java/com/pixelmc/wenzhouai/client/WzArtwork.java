package com.pixelmc.wenzhouai.client;

import com.pixelmc.hub.client.render.HubDraw;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.types.Rect;
import java.io.IOException;
import java.io.InputStream;

final class WzArtwork {
    private static class Boat {
        static final Image IMAGE = load();

        private static Image load() {
            try (InputStream in =
                    WzArtwork.class.getResourceAsStream("/assets/wenzhouai/textures/ui/boat.png")) {
                if (in == null) throw new IllegalStateException("Missing WenZhou boat");
                return Image.makeFromEncoded(in.readAllBytes());
            } catch (IOException error) {
                throw new IllegalStateException("Unable to decode WenZhou boat", error);
            }
        }
    }

    static void boat(HubDraw draw, float x, float y, float width) {
        Image image = Boat.IMAGE;
        float height = width / 1.7f;
        float imageWidth = width * 1.2f;
        float imageHeight = imageWidth * image.getHeight() / image.getWidth();
        draw.save();
        draw.clipRoundRect(x, y, width, height, 0);
        draw.canvas()
                .drawImageRect(
                        image,
                        Rect.makeWH(image.getWidth(), image.getHeight()),
                        Rect.makeXYWH(
                                x + (width - imageWidth) * .5f,
                                y + (height - imageHeight) * .5f,
                                imageWidth,
                                imageHeight),
                        SamplingMode.LINEAR,
                        null,
                        false);
        draw.restore();
    }

    private WzArtwork() {}
}
