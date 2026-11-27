/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client.selection;

/** Independent target and HUD clocks let a visible card survive a target change. Times are seconds. */
public final class SelectionMotion<T> {
    private T current, outgoing;

    public T current() { return current; }

    public T outgoing() { return outgoing; }
    private double last, lost=Double.NaN, switched, panelTime, edgeTime;
    private float oldFill;
    private static final double EDGE=.30, WIRE=.18, CARD=.22, TOTAL=EDGE+WIRE+CARD, GRACE=.12;

    public void clear(double now) {
        current=null;outgoing=null;last=now;lost=Double.NaN;panelTime=0;edgeTime=0;
    }

    public void observe(T target,double now) {
        if(current!=null) {
            if(!Double.isNaN(lost)) {
                double retreat=Math.max(0,now-Math.max(last,lost+GRACE))*2.1;
                panelTime=Math.max(0,panelTime-retreat);
                edgeTime=Math.min(edgeTime,Math.min(EDGE,panelTime));
                if(panelTime==0 && now>=lost+GRACE) {current=null;outgoing=null;}
            } else {
                panelTime=Math.min(TOTAL,panelTime+now-last);
                edgeTime=Math.min(EDGE,edgeTime+now-last);
            }
        }
        last=now;
        if(outgoing!=null && now-switched>=.17) outgoing=null;
        if(target==null) {
            if(Double.isNaN(lost)) lost=now;
            return;
        }
        lost=Double.NaN;
        if(target.equals(current)) return;
        // Before the shell appears, start the entrance again at the new outline.
        if(panelTime<=EDGE) panelTime=0;
        float old=edge();
        if(current!=null && (outgoing==null || old>=oldOpacity(now)*oldFill)) {
            outgoing=current;
            oldFill=old;
        } else if(outgoing!=null) oldFill*=oldOpacity(now);
        current=target;
        switched=now;
        edgeTime=0;
    }

    public float oldFill(){return oldFill;}
    public float oldOpacity(double now){return outgoing==null?0:1-smooth((now-switched)/.17);}
    public float edge(){return smooth(edgeTime/EDGE);}
    public float wire(){return smooth((panelTime-EDGE)/WIRE);}
    public float card(){return smooth((panelTime-EDGE-WIRE)/CARD);}
    public static float smooth(double t){t=Math.max(0,Math.min(1,t));return (float)(t*t*(3-2*t));}
}

