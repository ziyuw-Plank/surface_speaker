package dev.surfacespeaker.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Circular CAVA-inspired log-frequency spectrum. Rendered entirely on-device. */
public final class SpectrumView extends View {
    private static final int BARS = 72;
    private final float[] levels = new float[BARS];
    private final float[] goals = new float[BARS];
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private boolean playing = false;
    private int accent = 0xffc9fb74;
    private float energy;

    public SpectrumView(Context context) { super(context); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
    void setAccent(int color) { accent = color; invalidate(); }
    void setPlaying(boolean active) { playing = active; if (!active) { energy=0;for(int i=0;i<BARS;i++)goals[i]=0; } invalidate(); }
    void pushSpectrum(float[] logarithmic, float rms) {
        for (int i=0;i<BARS;i++) goals[i] = Math.min(1, Math.max(0, logarithmic[i]));
        energy = Math.max(0, Math.min(1, rms)); invalidate();
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w=getWidth(), h=getHeight(), cx=w/2, cy=h/2;
        float r=Math.min(w,h)*0.30f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1));p.setColor(0xff30362d);
        c.drawCircle(cx,cy,r*0.88f,p);c.drawCircle(cx,cy,r*1.12f,p);
        p.setColor(0xff49543e);p.setStrokeWidth(dp(2));
        c.drawCircle(cx,cy,r,p);
        p.setColor(accent); p.setStrokeCap(Paint.Cap.ROUND);
        boolean animating=false;
        for(int i=0;i<BARS;i++) {
            float next=levels[i]+(goals[i]-levels[i])*(playing?0.24f:0.14f);
            if (Math.abs(goals[i]-next)>0.004f) animating=true;
            levels[i]=next;
            float phase=(float)(Math.PI*2*i/BARS-Math.PI/2);
            float v=Math.max(0.025f,next);
            float inner=r+dp(9),outer=inner+dp(5)+v*dp(64);
            float cs=(float)Math.cos(phase),sn=(float)Math.sin(phase);
            p.setAlpha((int)(110+v*145));p.setStrokeWidth(dp(2.7f));
            c.drawLine(cx+inner*cs,cy+inner*sn,cx+outer*cs,cy+outer*sn,p);
        }
        p.setAlpha(255);p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(4));p.setColor(accent);
        oval.set(cx-r*0.78f,cy-r*0.78f,cx+r*0.78f,cy+r*0.78f);
        c.drawArc(oval,-90,Math.max(12,energy*300),false,p);
        p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);
        p.setColor(0xffedf1e8);p.setTextSize(dp(25));p.setFakeBoldText(true);
        c.drawText("SURFACE",cx,cy-dp(4),p);
        p.setTextSize(dp(18));c.drawText("SPEAKER",cx,cy+dp(20),p);
        p.setFakeBoldText(false);p.setTextSize(dp(10));p.setColor(0xffb9c5ae);
        c.drawText(playing?"LIVE AUDIO REACTIVE":"HAPTIC AUDIO LAB",cx,cy+dp(43),p);
        if (animating || playing) postInvalidateDelayed(33);
    }
    private float dp(float n) {return n*getResources().getDisplayMetrics().density;}
}
