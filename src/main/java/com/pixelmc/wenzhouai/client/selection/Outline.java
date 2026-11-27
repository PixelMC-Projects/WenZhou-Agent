/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client.selection;

import com.mojang.blaze3d.vertex.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.fml.ModList;
import org.joml.Matrix4f;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.OptionalDouble;

public abstract class Outline extends RenderType {
    private Outline() { super("unused", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 256, false, false, ()->{}, ()->{}); }

    private static ShaderInstance outlineShader;
    private static final ShaderStateShard OUTLINE_SHADER=new ShaderStateShard(()->outlineShader);
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new BufferBuilder(4096));
    private static final ShaderPack SHADER_PACK = detectShaderPack();
    private static boolean fallback;

    private record ShaderPack(Object api, Method active, boolean unknown) {
        boolean activeNow() {
            if (unknown) return true;
            if (active == null) return false;
            try {
                return Boolean.TRUE.equals(active.invoke(api));
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                return true;
            }
        }
    }

    private static ShaderPack detectShaderPack() {
        if (!ModList.get().isLoaded("oculus") && !ModList.get().isLoaded("iris"))
            return new ShaderPack(null, null, false);
        for (String name : new String[] {"net.irisshaders.iris.api.v0.IrisApi", "net.coderbot.iris.api.v0.IrisApi"}) {
            try {
                Class<?> type = Class.forName(name);
                return new ShaderPack(type.getMethod("getInstance").invoke(null),
                        type.getMethod("isShaderPackInUse"), false);
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                // The next API name covers another supported Iris generation.
            }
        }
        return new ShaderPack(null, null, true);
    }

    public static boolean customAvailable() {
        return outlineShader != null && !fallback && !SHADER_PACK.activeNow();
    }

    public static void registerShader(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    new ResourceLocation("wenzhouai:outline"),DefaultVertexFormat.POSITION_COLOR_TEX),
                    shader -> { outlineShader = shader; fallback = false; });
        } catch (IOException | RuntimeException unavailable) {
            outlineShader = null;
            LogUtils.getLogger().warn("WenZhou custom block outline unavailable; using the game outline", unavailable);
        }
    }

    private static RenderType line(String name, double width, boolean luminous) {
        return create(name, DefaultVertexFormat.POSITION_COLOR_TEX, VertexFormat.Mode.LINES, 4096, false, false,
                CompositeState.builder().setShaderState(OUTLINE_SHADER)
                        .setLineState(new LineStateShard(OptionalDouble.of(width)))
                        .setTransparencyState(luminous ? LIGHTNING_TRANSPARENCY : TRANSLUCENT_TRANSPARENCY)
                        .setLayeringState(VIEW_OFFSET_Z_LAYERING).setWriteMaskState(COLOR_WRITE)
                        .setCullState(NO_CULL).createCompositeState(false));
    }

    private static final RenderType[] LAYERS={
        line("wz_spill",22,true),line("wz_bloom",14,true),
        line("wz_near",8,true),line("wz_core",4.7,false)
    };
    private static final int[] ALPHA={20,38,75,255};

    public static void draw(RenderLevelStageEvent event,BlockPos pos,OutlinePath path,float theme,float fill,float opacity,double time) {
        if (fill <= 0 || opacity <= 0) return;
        var stack=event.getPoseStack();
        var camera=event.getCamera().getPosition();
        stack.pushPose();
        try {
            stack.translate(pos.getX()-camera.x,pos.getY()-camera.y,pos.getZ()-camera.z);
            double reach=fill*path.extent;
            double angle=time*2*Math.PI/3.6;
            double sin=Math.sin(angle),cos=Math.cos(angle);
            int low=Atmosphere.mix(0x2277F6,0x1B102B,theme);
            int mid=Atmosphere.mix(0x68C9FF,0x7335CE,theme);
            int high=Atmosphere.mix(0xFAFFFF,0xE8AAFF,theme);
            var clip=new Matrix4f(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix()).mul(stack.last().pose());
            var window=Minecraft.getInstance().getWindow();
            for(var strip:path.strips){
                strip.projectWidth(clip,window.getWidth(),window.getHeight());
                for(int i=0;i<strip.color.length;i++){
                    float light=(float)(.5+.5*(strip.cos[i]*cos+strip.sin[i]*sin));
                    strip.color[i]=light<.55f?Atmosphere.mix(low,mid,light/.55f):Atmosphere.mix(mid,high,(light-.55f)/.45f);
                }
            }
            for(int layer=0;layer<LAYERS.length;layer++) {
                var type=LAYERS[layer];
                var out=BUFFERS.getBuffer(type);
                try {
                    int alpha=Math.round(opacity*ALPHA[layer]);
                    for(var strip:path.strips) {
                        if(strip.widthNormal.lengthSquared()==0) continue;
                        var e=strip.edge;
                        double left=Math.min(1,(reach-e.start())/e.length());
                        double right=Math.max(0,1-(reach-e.end())/e.length());
                        if(fill>=1 || left>=right) part(out,stack.last(),strip,0,1,alpha);
                        else {
                            part(out,stack.last(),strip,0,left,alpha);
                            part(out,stack.last(),strip,right,1,alpha);
                        }
                    }
                } finally {
                    try {
                        BUFFERS.endBatch(type);
                    } catch (RuntimeException incompatible) {
                        type.clearRenderState();
                        throw incompatible;
                    }
                }
            }
        } catch (RuntimeException incompatible) {
            fallback = true;
            LogUtils.getLogger().warn("WenZhou switched to the game outline after a draw failure", incompatible);
        } finally {
            stack.popPose();
        }
    }

    /** Submit the same animated silhouette to the native line layer inside its highlight pass. */
    public static void drawCompatible(RenderHighlightEvent.Block event, BlockPos pos, OutlinePath path,
                                      float theme, float fill, float opacity, double time) {
        if (fill <= 0 || opacity <= 0) return;
        var stack = event.getPoseStack();
        var camera = event.getCamera().getPosition();
        var out = event.getMultiBufferSource().getBuffer(RenderType.lines());
        stack.pushPose();
        try {
            stack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
            double reach = fill * path.extent;
            double angle = time * 2 * Math.PI / 3.6;
            double sin = Math.sin(angle), cos = Math.cos(angle);
            int low = Atmosphere.mix(0x459AFB, 0x783FC0, theme);
            int high = Atmosphere.mix(0xECFAFF, 0xE8AAFF, theme);
            int alpha = Math.round(opacity * 240);
            for (var strip : path.strips) {
                var edge = strip.edge;
                double left = Math.min(1, (reach - edge.start()) / edge.length());
                double right = Math.max(0, 1 - (reach - edge.end()) / edge.length());
                int first = shaded(low, high, sin, cos, strip, 0);
                int last = shaded(low, high, sin, cos, strip, 1);
                if (fill >= 1 || left >= right) {
                    compatiblePart(out, stack.last(), strip, 0, 1, first, last, alpha);
                } else {
                    compatiblePart(out, stack.last(), strip, 0, left, first, last, alpha);
                    compatiblePart(out, stack.last(), strip, right, 1, first, last, alpha);
                }
            }
        } finally {
            stack.popPose();
        }
    }

    private static int shaded(int low, int high, double sin, double cos, OutlinePath.Strip strip, int end) {
        int i = end == 0 ? 0 : strip.color.length - 1;
        return Atmosphere.mix(low, high, (float) (.5 + .5 * (strip.cos[i] * cos + strip.sin[i] * sin)));
    }

    private static void compatiblePart(VertexConsumer out, PoseStack.Pose pose, OutlinePath.Strip strip,
                                       double start, double end, int first, int last, int alpha) {
        if (end <= start) return;
        var direction = strip.delta.scale(1 / strip.edge.length());
        compatibleVertex(out, pose, strip.edge.a().lerp(strip.edge.b(), start), direction,
                Atmosphere.mix(first, last, (float) start), alpha);
        compatibleVertex(out, pose, strip.edge.a().lerp(strip.edge.b(), end), direction,
                Atmosphere.mix(first, last, (float) end), alpha);
    }

    private static void compatibleVertex(VertexConsumer out, PoseStack.Pose pose, Vec3 point, Vec3 normal,
                                         int color, int alpha) {
        out.vertex(pose.pose(), (float) point.x, (float) point.y, (float) point.z)
                .color(color >> 16 & 255, color >> 8 & 255, color & 255, alpha)
                .normal(pose.normal(), (float) normal.x, (float) normal.y, (float) normal.z).endVertex();
    }

    private static void part(VertexConsumer out,PoseStack.Pose pose,OutlinePath.Strip strip,double start,double end,int alpha) {
        if(end<=start)return;
        int count=strip.color.length-1;
        for(int i=(int)(start*count);i<Math.ceil(end*count);i++) {
            double left=Math.max(start,(double)i/count),right=Math.min(end,(double)(i+1)/count);
            vertex(out,pose,strip,left,i,alpha);
            vertex(out,pose,strip,right,i,alpha);
        }
    }

    private static void vertex(VertexConsumer out,PoseStack.Pose pose,OutlinePath.Strip strip,double t,int i,int alpha) {
        Vec3 a=strip.edge.a(),d=strip.delta;
        float blend=(float)Math.max(0,Math.min(1,t*(strip.color.length-1)-i));
        int color=Atmosphere.mix(strip.color[i],strip.color[i+1],blend);
        out.vertex(pose.pose(),(float)(a.x+d.x*t),(float)(a.y+d.y*t),(float)(a.z+d.z*t))
                .color(color>>16&255,color>>8&255,color&255,alpha)
                .uv(strip.widthNormal.x,strip.widthNormal.y).endVertex();
    }
}

