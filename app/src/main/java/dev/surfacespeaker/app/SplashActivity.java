package dev.surfacespeaker.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/** v0.14 cover screen: animated vibration rings + wordmark, then MainActivity. Tap to skip. */
public final class SplashActivity extends Activity {
    private final Handler h=new Handler(Looper.getMainLooper());
    private boolean done;
    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(0xff0b0f0a);getWindow().setNavigationBarColor(0xff0b0f0a);
        Cover c=new Cover(this);c.setOnClickListener(v->go());setContentView(c);
        h.postDelayed(this::go,1600);
    }
    private void go(){if(done)return;done=true;startActivity(new Intent(this,MainActivity.class));overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);finish();}

    static final class Cover extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private final long t0=System.currentTimeMillis();
        Cover(Context c){super(c);}
        @Override protected void onDraw(Canvas c){
            int w=getWidth(),hh=getHeight();float d=getResources().getDisplayMetrics().density;
            p.setShader(new LinearGradient(0,0,0,hh,0xff0b0f0a,0xff17201a,Shader.TileMode.CLAMP));c.drawRect(0,0,w,hh,p);p.setShader(null);
            float cx=w/2f,cy=hh*0.42f,ph=((System.currentTimeMillis()-t0)%1600)/1600f;
            p.setStyle(Paint.Style.STROKE);
            for(int i=0;i<5;i++){float k=(i+ph)/5f;p.setStrokeWidth(2*d);p.setColor(Color.argb((int)(200*(1-k)),201,251,116));c.drawCircle(cx,cy,(40+150*k)*d,p);}
            p.setStyle(Paint.Style.FILL);p.setColor(0xffc9fb74);c.drawCircle(cx,cy,34*d,p);
            p.setColor(0xff0b0f0a);p.setStrokeWidth(3*d);p.setStyle(Paint.Style.STROKE);Path wave=new Path();
            for(int x=-22;x<=22;x++){float y=(float)(Math.sin(x/3.5+ph*6.28)*10*Math.cos(x/28.0*Math.PI));if(x==-22)wave.moveTo(cx+x*d,cy+y*d);else wave.lineTo(cx+x*d,cy+y*d);}
            c.drawPath(wave,p);p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);p.setColor(0xffeef2e8);p.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));p.setTextSize(28*d);p.setLetterSpacing(.12f);
            c.drawText("SURFACE SPEAKER",cx,hh*0.70f,p);
            p.setTypeface(Typeface.DEFAULT);p.setTextSize(13*d);p.setLetterSpacing(.05f);p.setColor(0xffabb5a4);
            c.drawText("骨传导质感 · Bone-conduction feel",cx,hh*0.70f+26*d,p);
            p.setColor(0xffc9fb74);p.setTextSize(11*d);c.drawText("v0.14",cx,hh-40*d,p);
            postInvalidateOnAnimation();
        }
    }
}
