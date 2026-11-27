/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client.selection;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;

public final class Atmosphere {
    private final VertexConsumer buffer;
    private final Matrix4f pose;
    private Atmosphere(GuiGraphics gui) {
        pose=gui.pose().last().pose();
        buffer=gui.bufferSource().getBuffer(Glow.TYPE);
    }

    private abstract static class Glow extends RenderType {
        private Glow() {
            super("unused", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 256, false, false, () -> {}, () -> {});
        }

        private static final RenderType TYPE = create("wz_selection_hud", DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS, 4096, false, false,
                CompositeState.builder().setShaderState(RENDERTYPE_GUI_OVERLAY_SHADER)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setDepthTestState(NO_DEPTH_TEST)
                        .setCullState(NO_CULL).setWriteMaskState(COLOR_WRITE).createCompositeState(false));
    }

    public static void render(GuiGraphics gui,float w,float h,float tx,float ty,float line,float lock,
                       float theme,float opacity,double time) {
        if(opacity<=.002f) return;
        Atmosphere mesh=new Atmosphere(gui);
        int base=mix(0x276DC6,0x592390,theme),tip=mix(0x92EAF6,0xDFB5F4,theme);
        float depth=Math.max(37,Math.min(136,Math.min(w,h)*.075f));
        // Four stationary pools. Their different lengths and offsets keep the edges soft and asymmetric.
        for(int side=0;side<4;side++) {
            int spans=32;
            float before=edgeLight(side,0);
            for(int i=0;i<spans;i++) {
                float left=i/(float)spans,right=(i+1)/(float)spans;
                float after=edgeLight(side,right);
                for(int j=0;j<5;j++) {
                    float inner=j/5f,outer=(j+1)/5f;
                    mesh.atmosphereVertex(side,w,h,left,inner,before,depth,base,tip,opacity);
                    mesh.atmosphereVertex(side,w,h,right,inner,after,depth,base,tip,opacity);
                    mesh.atmosphereVertex(side,w,h,right,outer,after,depth,base,tip,opacity);
                    mesh.atmosphereVertex(side,w,h,left,outer,before,depth,base,tip,opacity);
                }
                before=after;
            }
        }
        float cx=w*.5f,cy=h*.5f;
        if(line>0)mesh.leader(cx,cy,tx,ty,base,tip,opacity,line,time);
        mesh.reticle(cx,cy,tip,opacity,lock);
        gui.flush();
    }

    private static float edgeLight(int side,float t){
        float centre=switch(side){case 0 -> .71f; case 1 -> .40f; case 2 -> .64f; default -> .31f;};
        float width=side%2==0?.34f:.27f;
        float v=Math.max(0,1-Math.abs(t-centre)/width);
        return v*v*(3-2*v);
    }
    private void atmosphereVertex(int side,float w,float h,float t,float inset,float light,float depth,int base,int tip,float opacity){
        float falloff=1-inset;
        edge(side,w,h,t,depth*(.45f+.55f*light)*inset,mix(base,tip,.24f+.56f*light),
                opacity*(.13f+.29f*light)*falloff*falloff);
    }
    private void edge(int side,float w,float h,float fraction,float d,int color,float alpha) {
        float x= switch(side) { case 0 -> d+(w-2*d)*fraction; case 1 -> w-d; case 2 -> w-d-(w-2*d)*fraction; default -> d; };
        float y= switch(side) { case 0 -> d; case 1 -> d+(h-2*d)*fraction; case 2 -> h-d; default -> h-d-(h-2*d)*fraction; };
        vertex(x,y,color,alpha);
    }

    // A diagonal tangent eases into a horizontal approach. The signal follows arc length through the bend.
    private void leader(float cx,float cy,float endX,float endY,int base,int tip,float opacity,float reveal,double time) {
        float side=endX>=cx?1:-1,dy=endY-cy;
        float startX=cx+side*7,startY=cy+7;
        float bendX=startX+side*(dy-5)*.96f, bendY=endY;
        float lastX=startX,lastY=startY;
        float total=Math.max(1,(float)Math.hypot(bendX-startX,dy-5)+Math.abs(endX-bendX));
        float progress=0;
        float limit=reveal>=1?Float.POSITIVE_INFINITY:reveal*total;
        for(int i=1;i<=36;i++) {
            float x,y;
            if(i<=14) {
                float t=i/14f;x=startX+(bendX-side*5-startX)*t;y=startY+(bendY-5-startY)*t;
            } else if(i<=22) {
                float t=(i-14)/8f,mt=1-t;
                x=mt*mt*(bendX-side*5)+2*mt*t*bendX+t*t*(bendX+side*7);
                y=mt*mt*(bendY-5)+(1-mt*mt)*bendY;
            } else {
                float t=(i-22)/14f;x=bendX+side*7+(endX-bendX-side*7)*t;y=endY;
            }
            float length=(float)Math.hypot(x-lastX,y-lastY);
            float pos=(progress+.5f*length)/total;
            if(progress>=limit) break;
            float fraction=Math.min(1,(limit-progress)/Math.max(.001f,length));
            float u=lastX+(x-lastX)*fraction,v=lastY+(y-lastY)*fraction;
            float centre=reveal<1?reveal:(float)((time%3.8)/2.35);
            float glint=(float)Math.exp(-Math.pow((pos-centre)/.11,2));
            int color=mix(mix(base,tip,Math.min(1,pos)),0xFFFFFF,.24f+.62f*glint);
            int glow=mix(base,tip,.64f);
            wireGlow(lastX,lastY,u,v,12+3*glint,glow,(.48f+.12f*glint)*opacity);
            stroke(lastX,lastY,u,v,8.5f,0x173347,.40f*opacity);
            stroke(lastX,lastY,u,v,5.8f+glint*1.6f,base,.76f*opacity);
            stroke(lastX,lastY,u,v,2.8f+glint,color,.97f*opacity);
            progress+=length;lastX=x;lastY=y;
        }
        if(reveal>.94f) {
            float contact=SelectionMotion.smooth((reveal-.94f)/.06f);
            contactGlow(endX,endY,mix(base,tip,.74f),opacity*contact);
            stroke(endX,endY-3,endX,endY+3,2,tip,opacity*contact);
        }
    }

    private void wireGlow(float x,float y,float u,float v,float radius,int color,float alpha) {
        float length=(float)Math.hypot(u-x,v-y);
        if(length<.001f) return;
        float nx=(y-v)/length,ny=(u-x)/length;
        for(int side=-1;side<=1;side+=2) {
            for(int band=0;band<3;band++) {
                float a=band/3f,b=(band+1)/3f;
                float near=side*radius*a,far=side*radius*b;
                float inner=alpha*(1-a)*(1-a),outer=alpha*(1-b)*(1-b);
                vertex(x+nx*near,y+ny*near,color,inner);vertex(u+nx*near,v+ny*near,color,inner);
                vertex(u+nx*far,v+ny*far,color,outer);vertex(x+nx*far,y+ny*far,color,outer);
            }
        }
    }

    private void contactGlow(float x,float y,int color,float opacity) {
        float lastX=15,lastY=0;
        for(int i=1;i<=20;i++) {
            double angle=i*Math.PI/10;
            float dx=15*(float)Math.cos(angle),dy=21*(float)Math.sin(angle);
            vertex(x,y,color,.45f*opacity);vertex(x+lastX,y+lastY,color,0);
            vertex(x+dx,y+dy,color,0);vertex(x,y,color,.45f*opacity);
            lastX=dx;lastY=dy;
        }
    }

    private void reticle(float x,float y,int ink,float opacity,float lock) {
        float inner=10-3*lock,outer=inner+12;
        for(int sign:new int[]{-1,1}) {
            float a=sign*inner,b=sign*outer;
            stroke(x+a,y,x+b,y,5.2f,0x213343,.78f*opacity);
            stroke(x,y+a,x,y+b,5.2f,0x213343,.78f*opacity);
            stroke(x+a,y,x+b,y,3f,ink,opacity);
            stroke(x,y+a,x,y+b,3f,ink,opacity);
        }
    }

    private void stroke(float x,float y,float u,float v,float width,int color,float alpha){
        float length=(float)Math.hypot(u-x,v-y);
        if(length<.0001f)return;
        float nx=-(v-y)/length*width*.5f,ny=(u-x)/length*width*.5f;
        vertex(x+nx,y+ny,color,alpha);vertex(u+nx,v+ny,color,alpha);
        vertex(u-nx,v-ny,color,alpha);vertex(x-nx,y-ny,color,alpha);
    }
    private void vertex(float x,float y,int rgb,float alpha) {
        buffer.vertex(pose,x,y,0).color(rgb>>16&255,rgb>>8&255,rgb&255,Math.max(0,Math.min(255,Math.round(alpha*255)))).endVertex();
    }
    static int mix(int a,int b,float t) {
        int r=Math.round((a>>16&255)*(1-t)+(b>>16&255)*t);
        int g=Math.round((a>>8&255)*(1-t)+(b>>8&255)*t);
        int bl=Math.round((a&255)*(1-t)+(b&255)*t);
        return r<<16|g<<8|bl;
    }
}

