package dev.surfacespeaker.app;

/** Dependency-free JVM regression test, invoked from GitHub Actions with javac/java. */
public final class MotorPcmDspSmokeTest {
    private static void verify(boolean pass,String why){if(!pass)throw new AssertionError(why);}
    private static double rms(int hz,float input,int gain) {return rms(hz,input,gain,65);}
    private static double rms(int hz,float input,int gain,int detail) {
        MotorPcmDsp p=new MotorPcmDsp();double sum=0;int count=0;
        for(int i=0;i<24000;i++){
            int s=p.convert((float)(input*Math.sin(2*Math.PI*hz*i/24000)),gain,detail);
            verify(Math.abs(s)<=MotorPcmDsp.PEAK,"PCM amplitude cap exceeded");
            if(i>=6000){sum+=s*s;count++;}
        }
        return Math.sqrt(sum/count);
    }
    public static void main(String[] args){
        MotorPcmDsp silent=new MotorPcmDsp();
        for(int i=0;i<12000;i++)verify(silent.convert(0f,58)==0,"silence creates haptics");
        double low=rms(200,.11f,10);
        double high=rms(200,.11f,80);
        verify(high>low*1.25,"native gain setting not applied: "+low+"->"+high);
        double mid=rms(300,.11f,58);
        double upper=rms(650,.11f,58);
        verify(mid>12&&upper>12,"signal detail lost: "+mid+" "+upper);
        double narrow=rms(700,.05f,45,0), enhanced=rms(700,.05f,45,100);
        verify(enhanced>narrow*1.15,"detail slider does not preserve higher audio frequencies: "+narrow+"->"+enhanced);
        System.out.printf("DSP smoke passed: gain RMS %.1f->%.1f, 300Hz %.1f, 650Hz %.1f%n",low,high,mid,upper);
    }
}