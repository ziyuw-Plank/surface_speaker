package dev.surfacespeaker.app;

import android.content.Context;
import android.media.audiofx.HapticGenerator;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.os.vibrator.VibratorEnvelopeEffectInfo;
import android.os.vibrator.VibratorFrequencyProfile;

/** Best-effort beat/energy haptics, NOT PCM-to-LRA playback. Never writes sysfs. */
final class HapticEngine {
    static final class Features {
        boolean present, amplitude, envelope, nativeAudio, vendorClass;
        float minHz, maxHz;
        long minSegmentMs;
        String vendorName;
    }
    static final class Tune {
        int gain = 62, bass=68, gate=6, smoothing=35, pulse=48, frequency=130, speaker=55;
        Tune copy() {Tune t=new Tune();t.gain=gain;t.bass=bass;t.gate=gate;t.smoothing=smoothing;t.pulse=pulse;t.frequency=frequency;t.speaker=speaker;return t;}
    }
    static Tune preset(int i) {
        Tune t=new Tune();
        if(i==1){t.gain=68;t.bass=86;t.gate=5;t.smoothing=42;t.pulse=55;}
        if(i==2){t.gain=54;t.bass=40;t.gate=5;t.smoothing=20;t.pulse=35;}
        return t;
    }
    final Vibrator motor;
    final Features features=new Features();
    private long lastPulse, totalMotionSince;
    private int outputPulses;
    private String lastOutputError="";
    int pulseCount(){return outputPulses;}
    String lastError(){return lastOutputError;}
    private float smooth;
    private boolean enabled;
    HapticEngine(Context context) {
        Vibrator m=null;
        if(Build.VERSION.SDK_INT>=31){
            VibratorManager manager=(VibratorManager)context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if(manager!=null) m=manager.getDefaultVibrator();
        }
        if(m==null)m=(Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE);
        motor=m;
        features.present=m!=null&&m.hasVibrator();
        features.amplitude=features.present&&m.hasAmplitudeControl();
        try{features.nativeAudio=Build.VERSION.SDK_INT>=31&&HapticGenerator.isAvailable();}catch(Throwable ignored){}
        features.vendorName=MiHapticProbe.classReport();
        features.vendorClass=features.vendorName.contains("存在");
        if(features.present && Build.VERSION.SDK_INT>=36) try {
            VibratorFrequencyProfile profile=m.getFrequencyProfile();
            VibratorEnvelopeEffectInfo env=m.getEnvelopeEffectInfo();
            features.envelope=m.areEnvelopeEffectsSupported()&&profile!=null&&env!=null;
            if(features.envelope){
                features.minHz=profile.getMinFrequencyHz();
                features.maxHz=profile.getMaxFrequencyHz();
                features.minSegmentMs=env.getMinControlPointDurationMillis();
            }
        }catch(Throwable ignored) {features.envelope=false;}
    }
    void setEnabled(boolean enabled){this.enabled=enabled;if(!enabled)stop();}
    boolean isEnabled(){return enabled;}
    void stop(){if(motor!=null) try{motor.cancel();}catch(Exception ignored){} smooth=0;lastPulse=0;totalMotionSince=0;}
    void onAudio(float rms,float bass,Tune t, boolean envelopeEnabled) {
        if(!enabled||!features.present)return;
        long now=SystemClock.uptimeMillis();
        float combined=Math.min(1, 0.46f*rms + 0.54f*bass*(0.5f+t.bass/100f));
        float alpha=0.19f+(100-t.smoothing)/100f*0.4f;
        smooth+=alpha*(combined-smooth);
        float gate=t.gate/100f*0.25f;
        if(smooth<gate)return;
        // Sensible signal-dependent intensity, large enough to feel while remaining capped.
        float intensity=Math.min(.67f, Math.max(.12f, (.18f+smooth*1.35f)*(t.gain/100f)));
        long pulse=Math.min(65,Math.max(25,t.pulse));
        if(now-lastPulse<Math.max(95,pulse+40))return;
        if(totalMotionSince==0)totalMotionSince=now;
        if(now-totalMotionSince>8_000L){
            if(now-totalMotionSince<8_450L)return;
            totalMotionSince=now;
        }
        lastPulse=now;
        try {
            if(envelopeEnabled && features.envelope && Build.VERSION.SDK_INT>=36){
                float f=Math.max(features.minHz,Math.min(features.maxHz,t.frequency));
                long half=Math.max(features.minSegmentMs, pulse/2);
                motor.vibrate(new VibrationEffect.WaveformEnvelopeBuilder()
                    .setInitialFrequencyHz(f)
                    .addControlPoint(intensity, f, half)
                    .addControlPoint(0f, f, half).build());
            }else if(features.amplitude){
                motor.vibrate(VibrationEffect.createOneShot(pulse,Math.min(175,Math.max(30,Math.round(255*intensity)))));
            }else motor.vibrate(VibrationEffect.createOneShot(pulse,VibrationEffect.DEFAULT_AMPLITUDE));
            outputPulses++;
            lastOutputError="";
        }catch(RuntimeException e){lastOutputError=e.getClass().getSimpleName()+": "+e.getMessage();}
    }
    void test(Tune t,boolean envelopePreferred){
        if(!features.present)return;
        try{
            if(envelopePreferred && features.envelope && Build.VERSION.SDK_INT>=36){
                float f=Math.max(features.minHz,Math.min(features.maxHz,t.frequency));
                long seg=Math.max(50,features.minSegmentMs);
                motor.vibrate(new VibrationEffect.WaveformEnvelopeBuilder()
                    .addControlPoint(0.12f,f,seg).addControlPoint(0,f,seg).build());
            }else motor.vibrate(VibrationEffect.createOneShot(140,features.amplitude?110:VibrationEffect.DEFAULT_AMPLITUDE));
        }catch(Exception e){lastOutputError=e.getClass().getSimpleName()+": "+e.getMessage();}
    }
}