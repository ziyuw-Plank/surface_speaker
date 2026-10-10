package dev.surfacespeaker.app;

/** Shared, stateful 24 kHz motor-audio DSP for local previews and live capture.
 * Reversible stateful spectral balance, bounded slow loudness compensation and 8-bit TPDF dithering.
 * Designed for detail and safe signal scaling; NOT an LRA frequency calibration. */
final class MotorPcmDsp {
    static final int RATE=24000;
    static final int PEAK=92; // signed 8-bit sample ceiling, independent of hardware Vmax
    private static final float HP=(float)Math.exp(-2*Math.PI*52/RATE);
    private static final float LP=(float)(1-Math.exp(-2*Math.PI*1250/RATE));
    private static final float BASS_LP=(float)(1-Math.exp(-2*Math.PI*280/RATE));
    private float oldX,hi,lowA,lowB,bassLP,rmsSq=0.0004f,normalizer=1f;
    private int seed=0x1234ab8f;

    byte convert(float input,int gain) {return convert(input,gain,65);}
    byte convert(float input,int gain,int detail) {
        float x=Math.max(-1f,Math.min(1f,input));
        hi=HP*(hi+x-oldX);oldX=x;
        lowA+=LP*(hi-lowA);
        lowB+=LP*(lowA-lowB);
        float wide=0.65f*lowA+0.35f*lowB;
        bassLP+=BASS_LP*(wide-bassLP);
        // Tune only the information the LRA can plausibly respond to (roughly 280-1250 Hz).
        // A shelf rather than a hard gate preserves phase transitions and transients.
        float shelf=0.65f+Math.max(0,Math.min(100,detail))*0.011f;
        float y=bassLP+(wide-bassLP)*shelf;
        // ~120 ms detector, with fast gain reduction and slow return to protect transients.
        rmsSq+=0.00034f*(y*y-rmsSq);
        float rms=(float)Math.sqrt(Math.max(0,rmsSq));
        float target=Math.max(0.8f,Math.min(5.0f,0.23f/(rms+0.055f)));
        normalizer+=(target<normalizer?0.009f:0.00025f)*(target-normalizer);
        float dial=0.75f+Math.max(0,Math.min(100,gain))*0.075f;
        float v=y*normalizer*dial;
        // Soft limiter preserves signal polarity/phase and attenuates hot peaks instead of hard clipping.
        v=v/(1f+0.32f*Math.abs(v));
        v=Math.max(-1f,Math.min(1f,v));
        // Avoid manufacturing constant vibration from quiet capture noise.
        float gate=Math.min(1f,Math.max(0f,(rms-0.0025f)/0.011f));
        v*=gate;
        seed^=seed<<13;seed^=seed>>>17;seed^=seed<<5;
        float rand1=((seed>>>16)&65535)/65536f;
        seed^=seed<<13;seed^=seed>>>17;seed^=seed<<5;
        float rand2=((seed>>>16)&65535)/65536f;
        int s=Math.round(v*PEAK+(rand1-rand2)*0.4f);
        return (byte)Math.max(-PEAK,Math.min(PEAK,s));
    }
}