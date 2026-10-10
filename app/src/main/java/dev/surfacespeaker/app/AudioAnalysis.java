package dev.surfacespeaker.app;

/** 1024-point real PCM analysis. No audio is stored or transmitted. */
final class AudioAnalysis {
    static final int N = 1024;
    static final class Frame {
        final float[] bars;
        final float rms, bass;
        Frame(float[] bars,float rms,float bass){this.bars=bars;this.rms=rms;this.bass=bass;}
    }
    private final double[] re=new double[N], im=new double[N];
    Frame analyze(short[] pcm,int size,int sampleRate){
        int n=Math.min(N,size);
        double energy=0;
        for(int j=0;j<N;j++) {
            double v=j<n?pcm[size-n+j]/32768.0:0.0;
            energy+=v*v;
            re[j]=v*(0.5-0.5*Math.cos(2*Math.PI*j/(N-1)));
            im[j]=0;
        }
        int bit=0;
        for(int i=1;i<N;i++){
            int m=N>>1;
            while((bit&m)!=0){bit^=m;m>>=1;}
            bit^=m;
            if(i<bit){double a=re[i];re[i]=re[bit];re[bit]=a;}
        }
        for(int len=2;len<=N;len<<=1){
            double theta=-2*Math.PI/len, wr0=Math.cos(theta),wi0=Math.sin(theta);
            for(int i=0;i<N;i+=len){
                double wr=1,wi=0;
                for(int j=0;j<len/2;j++){
                    int x=i+j,y=x+len/2;
                    double ar=re[y]*wr-im[y]*wi,ai=re[y]*wi+im[y]*wr;
                    re[y]=re[x]-ar;im[y]=im[x]-ai;
                    re[x]+=ar;im[x]+=ai;
                    double nx=wr*wr0-wi*wi0;wi=wr*wi0+wi*wr0;wr=nx;
                }
            }
        }
        float[] bars=new float[72];double bassSum=0,bassWeight=0;
        for(int j=0;j<72;j++){
            double low=45*Math.pow(13000.0/45,j/72.0);
            double high=45*Math.pow(13000.0/45,(j+1)/72.0);
            int left=Math.max(1,(int)Math.floor(low*N/sampleRate));
            int right=Math.min(N/2-1,Math.max(left,(int)Math.ceil(high*N/sampleRate)));
            double peak=0;
            for(int k=left;k<=right;k++){
                double magnitude=Math.hypot(re[k],im[k])/(N*.25);
                peak=Math.max(peak,magnitude);
            }
            bars[j]=(float)Math.max(0,Math.min(1,Math.sqrt(peak)*2.4));
            if(low<260){bassSum+=peak;bassWeight++;}
        }
        float rms=(float)Math.max(0,Math.min(1,Math.sqrt(energy/Math.max(1,n))*5.0));
        float bass=(float)Math.max(0,Math.min(1,Math.sqrt(bassSum/Math.max(1,bassWeight))*4.0));
        return new Frame(bars,rms,bass);
    }
}