package dev.surfacespeaker.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicBoolean;

/** Consented, system-playback-only capture. No mic capture, recording files, or network transmission. */
public final class PlaybackCaptureService extends Service {
    static final String START="dev.surfacespeaker.capture.START",STOP="dev.surfacespeaker.capture.STOP";
    static final String CODE="result_code",DATA="result_data",CHANNEL="surface_capture";
    interface Listener {
        void onCaptureFrame(AudioAnalysis.Frame frame,int pulses);
        void onCaptureState(String message,boolean active);
    }
    static volatile Listener listener;
    static volatile boolean running;
    static volatile boolean preferNativeFifo;
    static volatile String routeMessage="Idle";
    static volatile boolean richtapActive;
    static volatile String latencyText="-";
    static volatile int nativeBlocks;
    static volatile int nativeDropped;
    static volatile int nativeProcessingMs;
    static volatile int activeFrameMs;
    static void setNativeRoute(boolean requested){preferNativeFifo=requested;}

    private static volatile HapticEngine.Tune settings=HapticEngine.preset(0);
    static void updateTune(HapticEngine.Tune tune){settings=tune.copy();}
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final AtomicBoolean active=new AtomicBoolean(false);
    private AudioRecord recorder;
    private MediaProjection projection;
    private Thread captureThread;
    private HapticEngine motor;
    private volatile RootFifoTester.LiveSession liveSession;
    private volatile String stopError;

    private void state(String s,boolean active){
        handler.post(()->{Listener l=listener;if(l!=null)l.onCaptureState(s,active);});
    }
    @Override public void onCreate(){super.onCreate();motor=new HapticEngine(this);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        if(STOP.equals(intent.getAction())){shutdown();stopSelf();return START_NOT_STICKY;}
        if(!START.equals(intent.getAction()))return START_NOT_STICKY;
        stopError=null;
        if(active.get()){state("Already capturing",true);return START_NOT_STICKY;}
        try {
            NotificationManager nm=getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,"SurfaceSpeaker audio capture",NotificationManager.IMPORTANCE_LOW));
            Intent stop=new Intent(this,PlaybackCaptureService.class).setAction(STOP);
            PendingIntent stopAction=PendingIntent.getService(this,11,stop,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification=new Notification.Builder(this,CHANNEL)
                .setContentTitle("SurfaceSpeaker")
                .setContentText("Analyzing permitted audio playback")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"Stop",stopAction).build())
                .build();
            startForeground(43,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            Intent data=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra(DATA,Intent.class):(Intent)intent.getParcelableExtra(DATA);
            if(data==null)throw new IllegalStateException("Projection consent missing");
            int code=intent.getIntExtra(CODE,0);
            MediaProjectionManager manager=(MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection=manager.getMediaProjection(code,data);
            if(projection==null)throw new IllegalStateException("Projection unavailable");
            projection.registerCallback(new MediaProjection.Callback(){
                @Override public void onStop(){stopError="Android ended audio capture permission";shutdown();stopSelf();}
            },handler);
            AudioPlaybackCaptureConfiguration capture=new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            int sampleRate=48000;
            int minBuffer=AudioRecord.getMinBufferSize(sampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(minBuffer<=0)throw new IllegalStateException("No audio input buffer");
            recorder=new AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(capture)
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(minBuffer).build(); // v0.15.3: minimum buffer (capacity only; reads are 5 ms)
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("AudioRecord not initialized");
            recorder.startRecording();
            if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("Audio capture could not start");
            active.set(true);running=true;motor.setEnabled(true);state("Capture running",true);
            captureThread=new Thread(()->captureLoop(sampleRate),"surface-pcm-capture");captureThread.start();
        }catch(Exception e){stopError="Capture failed: "+e.getClass().getSimpleName()+" "+e.getMessage();shutdown();stopSelf();}
        return START_NOT_STICKY;
    }
    private void captureLoop(int sampleRate){
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioAnalysis fft=new AudioAnalysis();short[] pcm=new short[240];short[] hist=new short[AudioAnalysis.N];long lastUi=0;long framesRead=0;android.media.AudioTimestamp ats=new android.media.AudioTimestamp();int captureMs=-1;
        final boolean nativeMode=preferNativeFifo && RootFifoTester.deviceCandidate();
        // 48ms response (more restarts), 60ms balanced, 96ms smoother (more latency).
        final int frameMs=settings.liveFrameMs==96?96:settings.liveFrameMs==60?60:48;
        int frameSamples=frameMs*24;
        LivePcmConverter converter=null;
        nativeBlocks=0;nativeDropped=0;nativeProcessingMs=0;activeFrameMs=frameMs;
        if(nativeMode){
            try{
                // v0.15: try the gapless RichTap ring first (10 ms slots); fall back to FF_CUSTOM segments.
                try{
                    int lead=frameMs==48?3:frameMs==60?5:10;
                    liveSession=RootFifoTester.openLive(this,RootFifoTester.RICHTAP_FRAME,true,settings.fifoBoost?104:80,lead);
                    frameSamples=RootFifoTester.RICHTAP_FRAME;activeFrameMs=10;richtapActive=true;
                    routeMessage="RichTap gapless stream · 5ms slots · ring start "+(lead*5)+"ms · gain "+(settings.fifoBoost?104:80)+"/128";
                }catch(Exception rt){
                    richtapActive=false;
                    liveSession=RootFifoTester.openLive(this,frameSamples,false);
                    routeMessage="Native FIFO "+frameMs+"ms segments (RichTap failed: "+rt.getMessage()+")";
                }
                converter=new LivePcmConverter(frameSamples);
                motor.setEnabled(false);
                state(routeMessage,true);
            }catch(Exception e){
                routeMessage="Native FIFO unavailable: "+e.getMessage();
                stopError=routeMessage;shutdown();stopSelf();return;
            }
        }else{routeMessage="Android Haptics compatibility";}

        final LivePcmConverter[] conv={converter};
        int silent=0;
        while(active.get()){
            int count;
            try {count=recorder.read(pcm,0,pcm.length,AudioRecord.READ_BLOCKING);}catch(Exception e){stopError="Audio read error: "+e.getMessage();break;}
            if(count<0){stopError="Audio read returned error "+count;break;}
            if(count<=0)continue;
            framesRead+=count;
            System.arraycopy(hist,count,hist,0,hist.length-count);System.arraycopy(pcm,0,hist,hist.length-count,count);

            if(nativeMode){
                try{
                    conv[0].setTune(settings.gain,settings.detail,settings.bassClean,settings.trebleStable);
                    conv[0].consume(pcm,count,sampleRate,frameData->{
                        RootFifoTester.LiveSession session=liveSession;
                        if(session==null){if(!active.get())return;throw new IllegalStateException("Native session closed unexpectedly");}
                        HapticEngine.Tune nowTune=settings;
                        float level=motor.effectiveOutputScale(nowTune);
                        // Square-root law keeps dynamics audible at normal system volume.
                        // 8300 mV custom_vmax * ceiling% -> ~2.0 V (24) / ~2.5 V (30); v13 48-56% gave ~4-5 V and rattle.
                        // RichTap: Vmax set by helper gain; FIFO: 42% x 8300 mV ~3.5 V default, 56% boost (old max). DSP handles rattle.
                        int ceiling=richtapActive?56:(nowTune.fifoBoost?56:42);
                        int strength=level<=0f?0:Math.max(1,Math.min(ceiling,Math.round(ceiling*(float)Math.sqrt(level))));
                        int peak=0;for(byte sample:frameData)peak=Math.max(peak,Math.abs((int)sample));
                        if(peak<3)strength=0;
                        session.writeFrame(frameData,strength);
                        nativeBlocks=session.frames();nativeDropped=session.dropped();nativeProcessingMs=session.nativeDurationMs();
                        if(!session.alive())throw new IllegalStateException(session.recentLog());
                    });
                }catch(Exception e){
                    if(!active.get())break;
                    if(richtapActive){
                        // Mid-stream fallback: RichTap failed -> segmented FF_CUSTOM FIFO, keep capturing.
                        String why=e.getMessage();
                        try{RootFifoTester.LiveSession old=liveSession;liveSession=null;if(old!=null)old.close();}catch(Exception ignored){}
                        try{
                            int fs=frameMs*24;liveSession=RootFifoTester.openLive(this,fs,false);
                            conv[0]=new LivePcmConverter(fs);activeFrameMs=frameMs;richtapActive=false;
                            routeMessage="Native FIFO "+frameMs+"ms segments (RichTap stopped: "+why+")";state(routeMessage,true);
                            continue;
                        }catch(Exception f){e=f;}
                    }
                    routeMessage=(richtapActive?"RichTap":"Native FIFO")+" error: "+e.getMessage();stopError=routeMessage;break;
                }
            }else motor.onPcm(pcm,count,sampleRate,settings);
            long now=android.os.SystemClock.uptimeMillis();
            if(now-lastUi>85){
                lastUi=now;
                // Capture-side delay: how old the newest frame we just read is, from AudioRecord's timestamp.
                try{if(recorder!=null&&recorder.getTimestamp(ats,android.media.AudioTimestamp.TIMEBASE_MONOTONIC)==AudioRecord.SUCCESS){
                    long ageNs=System.nanoTime()-ats.nanoTime+(long)((ats.framePosition-framesRead)*1e9/sampleRate);
                    captureMs=(int)Math.max(0,ageNs/1000000);}}catch(Exception ignored){}
                RootFifoTester.LiveSession ls=liveSession;
                if(ls!=null&&richtapActive&&ls.ringMs>=0)
                    latencyText="capture "+(captureMs<0?"?":captureMs)+" + ring "+ls.ringMs+" + HW≈"+ls.hwMs+" ≈ "+((captureMs<0?0:captureMs)+ls.ringMs+ls.hwMs)+" ms (+mixer, lead "+ls.lead*5+"ms"+(ls.leadUps>0?", grew x"+ls.leadUps:"")+")";
                else if(ls!=null)latencyText="FIFO "+activeFrameMs+"ms segments; native "+nativeProcessingMs+"ms";
                // FFT is visual feedback only; do not run it for every 10.7ms audio chunk.
                AudioAnalysis.Frame frame=fft.analyze(hist,hist.length,sampleRate);
                if(frame.rms<0.006f)silent++;else silent=0;
                int pulses=nativeMode?nativeBlocks:motor.pulseCount();
                handler.post(()->{Listener l=listener;if(l!=null)l.onCaptureFrame(frame,pulses);});
                if(silent==40)state("No capturable audio detected: app may block capture",true);
            }
        }
        if(active.get()){shutdown();stopSelf();}
    }
    private synchronized void shutdown(){
        if(!active.getAndSet(false)&&recorder==null&&projection==null)return;
        running=false;
        if(motor!=null)motor.setEnabled(false);
        RootFifoTester.LiveSession session=liveSession;liveSession=null;
        if(session!=null)session.close();
        AudioRecord r=recorder;recorder=null;
        if(r!=null){try{r.stop();}catch(Exception ignored){}try{r.release();}catch(Exception ignored){}}
        MediaProjection p=projection;projection=null;
        if(p!=null)try{p.stop();}catch(Exception ignored){}
        state(stopError==null?"Capture stopped by user or service":"Capture ended: "+stopError,false);
    }
    @Override public void onDestroy(){shutdown();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}