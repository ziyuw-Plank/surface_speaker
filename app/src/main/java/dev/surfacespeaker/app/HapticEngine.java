package dev.surfacespeaker.app;

import android.content.Context;
import android.media.AudioManager;
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
        int gain = 58, bass=68, detail=65, gate=6, smoothing=45, pulse=60, frequency=130, speaker=55;
        boolean followVolume=true;
        boolean fifoBoost=false; // Opt-in experimental output tier; never alters Vmax or sysfs.
        int volumeRatio=65; // percentage of the app's capped haptic output
        Tune copy() {Tune t=new Tune();t.gain=gain;t.bass=bass;t.detail=detail;t.gate=gate;t.smoothing=smoothing;t.pulse=pulse;t.frequency=frequency;t.speaker=speaker;t.followVolume=followVolume;t.volumeRatio=volumeRatio;t.fifoBoost=fifoBoost;return t;}
    }
    static Tune preset(int i) {
        Tune t=new Tune();
        if(i==1){t.gain=62;t.bass=85;t.detail=40;t.gate=5;t.smoothing=45;t.pulse=60;}
        if(i==2){t.gain=48;t.bass=38;t.detail=92;t.gate=5;t.smoothing=20;t.pulse=40;}
        return t;
    }
    final Vibrator motor;
    private final AudioManager audioManager;
    private long lastVolumeRead;
    private float cachedMediaFraction=1f;
    final Features features=new Features();
    private long lastPulse, totalMotionSince;
    private int outputPulses;
    private String lastOutputError="";
    int pulseCount(){return outputPulses;}
    String lastError(){return lastOutputError;}
    private float smooth;
    // A band-limited audio envelope, sampled at ~100 Hz; this is NOT raw PCM motor drive.
    private float previousInput, highpass, lowpass, envelopeLevel;
    private double segmentEnergy;
    private int segmentCount, envelopeCount;
    private final long[] envelopeDurations = new long[8];
    private final int[] envelopeAmplitudes = new int[8];
    private long rollingStartMs;
    private boolean enabled;
    HapticEngine(Context context) {
        audioManager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
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
    /** Media volume is the Android STREAM_MUSIC index; this does not claim measured SPL. */
    synchronized float mediaVolumeFraction() {
        long now=SystemClock.uptimeMillis();
        if(now-lastVolumeRead>180 || lastVolumeRead==0){
            lastVolumeRead=now;
            if(audioManager!=null){
                try {
                    int max=audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    cachedMediaFraction=max>0 ? Math.max(0f,Math.min(1f,(float)audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)/max)) : 0f;
                } catch(Exception ignored){}
            }
        }
        return cachedMediaFraction;
    }
    float effectiveOutputScale(Tune t) {
        return Math.max(0,Math.min(1,t.volumeRatio/100f))*(t.followVolume?mediaVolumeFraction():1f);
    }
    void setEnabled(boolean enabled){this.enabled=enabled;if(!enabled)stop();}
    boolean isEnabled(){return enabled;}
    void stop(){if(motor!=null) try{motor.cancel();}catch(Exception ignored){} smooth=0;lastPulse=0;totalMotionSince=0;resetEnvelope();}
    private void resetEnvelope() {
        previousInput=highpass=lowpass=envelopeLevel=0f;
        segmentEnergy=0;
        segmentCount=envelopeCount=0;
        rollingStartMs=0;
    }
    /** Converts allowed playback PCM to 10 ms amplitude envelopes in the 35-300 Hz range.
      * Batched waveform updates prevent the old 95 ms one-shot pulse train.
      * This follows intensity dynamics, NOT pitch or a broadband audio waveform.
      */
    synchronized void onPcm(short[] samples, int count, int sampleRate, Tune t) {
        if(!enabled || !features.present || !features.amplitude || sampleRate<4000 || count<=0)return;
        final float hpCoeff=(float)Math.exp(-2*Math.PI*36.0/sampleRate);
        final float lpAlpha=1f-(float)Math.exp(-2*Math.PI*290.0/sampleRate);
        final int perSegment=Math.max(48, sampleRate/100);
        for(int i=0;i<count;i++) {
            float x=samples[i]/32768f;
            highpass=hpCoeff*(highpass+x-previousInput);
            previousInput=x;
            lowpass += lpAlpha*(highpass-lowpass);
            float band=(float)(lowpass*(0.55+0.45*t.bass/100.0));
            segmentEnergy+=band*band;
            if(++segmentCount>=perSegment){
                double rms=Math.sqrt(segmentEnergy/segmentCount);
                segmentEnergy=0;segmentCount=0;
                float threshold=0.0025f + t.gate/70f*0.035f;
                float target=(float)Math.max(0, rms-threshold);
                target=(float)Math.min(0.80, Math.sqrt(target)*(0.88+1.7*t.gain/100f));
                // High attack, tunable release keeps connected notes without exaggerated 'thumps'.
                float attack=0.48f-t.smoothing/100f*0.18f;
                float release=0.30f-t.smoothing/100f*0.23f;
                envelopeLevel+=(target-envelopeLevel)*(target>envelopeLevel?attack:release);
                int amp=(int)Math.min(170, Math.max(0, Math.round(185f*envelopeLevel*effectiveOutputScale(t))));
                if(amp<13)amp=0;
                int slot=envelopeCount++;
                envelopeDurations[slot]=Math.max(5,Math.round(segmentCountDuration(perSegment,sampleRate)));
                envelopeAmplitudes[slot]=amp;
                if(envelopeCount>=Math.max(4,Math.min(8,Math.round(t.pulse/10f))))flushEnvelope();
            }
        }
    }
    private static float segmentCountDuration(int samples,int sampleRate){return 1000f*samples/sampleRate;}
    private void flushEnvelope(){
        if(envelopeCount==0)return;
        long now=SystemClock.uptimeMillis();
        if(rollingStartMs==0)rollingStartMs=now;
        if(now-rollingStartMs>8_000){
            if(now-rollingStartMs<8_350){envelopeCount=0;return;}
            rollingStartMs=now;
        }
        int max=0;
        for(int j=0;j<envelopeCount;j++)max=Math.max(max,envelopeAmplitudes[j]);
        if(max<13){envelopeCount=0;return;}
        try{
            long[] times=new long[envelopeCount];
            int[] amps=new int[envelopeCount];
            System.arraycopy(envelopeDurations,0,times,0,envelopeCount);
            System.arraycopy(envelopeAmplitudes,0,amps,0,envelopeCount);
            motor.vibrate(VibrationEffect.createWaveform(times,amps,-1));
            outputPulses++;lastOutputError="";
        }catch(RuntimeException err){lastOutputError=err.getClass().getSimpleName()+": "+err.getMessage();}
        envelopeCount=0;
    }
    void onAudio(float rms,float bass,Tune t, boolean envelopeEnabled) {
        if(!enabled||!features.present)return;
        long now=SystemClock.uptimeMillis();
        float combined=Math.min(1, 0.46f*rms + 0.54f*bass*(0.5f+t.bass/100f));
        float alpha=0.19f+(100-t.smoothing)/100f*0.4f;
        smooth+=alpha*(combined-smooth);
        float gate=t.gate/100f*0.25f;
        if(smooth<gate)return;
        // Sensible signal-dependent intensity, large enough to feel while remaining capped.
        float intensity=Math.min(.67f, Math.max(0f, (.18f+smooth*1.35f)*(t.gain/100f)))*effectiveOutputScale(t);
        if(intensity<.03f)return;
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
            }else {
                int strength=Math.max(0,Math.min(110,Math.round(110*effectiveOutputScale(t))));
                if(strength==0)return;
                motor.vibrate(VibrationEffect.createOneShot(110,features.amplitude?Math.max(1,strength):VibrationEffect.DEFAULT_AMPLITUDE));
            }
        }catch(Exception e){lastOutputError=e.getClass().getSimpleName()+": "+e.getMessage();}
    }
}