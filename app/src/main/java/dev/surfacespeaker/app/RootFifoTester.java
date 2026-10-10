package dev.surfacespeaker.app;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;

/** Explicit opt-in native FF_CUSTOM tests. Android playback/capture never calls this automatically. */
final class RootFifoTester {
    private RootFifoTester() {}
    private static final AtomicBoolean busy=new AtomicBoolean(false);
    private static volatile File stopMarker;
    static void cancel(){
        File f=stopMarker;
        if(f!=null)try{f.createNewFile();}catch(Exception ignored){}
    }
    private static String quote(String s){return "'"+s.replace("'","'\"'\"'")+"'";}
    static boolean deviceCandidate(){
        if(!"shennong".equalsIgnoreCase(Build.DEVICE))return false;
        for(String abi:Build.SUPPORTED_ABIS)if("arm64-v8a".equals(abi))return true;
        return false;
    }
    static String run(Context context,boolean test,int frequency,int percent){
        if(!deviceCandidate())return "DISABLED: only Xiaomi shennong arm64 is enabled";
        if(test&&(frequency<100||frequency>300||percent<1||percent>56))return "DISABLED: parameter bounds";
        String args=test?("test "+frequency+" "+percent):"probe";
        return execute(context,args,null);
    }
    static String longTone(Context context,int frequency,int strength){
        if(!deviceCandidate())return "DISABLED: Xiaomi shennong arm64 only";
        if(frequency<100||frequency>300||strength<1||strength>56)return "DISABLED: tone limits";
        return execute(context,"long-tone "+frequency+" "+strength,null);
    }
    static String localPreview(Context context,Uri uri,int strength,int gain){
        if(!deviceCandidate())return "DISABLED: Xiaomi shennong arm64 only";
        if(uri==null)return "ERROR: select an audio file first";
        if(strength<1||strength>56)return "ERROR: media volume muted or invalid output limit";
        if(!busy.compareAndSet(false,true))return "ERROR: native test already running";
        LocalPcmPreview.Clip clip=null;
        try {
            clip=LocalPcmPreview.decode(context,uri,gain);
            return runPrivileged(context,"play-file",strength,clip.file);
        }catch(Exception e){return "ERROR:decode/preview "+e.getClass().getSimpleName()+": "+e.getMessage();}
        finally{if(clip!=null)clip.file.delete();busy.set(false);}
    }
    private static String execute(Context context,String args,File pcm){
        if(!busy.compareAndSet(false,true))return "ERROR: native test already running";
        try{return runPrivileged(context,args,0,pcm);}
        finally{busy.set(false);}
    }
    /** 60 ms live frames, single-slot newest-frame queue, native completion acknowledgement.
     * A full queue drops outdated PCM rather than allowing hidden pipe buffering to drift.
     * FF_CUSTOM is still discrete; low latency != gapless or phase synchronized. */
    static final class LiveSession implements AutoCloseable {
        static final int LIVE_SAMPLES=1440; // 60 ms at 24 kHz
        static final int STALE_MS=120;
        private static final class Frame {
            final byte[] pcm;final int strength;final long received;
            Frame(byte[] pcm,int strength){this.pcm=pcm;this.strength=strength;received=android.os.SystemClock.uptimeMillis();}
        }
        private final Process process;
        private final OutputStream out;
        private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean(false);
        private final java.util.concurrent.ArrayBlockingQueue<Frame> queue=new java.util.concurrent.ArrayBlockingQueue<>(1);
        private final Object ackLock=new Object();
        private final File temp;
        private final File marker;
        private volatile String recentLog="";
        private final CountDownLatch ready=new CountDownLatch(1);
        private volatile boolean readyOk;
        private volatile int frames,dropped;
        private volatile String writerError="";
        private int acknowledged;
        private final Thread writer;
        LiveSession(Process p,File f,File m){
            process=p;out=p.getOutputStream();temp=f;marker=m;
            new Thread(()->{
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))){
                    String line;
                    while((line=reader.readLine())!=null){
                        recentLog=line;
                        if(line.startsWith("READY:")){readyOk=true;ready.countDown();}
                        if(line.startsWith("NEXT:")){synchronized(ackLock){acknowledged++;ackLock.notifyAll();}}
                        if(line.startsWith("ERROR:")){ready.countDown();}
                    }
                }catch(Exception e){recentLog="Root output: "+e.getMessage();}
                finally{ready.countDown();synchronized(ackLock){ackLock.notifyAll();}}
            },"fifo-root-log").start();
            writer=new Thread(this::writeLoop,"fifo-low-latency-writer");
            writer.start();
        }
        boolean awaitReady()throws InterruptedException {return ready.await(3,TimeUnit.SECONDS)&&readyOk&&process.isAlive();}
        void writeFrame(byte[] audio,int strength){
            if(closed.get()||!process.isAlive()||!writerError.isEmpty())
                throw new IllegalStateException("Native FIFO exited: "+recentLog+" "+writerError);
            if(audio.length!=LIVE_SAMPLES)throw new IllegalArgumentException("Expected 60ms PCM frame");
            Frame frame=new Frame(audio,Math.max(0,Math.min(56,strength)));
            if(!queue.offer(frame)){queue.poll();dropped++;if(!queue.offer(frame))dropped++;}
        }
        private void writeLoop(){
            try{
                int sent=0;
                while(!closed.get()&&process.isAlive()){
                    Frame frame=queue.poll(120,TimeUnit.MILLISECONDS);
                    if(frame==null)continue;
                    if(android.os.SystemClock.uptimeMillis()-frame.received>STALE_MS){dropped++;continue;}
                    out.write(frame.strength);out.write(frame.pcm);out.flush();sent++;
                    long until=android.os.SystemClock.uptimeMillis()+1200;
                    synchronized(ackLock){
                        while(!closed.get()&&process.isAlive()&&acknowledged<sent){
                            long remaining=until-android.os.SystemClock.uptimeMillis();
                            if(remaining<=0)throw new java.io.IOException("Native FIFO segment ACK timeout; "+recentLog);
                            ackLock.wait(remaining);
                        }
                    }
                    frames=sent;
                }
            }catch(InterruptedException e){Thread.currentThread().interrupt();}
            catch(Exception e){writerError=e.getClass().getSimpleName()+": "+e.getMessage();recentLog=writerError;}
        }
        int frames(){return frames;}
        int dropped(){return dropped;}
        String recentLog(){return writerError.isEmpty()?recentLog:writerError;}
        boolean alive(){return !closed.get()&&process.isAlive()&&writerError.isEmpty()&&writer.isAlive();}
        @Override public void close(){
            if(!closed.compareAndSet(false,true))return;
            queue.clear();writer.interrupt();
            try{marker.createNewFile();}catch(Exception ignored){}
            synchronized(ackLock){ackLock.notifyAll();}
            try{out.close();}catch(Exception ignored){}
            try{if(!process.waitFor(1,TimeUnit.SECONDS)){process.destroy();if(!process.waitFor(1,TimeUnit.SECONDS))process.destroyForcibly();}}
            catch(InterruptedException ignored){process.destroyForcibly();Thread.currentThread().interrupt();}
            marker.delete();temp.delete();busy.set(false);
        }
    }
    static LiveSession openLive(Context context)throws Exception {
        if(!deviceCandidate())throw new IllegalStateException("Native FIFO only enabled on shennong arm64");
        if(!busy.compareAndSet(false,true))throw new IllegalStateException("Another native FIFO test is active");
        File source=new File(context.getCacheDir(),"surface_fifo_stream_"+System.nanoTime());
        File marker=new File(context.getCacheDir(),"sf-stop-"+System.nanoTime());
        try {
            try(InputStream in=context.getAssets().open("native_fifo_arm64");FileOutputStream out=new FileOutputStream(source)){
                byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))>0)out.write(buffer,0,n);
            }
            String workdir="/data/local/tmp/ssfifo-stream-"+Long.toHexString(System.nanoTime());
            String executable=workdir+"/fifo";
            String inner="mkdir -m 0700 "+quote(workdir)+" || exit 6; cp "+quote(source.getAbsolutePath())+" "+quote(executable)
                +" && chmod 0700 "+quote(executable)+" && "+quote(executable)+" live-stdin "+quote(marker.getAbsolutePath())
                +"; ret=$?; rm -f "+quote(executable)+"; rmdir "+quote(workdir)+"; exit $ret";
            Process proc=new ProcessBuilder("/system/bin/sh","-c","su -c "+quote(inner)).redirectErrorStream(true).start();
            LiveSession session=new LiveSession(proc,source,marker);
            if(!session.awaitReady()) {String log=session.recentLog();session.close();throw new IllegalStateException("Root FIFO could not start: "+log);}
            return session;
        }catch(Exception e){source.delete();marker.delete();busy.set(false);throw e;}
    }
    private static String runPrivileged(Context context,String args,int strength,File pcm){
        File source=new File(context.getCacheDir(),"surface_fifo_arm64");
        String workdir="/data/local/tmp/ssfifo-"+java.util.UUID.randomUUID().toString().replace("-","").substring(0,16);
        String executable=workdir+"/fifo",remotePcm=workdir+"/preview.raw";
        Process process=null;
        File marker=new File(context.getCacheDir(),"ssfifo-stop-"+java.util.UUID.randomUUID().toString().substring(0,12));
        stopMarker=marker;
        try(InputStream in=context.getAssets().open("native_fifo_arm64");FileOutputStream out=new FileOutputStream(source)){
            byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);
        }catch(Exception e){return "ERROR:extract native helper: "+e.getMessage();}
        try {
            String extra=pcm==null?"":" && cp "+quote(pcm.getAbsolutePath())+" "+quote(remotePcm);
            String actual=pcm==null?args:"play-file "+quote(remotePcm)+" "+strength;
            if(!"probe".equals(args))actual+=" "+quote(marker.getAbsolutePath());
            String inner="mkdir -m 0700 "+quote(workdir)+" || exit 6; cp "+quote(source.getAbsolutePath())+" "+quote(executable)
                +" && chmod 0700 "+quote(executable)+extra+" && "+quote(executable)+" "+actual
                +"; ret=$?; if [ $ret -eq 0 ] && [ "+quote(args)+" != probe ]; then "
                +" dmesg 2>/dev/null | grep 'qcom-hv-haptics' | tail -8 || true; fi; "
                +" rm -f "+quote(executable)+" "+quote(remotePcm)+"; rmdir "+quote(workdir)+"; exit $ret";
            process=new ProcessBuilder("/system/bin/sh","-c","su -c "+quote(inner)).redirectErrorStream(true).start();
            if(!process.waitFor(15,TimeUnit.SECONDS)){
                process.destroyForcibly();return "ERROR: rooted helper timeout. Check kernel log and restart haptics if needed";
            }
            String result=new String(process.getInputStream().readNBytes(8192),StandardCharsets.UTF_8);
            return "exit="+process.exitValue()+"\n"+result;
        }catch(Exception e){return "ERROR:"+e.getClass().getSimpleName()+": "+e.getMessage();}
        finally{source.delete();marker.delete();if(stopMarker==marker)stopMarker=null;if(process!=null&&process.isAlive())process.destroyForcibly();}
    }
}