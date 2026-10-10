package dev.surfacespeaker.app;

/** In-memory playback PCM -> 24 kHz signed 8-bit, 52-820 Hz band-limited motor samples.
 * Never stores or transmits audio. Blocks are 60 ms, and the kernel side is segmented. */
final class LivePcmConverter {
    interface Consumer { void accept(byte[] frame)throws Exception; }
    private final byte[] block=new byte[RootFifoTester.LiveSession.LIVE_SAMPLES];
    private int used;
    private long inputSample;
    private double nextOutput;
    private float last;
    private final MotorPcmDsp dsp=new MotorPcmDsp();
    private volatile int gain=58;
    void setGain(int v){gain=Math.max(0,Math.min(100,v));}
    void consume(short[] pcm,int count,int rate,Consumer consumer)throws Exception {
        for(int i=0;i<count;i++){
            float x=pcm[i]/32768f;
            while(nextOutput<=inputSample){
                float v=inputSample==0?x:last+(x-last)*(float)Math.max(0,Math.min(1,nextOutput-(inputSample-1)));
                block[used++]=dsp.convert(v,gain);
                if(used==block.length){consumer.accept(block.clone());used=0;}
                nextOutput+=(double)rate/24000;
            }
            last=x;inputSample++;
        }
    }
}