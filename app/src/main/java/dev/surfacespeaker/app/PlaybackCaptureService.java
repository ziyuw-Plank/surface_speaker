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
    static volatile int nativeBlocks;
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

    private void state(String s,boolean active){
        handler.post(()->{Listener l=listener;if(l!=null)l.onCaptureState(s,active);});
    }
    @Override public void onCreate(){super.onCreate();motor=new HapticEngine(this);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        if(STOP.equals(intent.getAction())){shutdown();stopSelf();return START_NOT_STICKY;}
        if(!START.equals(intent.getAction()))return START_NOT_STICKY;
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
                @Override public void onStop(){shutdown();stopSelf();}
            },handler);
            AudioPlaybackCaptureConfiguration capture=new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            int sampleRate=22050;
            int minBuffer=AudioRecord.getMinBufferSize(sampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(minBuffer<=0)throw new IllegalStateException("No audio input buffer");
            recorder=new AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(capture)
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(Math.max(minBuffer,AudioAnalysis.N*4)).build();
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("AudioRecord not initialized");
            recorder.startRecording();
            if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("Audio capture could not start");
            active.set(true);running=true;motor.setEnabled(true);state("Capture running",true);
            captureThread=new Thread(()->captureLoop(sampleRate),"surface-pcm-capture");captureThread.start();
        }catch(Exception e){state("Capture failed: "+e.getClass().getSimpleName()+" "+e.getMessage(),false);shutdown();stopSelf();}
        return START_NOT_STICKY;
    }
    private void captureLoop(int sampleRate){
        AudioAnalysis fft=new AudioAnalysis();short[] pcm=new short[AudioAnalysis.N];long lastUi=0;
        final boolean nativeMode=preferNativeFifo && RootFifoTester.deviceCandidate();
        final LivePcmConverter converter=nativeMode?new LivePcmConverter():null;
        nativeBlocks=0;
        if(nativeMode){
            try{
                liveSession=RootFifoTester.openLive(this);
                motor.setEnabled(false);
                routeMessage="Native FIFO: buffering 500ms blocks; possible seams";
                state(routeMessage,true);
            }catch(Exception e){
                routeMessage="Native FIFO unavailable: "+e.getMessage();
                state(routeMessage,false);shutdown();stopSelf();return;
            }
        }else{routeMessage="Android Haptics compatibility";}

        int silent=0;
        while(active.get()){
            int count;
            try {count=recorder.read(pcm,0,pcm.length,AudioRecord.READ_BLOCKING);}catch(Exception e){state("Audio read error: "+e.getMessage(),false);break;}
            if(count<0){state("Audio read returned error "+count,false);break;}
            if(count<256)continue;
            AudioAnalysis.Frame frame=fft.analyze(pcm,count,sampleRate);
            if(frame.rms<0.006f)silent++;else silent=0;
            if(nativeMode){
                try{
                    converter.consume(pcm,count,sampleRate,frameData->{
                        RootFifoTester.LiveSession session=liveSession;
                        if(session==null)throw new IllegalStateException("Native root stream stopped");
                        HapticEngine.Tune nowTune=settings;
                        float level=motor.effectiveOutputScale(nowTune);
                        // Square-root law keeps dynamics audible at normal system volume.
                        int strength=level<=0f?0:Math.max(1,Math.min(40,Math.round(40f*(float)Math.sqrt(level))));
                        int peak=0;for(byte sample:frameData)peak=Math.max(peak,Math.abs((int)sample));
                        if(peak<3)strength=0;
                        session.writeFrame(frameData,strength);
                        nativeBlocks=session.frames();
                    });
                }catch(Exception e){routeMessage="Native FIFO streaming error: "+e.getMessage();state(routeMessage,false);break;}
            }else motor.onPcm(pcm,count,sampleRate,settings);
            long now=android.os.SystemClock.uptimeMillis();
            if(now-lastUi>65){
                lastUi=now;
                int pulses=nativeMode?nativeBlocks:motor.pulseCount();
                handler.post(()->{Listener l=listener;if(l!=null)l.onCaptureFrame(frame,pulses);});
            }
            if(silent==150)state("No capturable audio detected: app may block capture",true);
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
        state("Capture stopped",false);
    }
    @Override public void onDestroy(){shutdown();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}