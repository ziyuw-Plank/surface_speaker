/*
 * SurfaceSpeaker v0.14: shennong-only RichTap shared-ring compatibility experiment.
 * Derived from static analysis of the user's exact libaacvibrator.so and
 * qcom-hv-haptics.ko (Android 16 kernel 6.1.138). Not a generic ABI.
 * Do not use on an unverified other device/driver build.
 */
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#include <signal.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define DEV "/dev/qcom_haptic"
#define MMAP_SIZE 16384u
#define SLOTS 16u
#define SLOT_BYTES 1024u
#define PAYLOAD_OFFSET 24u
#define PAYLOAD_MAX 960u /* keep below 1000 byte structural maximum */
#define RATE 24000
#define RICHTAP_STREAM_MODE 0x520Au
#define RICHTAP_STOP_MODE 0x5212u
#define READY 0x55u
#define FREE 0xFFu
#define END 0xAAu
#define MAX_QUEUE 4u
#define MAX_AMP 36 /* more conservative than FF_CUSTOM; not calibrated motor power */

static volatile sig_atomic_t stop_requested;
static const char *stop_path;
static int active_fd=-1;
static unsigned char *mapped;
static int stream_started;
static void signal_stop(int signum){(void)signum; stop_requested=1;}
static uint64_t millis(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return (uint64_t)t.tv_sec*1000+t.tv_nsec/1000000;}
static void pause_ms(long ms){struct timespec ts={.tv_sec=ms/1000,.tv_nsec=(ms%1000)*1000000};while(nanosleep(&ts,&ts)<0&&errno==EINTR&&!stop_requested){} }
static int stopping(void){return stop_requested || (stop_path && access(stop_path,F_OK)==0);}
static unsigned char *slot(unsigned index){return mapped+index*SLOT_BYTES;}
static unsigned char get_state(unsigned index){return __atomic_load_n(slot(index),__ATOMIC_ACQUIRE);}
static void put_state(unsigned index,unsigned char state){__atomic_store_n(slot(index),state,__ATOMIC_RELEASE);}
static void cleanup(void){
    if(active_fd>=0 && stream_started){if(ioctl(active_fd,RICHTAP_STOP_MODE,0)<0)fprintf(stderr,"WARN:STOP_MODE errno=%d\n",errno);stream_started=0;}
    if(mapped){munmap(mapped,MMAP_SIZE);mapped=NULL;}
    if(active_fd>=0){close(active_fd);active_fd=-1;}
}
static int verify_device(void){
    char value[128]={0};FILE *f=popen("getprop ro.product.device","r");
    if(!f){fprintf(stderr,"ERROR: cannot verify device\n");return -1;}
    int ok=fgets(value,sizeof(value),f)!=NULL;pclose(f);
    if(!ok || strncmp(value,"shennong\n",8)!=0){fprintf(stderr,"ERROR: device is not shennong\n");return -1;}
    struct stat s;
    if(stat(DEV,&s)!=0||!S_ISCHR(s.st_mode)||(unsigned long)s.st_rdev != ((10u<<8)|108u)){
        fprintf(stderr,"ERROR: unexpected /dev/qcom_haptic character device\n");return -1;
    }
    return 0;
}
/* Linux device numbers: avoids dependency on host major()/minor() macros. */
static int setup_ring(void){
    active_fd=open(DEV,O_RDWR|O_CLOEXEC);
    if(active_fd<0){fprintf(stderr,"ERROR:open RichTap: %s\n",strerror(errno));return -1;}
    void *p=mmap(NULL,MMAP_SIZE,PROT_READ|PROT_WRITE,MAP_SHARED,active_fd,0);
    if(p==MAP_FAILED){fprintf(stderr,"ERROR:mmap 16KiB: %s\n",strerror(errno));return -1;}
    mapped=p;
    /* Match vendor initialisation: only userspace-linked +0x10 pointer,
       state and length. Never touch kernel-owned +0x08 linkage. */
    for(unsigned i=0;i<SLOTS;i++){
        unsigned char *s=slot(i);
        uintptr_t next=(uintptr_t)slot((i+1)%SLOTS);
        memcpy(s+16,&next,sizeof(next));
        s[2]=s[3]=0;
        put_state(i,FREE);
    }
    return 0;
}
static unsigned pending(void){unsigned n=0;for(unsigned i=0;i<SLOTS;i++)if(get_state(i)==READY)n++;return n;}
static int queue_samples(const int8_t *src,size_t len,unsigned *cursor,int amplitude,uint64_t deadline){
    while(len>0){
        if(stopping())return -2;
        if(millis()>deadline){fprintf(stderr,"ERROR:RichTap buffer not consumed; FIFO timeout\n");return -1;}
        unsigned index=*cursor%SLOTS;
        if(get_state(index)!=FREE || pending()>=MAX_QUEUE){pause_ms(1);continue;}
        size_t n=len<PAYLOAD_MAX?len:PAYLOAD_MAX;
        unsigned char *p=slot(index);
        for(size_t i=0;i<n;i++){
            int val=((int)src[i]*amplitude)/100;
            if(val>MAX_AMP)val=MAX_AMP;
            if(val< -MAX_AMP)val=-MAX_AMP;
            p[PAYLOAD_OFFSET+i]=(unsigned char)(int8_t)val;
        }
        p[2]=(unsigned char)n;p[3]=(unsigned char)(n>>8);
        put_state(index,READY);
        *cursor=(*cursor+1)%SLOTS;
        src+=n;len-=n;
    }
    return 0;
}
static int begin_stream(void){
    if(stream_started)return 0;
    if(ioctl(active_fd,RICHTAP_STREAM_MODE,0)<0){fprintf(stderr,"ERROR:STREAM_MODE errno=%d (%s)\n",errno,strerror(errno));return -1;}
    stream_started=1;return 0;
}
static int do_tone(void){
    /* 200 Hz x 0.35 s; modest duty and 12% user gain. */
    enum { TOTAL=8400, CHUNK=480 };
    int8_t b[CHUNK];unsigned cursor=0;
    uint64_t started=millis();
    for(int start=0;start<TOTAL && !stopping();start+=CHUNK){
        for(int j=0;j<CHUNK;j++){
            int i=start+j; double fade=fmin(1.0,fmin((double)i/960.0,(double)(TOTAL-i-1)/960.0));
            if(fade<0)fade=0;
            b[j]=(int8_t)lrint(80.0*fade*sin(2.0*3.14159265358979323846*200*i/RATE));
        }
        /* prime first 40ms before starting to avoid immediate underrun */
        if(queue_samples(b,CHUNK,&cursor,12,millis()+150)!=0)return 5;
        if(start==480 && begin_stream()!=0)return 6;
        if(millis()-started>1600){fprintf(stderr,"ERROR:Tone safety timeout\n");return 7;}
    }
    if(!stream_started && begin_stream()!=0)return 6;
    /* Give the last samples time to complete; never block longer than the experiment cap. */
    uint64_t limit=millis()+450;
    while(pending()>0 && millis()<limit && !stopping())pause_ms(2);
    printf("OK: short RichTap mmap STREAM_MODE experiment submitted; actual playback unverified\n");
    return stopping()?130:0;
}
static int do_live(int n){
    if(n!=1152 && n!=1440 && n!=2304){fprintf(stderr,"ERROR:unsupported block\n");return 2;}
    int8_t *samples=malloc((size_t)n);if(!samples)return 4;
    unsigned cursor=0;int batches=0;float sustained=0.0f;
    setvbuf(stdout,NULL,_IOLBF,0);
    puts("READY: RichTap mmap stream opened; auto fallback on playback faults");
    while(!stopping()){
        unsigned char strength;
        if(fread(&strength,1,1,stdin)!=1)break;
        if(fread(samples,1,(size_t)n,stdin)!=(size_t)n){fprintf(stderr,"ERROR:short PCM block\n");free(samples);return 8;}
        if(strength>56){fprintf(stderr,"ERROR:strength >56\n");free(samples);return 8;}
        uint64_t ts=millis();
        // Conservative rolling energy budget without arbitrary 20 s playback cutoff.
        float energy=0.0f;
        for(int i=0;i<n;i++){float x=(float)samples[i]/92.0f;energy+=x*x;}
        sustained=0.996f*sustained+0.004f*(energy/n)*((float)strength/56.0f);
        float derate=sustained>0.20f?fmaxf(0.55f,1.0f-(sustained-0.20f)*1.4f):1.0f;
        int out_strength=(int)lrintf((float)strength*65.0f/56.0f*derate);
        /* MAX_AMP still limits each physical waveform byte. */
        int rc=queue_samples(samples,(size_t)n,&cursor,out_strength,ts+180);
        if(rc){free(samples);return rc==-2?0:9;}
        if(!stream_started && begin_stream()!=0){free(samples);return 10;}
        batches++;
        printf("NEXT:%d:%llu\n",batches,(unsigned long long)(millis()-ts));
    }
    free(samples);return 0;
}
int main(int argc,char **argv){
    signal(SIGTERM,signal_stop);signal(SIGINT,signal_stop);
    if(argc<2){fprintf(stderr,"usage: richtap probe | tone [stop-marker] | live-stdin <stop-marker> <frame-samples>\n");return 2;}
    if(verify_device()!=0)return 3;
    if(!strcmp(argv[1],"probe")){
        active_fd=open(DEV,O_RDONLY|O_CLOEXEC);
        if(active_fd<0){fprintf(stderr,"ERROR: open RichTap: %s\n",strerror(errno));return 4;}
        puts("OK: qcom_haptic device verified; mmap/stream ABI not yet exercised");cleanup();return 0;
    }
    if(strcmp(argv[1],"tone") && strcmp(argv[1],"live-stdin")){fprintf(stderr,"ERROR: invalid mode\n");return 2;}
    if(!strcmp(argv[1],"live-stdin") && argc!=4)return 2;
    stop_path=argc>2?argv[2]:NULL;
    if(setup_ring()!=0){cleanup();return 5;}
    int rc=!strcmp(argv[1],"tone")?do_tone():do_live(atoi(argv[3]));
    cleanup();return rc;
}