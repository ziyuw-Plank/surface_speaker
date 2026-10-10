package dev.surfacespeaker.app;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Experimental root-only short tone test, never called by audio callbacks. */
final class RootFifoTester {
    private RootFifoTester() {}
    private static String quoted(String s){return "'" + s.replace("'", "'\"'\"'") + "'";}
    static boolean deviceCandidate() {
        if(!"shennong".equalsIgnoreCase(Build.DEVICE)) return false;
        for(String a:Build.SUPPORTED_ABIS) if("arm64-v8a".equals(a)) return true;
        return false;
    }
    static String run(Context context, boolean test, int frequency, int percent) {
        if(!deviceCandidate()) return "DISABLED: only validated ABI candidate for Xiaomi shennong arm64";
        if(test && (frequency<100 || frequency>300 || percent<1 || percent>25))return "DISABLED: parameter limit exceeded";
        File source=new File(context.getCacheDir(),"surface_fifo_arm64");
        String workdir="/data/local/tmp/ssfifo-"+java.util.UUID.randomUUID().toString().replace("-","").substring(0,20);
        String executable=workdir+"/fifo";
        Process process=null;
        try(InputStream in=context.getAssets().open("native_fifo_arm64");FileOutputStream out=new FileOutputStream(source)) {
            byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);
        }catch(Exception e){return "ERROR:extract native helper: "+e.getMessage();}
        try {
            // The root shell controls a short-lived binary at a fixed /data/local/tmp path.
            // Only quoted constant paths + validated integers reach the shell; no arbitrary shell UI.
            String args=test?("test "+frequency+" "+percent):"probe";
            String inner="mkdir -m 0700 "+quoted(workdir)+" || exit 6; cp "+quoted(source.getAbsolutePath())+" "+quoted(executable)
                +" && chmod 0700 "+quoted(executable)
                +" && "+quoted(executable)+" "+args
                +"; ret=$?; rm -f "+quoted(executable)+"; rmdir "+quoted(workdir)+"; exit $ret";
            process=new ProcessBuilder("/system/bin/sh","-c","su -c "+quoted(inner))
                    .redirectErrorStream(true).start();
            if(!process.waitFor(7,TimeUnit.SECONDS)){
                process.destroyForcibly();return "ERROR:Native probe timeout; verify no playback remains";
            }
            String result=new String(process.getInputStream().readNBytes(8192),StandardCharsets.UTF_8);
            return "exit="+process.exitValue()+"\n"+result;
        }catch(Exception e){return "ERROR:"+e.getClass().getSimpleName()+": "+e.getMessage();}
        finally{source.delete();if(process!=null && process.isAlive())process.destroyForcibly();}
    }
}