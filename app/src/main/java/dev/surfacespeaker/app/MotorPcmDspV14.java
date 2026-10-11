package dev.surfacespeaker.app;

/** v0.14 "bone-conduction style" PCM shaping for the LRA / FF_CUSTOM path.
 *  Drop-in replacement for {@link MotorPcmDsp} (same API, same PEAK safety cap).
 *  Chain: 2nd-order Butterworth HP -> psychoacoustic virtual bass (missing
 *  fundamental harmonics, re-injected above the HP corner) -> LRA resonance
 *  notch at f0=130 Hz (tames boom/rattle) -> "bone" presence peak ~1 kHz -> 2nd-order LP
 *  -> slow AGC + soft-knee tanh limiter -> hysteresis gate -> TPDF dither.
 */
final class MotorPcmDspV14 {
    static final int RATE=24000, PEAK=92;

    /** RBJ biquad, transposed direct form II. */
    static final class Biquad {
        float b0,b1,b2,a1,a2,z1,z2;
        void set(double b0,double b1,double b2,double a0,double a1,double a2){
            this.b0=(float)(b0/a0);this.b1=(float)(b1/a0);this.b2=(float)(b2/a0);this.a1=(float)(a1/a0);this.a2=(float)(a2/a0);
        }
        void lowpass(double f,double q){double w=2*Math.PI*f/RATE,c=Math.cos(w),al=Math.sin(w)/(2*q);set((1-c)/2,1-c,(1-c)/2,1+al,-2*c,1-al);}
        void highpass(double f,double q){double w=2*Math.PI*f/RATE,c=Math.cos(w),al=Math.sin(w)/(2*q);set((1+c)/2,-(1+c),(1+c)/2,1+al,-2*c,1-al);}
        void bandpass(double f,double q){double w=2*Math.PI*f/RATE,c=Math.cos(w),al=Math.sin(w)/(2*q);set(al,0,-al,1+al,-2*c,1-al);}
        void peak(double f,double q,double db){double A=Math.pow(10,db/40),w=2*Math.PI*f/RATE,c=Math.cos(w),al=Math.sin(w)/(2*q);
            set(1+al*A,-2*c,1-al*A,1+al/A,-2*c,1-al/A);}
        float run(float x){float y=b0*x+z1;z1=b1*x-a1*y+z2;z2=b2*x-a2*y;return y;}
    }

    private final Biquad hp=new Biquad(), hpB=new Biquad(), bassLp=new Biquad(), harmBp=new Biquad(),
        notch=new Biquad(), presence=new Biquad(), lp1=new Biquad(), lp2=new Biquad();
    private float hpHz=95, lpHz=900, resonanceHz=130, virtualBass=0.9f;
    private float env, pkEnv, compGain=1f, lastOut, rmsSq=0.0004f, normalizer=1f, gateGain;
    private int lastDetail=-1, seed=0x1234ab8f;

    /** Device LRA f0 (Hz). Default 127.7 Hz (shennong lra_frequency_hz=1277); updated by loadDeviceF0(). */
    static volatile float deviceF0=127.7f;
    MotorPcmDspV14(){setTuning(52,60);setResonance(deviceF0);}
    /** Parse /sys/class/qcom-haptics/lra_frequency_hz ("1277" = 127.7 Hz). Read-only. Returns clamped Hz or -1. */
    static float parseF0(String raw){
        if(raw==null)return -1;
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(raw);
        if(!m.find())return -1;
        float v=Float.parseFloat(m.group(1));
        if(v>1000)v/=10f;
        if(v<50||v>400)return -1;
        return Math.max(120f,Math.min(140f,v));
    }

    /** Same controls as v0.13: bassClean 0..100 -> HP 60..140 Hz, trebleStable 0..100 -> LP 1400..600 Hz. */
    void setTuning(int bassClean,int trebleStable){
        hpHz=60f+clamp(bassClean)*0.8f;
        lpHz=1400f-clamp(trebleStable)*8f;
        rebuild();
    }
    /** LRA resonant frequency (shennong: ro.odm.mm.vibrator resonant_frequency=130 Hz). */
    void setResonance(float hz){resonanceHz=Math.max(100,Math.min(320,hz));rebuild();}
    /** 0..1.5 amount of psychoacoustic bass harmonics. */
    void setVirtualBass(float amount){virtualBass=Math.max(0,Math.min(1.5f,amount));}

    private void rebuild(){
        hp.highpass(hpHz,0.5412); hpB.highpass(hpHz,1.3066); // 4th-order: 24 dB/oct below usable band (anti-rattle)
        bassLp.lowpass(hpHz*1.1,0.7071);                 // band the motor cannot reproduce
        harmBp.bandpass(Math.min(lpHz*0.8,hpHz*3.2),0.9);  // keep 2nd-4th harmonics only
        notch.peak(resonanceHz,2.2,-6.0);
        lp1.lowpass(lpHz,0.5412); lp2.lowpass(lpHz,1.3066); // 4th-order Butterworth
        lastDetail=-1;
    }
    private static int clamp(int v){return Math.max(0,Math.min(100,v));}

    byte convert(float input,int gain){return convert(input,gain,65);}
    byte convert(float input,int gain,int detail){
        if(detail!=lastDetail){presence.peak(1050,0.9,-1.0+clamp(detail)*0.07);lastDetail=detail;} // -1..+6 dB
        float x=Math.max(-1f,Math.min(1f,input));
        // Virtual bass: envelope-normalised half-wave nonlinearity on the sub-HP band.
        float lo=bassLp.run(x);
        float a=Math.abs(lo); env+= (a>env?0.02f:0.0008f)*(a-env);
        float nl=(lo>0?lo:0)+0.35f*lo*lo/(env+1e-3f)*(lo>0?1:-1)*0.5f;
        float harm=harmBp.run(nl)*virtualBass*1.6f;
        float y=hpB.run(hp.run(x))+harm;
        y=notch.run(y);
        y=presence.run(y);
        y=lp2.run(lp1.run(y));
        // AGC (slower release than v13 to avoid pumping) + soft-knee limiter.
        rmsSq+=0.00012f*(y*y-rmsSq);
        float rms=(float)Math.sqrt(Math.max(0,rmsSq));
        float target=Math.max(0.9f,Math.min(2.4f,0.22f/(rms+0.07f)));
        normalizer+=(target<normalizer?0.004f:0.00007f)*(target-normalizer);
        float dial=0.75f+clamp(gain)*0.075f;
        float drive=y*normalizer*dial*0.90f;
        // Feed-forward peak compressor (1 ms attack, 120 ms release, 4:1 above 0.55):
        // keeps the tanh stage near-linear so transients do not clip into buzz.
        float pk=Math.abs(drive); pkEnv+=(pk>pkEnv?0.04f:0.00035f)*(pk-pkEnv);
        float want=pkEnv>0.55f?(0.55f+(pkEnv-0.55f)*0.25f)/pkEnv:1f;
        compGain+=(want<compGain?0.05f:0.0004f)*(want-compGain);
        float v=(float)Math.tanh(drive*compGain);
        // Slew limiter: caps per-sample step (~0.2 FS/sample: passes a full-scale 1.2 kHz sine, blocks steeper clicks) to stop harsh edges hitting the LRA end-stops.
        float maxStep=0.20f; v=lastOut+Math.max(-maxStep,Math.min(maxStep,v-lastOut)); lastOut=v;
        // Gate with hysteresis + smoothing: no clicks on fade-in/out.
        float open=rms>0.006f?1f:(rms<0.0025f?0f:gateGain>0.5f?1f:0f);
        gateGain+=(open>gateGain?0.01f:0.002f)*(open-gateGain);
        v*=gateGain;
        seed^=seed<<13;seed^=seed>>>17;seed^=seed<<5;
        float r1=((seed>>>16)&65535)/65536f;
        seed^=seed<<13;seed^=seed>>>17;seed^=seed<<5;
        float r2=((seed>>>16)&65535)/65536f;
        int sample=Math.round(v*PEAK+(r1-r2)*0.4f*gateGain);
        return (byte)Math.max(-PEAK,Math.min(PEAK,sample));
    }
}
