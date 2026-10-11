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
 * usage: richtap2 live <stop-marker> [lead_slots=4 (x5ms, adaptive)] [gain0_128=80] [idle_ms=1500]
 *   (embedded in native_fifo_arm64 as: fifo rt2-live|rt2-tone|rt2-f0 ...)
 *   LRA f0 on this phone: ro.odm.mm.vibrator resonant_frequency=130 Hz
 *        richtap2 tone <hz> (0.5 s, gain 24) | richtap2 f0
 */
#ifndef RT2_EMBED
#define _GNU_SOURCE
#endif
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
#define SLOT_SAMPLES 120u          /* 5 ms @ 24 kHz; multiple of 4 (HAP_PTN_FIFO_DIN_NUM) */
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
    if(streaming){
        /* Terminal slot: kernel sees FINISHED instead of reading an empty slot ("invalid data"). */
        unsigned char *s=slot(cursor);
        if(st(cursor)!=S_VALID){int16_t z=0;memcpy(s+2,&z,2);__atomic_store_n(s,0xAAu,__ATOMIC_RELEASE);}
        uint64_t t=now_us();while(pending()&&now_us()-t<200000)sleep_us(2000);
        ioctl(fd,IOC_STOP,0);streaming=0;
    }
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

/* Framed stdin protocol (same as live-stdin): [u8 strength 0..56][RT2_FRAME s8 samples].
 * Prints "NEXT:<n>:<us>" per frame so the Java LiveSession ACK logic is reused. */
#define RT2_FRAME SLOT_SAMPLES
static int read_full(unsigned char *p,size_t n){
    while(n){ssize_t r=read(0,p,n);if(r<=0){if(r<0&&errno==EINTR)continue;return -1;}p+=r;n-=(size_t)r;}
    return 0;
}
/* Kernel facts (qcom-hv-haptics.c RichTap patch): richtap_set_fifo() sets FIFO-empty threshold 480
 * samples (20 ms). Each FIFO-empty IRQ copies min(free HW space = 2048-480 = 1568, READY ring data);
 * the observed "update fifo len 1572" was the free-space bound because the ring was kept full.
 * With a shallow ring the kernel copies only what is ready, so latency ~= 20 ms HW floor + ring lead.
 * If the IRQ finds NO ready slot (3 x 1 ms retries) the kernel ends stream mode -> we detect that
 * (ring stops draining) and restart with a larger lead (adaptive). */
static uint64_t wts[SLOTS];
static unsigned char seen[SLOTS];
static double ring_ms=0;
static unsigned consumed_scan(uint64_t t){
    unsigned freed=0;
    for(unsigned i=0;i<SLOTS;i++){unsigned char v=st(i);
        if(seen[i]==S_VALID&&v!=S_VALID){freed++;double age=(t-wts[i])/1000.0;ring_ms=ring_ms<=0?age:ring_ms*0.9+age*0.1;}
        seen[i]=v;}
    return freed;
}
static int live(unsigned lead,unsigned gain,unsigned idle_ms){
    unsigned char in[1+RT2_FRAME];int8_t buf[RT2_FRAME],zero[RT2_FRAME]={0};
    uint64_t last_audio=0,last_input=0,last_consume=0,restart_window=0;unsigned long long frames=0,shown=0;unsigned quick_restarts=0;
    unsigned max_lead=14;
    if(gain>128){gain=128;}
    if(lead<2)lead=2;
    if(lead>max_lead)lead=max_lead;
    rt_priority();setvbuf(stdout,NULL,_IOLBF,0);
    if(ioctl(fd,IOC_GAIN,(unsigned long)gain)<0)fprintf(stderr,"WARN: SETTING_GAIN %s\n",strerror(errno));
    printf("READY: richtap2 gapless ring, 5ms slots, lead %u\n",lead);
    struct pollfd pfd={.fd=0,.events=POLLIN};
    while(!stopping()){
        int pr=poll(&pfd,1,1);
        uint64_t t=now_us();
        if(streaming&&consumed_scan(t))last_consume=t;
        if(pr>0){
            if(pfd.revents&(POLLHUP|POLLERR)&&!(pfd.revents&POLLIN))break;
            if(read_full(in,sizeof in))break;
            t=now_us();last_input=t;
            unsigned strength=in[0];if(strength>56){puts("ERROR: strength >56");return 8;}
            int loud=0;
            for(unsigned k=0;k<RT2_FRAME;k++){int v=((int)(int8_t)in[1+k]*(int)strength)/56;buf[k]=(int8_t)v;if(v>2||v<-2)loud=1;}
            if(loud)last_audio=t;
            if(!streaming&&loud){if(start_stream()){puts("ERROR: STREAM_MODE failed");return 4;}memset(seen,S_FREE,sizeof seen);last_consume=t;}
            if(streaming){
                unsigned idx=cursor;int rc=put_slot(buf,RT2_FRAME,lead);
                if(rc==0){wts[idx]=t;seen[idx]=S_VALID;}
                if(rc<0){fprintf(stderr,"WARN: slot busy timeout\n");}
            }
            frames++;printf("NEXT:%llu:%llu\n",frames,(unsigned long long)((now_us()-t)/1000));
        }
        if(!streaming)continue;
        t=now_us();
        /* Input gap (capture hiccup) -> insert silence so the kernel IRQ never finds an empty ring. */
        if(pending()==0&&t-last_input>2*5000ull){unsigned idx=cursor;if(put_slot(zero,RT2_FRAME,lead)==0){wts[idx]=t;seen[idx]=S_VALID;keepalive++;last_input=t;}}
        /* Ring has data but kernel stopped draining for >80 ms -> stream ended (underrun). Restart, grow lead. */
        if(pending()>0&&t-last_consume>80000){
            if(t-restart_window>3000000){restart_window=t;quick_restarts=0;}
            if(++quick_restarts>6){puts("ERROR: RichTap stream keeps stopping (6 restarts/3s)");stop_stream();return 9;}
            if(lead<max_lead)lead+=2;
            fprintf(stderr,"STATUS: underrun -> restart, lead now %u slots (%u ms)\n",lead,lead*5);
            printf("LEADUP:%u\n",lead);
            streaming=0; /* kernel already stopped; skip terminal slot */
            ioctl(fd,IOC_STOP,0);
            if(start_stream()){puts("ERROR: restart failed");return 4;}
            memset(seen,S_FREE,sizeof seen);last_consume=t;
        }
        if(t-last_audio>(uint64_t)idle_ms*1000)stop_stream();
        if(frames>=shown+200){shown=frames;
            printf("LAT:%.0f:%u:%u\n",ring_ms,20+lead*5/2,lead); /* ring age ms, est. HW FIFO ms, lead slots */
            fprintf(stderr,"STATUS: rt2 sent=%llu drop=%llu keep=%llu restart=%llu pend=%u ring=%.0fms\n",sent,dropped,keepalive,restarts,pending(),ring_ms);}
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
#ifdef RT2_EMBED
static int richtap2_main(int argc,char **argv){
#else
int main(int argc,char **argv){
#endif
    signal(SIGINT,on_sig);signal(SIGTERM,on_sig);
    if(argc<2){fprintf(stderr,"usage: live <marker> [lead] [gain] [idle_ms] | tone <hz> | f0\n");return 2;}
    if(verify()||open_ring())return 3;
    int rc=2;
    if(!strcmp(argv[1],"f0")){uint32_t f=0;rc=ioctl(fd,IOC_F0,&f);printf("F0 rc=%d value=%u (Hz, driver units unverified)\n",rc,f);rc=0;}
    else if(!strcmp(argv[1],"tone")&&argc>2)rc=tone(atoi(argv[2]));
    else if(!strcmp(argv[1],"live")&&argc>2){marker=argv[2];
        rc=live(argc>3?(unsigned)atoi(argv[3]):4,argc>4?(unsigned)atoi(argv[4]):80,argc>5?(unsigned)atoi(argv[5]):1500);}
    if(ring){munmap(ring,MMAP_SIZE);}
    if(fd>=0){close(fd);}
    return rc;
}
