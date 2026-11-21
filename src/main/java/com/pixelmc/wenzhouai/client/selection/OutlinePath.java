/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client.selection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Vector2f;

/** Shortest travel distance along the outline, measured from the player's hit. */
public final class OutlinePath {
    private static final int MAX_SHAPE_EDGES = 96;
    record Segment(Vec3 a, Vec3 b, double length, double start, double end) {}
    private record Link(int a, int b, double length) {}
    final List<Segment> segments;
    final double extent;
    final Strip[] strips;

    /** Sample positions and spatial phase are fixed for the lifetime of a selection. */
    static final class Strip {
        final Segment edge;
        final Vec3 delta;
        final Vector2f widthNormal=new Vector2f();
        final double[] sin,cos;
        final int[] color;

        Strip(Segment edge,net.minecraft.world.phys.AABB bounds) {
            this.edge=edge;
            delta=edge.b.subtract(edge.a);
            int count=Math.max(2,Math.min(24,(int)Math.ceil(edge.length*16)));
            sin=new double[count+1];cos=new double[count+1];color=new int[count+1];
            var centre=bounds.getCenter();
            for(int i=0;i<=count;i++) {
                var p=edge.a.lerp(edge.b,(double)i/count);
                double phase=Math.atan2((p.z-centre.z)/Math.max(.01,bounds.getZsize()),
                        (p.x-centre.x)/Math.max(.01,bounds.getXsize()))
                        +2*Math.PI*.33*(p.y-bounds.minY)/Math.max(.01,bounds.getYsize());
                sin[i]=Math.sin(phase);cos[i]=Math.cos(phase);
            }
        }

        // The projected tangent of a straight edge is constant. Use homogeneous coordinates
        // so an endpoint on the camera plane never causes a divide by zero or flips the strip.
        void projectWidth(Matrix4f m,int width,int height) {
            Vec3 a=edge.a();
            double x=m.m00()*a.x+m.m10()*a.y+m.m20()*a.z+m.m30();
            double y=m.m01()*a.x+m.m11()*a.y+m.m21()*a.z+m.m31();
            double w=m.m03()*a.x+m.m13()*a.y+m.m23()*a.z+m.m33();
            double dx=m.m00()*delta.x+m.m10()*delta.y+m.m20()*delta.z;
            double dy=m.m01()*delta.x+m.m11()*delta.y+m.m21()*delta.z;
            double dw=m.m03()*delta.x+m.m13()*delta.y+m.m23()*delta.z;
            double sx=(dx*w-x*dw)*width,sy=(dy*w-y*dw)*height;
            double length=Math.hypot(sx,sy);
            if(length<=1e-8) widthNormal.zero();
            else widthNormal.set((float)(-sy/length),(float)(sx/length));
        }
    }

    private OutlinePath(List<Segment> segments, double extent, VoxelShape shape) {
        this.segments = segments;
        this.extent = extent;
        strips=new Strip[segments.size()];
        if(strips.length>0){
            var bounds=shape.bounds();
            for(int i=0;i<strips.length;i++) strips[i]=new Strip(segments.get(i),bounds);
        }
    }

    public static OutlinePath capture(VoxelShape shape, Vec3 hit) {
        var nodes = new ArrayList<Vec3>();
        var ids = new HashMap<Vec3, Integer>();
        var edges = new ArrayList<Link>();
        shape.forAllEdges((x,y,z,u,v,w) -> {
            if (edges.size() > MAX_SHAPE_EDGES) return;
            Vec3 a = new Vec3(x,y,z), b = new Vec3(u,v,w);
            if (!a.equals(b)) edges.add(new Link(node(a,nodes,ids), node(b,nodes,ids), a.distanceTo(b)));
        });
        if (edges.size() > MAX_SHAPE_EDGES) return capture(Shapes.create(shape.bounds()), hit);
        if (edges.isEmpty()) return new OutlinePath(List.of(), 0, shape);
        Vec3 seed = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (Link e : edges) {
            Vec3 a = nodes.get(e.a), ab = nodes.get(e.b).subtract(a);
            double t = hit.subtract(a).dot(ab) / ab.lengthSqr();
            Vec3 projected = a.add(ab.scale(Math.max(0, Math.min(1, t))));
            double distance = projected.distanceToSqr(hit);
            if (distance < nearest) {
                nearest = distance;
                seed = a.add(ab.scale(Math.max(.07, Math.min(.93, t))));
            }
        }
        int start = node(seed, nodes, ids);
        var parts = new ArrayList<Link>();
        for (Link edge : edges) {
            Vec3 a = nodes.get(edge.a), ab = nodes.get(edge.b).subtract(a);
            var cuts = new ArrayList<Integer>();
            cuts.add(edge.a);
            cuts.add(edge.b);
            for (int i=0; i<nodes.size(); i++) {
                Vec3 delta = nodes.get(i).subtract(a);
                double t = delta.dot(ab) / ab.lengthSqr();
                if (t>0 && t<1 && delta.subtract(ab.scale(t)).lengthSqr()<1e-14) cuts.add(i);
            }
            cuts.sort(java.util.Comparator.comparingDouble(i -> a.distanceToSqr(nodes.get(i))));
            for (int i=1; i<cuts.size(); i++) {
                int left=cuts.get(i-1), right=cuts.get(i);
                parts.add(new Link(left,right,nodes.get(left).distanceTo(nodes.get(right))));
            }
        }
        double[] distance=new double[nodes.size()];
        java.util.Arrays.fill(distance,Double.POSITIVE_INFINITY);
        distance[start]=0;
        boolean[] visited=new boolean[nodes.size()];
        for(int step=0; step<nodes.size(); step++) {
            int next=-1;
            for(int i=0;i<nodes.size();i++)
                if(!visited[i] && (next<0 || distance[i]<distance[next])) next=i;
            if(!Double.isFinite(distance[next])) {
                // Detached shape pieces light after the nearest reached point crosses the gap.
                for(int i=0;i<nodes.size();i++) if(!visited[i])
                    for(int j=0;j<nodes.size();j++) if(visited[j]) {
                        double arrival=distance[j]+nodes.get(j).distanceTo(nodes.get(i));
                        if(arrival<distance[next]) {next=i;distance[i]=arrival;}
                    }
            }
            visited[next]=true;
            for(Link e:parts) {
                int other=e.a==next?e.b:e.b==next?e.a:-1;
                if(other>=0) distance[other]=Math.min(distance[other],distance[next]+e.length);
            }
        }
        double extent=0;
        var result=new ArrayList<Segment>();
        for(Link e:parts) {
            result.add(new Segment(nodes.get(e.a),nodes.get(e.b),e.length,distance[e.a],distance[e.b]));
            extent=Math.max(extent,(distance[e.a]+distance[e.b]+e.length)/2);
        }
        return new OutlinePath(result,extent,shape);
    }

    private static int node(Vec3 point, List<Vec3> nodes, HashMap<Vec3,Integer> ids) {
        return ids.computeIfAbsent(point,p -> {nodes.add(p);return nodes.size()-1;});
    }
}
