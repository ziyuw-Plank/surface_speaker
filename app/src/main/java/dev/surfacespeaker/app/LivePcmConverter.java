package dev.surfacespeaker.app;

/** In-memory playback PCM -> 24 kHz signed 8-bit, 60-500 Hz band-limited motor samples.
 * Never stores or transmits audio. Blocks are 60 ms, and the kernel side is segmented. */
final class LivePcmConverter {
    interface Consumer { void accept(byte[] frame)throws Exception; }
    private final byte[] block=new byte[RootFifoTester.LiveSession.LIVE_SAMPLES];
    private int used;
    private long inputSample;
    private double nextOutput;
    private float last, old, hp, lp;
    private int phase;
    void consume(short[] pcm,int count,int rate,Consumer consumer)throws Exception {
        float a=(float)Math.exp(-2*Math.PI*60/24000);
        float b=1f-(float)Math.exp(-2*Math.PI*500/24000);
        for(int i=0;i<count;i++){
            float x=pcm[i]/32768f;
            while(nextOutput<=inputSample){
                float v=inputSample==0?x:last+(x-last)*(float)Math.max(0,Math.min(1,nextOutput-(inputSample-1)));
                hp=a*(hp+v-old);old=v;lp+=b*(hp-lp);
                // Gentle compression restores quiet passages while leaving headroom.
                int sample=Math.round((float)Math.tanh(lp*4.8)*76);
                if(sample>76)sample=76;if(sample< -76)sample=-76;
                block[used++]=(byte)sample;
                if(used==block.length){consumer.accept(block.clone());used=0;}
                nextOutput+=(double)rate/24000;
            }
            last=x;inputSample++;
        }
    }
}