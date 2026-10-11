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
    static String localPreview(Context context,Uri uri,int strength,int gain,int detail,int bassClean,int trebleStable){
        if(!deviceCandidate())return "DISABLED: Xiaomi shennong arm64 only";
        if(uri==null)return "ERROR: select an audio file first";
        if(strength<1||strength>56)return "ERROR: media volume muted or invalid output limit";
        if(!busy.compareAndSet(false,true))return "ERROR: native test already running";
        LocalPcmPreview.Clip clip=null;
        try {
            clip=LocalPcmPreview.decode(context,uri,gain,detail,bassClean,trebleStable);
            return runPrivileged(context,"play-file",strength,clip.file);
        }catch(Exception e){return "ERROR:decode/preview "+e.getClass().getSimpleName()+": "+e.getMessage();}
        finally{if(clip!=null)clip.file.delete();busy.set(false);}
    }
    static final String F0_PATH="/sys/class/qcom-haptics/lra_frequency_hz";
    /** Read LRA f0 (read-only): direct read first, then `su cat`. Updates MotorPcmDspV14.deviceF0. */
    static String loadDeviceF0(){
        String raw=null;
        try(BufferedReader r=new BufferedReader(new java.io.FileReader(F0_PATH))){raw=r.readLine();}catch(Exception ignored){}
        if(MotorPcmDspV14.parseF0(raw)<0){
            try{Process p=new ProcessBuilder("su","-c","cat "+F0_PATH).redirectErrorStream(true).start();
                if(p.waitFor(3,TimeUnit.SECONDS))raw=new String(p.getInputStream().readNBytes(64),StandardCharsets.UTF_8).trim();else p.destroyForcibly();
            }catch(Exception ignored){}
        }
        float f=MotorPcmDspV14.parseF0(raw);
        if(f>0)MotorPcmDspV14.deviceF0=f;
        return "LRA f0: raw="+raw+" -> "+MotorPcmDspV14.deviceF0+" Hz"+(f>0?"":" (fallback)");
    }
    /** Copy the bundled helper to app files dir so the user can run it manually via su. */
    static String persistHelper(Context context){
        File dst=new File(context.getFilesDir(),"native_fifo_arm64");
        try(InputStream in=context.getAssets().open("native_fifo_arm64");FileOutputStream out=new FileOutputStream(dst)){
            byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);
        }catch(Exception e){return "ERROR: extract "+e.getMessage();}
        dst.setExecutable(true,true);
        return dst.getAbsolutePath();
    }
    /** Safe RichTap ring test: 0.5 s, 130 Hz, gain 24/128 (fixed inside helper), then filtered dmesg. */
    static String richtapTone(Context context){
        if(!deviceCandidate())return "DISABLED: Xiaomi shennong arm64 only";
        String path=persistHelper(context);
        StringBuilder sb=new StringBuilder();
        sb.append("Helper copy: ").append(path).append("\nManual: su -c 'cp ").append(path).append(" /data/local/tmp/nf && chmod 700 /data/local/tmp/nf && /data/local/tmp/nf rt2-tone 130'\n\n");
        sb.append("== rt2-tone 130 ==\n").append(execute(context,"rt2-tone 130",null)).append("\n");
        try{Process p=new ProcessBuilder("su","-c","dmesg | grep -iE 'richtap|fifo|haptic' | tail -30").redirectErrorStream(true).start();
            if(!p.waitFor(5,TimeUnit.SECONDS))p.destroyForcibly();
            sb.append("== dmesg (richtap|fifo|haptic, last 30) ==\n").append(new String(p.getInputStream().readNBytes(16384),StandardCharsets.UTF_8));
        }catch(Exception e){sb.append("dmesg error: ").append(e.getMessage());}
        return sb.toString();
    }
    private static String execute(Context context,String args,File pcm){
        if(!busy.compareAndSet(false,true))return "ERROR: native test already running";
        try{return runPrivileged(context,args,0,pcm);}
        finally{busy.set(false);}
    }
    /** Selectable 48/60/96 ms FF_CUSTOM blocks, single-slot newest-frame queue, native acknowledgement.
     * A full queue drops outdated PCM rather than allowing hidden pipe buffering to drift.
     * FF_CUSTOM is still discrete; low latency != gapless or phase synchronized. */
    static final class LiveSession implements AutoCloseable {
        static final int DEFAULT_SAMPLES=1152; // 48 ms at 24kHz
        private final int frameSamples;
        private final int staleMs;
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
        private final java.util.ArrayDeque<String> tail=new java.util.ArrayDeque<>();
        String tailLog(){synchronized(tail){return String.join(" | ",tail);}}
        private final CountDownLatch ready=new CountDownLatch(1);
        private volatile boolean readyOk;
        private volatile int frames,dropped;
        private volatile int nativeDurationMs;
        private volatile String writerError="";
        private int acknowledged;
        private final Thread writer;
        LiveSession(Process p,File f,File m,int samples){
            process=p;out=p.getOutputStream();temp=f;marker=m;
            frameSamples=samples;staleMs=Math.max(75,(samples/24)*2);
            new Thread(()->{
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))){
                    String line;
                    while((line=reader.readLine())!=null){
                        recentLog=line;synchronized(tail){tail.addLast(line);while(tail.size()>6)tail.removeFirst();}
                        if(line.startsWith("READY:")){readyOk=true;ready.countDown();}
                        if(line.startsWith("NEXT:")){
                            String[] items=line.split(":");
                            if(items.length>=3)try{nativeDurationMs=Integer.parseInt(items[2]);}catch(NumberFormatException ignored){}
                            synchronized(ackLock){acknowledged++;ackLock.notifyAll();}
                        }
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
            if(audio.length!=frameSamples)throw new IllegalArgumentException("Expected "+frameSamples+" PCM samples");
            Frame frame=new Frame(audio,Math.max(0,Math.min(56,strength)));
            if(!queue.offer(frame)){queue.poll();dropped++;if(!queue.offer(frame))dropped++;}
        }
        private void writeLoop(){
            try{
                int sent=0;
                while(!closed.get()&&process.isAlive()){
                    Frame frame=queue.poll(120,TimeUnit.MILLISECONDS);
                    if(frame==null)continue;
                    if(android.os.SystemClock.uptimeMillis()-frame.received>staleMs){dropped++;continue;}
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
        int nativeDurationMs(){return nativeDurationMs;}
        String recentLog(){return (writerError.isEmpty()?"":writerError+" | ")+tailLog();}
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
    static final int RICHTAP_FRAME=240; // 10 ms slots for the gapless /dev/qcom_haptic ring (v0.15 experimental)
    static LiveSession openLive(Context context,int frameSamples)throws Exception {return openLive(context,frameSamples,false,80);}
    static LiveSession openLive(Context context,int frameSamples,boolean richtap)throws Exception {return openLive(context,frameSamples,richtap,80);}
    /** richtapGain 0..128 scales the driver's RichTap Vmax; lead 10 x 10 ms slots covers the kernel's ~1572-sample refills. */
    static LiveSession openLive(Context context,int frameSamples,boolean richtap,int richtapGain)throws Exception {
        if(richtap?frameSamples!=RICHTAP_FRAME:(frameSamples!=1152 && frameSamples!=1440 && frameSamples!=2304))throw new IllegalArgumentException("Unsupported frame samples");
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
                +" && chmod 0700 "+quote(executable)+" && "+quote(executable)+(richtap?" rt2-live "+quote(marker.getAbsolutePath())+" 10 "+Math.max(16,Math.min(128,richtapGain))+" 1500":" live-stdin "+quote(marker.getAbsolutePath())+" "+frameSamples)
                +"; ret=$?; rm -f "+quote(executable)+"; rmdir "+quote(workdir)+"; exit $ret";
            Process proc=new ProcessBuilder("/system/bin/sh","-c","su -c "+quote(inner)).redirectErrorStream(true).start();
            LiveSession session=new LiveSession(proc,source,marker,frameSamples);
            if(!session.awaitReady()) {String log=session.tailLog();session.close();throw new IllegalStateException("Root FIFO could not start: "+log);}
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