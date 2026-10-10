/*
 * SurfaceSpeaker v0.6: opt-in, short-duration FF_CUSTOM compatibility probe.
 * ABI is based on the public qcom-hv-haptics.c custom_fifo_data path.
 * This is NOT an unlimited PCM streamer. No /dev/qcom_haptic access or sysfs writes.
 */
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <math.h>
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
int main(int argc,char **argv) {
    if(argc<2){fprintf(stderr,"Usage: native_fifo_arm64 probe | test <frequency 100..300> <strength 1..25>\n");return 2;}
    if(strcmp(argv[1],"probe") && strcmp(argv[1],"test")){fprintf(stderr,"ERROR:unknown command\n");return 2;}
    char path[64];int fd=open_verified(path,sizeof(path));if(fd<0)return 3;
    if(!strcmp(argv[1],"probe")) {printf("OK:verified %s FF_PERIODIC+FF_CUSTOM, 64-bit ABI candidate (not playback-verified)\n",path);close(fd);return 0;}
    if(argc!=4){fprintf(stderr,"ERROR:test requires frequency and strength\n");close(fd);return 2;}
    char *end1,*end2;long hz=strtol(argv[2],&end1,10),level=strtol(argv[3],&end2,10);
    if(*end1||*end2||hz<100||hz>300||level<1||level>25){fprintf(stderr,"ERROR:range freq 100..300 Hz, strength 1..25%%\n");close(fd);return 2;}
    /* 24 kHz * 50ms, fits in observed 2048-byte FIFO. Signed 8-bit PCM. */
    enum { SAMPLE_RATE=24000, SAMPLES=1200 };
    uint8_t samples[SAMPLES];
    for(int i=0;i<SAMPLES;i++) {
        double env=fmin(1.,fmin((double)i/180.,(SAMPLES-i-1)/180.));
        /* The PCM buffer is capped independently of the driver magnitude. */
        int value=(int)lrint(27.0*env*sin(2*M_PI*(double)hz*i/SAMPLE_RATE));
        samples[i]=(uint8_t)(int8_t)value;
    }
    struct qcom_fifo_payload payload={.idx=0,.length=SAMPLES,.play_rate_hz=SAMPLE_RATE,.data=samples};
    struct ff_effect effect;memset(&effect,0,sizeof(effect));
    effect.type=FF_PERIODIC;effect.id=-1;
    effect.replay.length=60;
    effect.u.periodic.waveform=FF_CUSTOM;
    effect.u.periodic.magnitude=(uint16_t)((32767L*level)/100L);
    /* Matches vendor ABI: custom_len is checked against sizeof(struct custom_fifo_data). */
    effect.u.periodic.custom_len=sizeof(payload);
    effect.u.periodic.custom_data=(int16_t*)&payload;
    if(ioctl(fd,EVIOCSFF,&effect)<0){fprintf(stderr,"ERROR:EVIOCSFF: %s\n",strerror(errno));close(fd);return 4;}
    struct input_event ev;memset(&ev,0,sizeof(ev));ev.type=EV_FF;ev.code=effect.id;ev.value=1;
    if(write(fd,&ev,sizeof(ev))!=(ssize_t)sizeof(ev)) {
        fprintf(stderr,"ERROR:start write: %s\n",strerror(errno));ioctl(fd,EVIOCRMFF,effect.id);close(fd);return 5;
    }
    sleep_ms(62);
    ev.value=0;
    (void)write(fd,&ev,sizeof(ev));
    sleep_ms(14);
    (void)ioctl(fd,EVIOCRMFF,effect.id);
    close(fd);
    printf("OK:sent 50ms/24kHz custom FIFO effect at %ld Hz; requested magnitude=%ld%%; physical output not measured\n",hz,level);
    return 0;
}