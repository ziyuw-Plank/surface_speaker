package dev.surfacespeaker.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes at most four seconds of a user-selected local audio file.
 * Output is signed 8-bit, 24 kHz, deliberately band-limited and amplitude capped.
 * This is an offline, single-shot FIFO preview; it is NOT a gapless live streaming API.
 */
final class LocalPcmPreview {
    static final int SAMPLE_RATE=24000, MAX_SAMPLES=4*SAMPLE_RATE;
    static final class Clip {
        final File file;
        final int samples;
        Clip(File f,int n){file=f;samples=n;}
    }
    private static final class Converter {
        final byte[] pcm=new byte[MAX_SAMPLES];
        private int count;
        private long sourceIndex;
        private double nextPosition;
        private float last, oldX, high, low;
        private static final float HP=(float)Math.exp(-2*Math.PI*60/SAMPLE_RATE);
        private static final float LP=1-(float)Math.exp(-2*Math.PI*480/SAMPLE_RATE);
        boolean full(){return count>=MAX_SAMPLES;}
        void put(float value,int inputRate){
            if(full())return;
            float x=Math.max(-1f,Math.min(1f,value));
            while(nextPosition<=sourceIndex && !full()) {
                float v;
                if(sourceIndex==0)v=x;
                else v=last+(x-last)*(float)Math.min(1,Math.max(0,nextPosition-(sourceIndex-1)));
                high=HP*(high+v-oldX);
                oldX=v;
                low+=LP*(high-low);
                // PCM output stays within about +/-24/127 prior to driver magnitude scaling.
                float shaped=(float)Math.tanh(low*3.4)*64;
                float fade=Math.min(1f, Math.min((count+1)/1800f,(MAX_SAMPLES-count)/1800f));
                pcm[count++]=(byte)Math.round(Math.max(-64,Math.min(64,shaped*fade)));
                nextPosition+=(double)inputRate/SAMPLE_RATE;
            }
            last=x;
            sourceIndex++;
        }
        Clip write(Context c)throws Exception{
            if(count<2400)throw new IllegalStateException("Audio shorter than 100 ms or decoder returned no PCM");
            // Apply a real end fade even when the file is shorter than four seconds.
            int ramp=Math.min(1800,count/3);
            for(int i=0;i<ramp;i++)pcm[count-1-i]=(byte)Math.round(pcm[count-1-i]*((float)i/ramp));
            File file=File.createTempFile("ssfifo-clip-",".raw",c.getCacheDir());
            try(FileOutputStream out=new FileOutputStream(file)){out.write(pcm,0,count);}
            return new Clip(file,count);
        }
    }
    static Clip decode(Context context,Uri uri) throws Exception {
        MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;
        Converter cv=new Converter();
        try {
            extractor.setDataSource(context,uri,null);
            int track=-1;MediaFormat format=null;
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat f=extractor.getTrackFormat(i);
                String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("audio/")){track=i;format=f;break;}
            }
            if(track<0||format==null)throw new IllegalArgumentException("No audio track");
            extractor.selectTrack(track);
            codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format,null,null,0);codec.start();
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            boolean inputDone=false,outputDone=false;
            int sampleRate=format.containsKey(MediaFormat.KEY_SAMPLE_RATE)?format.getInteger(MediaFormat.KEY_SAMPLE_RATE):44100;
            int channels=format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)?format.getInteger(MediaFormat.KEY_CHANNEL_COUNT):2;
            int encoding=AudioFormat.ENCODING_PCM_16BIT;
            int idle=0;
            while(!outputDone&&!cv.full()&&idle<120){
                boolean progressed=false;
                if(!inputDone){
                    int inputIndex=codec.dequeueInputBuffer(10000);
                    if(inputIndex>=0){
                        ByteBuffer dest=codec.getInputBuffer(inputIndex);
                        int count=extractor.readSampleData(dest,0);
                        if(count<0){codec.queueInputBuffer(inputIndex,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}
                        else{codec.queueInputBuffer(inputIndex,0,count,extractor.getSampleTime(),0);extractor.advance();}
                        progressed=true;
                    }
                }
                int outputIndex=codec.dequeueOutputBuffer(info,10000);
                if(outputIndex==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    MediaFormat changed=codec.getOutputFormat();
                    sampleRate=changed.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels=changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if(changed.containsKey(MediaFormat.KEY_PCM_ENCODING))encoding=changed.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    progressed=true;
                }else if(outputIndex>=0){
                    if(info.size>0){
                        ByteBuffer out=codec.getOutputBuffer(outputIndex).duplicate().order(ByteOrder.LITTLE_ENDIAN);
                        out.position(info.offset);out.limit(info.offset+info.size);
                        int bytesPerSample=encoding==AudioFormat.ENCODING_PCM_FLOAT?4:2;
                        if(encoding!=AudioFormat.ENCODING_PCM_16BIT && encoding!=AudioFormat.ENCODING_PCM_FLOAT)
                            throw new IllegalArgumentException("Unsupported PCM decoder format: "+encoding);
                        channels=Math.max(1,channels);
                        int frames=info.size/(bytesPerSample*channels);
                        for(int f=0;f<frames && !cv.full();f++){
                            float v=0;
                            for(int ch=0;ch<channels;ch++){
                                v+=encoding==AudioFormat.ENCODING_PCM_FLOAT?out.getFloat():out.getShort()/32768f;
                            }
                            cv.put(v/channels,sampleRate);
                        }
                    }
                    outputDone=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    codec.releaseOutputBuffer(outputIndex,false);progressed=true;
                }
                idle=progressed?0:idle+1;
            }
            return cv.write(context);
        }finally{
            if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}
            extractor.release();
        }
    }
}