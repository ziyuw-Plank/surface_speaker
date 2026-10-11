/*
 * SurfaceSpeaker DRAFT (not built into the APK, not hardware-verified):
 * gapless low-latency PCM streaming through the RichTap shared ring of
 * /dev/qcom_haptic (qcom-hv-haptics.c, CONFIG_RICHTAP_FOR_PMIC_ENABLE).
 *
 * ABI (from public qcom-hv-haptics.c with RichTap patch; must be re-checked
 * against the user's own qcom-hv-haptics.ko):
 *   ioctl group 0x52: STREAM_MODE=_IO(0x52,0x0A)=0x520A, STOP_MODE=0x5212,
 *                     SETTING_GAIN=0x5205 (arg 0..128 * lower_mv / 128), GET_F0=0x5211
 *   mmap 16 KiB (PAGE_SIZE<<2) = 16 slots x 1024 B, #pragma pack(4):
 *     +0 u8 status (0x55 VALID, 0xAA FINISHED, 0xFF INVALID/free)
 *     +2 s16 length  +8 kernel_next(kernel-owned)  +16 user_next  +24 data[1000] (s8 PCM)
 *   Kernel: STREAM_MODE -> stop FIFO, schedule stream work, THEN richtap_clean_buf(0xFF).
 *   Work waits <=100 ms for slot 0 VALID, pre-fills HW FIFO (2048 samples on HAP525),
 *   then FIFO-empty IRQ (threshold ~288 samples) copies further VALID slots in order.
 *   If HW fill < 24 samples, or the ring has no VALID slot, kernel ends stream mode.
 *
 * Fixes vs the reverted v0.14/0.15 richtap_stream.c:
 *  1. STREAM_MODE is issued BEFORE writing samples. The old helper queued the first
 *     block and then called STREAM_MODE, whose richtap_clean_buf() wiped it -> kernel
 *     "first length invalid" -> stream died, then the helper timed out / fell back.
 *  2. Ring cursor restarts at slot 0 on every (re)start; kernel always begins at start_buf.
 *  3. Small 10 ms slots (240 samples) and a bounded lead (default 3 slots = 30 ms)
 *     instead of 4 x 960 samples (160 ms) queued + up to 2048 in HW.
 *  4. Never lets the ring run dry while live: input gaps are filled with zero slots
 *     (keep-alive), so the kernel never hits the underrun/erase path -> no restart clicks.
 *     After `idle_ms` of pure silence it stops cleanly and restarts on next audio.
 *  5. Latency cap: if more than max_lead slots are pending, incoming audio is dropped
 *     (oldest-first is impossible once VALID; we drop new input instead and count it).
 *
 * Input on stdin: raw signed 8-bit PCM @ 24 kHz, any chunking (already DSP'd + capped).
 * usage: richtap2 live <stop-marker> [lead_slots=3] [gain0_128=64] [idle_ms=1500]
 *        richtap2 tone <hz> (0.5 s, gain 24) | richtap2 f0
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define DEV "/dev/qcom_haptic"
#define MMAP_SIZE 16384u
#define SLOTS 16u
#define SLOT_BYTES 1024u
#define PAYLOAD 24u
#define SLOT_SAMPLES 240u          /* 10 ms @ 24 kHz; multiple of 4 (HAP_PTN_FIFO_DIN_NUM) */
#define RATE 24000
#define IOC_STREAM 0x520Au
#define IOC_STOP   0x5212u
#define IOC_GAIN   0x5205u
#define IOC_F0     0x5211u
#define S_VALID 0x55u
#define S_FREE  0xFFu
#define PEAK 92

static volatile sig_atomic_t stop_flag;
static const char *marker;
static int fd=-1, streaming;
static unsigned char *ring;
static unsigned cursor;
static unsigned long long sent, dropped, keepalive, restarts;

static void on_sig(int s){(void)s;stop_flag=1;}
static uint64_t now_us(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return (uint64_t)t.tv_sec*1000000ull+t.tv_nsec/1000;}
static void sleep_us(long us){struct timespec t={us/1000000,(us%1000000)*1000};nanosleep(&t,NULL);}
static int stopping(void){return stop_flag||(marker&&access(marker,F_OK)==0);}
static unsigned char *slot(unsigned i){return ring+(i%SLOTS)*SLOT_BYTES;}
static unsigned char st(unsigned i){return __atomic_load_n(slot(i),__ATOMIC_ACQUIRE);}
static unsigned pending(void){unsigned n=0;for(unsigned i=0;i<SLOTS;i++)if(st(i)==S_VALID)n++;return n;}

static int verify(void){
    char v[64]={0};FILE *p=popen("getprop ro.product.device","r");
    if(!p||!fgets(v,sizeof v,p)){if(p)pclose(p);return -1;}pclose(p);
    if(strncmp(v,"shennong",8)){fprintf(stderr,"ERROR: not shennong\n");return -1;}
    struct stat s;if(stat(DEV,&s)||!S_ISCHR(s.st_mode)){fprintf(stderr,"ERROR: %s missing\n",DEV);return -1;}
    return 0;
}
static int open_ring(void){
    fd=open(DEV,O_RDWR|O_CLOEXEC);if(fd<0){perror("open");return -1;}
    void *m=mmap(NULL,MMAP_SIZE,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0);
    if(m==MAP_FAILED){perror("mmap");return -1;}
    ring=m;
    for(unsigned i=0;i<SLOTS;i++){uintptr_t nx=(uintptr_t)slot(i+1);memcpy(slot(i)+16,&nx,sizeof nx);}
    return 0;
}
static void stop_stream(void){
    if(streaming){ioctl(fd,IOC_STOP,0);streaming=0;}
}
static int start_stream(void){
    stop_stream();
    if(ioctl(fd,IOC_STREAM,0)<0){fprintf(stderr,"ERROR: STREAM_MODE %s\n",strerror(errno));return -1;}
    /* ioctl returns after richtap_clean_buf(0xFF): ring is free, kernel waits on slot 0. */
    cursor=0;streaming=1;restarts++;return 0;
}
/* Write one 10 ms slot. Returns 0 ok, 1 dropped (lead cap), -1 kernel stalled. */
static int put_slot(const int8_t *pcm,unsigned n,unsigned max_lead){
    if(pending()>=max_lead){dropped++;return 1;}
    unsigned char *s=slot(cursor);
    uint64_t t0=now_us();
    while(st(cursor)==S_VALID){ /* kernel has not consumed this slot yet */
        if(now_us()-t0>60000)return -1;
        sleep_us(500);
    }
    for(unsigned i=0;i<n;i++){int v=pcm[i];if(v>PEAK)v=PEAK;if(v<-PEAK)v=-PEAK;s[PAYLOAD+i]=(unsigned char)(int8_t)v;}
    int16_t len=(int16_t)n;memcpy(s+2,&len,2);
    __atomic_store_n(s,S_VALID,__ATOMIC_RELEASE);
    cursor=(cursor+1)%SLOTS;sent++;return 0;
}
static void rt_priority(void){
    struct sched_param p={.sched_priority=10};
    if(sched_setscheduler(0,SCHED_FIFO,&p)!=0)fprintf(stderr,"WARN: SCHED_FIFO unavailable (%s)\n",strerror(errno));
}

static int live(unsigned lead,unsigned gain,unsigned idle_ms){
    int8_t buf[SLOT_SAMPLES],zero[SLOT_SAMPLES]={0};unsigned have=0;
    uint64_t last_audio=0,next_due=0;int8_t tmp[4096];
    if(gain>128){gain=128;}
    ioctl(fd,IOC_GAIN,(unsigned long)gain);
    rt_priority();setvbuf(stdout,NULL,_IOLBF,0);
    puts("READY: richtap2 gapless ring, 10ms slots");
    struct pollfd pfd={.fd=0,.events=POLLIN};
    while(!stopping()){
        int pr=poll(&pfd,1,2);   /* 2 ms tick */
        if(pr>0){
            ssize_t r=read(0,tmp,sizeof tmp);if(r<=0)break;
            for(ssize_t i=0;i<r;i++){
                buf[have++]=tmp[i];
                if(have==SLOT_SAMPLES){
                    int loud=0;for(unsigned k=0;k<SLOT_SAMPLES;k++)if(buf[k]>2||buf[k]<-2){loud=1;break;}
                    uint64_t t=now_us();if(loud)last_audio=t;
                    if(!streaming&&loud){if(start_stream())return 4;next_due=t;}
                    if(streaming){int rc=put_slot(buf,SLOT_SAMPLES,lead);if(rc<0){fprintf(stderr,"WARN: kernel stalled, restart\n");if(start_stream())return 4;}
                        next_due=t+10000;}
                    have=0;
                }
            }
        }
        if(!streaming)continue;
        uint64_t t=now_us();
        /* keep-alive: input late by >1 slot -> insert silence so ring never drains */
        if(t>next_due+10000 && pending()<2){put_slot(zero,SLOT_SAMPLES,lead);keepalive++;next_due=t;}
        if(t-last_audio>(uint64_t)idle_ms*1000){stop_stream();}
        static unsigned long long shown;if(sent>=shown+500){shown=sent;printf("STAT sent=%llu drop=%llu keep=%llu restart=%llu pend=%u\n",sent,dropped,keepalive,restarts,pending());}
    }
    stop_stream();
    fprintf(stderr,"DONE sent=%llu drop=%llu keepalive=%llu restarts=%llu\n",sent,dropped,keepalive,restarts);
    return 0;
}
static int tone(int hz){
    if(hz<50||hz>400)return 2;
    ioctl(fd,IOC_GAIN,24ul);if(start_stream())return 4;
    int8_t b[SLOT_SAMPLES];int total=RATE/2;
    for(int s0=0;s0<total&&!stopping();s0+=SLOT_SAMPLES){
        for(unsigned j=0;j<SLOT_SAMPLES;j++){int i=s0+j;double f=fmin(1,fmin(i/960.0,(total-i)/960.0));b[j]=(int8_t)lrint(60*f*sin(2*M_PI*hz*i/RATE));}
        while(put_slot(b,SLOT_SAMPLES,4)==1&&!stopping())sleep_us(1000);
    }
    uint64_t t=now_us();while(pending()&&now_us()-t<300000)sleep_us(2000);
    stop_stream();puts("OK: tone submitted (motor motion unverified)");return 0;
}
int main(int argc,char **argv){
    signal(SIGINT,on_sig);signal(SIGTERM,on_sig);
    if(argc<2){fprintf(stderr,"usage: live <marker> [lead] [gain] [idle_ms] | tone <hz> | f0\n");return 2;}
    if(verify()||open_ring())return 3;
    int rc=2;
    if(!strcmp(argv[1],"f0")){uint32_t f=0;rc=ioctl(fd,IOC_F0,&f);printf("F0 rc=%d value=%u (Hz, driver units unverified)\n",rc,f);rc=0;}
    else if(!strcmp(argv[1],"tone")&&argc>2)rc=tone(atoi(argv[2]));
    else if(!strcmp(argv[1],"live")&&argc>2){marker=argv[2];
        rc=live(argc>3?(unsigned)atoi(argv[3]):3,argc>4?(unsigned)atoi(argv[4]):64,argc>5?(unsigned)atoi(argv[5]):1500);}
    if(ring){munmap(ring,MMAP_SIZE);}
    if(fd>=0){close(fd);}
    return rc;
}
