package dev.surfacespeaker.app;

/** Stateful PCM shaping for an uncalibrated LRA (not a physical loudspeaker EQ).
 *  Dual high-pass removes sub-resonant rumble; tunable top roll-off reduces hiss,
 *  stutter and excess motor excitation. This cannot remove FF_CUSTOM restart gaps.
 */
final class MotorPcmDsp {
    static final int RATE=24000, PEAK=92;
    private float hpCoeff=(float)Math.exp(-2*Math.PI*93/RATE);
    private float lpCoeff=(float)(1-Math.exp(-2*Math.PI*810/RATE));
    private static final float BODY=(float)(1-Math.exp(-2*Math.PI*310/RATE));
    private float inOld, hp1, hp1Old, hp2, lp1, lp2, body;
    private float rmsSq=0.0004f, normalizer=1f;
    private int seed=0x1234ab8f;

    void setTuning(int bassClean,int trebleStable){
        float hp=55f+Math.max(0,Math.min(100,bassClean))*0.75f; // 55..130 Hz
        float lp=1250f-Math.max(0,Math.min(100,trebleStable))*7.0f; // 1250..550 Hz
        hpCoeff=(float)Math.exp(-2*Math.PI*hp/RATE);
        lpCoeff=(float)(1-Math.exp(-2*Math.PI*lp/RATE));
    }
    byte convert(float input,int gain){return convert(input,gain,65);}
    byte convert(float input,int gain,int detail){
        float x=Math.max(-1f,Math.min(1f,input));
        hp1=hpCoeff*(hp1+x-inOld);inOld=x;
        hp2=hpCoeff*(hp2+hp1-hp1Old);hp1Old=hp1;
        lp1+=lpCoeff*(hp2-lp1);
        lp2+=lpCoeff*(lp1-lp2);
        float wide=0.58f*lp1+0.42f*lp2;
        body+=BODY*(wide-body);
        // Controlled presence shelf; avoid excessive 1kHz+ excitation.
        float presence=0.76f+Math.max(0,Math.min(100,detail))*0.006f;
        float y=body+(wide-body)*presence;
        // Slower level detector and gain release reduce audible pumping.
        rmsSq+=0.00015f*(y*y-rmsSq);
        float rms=(float)Math.sqrt(Math.max(0,rmsSq));
        float target=Math.max(0.9f,Math.min(2.85f,0.22f/(rms+0.075f)));
        normalizer+=(target<normalizer?0.003f:0.00009f)*(target-normalizer);
        float dial=0.75f+Math.max(0,Math.min(100,gain))*0.075f;
        // Continuously differentiable limiting instead of a hard corner.
        float v=(float)Math.tanh(y*normalizer*dial*0.90f);
        float gate=Math.max(0f,Math.min(1f,(rms-0.002f)/0.011f));
        v*=gate;
        seed^=seed<<13;seed^=seed>>>17;seed^=seed<<5;
        float r1=((seed>>>16)&65535)/65536f;
        seed^=seed<<13;seed^=seed>>>17;seed^=seed<<5;
        float r2=((seed>>>16)&65535)/65536f;
        int sample=Math.round(v*PEAK+(r1-r2)*0.4f);
        return (byte)Math.max(-PEAK,Math.min(PEAK,sample));
    }
}