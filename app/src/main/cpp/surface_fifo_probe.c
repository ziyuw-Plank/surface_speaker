/*
 * SurfaceSpeaker v0.9: FF_CUSTOM previews plus experimental buffered live capture.
 * ABI is based on the public qcom-hv-haptics.c custom_fifo_data path.
 * This is NOT an unlimited PCM streamer. No /dev/qcom_haptic access or sysfs writes.
 */
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <math.h>
#include <signal.h>
#include <sys/stat.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif
struct qcom_fifo_payload {
    uint32_t idx;
    uint32_t length;
    uint32_t play_rate_hz;
    uint8_t *data;
};
_Static_assert(sizeof(struct qcom_fifo_payload) == 24, "Only 64-bit ABI is supported");
static volatile sig_atomic_t aborted = 0;
static const char *stop_marker = NULL;
static int live_mode = 0;
static void on_signal(int sig) {(void)sig;aborted=1;}
static void sleep_ms(int ms) {
    struct timespec tv = { .tv_sec = ms / 1000, .tv_nsec = (ms % 1000) * 1000000L };
    while(nanosleep(&tv, &tv) == -1 && errno == EINTR) {}
}
static int bit(const unsigned long *a, int n){ return (a[n / (8 * sizeof(long))] >> (n % (8 * sizeof(long)))) & 1; }
static int find_haptic(char *path, size_t cap) {
    for(int i=0;i<64;i++) {
        char namepath[120];snprintf(namepath,sizeof(namepath),"/sys/class/input/event%d/device/name",i);
        FILE *f=fopen(namepath,"r"); if(!f)continue;
        char s[128]={0};int ok=fgets(s,sizeof(s),f) && strncmp(s,"qcom-hv-haptics",15)==0; fclose(f);
        if(ok){snprintf(path,cap,"/dev/input/event%d",i);return 0;}
    }
    return -1;
}
static int open_verified(char *path,size_t cap) {
    if(find_haptic(path,cap)!=0){fprintf(stderr,"ERROR:no qcom-hv-haptics input device\n");return -1;}
    int fd=open(path,O_RDWR|O_CLOEXEC|O_NONBLOCK);
    if(fd<0){fprintf(stderr,"ERROR:open %s: %s\n",path,strerror(errno));return -1;}
    char name[128]={0};
    if(ioctl(fd,EVIOCGNAME(sizeof(name)),name)<0 || strcmp(name,"qcom-hv-haptics")!=0){
        fprintf(stderr,"ERROR:unexpected input device\n");close(fd);return -1;
    }
    unsigned long bits[(FF_MAX + 8*sizeof(long))/(8*sizeof(long)) + 1]={0};
    if(ioctl(fd,EVIOCGBIT(EV_FF,sizeof(bits)),bits)<0 ||
       !bit(bits,FF_PERIODIC) || !bit(bits,FF_CUSTOM)) {
        fprintf(stderr,"ERROR:FF_CUSTOM/FF_PERIODIC not advertised\n");close(fd);return -1;
    }
    return fd;
}
/* One upload drives one long FIFO effect; the kernel refills its 2048-byte FIFO via IRQ.
 * Never repeatedly submit short FF events to simulate audio. */
static int play_fifo(int fd, const char *path, uint8_t *data, int samples, int strength){
    if(samples<1200 || samples>96000 || strength<1 || strength>40){
        fprintf(stderr,"ERROR: unsupported FIFO samples=%d strength=%d\n",samples,strength);
        return 2;
    }
    // Cap ALL sample bytes, including a file supplied by a privileged caller.
    for(int i=0;i<samples;i++){
        int v=(int)(int8_t)data[i];
        if(v>64)v=64;
        if(v< -64)v=-64;
        int fade=i<360?i:(samples-1-i<360?samples-1-i:360);
        if(fade<0)fade=0;
        v=v*fade/360;
        data[i]=(uint8_t)(int8_t)v;
    }
    struct qcom_fifo_payload payload={.idx=0,.length=(uint32_t)samples,.play_rate_hz=24000,.data=data};
    struct ff_effect effect;memset(&effect,0,sizeof(effect));
    effect.type=FF_PERIODIC;effect.id=-1;
    effect.replay.length=(uint16_t)(samples/24+150);
    effect.u.periodic.waveform=FF_CUSTOM;
    effect.u.periodic.magnitude=(uint16_t)((32767L*strength)/100L);
    effect.u.periodic.custom_len=sizeof(payload);
    effect.u.periodic.custom_data=(int16_t*)&payload;
    fprintf(stderr,"STATUS: upload FF_CUSTOM samples=%d rate=24000 magnitude=%d percent\n",samples,strength);
    if(ioctl(fd,EVIOCSFF,&effect)<0){fprintf(stderr,"ERROR:EVIOCSFF: %s\n",strerror(errno));return 4;}
    fprintf(stderr,"STATUS: effect uploaded id=%d\n",effect.id);
    struct input_event ev;memset(&ev,0,sizeof(ev));ev.type=EV_FF;ev.code=effect.id;ev.value=1;
    if(write(fd,&ev,sizeof(ev))!=(ssize_t)sizeof(ev)){
        fprintf(stderr,"ERROR:start write: %s\n",strerror(errno));ioctl(fd,EVIOCRMFF,effect.id);return 5;
    }
    fprintf(stderr,"STATUS: EV_FF start accepted. Actual motor displacement is unverified.\n");
    int total=samples/24+(live_mode?0:60);
    for(int elapsed=0;elapsed<total && !aborted;elapsed+=(live_mode?10:25)){
        if(stop_marker && access(stop_marker,F_OK)==0){aborted=1;break;}
        sleep_ms(live_mode?10:25);
    }
    ev.value=0;(void)write(fd,&ev,sizeof(ev));
    if(!live_mode)sleep_ms(25);
    (void)ioctl(fd,EVIOCRMFF,effect.id);
    printf("OK:%s single FIFO upload %d samples (%.2fs), 24kHz, magnitude %d%%; kernel accepted FF_CUSTOM; actual motor vibration NOT verified\n",
        path,samples,samples/24000.0,strength);
    return aborted?130:0;
}
int main(int argc,char **argv){
    if(argc<2){fprintf(stderr,"Usage: fifo probe | test <hz> <strength> | long-tone <hz> <strength> | play-file <raw> <strength> | live-stdin\n");return 2;}
    signal(SIGINT,on_signal);signal(SIGTERM,on_signal);
    char path[64];int fd=open_verified(path,sizeof(path));if(fd<0)return 3;
    if(!strcmp(argv[1],"live-stdin")){
        /* EXPERIMENTAL: raw stdin transport, NOT an appendable kernel FIFO. Each
         * half-second FF_CUSTOM segment is a separate effect, with possible gaps. */
        if(argc!=3){fprintf(stderr,"ERROR:live-stdin requires stop marker path\n");close(fd);return 2;}
        stop_marker=argv[2];
        uint8_t *pcm=malloc(12000);if(!pcm){close(fd);return 3;}
        live_mode=1;
        setvbuf(stdout,NULL,_IOLBF,0);
        printf("READY: live FIFO experimental, 500ms blocks, potential seams\n");
        int chunks=0, failures=0;
        while(!aborted && chunks<120 && access(stop_marker,F_OK)!=0) { // 60s max
            uint8_t strength=0;
            if(fread(&strength,1,1,stdin)!=1)break;
            size_t n=fread(pcm,1,12000,stdin);
            if(n!=12000){fprintf(stderr,"ERROR:short incoming audio block %zu\n",n);failures++;break;}
            if(strength>40){fprintf(stderr,"ERROR:strength exceeds native safety ceiling\n");failures++;break;}
            if(strength==0){sleep_ms(500);}
            else {int rc=play_fifo(fd,"live",pcm,12000,strength);if(rc){failures++;break;}}
            chunks++;
            if(chunks%8==0){fprintf(stderr,"STATUS:live buffered chunks=%d (%.1f s)\n",chunks,chunks*0.5);}
            /* Periodic cooling at fixed conservative intensity envelope. */
            if(chunks%30==0 && chunks<120){fprintf(stderr,"STATUS:cooldown 1s after 15s playback\n");sleep_ms(1000);}
        }
        fprintf(stderr,"DONE:live blocks=%d errors=%d\n",chunks,failures);
        free(pcm);close(fd);return failures?9:0;
    }
    if(!strcmp(argv[1],"probe")){printf("OK: %s FF_PERIODIC+FF_CUSTOM advertised (no actual playback confirmed)\n",path);close(fd);return 0;}
    int tone=!strcmp(argv[1],"test")||!strcmp(argv[1],"long-tone");
    int file=!strcmp(argv[1],"play-file");
    if(!tone && !file){fprintf(stderr,"ERROR:unknown command\n");close(fd);return 2;}
    if(argc!=4 && argc!=5){fprintf(stderr,"ERROR:expected 2 parameters and optional stop marker\n");close(fd);return 2;}
    if(argc==5)stop_marker=argv[4];
    char *end=NULL;long strength=strtol(argv[3],&end,10);
    if(*end || strength<1 || strength>40){fprintf(stderr,"ERROR:strength must be 1..40%%\n");close(fd);return 2;}
    uint8_t *samples=NULL;int n=0;
    if(tone){
        end=NULL;long hz=strtol(argv[2],&end,10);
        if(*end||hz<100||hz>300){fprintf(stderr,"ERROR:frequency 100..300 Hz\n");close(fd);return 2;}
        n=!strcmp(argv[1],"test")?1200:48000;
        samples=calloc(n,1);if(!samples){close(fd);return 3;}
        for(int i=0;i<n;i++){
            int v=(int)lrint(64.0*sin(2*M_PI*(double)hz*i/24000.0));
            samples[i]=(uint8_t)(int8_t)v;
        }
    }else{
        const char *filename=argv[2];
        struct stat st;
        if(stat(filename,&st)<0||!S_ISREG(st.st_mode)||st.st_size<2400||st.st_size>96000){
            fprintf(stderr,"ERROR:PCM file must be 0.1-4.0s at 24kHz signed 8-bit\n");close(fd);return 2;
        }
        n=(int)st.st_size;samples=malloc(n);
        if(!samples){close(fd);return 3;}
        int in=open(filename,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
        if(in<0){perror("PCM open");free(samples);close(fd);return 3;}
        int readTotal=0;while(readTotal<n){ssize_t got=read(in,samples+readTotal,n-readTotal);if(got<=0)break;readTotal+=(int)got;}
        close(in);if(readTotal!=n){fprintf(stderr,"ERROR:short PCM file\n");free(samples);close(fd);return 3;}
    }
    int rc=play_fifo(fd,argv[1],samples,n,(int)strength);
    free(samples);close(fd);return rc;
}