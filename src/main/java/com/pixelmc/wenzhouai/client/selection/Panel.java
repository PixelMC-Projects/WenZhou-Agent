/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client.selection;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Panel {
    private static final float ART_SCALE = 1.75f;
    private static final int DESIGN_WIDTH = 320, DESIGN_HEIGHT = 148;
    private static final int WIDTH = WzSelectionHud.FRAME_WIDTH, HEIGHT = WzSelectionHud.FRAME_HEIGHT;
    private static final int PAD = 14;
    private static final int GLOW_PAD = 48;
    private static final int TITLE_SLOTS = 20;
    private static final ArrayDeque<Integer> freeTitleSlots = new ArrayDeque<>(TITLE_SLOTS);
    private static final ResourceLocation[] glows = new ResourceLocation[2];
    private static final ResourceLocation[] shells = new ResourceLocation[4];
    private record Title(String name, boolean dark) {}
    private record Cached(int slot, ResourceLocation texture) {}
    private static final Map<Title, Cached> titles = new LinkedHashMap<>(TITLE_SLOTS, .75f, true);
    private static final Font FONT = new Font("Microsoft YaHei UI",Font.PLAIN,12);
    private static final BufferedImage BOAT = logo();

    static {
        resetTitleSlots();
    }

    private static BufferedImage logo() {
        try (var stream=Panel.class.getResourceAsStream("/assets/wenzhouai/textures/ui/boat.png")) {
            var original=ImageIO.read(stream);
            int minX=original.getWidth(), minY=original.getHeight(), maxX=0, maxY=0;
            for(int y=0;y<original.getHeight();y++) for(int x=0;x<original.getWidth();x++)
                if ((original.getRGB(x,y)>>>24)>28) {
                    minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                }
            return original.getSubimage(minX,minY,maxX-minX+1,maxY-minY+1);
        } catch(IOException e) { throw new UncheckedIOException(e); }
    }

    public static void drawLayers(GuiGraphics gui,String title,String previous,float swap,boolean checking,boolean dark,
                           float x,float y,float opacity,float reveal) {
        if(opacity<=.001f || reveal<=0) return;
        int width=Math.round((WIDTH+2*PAD)*reveal);
        int bodyWidth=Math.min(WIDTH,Math.max(0,width-PAD));
        if(bodyWidth>0) drawGlow(gui,Math.round(x),Math.round(y),bodyWidth,dark,opacity);
        int shellSlot = (dark ? 2 : 0) + (checking ? 1 : 0);
        if (shells[shellSlot] == null) {
            ResourceLocation texture = location("shell_" + shellSlot);
            make("", dark, checking, false, texture);
            shells[shellSlot] = texture;
        }
        layer(gui, shells[shellSlot], x, y, opacity, width, false);
        if (previous != null && swap < 1)
            layer(gui, titleTexture(previous, dark), x, y - 3 * swap, opacity * (1 - swap), width, true);
        if (swap > 0)
            layer(gui, titleTexture(title, dark), x, y + 3 * (1 - swap), opacity * swap, width, true);
    }

    private static void drawGlow(GuiGraphics gui,int x,int y,int bodyWidth,boolean dark,float opacity) {
        int index=dark?1:0;
        if(glows[index]==null) glows[index]=makeGlow(dark);
        int texWidth=WIDTH+2*GLOW_PAD,texHeight=HEIGHT+2*GLOW_PAD;
        int left=GLOW_PAD+bodyWidth/2,right=2*GLOW_PAD+bodyWidth-left;
        // Slide the far cap with the reveal front, keeping the halo radius steady during the wipe.
        textured(gui, glows[index], x - GLOW_PAD, y - GLOW_PAD, 0, left, texHeight, texWidth, opacity);
        textured(gui, glows[index], x - GLOW_PAD + left, y - GLOW_PAD, texWidth - right, right, texHeight, texWidth, opacity);
    }

    public static void clearTitles() {
        titles.clear();
        resetTitleSlots();
    }

    private static void resetTitleSlots() {
        freeTitleSlots.clear();
        for (int slot = 0; slot < TITLE_SLOTS; slot++) freeTitleSlots.add(slot);
    }

    private static ResourceLocation makeGlow(boolean dark) {
        int width=WIDTH+2*GLOW_PAD,height=HEIGHT+2*GLOW_PAD;
        var canvas=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        int[] pixels=((java.awt.image.DataBufferInt)canvas.getRaster().getDataBuffer()).getData();
        int rim=dark?0xE5C9FF:0xA6F2FF;
        int bloom=dark?0xAF70FC:0x3BACF4;
        double radius=14.5*ART_SCALE;
        double dockY=GLOW_PAD+53*ART_SCALE;
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            double dx=Math.abs(x+.5-width*.5)-(WIDTH*.5-radius);
            double dy=Math.abs(y+.5-height*.5)-(HEIGHT*.5-radius);
            double d=Math.max(0,Math.hypot(Math.max(0,dx),Math.max(0,dy))+Math.min(0,Math.max(dx,dy))-radius);
            double near=Math.max(0,1-d/18),far=Math.max(0,1-d/GLOW_PAD);
            double dock=Math.max(0,1-Math.hypot((x-GLOW_PAD)/55.0,(y-dockY)/66.0));
            double intensity=.51*near+.45*far*far+.18*dock*far;
            int alpha=(int)Math.round(255*Math.min(1,intensity));
            int tint=Atmosphere.mix(bloom,rim,(float)(near*near*.68));
            pixels[y*width+x]=(alpha<<24)|tint;
        }
        return upload(canvas, location("glow_" + (dark ? "dark" : "light")));
    }

    private static ResourceLocation titleTexture(String name, boolean dark) {
        Title key = new Title(name, dark);
        var cached = titles.get(key);
        if (cached != null) return cached.texture;
        if (freeTitleSlots.isEmpty()) {
            var oldest = titles.keySet().iterator().next();
            freeTitleSlots.add(titles.remove(oldest).slot);
        }
        int slot = freeTitleSlots.remove();
        ResourceLocation texture = location("title_" + slot);
        try {
            make(name, dark, false, true, texture);
        } catch (RuntimeException failed) {
            freeTitleSlots.add(slot);
            throw failed;
        }
        titles.put(key, new Cached(slot, texture));
        return texture;
    }

    private static void layer(GuiGraphics gui, ResourceLocation texture, float x, float y,
                              float opacity, int visibleWidth, boolean titleOnly) {
        int height = titleOnly ? Math.round(42 * ART_SCALE) : HEIGHT + 2 * PAD;
        int top = Math.round(y) - PAD + (titleOnly ? PAD + Math.round(32 * ART_SCALE) : 0);
        textured(gui, texture, Math.round(x) - PAD, top, 0, visibleWidth, height, WIDTH + 2 * PAD, opacity);
    }

    private static void textured(GuiGraphics gui, ResourceLocation texture, int x, int y, int u,
                                 int width, int height, int textureWidth, float opacity) {
        if (width <= 0) return;
        var out = gui.bufferSource().getBuffer(FilteredText.type(texture));
        var pose = gui.pose().last().pose();
        float left = u / (float) textureWidth;
        float right = (u + width) / (float) textureWidth;
        int alpha = Math.max(0, Math.min(255, Math.round(opacity * 255)));
        out.vertex(pose, x, y, 0).color(255, 255, 255, alpha).uv(left, 0).uv2(LightTexture.FULL_BRIGHT).endVertex();
        out.vertex(pose, x, y + height, 0).color(255, 255, 255, alpha).uv(left, 1).uv2(LightTexture.FULL_BRIGHT).endVertex();
        out.vertex(pose, x + width, y + height, 0).color(255, 255, 255, alpha).uv(right, 1).uv2(LightTexture.FULL_BRIGHT).endVertex();
        out.vertex(pose, x + width, y, 0).color(255, 255, 255, alpha).uv(right, 0).uv2(LightTexture.FULL_BRIGHT).endVertex();
        gui.flush();
    }

    private abstract static class FilteredText extends RenderType {
        private static final Map<ResourceLocation, RenderType> TYPES = new HashMap<>();

        private FilteredText() {
            super("unused", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, VertexFormat.Mode.QUADS,
                    256, false, false, () -> {}, () -> {});
        }

        private static RenderType type(ResourceLocation texture) {
            return TYPES.computeIfAbsent(texture, id ->
                    create("wz_selection_texture", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                            VertexFormat.Mode.QUADS, 256, false, false,
                            CompositeState.builder().setShaderState(RENDERTYPE_TEXT_SHADER)
                                    .setTextureState(new TextureStateShard(id, true, false))
                                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setLightmapState(LIGHTMAP)
                                    .setDepthTestState(NO_DEPTH_TEST).setWriteMaskState(COLOR_WRITE)
                                    .createCompositeState(false)));
        }
    }

    private static void make(String title, boolean dark, boolean checking, boolean titleOnly, ResourceLocation texture) {
        int w=DESIGN_WIDTH, h=DESIGN_HEIGHT;
        int height=titleOnly?Math.round(42*ART_SCALE):HEIGHT+2*PAD;
        BufferedImage canvas=new BufferedImage(WIDTH+2*PAD,height,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=canvas.createGraphics();
        g.translate(PAD,titleOnly?-Math.round(32*ART_SCALE):PAD);
        g.scale(ART_SCALE,ART_SCALE);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        int ink=dark?0xF6F2FF:0x192E41, muted=dark?0xCAC4DA:0x495E72, accent=dark?0xDCC6FF:0x236278;
        if(titleOnly) {
            title(g,title,ink);
        } else {
            var body=new RoundRectangle2D.Float(.5f,.5f,w-1,h-1,29,29);
            g.setPaint(new GradientPaint(0,0,dark?new Color(27,29,49,252):new Color(251,253,255,252),
                    w,h,dark?new Color(48,38,67,250):new Color(228,240,252,250)));
            g.fill(body);
            g.setClip(body);
            g.setPaint(new RadialGradientPaint(269,29,104,new float[]{0,.55f,1},
                    new Color[]{dark?new Color(152,105,222,65):new Color(114,188,255,69),
                            dark?new Color(126,99,206,31):new Color(175,219,255,40),new Color(140,164,220,0)}));
            g.fillRect(149,0,171,108);
            g.setColor(dark?new Color(14,15,28,62):new Color(255,255,255,109));
            g.fillRect(0,106,w,42);
            g.setColor(dark?new Color(194,177,235,50):new Color(102,144,173,44));
            g.drawLine(0,106,w,106);
            g.setClip(null);
            g.setColor(dark?new Color(194,178,228,106):new Color(255,255,255,228));g.draw(body);
            label(g,"问舟",18,27,14,true,accent);
            g.setColor(new Color(dark?0x857695:0x9FB2C2));g.drawLine(57,16,57,27);
            label(g,"方块引用",68,27,13,false,muted);
            label(g,checking ? "正在核对方块" : "将引用方块数据",19,91,15,false,muted);
            int bw=64,bh=Math.round(bw*(float)BOAT.getHeight()/BOAT.getWidth());
            g.drawImage(BOAT,240,52-bh/2,bw,bh,null);
            keycap(g,18, "右键","引用",dark,ink);
            keycap(g,123,"Esc","返回",dark,muted);
        }
        g.dispose();
        upload(canvas, texture);
    }

    private static ResourceLocation location(String name) {
        return ResourceLocation.tryParse("wenzhouai:dynamic/world_picker/" + name);
    }

    private static ResourceLocation upload(BufferedImage canvas, ResourceLocation id) {
        // Upload the ARGB raster directly; switching targets must not PNG-encode text on the render thread.
        var image=new NativeImage(canvas.getWidth(),canvas.getHeight(),false);
        var pixels=((java.awt.image.DataBufferInt)canvas.getRaster().getDataBuffer()).getData();
        for(int i=0;i<pixels.length;i++) {
            int argb=pixels[i];
            image.setPixelRGBA(i%canvas.getWidth(),i/canvas.getWidth(),
                    (argb&0xff00ff00) | ((argb>>>16)&255) | ((argb&255)<<16));
        }
        var textures = Minecraft.getInstance().getTextureManager();
        if (textures.getTexture(id, null) instanceof DynamicTexture existing) {
            // Keep the GL name valid while renderer integrations finish their buffered draws.
            existing.setPixels(image);
            existing.upload();
        } else {
            var texture = new DynamicTexture(image);
            textures.register(id, texture);
        }
        return id;
    }
    private static void title(Graphics2D g,String shown,int ink) {
        g.setFont(FONT.deriveFont(Font.BOLD,27f));
        var metrics=g.getFontMetrics();
        if(metrics.stringWidth(shown)>211) {
            int cut=shown.length();
            while(cut>0 && metrics.stringWidth(shown.substring(0,cut)+"…")>211) cut=shown.offsetByCodePoints(cut,-1);
            shown=shown.substring(0,cut)+"…";
        }
        label(g,shown,17,65,27,true,ink);
    }

    private static void keycap(Graphics2D g,int x,String key,String action,boolean dark,int color) {
        g.setFont(FONT.deriveFont(Font.BOLD,12f));
        int width=Math.max(22,g.getFontMetrics().stringWidth(key)+12);
        g.setColor(dark?new Color(199,182,235,25):new Color(220,233,244));
        g.fillRoundRect(x,116,width,22,8,8);
        g.setColor(dark?new Color(212,196,245,66):new Color(179,203,222));
        g.drawRoundRect(x,116,width,22,8,8);
        label(g,key,x+(width-g.getFontMetrics().stringWidth(key))/2,132,12,true,color);
        label(g,action,x+width+7,132,14,false,color);
    }
    private static void label(Graphics2D g,String t,int x,int y,float size,boolean bold,int color) {
        g.setFont(FONT.deriveFont(bold?Font.BOLD:Font.PLAIN,size));
        g.setColor(new Color(color));g.drawString(t,x,y);
    }
}

