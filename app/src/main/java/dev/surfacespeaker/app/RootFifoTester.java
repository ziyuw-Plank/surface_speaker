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
        if(test&&(frequency<100||frequency>300||percent<1||percent>25))return "DISABLED: parameter bounds";
        String args=test?("test "+frequency+" "+percent):"probe";
        return execute(context,args,null);
    }
    static String longTone(Context context,int frequency,int strength){
        if(!deviceCandidate())return "DISABLED: Xiaomi shennong arm64 only";
        if(frequency<100||frequency>300||strength<1||strength>25)return "DISABLED: tone limits";
        return execute(context,"long-tone "+frequency+" "+strength,null);
    }
    static String localPreview(Context context,Uri uri,int strength){
        if(!deviceCandidate())return "DISABLED: Xiaomi shennong arm64 only";
        if(uri==null)return "ERROR: select an audio file first";
        if(strength<1||strength>25)return "ERROR: media volume muted or invalid output limit";
        if(!busy.compareAndSet(false,true))return "ERROR: native test already running";
        LocalPcmPreview.Clip clip=null;
        try {
            clip=LocalPcmPreview.decode(context,uri);
            return runPrivileged(context,"play-file",strength,clip.file);
        }catch(Exception e){return "ERROR:decode/preview "+e.getClass().getSimpleName()+": "+e.getMessage();}
        finally{if(clip!=null)clip.file.delete();busy.set(false);}
    }
    private static String execute(Context context,String args,File pcm){
        if(!busy.compareAndSet(false,true))return "ERROR: native test already running";
        try{return runPrivileged(context,args,0,pcm);}
        finally{busy.set(false);}
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
                +"; ret=$?; rm -f "+quote(executable)+" "+quote(remotePcm)+"; rmdir "+quote(workdir)+"; exit $ret";
            process=new ProcessBuilder("/system/bin/sh","-c","su -c "+quote(inner)).redirectErrorStream(true).start();
            if(!process.waitFor(12,TimeUnit.SECONDS)){
                process.destroyForcibly();return "ERROR: rooted helper timeout. Check kernel log and restart haptics if needed";
            }
            String result=new String(process.getInputStream().readNBytes(8192),StandardCharsets.UTF_8);
            return "exit="+process.exitValue()+"\n"+result;
        }catch(Exception e){return "ERROR:"+e.getClass().getSimpleName()+": "+e.getMessage();}
        finally{source.delete();marker.delete();if(stopMarker==marker)stopMarker=null;if(process!=null&&process.isAlive())process.destroyForcibly();}
    }
}