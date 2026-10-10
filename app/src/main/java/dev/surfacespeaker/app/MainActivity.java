package dev.surfacespeaker.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.projection.MediaProjectionManager;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.media.audiofx.HapticGenerator;
import android.media.audiofx.Visualizer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;
import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int REQUEST_FILE=91,REQUEST_RECORD=92,REQUEST_EXPORT=93,REQUEST_SHIZUKU=94,REQUEST_CAPTURE=95;
    private static final int BACK=0xff111410, PANEL=0xff1d231a, TEXT=0xffeef2e8;
    private static final int MUTED=0xffabb5a4, DISABLED=0xff64705e, ACCENT=0xffc9fb74;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final boolean zh=Locale.getDefault().getLanguage().equalsIgnoreCase("zh");
    private HapticEngine engine;
    private HapticEngine.Tune tune;
    private MediaPlayer player;
    private Visualizer visualizer;
    private HapticGenerator nativeEffect;
    private SpectrumView spectrum;
    private Uri file;
    private String report="";
    private String selectedName="";
    private boolean prepared, loading, running, useEnvelope=true, useNative=false, pendingCapture=false, rootAuthorized=false;
    private int preset=0;
    private TextView message, trackLabel, statusLabel, presetLabel, authorization, inputStatus, motorStatus, captureStatus;
    private Button captureButton;
    private boolean requestingCapture=false;
    private long lastNativeTestMs=0;
    private boolean nativePreviewBusy=false;
    // Preferred local playback engine. Native FIFO is an explicit 4-second preview, not full-track streaming.
    private boolean fifoPreferred=false;
    private Button fifoRouteButton;
    private TextView routeStatus;
    private Button continuousPreviewButton;
    private final ScrollView[] sections=new ScrollView[4];
    private final Button[] dock=new Button[4];
    private Button playButton, envelopeButton, nativeButton, shizukuButton;
    private TextView volumeLinkStatus, fifoStatus;
    private Button volumeFollowButton;
    private final Button[] presetButtons=new Button[4];
    private final java.util.ArrayList<SeekBar> tuneBars=new java.util.ArrayList<>();
    private final java.util.ArrayList<TextView> tuneLabels=new java.util.ArrayList<>();
    private Shizuku.UserServiceArgs serviceArgs;
    private ServiceConnection serviceConnection;
    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener = (request, result) -> {
        if(request==REQUEST_SHIZUKU){
            if(result==PackageManager.PERMISSION_GRANTED)runShizukuScan();
            else notifyStatus(t("Shizuku 权限被拒绝", "Shizuku permission denied"));
            updateCapabilityButtons();
        }
    };

    private String t(String cn,String en){return zh?cn:en;}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().setStatusBarColor(BACK);
        getWindow().setNavigationBarColor(BACK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        engine=new HapticEngine(this);
        loadPrefs();
        buildUi();
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        updateCapabilityButtons();
        PlaybackCaptureService.updateTune(tune);
        PlaybackCaptureService.setNativeRoute(fifoPreferred);
    }
    private void loadPrefs(){
        android.content.SharedPreferences p=getSharedPreferences("tune",MODE_PRIVATE);
        preset=p.getInt("preset",0); if(preset<0||preset>3)preset=0;
        tune=preset==3?new HapticEngine.Tune():HapticEngine.preset(preset);
        if(preset==3){
            tune.gain=p.getInt("gain",32);tune.bass=p.getInt("bass",55);
            tune.gate=p.getInt("gate",20);tune.smoothing=p.getInt("smooth",55);
            tune.pulse=Math.max(40,p.getInt("pulse",60));tune.frequency=p.getInt("freq",130);
            tune.speaker=p.getInt("speaker",35);
            tune.volumeRatio=p.getInt("volumeRatio",65);
            tune.followVolume=p.getBoolean("followVolume",true);
        }
        useEnvelope=p.getBoolean("envelope",true);
        useNative=p.getBoolean("native",false);
        fifoPreferred=p.getBoolean("prefer_fifo",RootFifoTester.deviceCandidate());
    }
    private void savePrefs(){getSharedPreferences("tune",MODE_PRIVATE).edit()
        .putInt("preset",preset).putInt("gain",tune.gain)
        .putInt("bass",tune.bass).putInt("gate",tune.gate)
        .putInt("smooth",tune.smoothing).putInt("pulse",tune.pulse)
        .putInt("freq",tune.frequency).putInt("speaker",tune.speaker)
        .putBoolean("envelope",useEnvelope).putBoolean("native",useNative)
        .putInt("volumeRatio",tune.volumeRatio).putBoolean("followVolume",tune.followVolume)
        .putBoolean("prefer_fifo",fifoPreferred).apply();}
    private int d(float size){return (int)(size*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable background(int color,int radius){GradientDrawable b=new GradientDrawable();b.setColor(color);b.setCornerRadius(d(radius));return b;}
    private TextView text(String s,int sp,int color,boolean bold){
        TextView x=new TextView(this);x.setText(s);x.setTextSize(sp);x.setTextColor(color);
        if(bold)x.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
        return x;
    }
    private Button button(String s){
        Button x=new Button(this);x.setAllCaps(false);x.setText(s);x.setTextSize(13);
        x.setTextColor(BACK);x.setBackground(background(ACCENT,14));x.setElevation(0);x.setMinimumHeight(d(44));return x;
    }
    private Button subtle(String s){Button b=button(s);b.setTextColor(TEXT);b.setBackground(background(0xff353c30,14));return b;}
    private void disabled(Button b,boolean enabled){
        b.setEnabled(enabled); b.setAlpha(enabled?1f:0.32f);
    }
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:d(w),h<0?h:d(h));}
    private LinearLayout card(LinearLayout root){
        LinearLayout c=column();c.setPadding(d(18),d(19),d(18),d(19));c.setBackground(background(PANEL,22));
        LinearLayout.LayoutParams l=lp(-1,-2);l.topMargin=d(15);root.addView(c,l);return c;
    }
    private void header(LinearLayout parent,String s,String summary){
        TextView title=text(s,17,TEXT,true);parent.addView(title);
        if(summary!=null){TextView sub=text(summary,12,MUTED,false);sub.setPadding(0,d(4),0,d(12));parent.addView(sub);}
    }
    private void buildUi(){
        LinearLayout shell=column();shell.setBackgroundColor(BACK);
        LinearLayout top=row();top.setPadding(d(20),d(15),d(20),d(8));
        TextView brand=text("S / S",18,ACCENT,true);brand.setLetterSpacing(.18f);top.addView(brand);
        TextView heading=text("  SURFACE SPEAKER",17,TEXT,true);top.addView(heading);
        TextView version=text("  v0.10",11,MUTED,false);top.addView(version);
        shell.addView(top,lp(-1,54));
        FrameLayout frame=new FrameLayout(this);shell.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        for(int i=0;i<4;i++){
            ScrollView scroller=new ScrollView(this);scroller.setFillViewport(true);
            scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
            LinearLayout page=column();page.setPadding(d(20),d(5),d(20),d(26));
            scroller.addView(page);frame.addView(scroller,new FrameLayout.LayoutParams(-1,-1));
            sections[i]=scroller;
            if(i==0){
                TextView intro=text(t("把声音变成触觉","Feel the sound"),21,TEXT,true);page.addView(intro);
                spectrum=new SpectrumView(this);page.addView(spectrum,lp(-1,270));
                statusLabel=text(t("等待音频信号","Waiting for audio"),12,ACCENT,false);
                statusLabel.setGravity(Gravity.CENTER);page.addView(statusLabel);
                LinearLayout live=card(page);
                header(live,t("实时音乐 · FIFO 优先","LIVE AUDIO · FIFO FIRST"),t("小米设备默认使用分段原生 FIFO，约 60 ms 分段；系统仍需要音频捕获授权，不读取画面。","Xiaomi defaults to Native FIFO (60ms segments). Android audio-only capture consent is still required."));
                captureButton=button(t("开始实时捕获","Start live capture"));live.addView(captureButton,lp(-1,52));
                captureButton.setOnClickListener(v->{if(PlaybackCaptureService.running)stopCapture();else if(!requestingCapture)startCapture();});
                captureStatus=text(t("未激活","Inactive"),13,MUTED,false);
                captureStatus.setPadding(0,d(10),0,d(2));live.addView(captureStatus);
                inputStatus=text(t("输入音频：无","Audio input: none"),12,MUTED,false);live.addView(inputStatus);
                motorStatus=text(t("振动指令：0","Motor commands: 0"),12,MUTED,false);live.addView(motorStatus);
                LinearLayout basic=card(page);
                header(basic,t("权限与激活","PERMISSIONS & STATUS"),t("授权≠驱动工作；每项独立检测","Authorization and actual driver output are different"));
                authorization=text("",13,MUTED,false);authorization.setLineSpacing(d(4),1f);basic.addView(authorization);
                Button refresh=subtle(t("刷新状态","Refresh status"));LinearLayout.LayoutParams rr=lp(-1,43);rr.topMargin=d(10);basic.addView(refresh,rr);refresh.setOnClickListener(v->updateCapabilityButtons());
                Button verify=button(t("测试马达 · 强度 43%","Test motor · 43% intensity"));LinearLayout.LayoutParams vr=lp(-1,46);vr.topMargin=d(11);basic.addView(verify,vr);
                disabled(verify,engine.features.present);
                verify.setOnClickListener(v->{engine.test(tune,false);motorStatus.setText(t("测试振动已发送；","Test pulse sent; ")+engine.lastError());notifyStatus(t("如果没有触感，请检查系统振动设置及省电策略","If no movement, check system haptic settings"));});
            }else if(i==1){
                TextView title=text(t("本地音乐","LOCAL AUDIO"),22,TEXT,true);page.addView(title);
                LinearLayout playback=card(page);
                header(playback,t("播放器","PLAYER"),t("默认 FIFO 需要 Root；兼容模式使用 Android 扬声器与振动","Native FIFO requires Root; compatibility mode uses Android audio and haptics"));
                trackLabel=text(t("尚未选择音乐","No track selected"),14,TEXT,false);trackLabel.setPadding(0,0,0,d(12));playback.addView(trackLabel);
                Button choose=subtle(t("选择音频文件","Choose audio file"));playback.addView(choose,lp(-1,50));choose.setOnClickListener(v->openFile());
                fifoRouteButton=subtle("");LinearLayout.LayoutParams rpRoute=lp(-1,46);rpRoute.topMargin=d(10);playback.addView(fifoRouteButton,rpRoute);
                fifoRouteButton.setOnClickListener(v->{
                    if(nativePreviewBusy || PlaybackCaptureService.running){notifyStatus(t("请先停止实时捕获或原生 FIFO","Stop live capture/native FIFO before changing routes"));return;}
                    stop();fifoPreferred=!fifoPreferred;savePrefs();PlaybackCaptureService.setNativeRoute(fifoPreferred);updatePlaybackRoute();updateDashboard();
                });
                routeStatus=text("",12,MUTED,false);routeStatus.setPadding(0,d(8),0,d(5));routeStatus.setTextIsSelectable(true);playback.addView(routeStatus);
                Button quickProbe=subtle(t("检测 FIFO / Root 是否可用","Check FIFO / Root access"));playback.addView(quickProbe,lp(-1,45));
                quickProbe.setOnClickListener(v->runFifo(false,200));
                playButton=button(t("开始播放","Play"));LinearLayout.LayoutParams pp=lp(-1,50);pp.topMargin=d(10);playback.addView(playButton,pp);
                disabled(playButton,false);playButton.setOnClickListener(v->onPlaybackPressed());
                LinearLayout fifoMusic=card(page);
                header(fifoMusic,t("连续 FIFO · 音频预览 (ROOT)","CONTINUOUS FIFO · AUDIO PREVIEW (ROOT)"),
                    t("将所选音频前 4 秒转换为 24kHz 低强度真实波形；单次上传后由内核 FIFO 自动续写。不是全曲实时播放。", 
                      "Converts the first four seconds of the selected track into a capped 24kHz waveform. Kernel FIFO refills it without repeated pulses. Not full-track real-time streaming."));
                continuousPreviewButton=subtle(t("预览 4 秒连续马达音频","Preview 4s continuous motor audio"));
                fifoMusic.addView(continuousPreviewButton,lp(-1,52));
                continuousPreviewButton.setOnClickListener(v->new AlertDialog.Builder(this)
                    .setTitle(t("连续 FIFO 音频预览","Continuous FIFO audio preview"))
                    .setMessage(t("仅实验用途：Root 通过内核 FF_CUSTOM 播放最多 4 秒波形。马达将真实运动，最大强度受限，建议将手机置于桌面并从低系统媒体音量开始。此模式不会同时播放扬声器。", 
                                  "Root sends up to 4s of band-limited PCM through the kernel FF_CUSTOM FIFO. The motor physically moves. Start at low media volume, place the phone on a stable surface. Speaker playback is stopped during this test."))
                    .setNegativeButton(t("取消","Cancel"),null)
                    .setPositiveButton(t("播放一次","Play once"),(dd,which)->runContinuousPreview()).show());
                disabled(continuousPreviewButton,RootFifoTester.deviceCandidate());
                Button stopNative=subtle(t("立即停止连续 FIFO","Stop native FIFO now"));
                LinearLayout.LayoutParams sn=lp(-1,43);sn.topMargin=d(8);fifoMusic.addView(stopNative,sn);
                stopNative.setOnClickListener(v->{RootFifoTester.cancel();notifyStatus(t("已发送 FIFO 停止请求","FIFO stop requested"));});

                Button stop=subtle(t("停止播放","Stop player"));LinearLayout.LayoutParams sp=lp(-1,45);sp.topMargin=d(8);playback.addView(stop,sp);
                stop.setOnClickListener(v->{RootFifoTester.cancel();stop();updatePlaybackRoute();notifyStatus(t("已发送停止指令","Stop requested"));});
                TextView info=text(t("部分编码格式可能不受系统播放器支持。请选择 MP3/WAV 文件测试。","Unsupported codecs may fail. Try an MP3/WAV file."),12,MUTED,false);
                LinearLayout.LayoutParams in=lp(-1,-2);in.topMargin=d(12);playback.addView(info,in);
            }else if(i==2){
                TextView title=text(t("振动调音","HAPTIC TUNING"),22,TEXT,true);page.addView(title);
                LinearLayout profiles=card(page);
                header(profiles,t("默认模板","PRESETS"),t("切换后立即应用；手动调整后保存为自定义","Presets apply immediately; changes save as Custom"));
                LinearLayout a=row(),b=row();profiles.addView(a);LinearLayout.LayoutParams gap=lp(-1,-2);gap.topMargin=d(9);profiles.addView(b,gap);
                String[] names={t("平衡","Balanced"),t("低频增强","Bass"),t("细节","Detail"),t("自定义","Custom")};
                for(int k=0;k<4;k++){
                    final int idx=k;Button x=subtle(names[k]);presetButtons[k]=x;
                    LinearLayout dest=k<2?a:b;
                    LinearLayout.LayoutParams param=new LinearLayout.LayoutParams(0,d(46),1);
                    if((k%2)==1)param.leftMargin=d(8);
                    dest.addView(x,param);x.setOnClickListener(v->selectPreset(idx));
                }
                presetLabel=text("",12,MUTED,false);presetLabel.setPadding(0,d(12),0,0);profiles.addView(presetLabel);
                LinearLayout adjust=card(page);
                header(adjust,t("细节调节","FINE TUNING"),t("松开滑块可触发一次短振动预览","Release a slider for a short haptic preview"));
                slider(adjust,t("振动增益","Haptic gain"),0,100,tune.gain,false,v->tune.gain=v);
                slider(adjust,t("低频权重","Bass weighting"),0,100,tune.bass,false,v->tune.bass=v);
                slider(adjust,t("触发阈值","Noise gate"),0,70,tune.gate,false,v->tune.gate=v);
                slider(adjust,t("平滑","Smoothing"),0,100,tune.smoothing,false,v->tune.smoothing=v);
                slider(adjust,t("振动包络刷新窗口 ms","Envelope update window ms"),40,80,tune.pulse,false,v->tune.pulse=v);
                slider(adjust,t("马达频率 Hz","Motor frequency Hz"),
                   engine.features.envelope?(int)Math.ceil(engine.features.minHz):40,
                   engine.features.envelope?(int)Math.floor(engine.features.maxHz):500,
                   tune.frequency,true,v->tune.frequency=v);
                slider(adjust,t("本地音量","Local player volume"),0,100,tune.speaker,false,v->{tune.speaker=v;if(player!=null)player.setVolume(v/100f,v/100f);});
                LinearLayout volume=card(page);
                header(volume,t("音量联动","VOLUME LINK"),t("根据系统媒体音量同比例缩放振动，静音时不输出","Scale motor with system media volume; muted = no motor output"));
                volumeFollowButton=subtle("");volume.addView(volumeFollowButton,lp(-1,46));
                volumeFollowButton.setOnClickListener(v->{tune.followVolume=!tune.followVolume;preset=3;savePrefs();PlaybackCaptureService.updateTune(tune);updateVolumeControls();});
                slider(volume,t("振动强度比例 %","Motor strength ratio %"),0,100,tune.volumeRatio,false,v->{tune.volumeRatio=v;updateVolumeControls();});
                volumeLinkStatus=text("",12,MUTED,false);volumeLinkStatus.setPadding(0,d(10),0,0);volume.addView(volumeLinkStatus);
                updateVolumeControls();
                LinearLayout routes=card(page);
                header(routes,t("输出路径","OUTPUT ROUTES"),t("不可用时禁用，不虚报支持","Disabled when hardware support is absent"));
                envelopeButton=subtle("");routes.addView(envelopeButton,lp(-1,48));
                envelopeButton.setOnClickListener(v->{useEnvelope=!useEnvelope;savePrefs();updateCapabilityButtons();});
                nativeButton=subtle("");LinearLayout.LayoutParams nr=lp(-1,48);nr.topMargin=d(8);routes.addView(nativeButton,nr);
                nativeButton.setOnClickListener(v->{useNative=!useNative;savePrefs();updateCapabilityButtons();});
            }else{
                TextView title=text(t("设备诊断","DIAGNOSTICS"),22,TEXT,true);page.addView(title);
                LinearLayout probe=card(page);
                header(probe,t("厂商驱动与授权","DRIVER & ACCESS"),t("Root/Shizuku 只读探测，不改写设备节点","Read-only Root/Shizuku probes; no device writes"));
                Button capabilities=subtle(t("硬件能力","Hardware features"));probe.addView(capabilities,lp(-1,48));capabilities.setOnClickListener(v->showCapabilities());
                LinearLayout access=row();LinearLayout.LayoutParams ar=lp(-1,48);ar.topMargin=d(10);probe.addView(access,ar);
                shizukuButton=subtle("Shizuku");access.addView(shizukuButton,new LinearLayout.LayoutParams(0,d(48),1));
                shizukuButton.setOnClickListener(v->runShizukuScan());
                Button rootButton=subtle("Root / KernelSU");LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,d(48),1);rp.leftMargin=d(8);access.addView(rootButton,rp);
                rootButton.setOnClickListener(v->runRootScan());
                Button test=subtle(t("再次测试原生振动","Test native vibration"));LinearLayout.LayoutParams tl=lp(-1,48);tl.topMargin=d(10);probe.addView(test,tl);
                disabled(test,engine.features.present);test.setOnClickListener(v->{engine.test(tune,false);notifyStatus(t("已请求原生振动；","Native vibration requested; ")+engine.lastError());});
                Button vendor=subtle(t("小米 HE 短振动试验","Xiaomi HE short test"));LinearLayout.LayoutParams vt=lp(-1,48);vt.topMargin=d(8);probe.addView(vendor,vt);
                disabled(vendor,engine.features.vendorClass);vendor.setOnClickListener(v->{new Thread(()->{String out=MiHapticProbe.shortTest();ui.post(()->notifyStatus(out));},"vendor-test").start();});
                LinearLayout experimental=card(page);
                header(experimental,t("Qualcomm FIFO · 实验引擎","Qualcomm FIFO · EXPERIMENTAL"),
                    t("仅 Xiaomi 14 Pro。已加入 2 秒单段连续正弦波及 4 秒音频预览，仍不支持全曲无缝流式播放。需 Root。",
                      "Xiaomi 14 Pro only. Single-effect 2s sine and 4s audio preview; NOT gapless whole-track streaming. Requires Root."));
                fifoStatus=text(RootFifoTester.deviceCandidate()?t("状态：需要检测","Status: run probe"):
                    t("此机型不支持该实验模块","Not available for this model"),12,MUTED,false);
                fifoStatus.setTextIsSelectable(true);experimental.addView(fifoStatus);
                Button fifoProbe=subtle(t("只读检查 FF_CUSTOM 接口","Read-only FF_CUSTOM probe"));
                LinearLayout.LayoutParams fp=lp(-1,46);fp.topMargin=d(8);experimental.addView(fifoProbe,fp);
                fifoProbe.setEnabled(RootFifoTester.deviceCandidate());fifoProbe.setAlpha(fifoProbe.isEnabled()?1f:.3f);
                fifoProbe.setOnClickListener(v->runFifo(false,200));
                LinearLayout tones=row();LinearLayout.LayoutParams tp=lp(-1,47);tp.topMargin=d(8);experimental.addView(tones,tp);
                for(int hz:new int[]{120,200,300}){
                    Button tone=subtle(hz+" Hz");LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,d(46),1);
                    if(hz!=120)bp.leftMargin=d(8);tones.addView(tone,bp);
                    tone.setEnabled(RootFifoTester.deviceCandidate());tone.setAlpha(tone.isEnabled()?1f:.3f);
                    tone.setOnClickListener(v->new AlertDialog.Builder(this)
                        .setTitle(t("短时实验波形","Experimental waveform"))
                        .setMessage(t("Root 将通过 EVIOCSFF 写入一段 50ms、24kHz 的低幅度波形。会使马达运动。未在本机验证真实频响。测试前请将手机置于桌面并避免连续重复试验。",
                          "Root will submit a 50ms, 24kHz low-amplitude effect using EVIOCSFF. The motor may move. Response is not yet measured. Place the phone on a table and avoid rapid repeated tests."))
                        .setNegativeButton(t("取消","Cancel"),null)
                        .setPositiveButton(t("执行一次","Test once"),(dd,which)->runFifo(true,hz)).show());
                }
                Button longTone=subtle(t("2 秒连续 200 Hz 正弦波 (Root)","2s continuous 200 Hz tone (Root)"));
                LinearLayout.LayoutParams lg=lp(-1,48);lg.topMargin=d(8);experimental.addView(longTone,lg);
                disabled(longTone,RootFifoTester.deviceCandidate());
                longTone.setOnClickListener(v->new AlertDialog.Builder(this)
                    .setTitle(t("连续波形硬件测试","Continuous waveform test"))
                    .setMessage(t("单次上传 2 秒正弦波，由内核 FIFO 中断持续填充。仅在 Root 可用时工作；低强度、限时、不会循环。", 
                                  "One 2-second sine-wave effect. Kernel IRQ refills FIFO; capped magnitude and automatic stop. Requires Root."))
                    .setNegativeButton(t("取消","Cancel"),null)
                    .setPositiveButton(t("确认测试","Confirm"),(dialog,which)->runLongTone()).show());
                Button export=subtle(t("导出驱动报告","Export diagnostics"));LinearLayout.LayoutParams et=lp(-1,48);et.topMargin=d(8);probe.addView(export,et);export.setOnClickListener(v->exportReport());
                message=text(t("本应用不会更改 AVB、SELinux 或系统分区。","No changes to AVB, SELinux or system partitions."),12,MUTED,false);
                message.setTextIsSelectable(true);message.setPadding(0,d(14),0,0);probe.addView(message);
            }
        }
        LinearLayout nav=row();nav.setPadding(d(8),d(10),d(8),d(14));nav.setBackgroundColor(0xff20271b);
        String[] titles={t("首页","Home"),t("音乐","Music"),t("调节","Tune"),t("设备","Device")};
        for(int i=0;i<4;i++){
            final int tab=i;Button b=subtle(titles[i]);dock[i]=b;
            LinearLayout.LayoutParams par=new LinearLayout.LayoutParams(0,d(52),1);
            if(i>0)par.leftMargin=d(5);nav.addView(b,par);b.setOnClickListener(v->showTab(tab));
        }
        shell.addView(nav,lp(-1,76));
        // API 36 enforces edge-to-edge: keep both the header and dock clear of system bars/cutout.
        shell.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            shell.setPadding(0,safe.top,0,safe.bottom);
            return insets;
        });
        setContentView(shell);
        shell.requestApplyInsets();
        showTab(0);refreshSliders();refreshPresets();updateCapabilityButtons();updatePlaybackRoute();
    }
    private void updatePlaybackRoute(){
        if(fifoRouteButton!=null){
            fifoRouteButton.setText(fifoPreferred?t("输出模式：● 原生 FIFO（默认）· 点击切换","Output: ● Native FIFO (default) · Tap to switch"):
               t("输出模式：● Android 兼容振动 · 点击切换","Output: ● Android compatibility · Tap to switch"));
            fifoRouteButton.setBackground(background(fifoPreferred?0xff357c62:0xff353c30,14));
            fifoRouteButton.setTextColor(fifoPreferred?Color.WHITE:TEXT);
        }
        if(routeStatus!=null && !nativePreviewBusy){
            routeStatus.setText(fifoPreferred?
                t("本地文件仍为前 4 秒 FIFO 预览；系统实时捕获使用分段 FIFO（约 60ms），可能有拼接间隙。", "Local FIFO preview remains 4s. Live capture sends buffered 60ms FF_CUSTOM segments, with possible gaps."):
                t("兼容模式使用 Android Vibrator；不等于连续 PCM 波形。","Compatibility uses Android Vibrator, not continuous PCM."));
        }
        if(playButton!=null && !nativePreviewBusy && (player==null || !player.isPlaying())){
            playButton.setText(fifoPreferred?t("▶ 播放 FIFO · 前 4 秒","▶ Play FIFO · first 4s"):t("▶ 扬声器 / 兼容振动","▶ Speaker / compat haptics"));
        }
        if(playButton!=null)disabled(playButton,file!=null&&!nativePreviewBusy);
    }
    private void onPlaybackPressed(){
        if(fifoPreferred)runContinuousPreview();
        else playPause();
    }
    private void updateVolumeControls(){
        if(volumeFollowButton!=null){
            volumeFollowButton.setText(t("跟随系统媒体音量：","Follow system media volume: ")+
                (tune.followVolume?t("已开启","On"):t("已关闭","Off")));
            volumeFollowButton.setBackground(background(tune.followVolume?0xff357c62:0xff353c30,14));
        }
        if(volumeLinkStatus!=null && engine!=null){
            int media=Math.round(100*engine.mediaVolumeFraction());
            int linked=Math.round(100*engine.effectiveOutputScale(tune));
            volumeLinkStatus.setText(t("系统媒体音量：","System media volume: ")+media+
                "%  ·  "+t("相对振动比例：","Relative motor scale: ")+linked+"%"+
                "\n"+t("这是驱动请求比例，不是实际声压或机械振幅。","Requested output scale, not measured acoustic loudness or motor displacement."));
        }
    }
    private void runFifo(boolean test,int hz){
        if(test && (PlaybackCaptureService.running || (player!=null && player.isPlaying()))){
            notifyStatus(t("请先停止音频播放再进行短波形测试","Stop playback/capture before short waveform test"));return;
        }
        if(test){
            long now=android.os.SystemClock.uptimeMillis();
            if(now-lastNativeTestMs<2000){notifyStatus(t("测试冷却中，请勿频繁重试","Cooldown active; avoid rapid testing"));return;}
            lastNativeTestMs=now;
        }
        int strength=nativeStrength();
        if(test && strength<1){notifyStatus(t("当前音量联动为静音，未驱动马达","Media volume is muted; no native effect sent"));return;}
        if(fifoStatus!=null)fifoStatus.setText(t("正在调用 Root 原生工具…","Running native tool via Root…"));
        if(routeStatus!=null)routeStatus.setText(t("正在检测设备权限和 EV_FF…","Checking Root and EV_FF permissions…"));
        new Thread(()->{
            String result=RootFifoTester.run(getApplicationContext(),test,hz,Math.max(1,strength));
            ui.post(()->{
                if(fifoStatus!=null)fifoStatus.setText(result);
                if(routeStatus!=null)routeStatus.setText(result);
                notifyStatus(result.length()>110?result.substring(0,110):result);
            });
        },"fifo-experimental").start();
    }
    private int nativeStrength(){
        float level=engine.effectiveOutputScale(tune);
        // Conservative ceiling with perceptual attenuation; 48% maximum is not measured displacement.
        return level<=0f?0:Math.max(1,Math.min(48,Math.round(48f*(float)Math.sqrt(level))));
    }
    private void runLongTone(){
        if(nativePreviewBusy || PlaybackCaptureService.running){
            notifyStatus(t("先停止其他音频测试","Stop the other audio test first"));return;
        }
        int strength=nativeStrength();
        if(strength<1){notifyStatus(t("媒体音量为零，未启动","Muted: native test not started"));return;}
        nativePreviewBusy=true;
        notifyStatus(t("正在发送 2 秒连续波形…","Sending 2-second continuous FIFO tone…"));
        new Thread(()->{
            String out=RootFifoTester.longTone(getApplicationContext(),200,Math.min(48,strength));
            ui.post(()->{nativePreviewBusy=false;if(fifoStatus!=null)fifoStatus.setText(out);notifyStatus(out);});
        },"fifo-long-tone").start();
    }
    private void runContinuousPreview(){
        if(nativePreviewBusy || PlaybackCaptureService.running){
            notifyStatus(t("请先停止实时捕获/其他测试","Stop capture/other test first"));return;
        }
        if(file==null){notifyStatus(t("请先选择本地音乐","Select a local audio file first"));showTab(1);return;}
        int strength=nativeStrength();
        if(strength<1){notifyStatus(t("媒体音量为零，未启动马达","Media volume muted: no motor output"));return;}
        stop(); // Do not run regular Vibrator pulses concurrently with native FIFO.
        nativePreviewBusy=true;
        if(routeStatus!=null)routeStatus.setText(t("正在解码并启动原生 FIFO；请查看随后显示的错误或执行结果…","Decoding and starting native FIFO; waiting for actual command status…"));
        if(playButton!=null){playButton.setText(t("原生 FIFO 运行中…","Native FIFO running…"));disabled(playButton,false);}
        if(continuousPreviewButton!=null){continuousPreviewButton.setText(t("转换并播放中…","Decoding and playing…"));disabled(continuousPreviewButton,false);}
        notifyStatus(t("解码前 4 秒并一次性上传 FIFO…","Decoding first 4s for a single FIFO upload…"));
        Uri selected=file;
        new Thread(()->{
            String out=RootFifoTester.localPreview(getApplicationContext(),selected,Math.min(48,strength));
            ui.post(()->{
                nativePreviewBusy=false;
                if(continuousPreviewButton!=null){continuousPreviewButton.setText(t("预览 4 秒连续马达音频","Preview 4s continuous motor audio"));disabled(continuousPreviewButton,RootFifoTester.deviceCandidate());}
                if(fifoStatus!=null)fifoStatus.setText(out);
                updatePlaybackRoute();
                // Preserve the exact last output/error rather than replacing it with the generic mode description.
                if(routeStatus!=null)routeStatus.setText(out);
                notifyStatus(out);
            });
        },"fifo-continuous-local").start();
    }
    private void showTab(int which){
        for(int i=0;i<4;i++){
            sections[i].setVisibility(i==which?View.VISIBLE:View.GONE);
            dock[i].setBackground(background(i==which?ACCENT:0xff343d2e,14));
            dock[i].setTextColor(i==which?BACK:TEXT);
        }
    }
    private void startCapture(){
        requestingCapture=true;PlaybackCaptureService.setNativeRoute(fifoPreferred);updateDashboard();
        if(!engine.features.present){requestingCapture=false;updateDashboard();notifyStatus(t("马达不可用","Vibrator unavailable"));return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            pendingCapture=true;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQUEST_RECORD);return;
        }
        try{
            MediaProjectionManager mgr=(MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mgr.createScreenCaptureIntent(),REQUEST_CAPTURE);
            notifyStatus(t("请允许系统音频捕获","Allow audio capture in Android dialog"));
        }catch(Exception e){requestingCapture=false;updateDashboard();notifyStatus(t("音频捕获授权失败：","Capture consent failed: ")+e.getMessage());}
    }
    private void stopCapture(){startService(new Intent(this,PlaybackCaptureService.class).setAction(PlaybackCaptureService.STOP));
        requestingCapture=false;updateCapabilityButtons();spectrum.setPlaying(false);
        notifyStatus(t("实时捕获停止","Live capture stopped"));}
    private void updateDashboard(){
        boolean live=PlaybackCaptureService.running;
        if(captureButton!=null){
            captureButton.setText(live?t("● 捕获中 · 点击停止","● Capturing · Tap to stop")
                :requestingCapture?t("等待系统授权…","Waiting for permission…")
                :t("开始实时捕获","Start live capture"));
            captureButton.setBackground(background(live?0xff357c62:requestingCapture?0xff8e9967:ACCENT,14));
            captureButton.setTextColor(live?Color.WHITE:BACK);
            captureButton.setAlpha(requestingCapture && !live?0.72f:1f);
        }
        if(captureStatus!=null){
            captureStatus.setText(live?(fifoPreferred?t("● FIFO 分段实时捕获 · 约 60ms 缓冲","● Live buffered FIFO · 60ms segments"):t("● Android 兼容捕获","● Android compatibility capture"))
                :requestingCapture?t("等待音频权限与系统同意","Waiting for system consent")
                :t("实时捕获未激活","Live capture inactive"));
            captureStatus.setTextColor(live?ACCENT:MUTED);
        }
        if(authorization!=null){
            boolean sh=false;try{sh=Shizuku.pingBinder()&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}catch(Throwable ignored){}
            authorization.setText(t("马达：","Vibrator: ")+(engine.features.present?t("可用","available"):t("不可用","unavailable"))+
                "   ·   "+t("振幅：","Amplitude: ")+(engine.features.amplitude?"✓":"—")+
                "\n"+t("音频捕获权限：","Audio permission: ")+(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED?"✓":t("未授权","not granted"))+
                "\nRoot: "+(rootAuthorized?t("已验证 UID 0","verified UID 0"):t("未验证（点设备页检测）","not verified; probe in Device"))+
                "\nShizuku: "+(sh?t("已授权","granted"):t("未连接或未授权","offline / unauthorized"))+
                "\nPWLE: "+(engine.features.envelope?t("支持","available"):t("不可用","unavailable"))+
                "   ·   HapticGenerator: "+(engine.features.nativeAudio?"✓":"—"));
        }
    }
    private interface ValueChange {void changed(int value);}
    private void slider(LinearLayout parent,String title,int low,int high,int init,boolean frequency,ValueChange change){
        LinearLayout r=row();LinearLayout.LayoutParams rlp=lp(-1,-2);rlp.topMargin=d(14);parent.addView(r,rlp);
        TextView lab=text(title,13,TEXT,false);r.addView(lab,new LinearLayout.LayoutParams(0,-2,1));
        TextView amount=text(String.valueOf(init),13,ACCENT,true);r.addView(amount);
        SeekBar bar=new SeekBar(this);bar.setMax(high-low);bar.setProgress(Math.max(0,Math.min(high-low,init-low)));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        parent.addView(bar,lp(-1,38));tuneBars.add(bar);tuneLabels.add(amount);
        bar.setTag(new int[]{low,high,frequency?1:0});
        if(frequency && !engine.features.envelope){bar.setEnabled(false);bar.setAlpha(.3f);lab.setTextColor(DISABLED);amount.setTextColor(DISABLED);}
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar s,int progress,boolean fromUser){
                int val=progress+low;amount.setText(String.valueOf(val));
                if(fromUser){change.changed(val);preset=3;savePrefs();refreshPresets();PlaybackCaptureService.updateTune(tune);}
            }
            @Override public void onStartTrackingTouch(SeekBar s){}
            @Override public void onStopTrackingTouch(SeekBar s){if(s.isEnabled() && !PlaybackCaptureService.running)engine.test(tune,false);}
        });
    }
    private void refreshSliders(){
        int[] values={tune.gain,tune.bass,tune.gate,tune.smoothing,tune.pulse,tune.frequency,tune.speaker,tune.volumeRatio};
        for(int i=0;i<tuneBars.size();i++){
            SeekBar s=tuneBars.get(i);int[] bounds=(int[])s.getTag();int val=Math.max(bounds[0],Math.min(bounds[1],values[i]));
            s.setProgress(val-bounds[0]);
            tuneLabels.get(i).setText(String.valueOf(val));
        }
    }
    private void refreshPresets(){
        for(int i=0;i<4;i++){
            Button b=presetButtons[i];if(b==null)continue;
            boolean active=preset==i;b.setTextColor(active?BACK:TEXT);
            b.setBackground(background(active?ACCENT:0xff353c30,12));
        }
        if(presetLabel!=null)presetLabel.setText(preset==3?t("自定义设置已保存到本机","Custom settings saved locally"):t("预设已应用，随时可以微调","Preset applied; fine-tune any parameter"));
    }
    private void selectPreset(int index){
        preset=index;
        if(index<3)tune=HapticEngine.preset(index);
        refreshSliders();refreshPresets();savePrefs();
        if(player!=null)player.setVolume(tune.speaker/100f,tune.speaker/100f);
        PlaybackCaptureService.updateTune(tune);
    }
    private void notifyStatus(String txt){if(message!=null)message.setText(txt);if(statusLabel!=null)statusLabel.setText(txt.length()>100?txt.substring(0,100):txt);}
    private void updateCapabilityButtons(){
        if(envelopeButton!=null){
            boolean supported=engine.features.envelope;
            envelopeButton.setText(t("频率包络 PWLE","Frequency envelope PWLE") + " · " +
              (supported?(useEnvelope?t("开启","On"):t("关闭","Off")):t("设备未开放","Unavailable")));
            disabled(envelopeButton,supported);
        }
        if(nativeButton!=null){
            boolean supported=engine.features.nativeAudio;
            nativeButton.setText("HapticGenerator · " +(supported?(useNative?t("开启","On"):t("关闭","Off")):t("不可用","Unavailable")));
            disabled(nativeButton,supported);
        }
        if(shizukuButton!=null){
            boolean binder=false,permitted=false;
            try {binder=Shizuku.pingBinder();if(binder)permitted=Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}
            catch(Throwable ignored){}
            shizukuButton.setText("Shizuku · "+(permitted?t("已授权","Granted"):(binder?t("请求授权","Authorize"):t("未连接","Offline"))));
            // Allow tapping an offline entry to show a meaningful explanation.
            shizukuButton.setAlpha(binder?1:.55f);
        }
        updateDashboard();
    }
    private String capabilityReport(){
        HapticEngine.Features f=engine.features;
        return "Device: " + Build.MANUFACTURER + " / " + Build.MODEL
            + "\nAndroid API: " + Build.VERSION.SDK_INT
            + "\nVibrator present: " + f.present
            + "\nAmplitude: " + f.amplitude
            + "\nWaveform envelope (PWLE): " + f.envelope
            + "\nHapticGenerator: " + f.nativeAudio
            + (f.envelope?"\nSupported frequency: "+f.minHz+" - "+f.maxHz+" Hz\nMin control point: "+f.minSegmentMs+" ms":"\nFrequency profile: unavailable")
            + "\nVendor classes:\n"+f.vendorName
            + "\nElevated read-only probe:\n"+report;
    }
    private void showCapabilities(){
        new AlertDialog.Builder(this).setTitle(t("硬件能力","Hardware capabilities"))
            .setMessage(capabilityReport())
            .setPositiveButton(t("确定","OK"),null)
            .setNeutralButton(t("复制","Copy"),(x,w)->{
                ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText("haptic report",capabilityReport()));
            }).show();
        updateCapabilityButtons();
    }
    private void runRootScan(){
        notifyStatus(t("正在请求 Root，只读检测驱动…","Requesting root; read-only driver probe…"));
        new Thread(()->{
            String result=ProbeCommands.execute(true);
            ui.post(()->{report="Root\n"+result;rootAuthorized=result.contains("uid=0(");
                notifyStatus(rootAuthorized?t("Root 已通过 UID 0 验证","Root verified as UID 0"):t("Root 执行失败，查看设备报告：","Root probe failed; see report: ")+result);
                updateCapabilityButtons();});
        },"su-inspector").start();
    }
    private void runShizukuScan(){
        try{
            if(!Shizuku.pingBinder()){notifyStatus(t("请启动 Shizuku 服务","Please start the Shizuku service"));updateCapabilityButtons();return;}
            if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED){
                Shizuku.requestPermission(REQUEST_SHIZUKU);return;
            }
            notifyStatus(t("通过 Shizuku 读取驱动状态…","Inspecting driver via Shizuku…"));
            serviceArgs=new Shizuku.UserServiceArgs(new ComponentName(this,PrivilegedProbeService.class))
                .daemon(false).processNameSuffix("haptic_probe").version(1).debuggable(false);
            serviceConnection=new ServiceConnection(){
                @Override public void onServiceConnected(ComponentName cn,IBinder b){
                    new Thread(()->{
                        String out;
                        try{out=IProbeService.Stub.asInterface(b).readHardware();}
                        catch(Exception e){out=e.getClass().getSimpleName()+": "+e.getMessage();}
                        final String result=out;
                        ui.post(()->{report="Shizuku\n"+result;notifyStatus(t("Shizuku 检测结束，可导出报告","Shizuku probe completed; export report"));updateCapabilityButtons();});
                        try{Shizuku.unbindUserService(serviceArgs,serviceConnection,true);}catch(Exception ignored){}
                    },"shizuku-readonly").start();
                }
                @Override public void onServiceDisconnected(ComponentName cn) { }
            };
            Shizuku.bindUserService(serviceArgs,serviceConnection);
        }catch(Throwable e){notifyStatus(t("Shizuku 无法启动：","Shizuku error: ")+e.getClass().getSimpleName()+" "+e.getMessage());}
    }
    private void exportReport(){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE,"surface-speaker-v0.5-diagnostics.txt");
        startActivityForResult(i,REQUEST_EXPORT);
    }
    private void openFile(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("audio/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i,REQUEST_FILE);
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req==REQUEST_CAPTURE){
            if(result!=RESULT_OK || data==null){requestingCapture=false;updateDashboard();notifyStatus(t("用户取消实时音频授权","Live audio consent cancelled"));return;}
            stop(); // local player only; background capture remains independent
            try{
                Intent service=new Intent(this,PlaybackCaptureService.class).setAction(PlaybackCaptureService.START)
                    .putExtra(PlaybackCaptureService.CODE,result).putExtra(PlaybackCaptureService.DATA,data);
                startForegroundService(service);
                captureStatus.setText(t("正在启动实时捕获…","Starting live capture…"));
            }catch(Exception e){requestingCapture=false;updateDashboard();notifyStatus(t("服务启动失败：","Capture service failed: ")+e.getMessage());}
            return;
        }
        if(result!=RESULT_OK || data==null || data.getData()==null){
            if(req==REQUEST_FILE)notifyStatus(t("没有选择文件","No file selected"));return;
        }
        if(req==REQUEST_FILE){
            stop();file=data.getData();
            try{getContentResolver().takePersistableUriPermission(file,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            selectedName=file.getLastPathSegment();
            try(Cursor cursor=getContentResolver().query(file,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
                if(cursor!=null&&cursor.moveToFirst())selectedName=cursor.getString(0);
            }catch(Exception ignored){}
            trackLabel.setText(selectedName==null?t("已选中文件","File selected"):selectedName);
            disabled(playButton,true);
            showTab(1);
            updatePlaybackRoute();
            if(fifoPreferred){
                notifyStatus(t("FIFO 已选为默认。点击播放启动最多 4 秒连续波形。","FIFO is default. Tap Play for up to four seconds of continuous waveform."));
            }else{
                notifyStatus(t("文件已选择，正在准备播放…","Track selected; preparing playback…"));
                playPause();
            }
        }else if(req==REQUEST_EXPORT){
            try(java.io.OutputStream stream=getContentResolver().openOutputStream(data.getData())){
                if(stream!=null)stream.write(capabilityReport().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                notifyStatus(t("已导出诊断报告","Report exported"));
            }catch(Exception e){notifyStatus(t("导出失败","Export failed")+": "+e.getMessage());}
        }
    }
    private void playPause(){
        if(player!=null && prepared){
            if(player.isPlaying()){
                player.pause();engine.setEnabled(false);spectrum.setPlaying(false);
                playButton.setText(t("继续播放","Resume"));notifyStatus(t("暂停","Paused"));
            }else{
                engine.setEnabled(!nativeActive() && visualizer!=null);player.start();spectrum.setPlaying(true);
                playButton.setText(t("暂停","Pause"));notifyStatus(t("播放中","Playing"));
            }
            return;
        }
        if(file==null||loading)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQUEST_RECORD);return;
        }
        startPlayback();
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==REQUEST_RECORD){
            boolean granted=results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED;
            if(pendingCapture){pendingCapture=false;if(granted)startCapture();else {requestingCapture=false;notifyStatus(t("实时音频捕获需要录音权限","Capture needs audio permission"));}}
            else {startPlayback();if(!granted)notifyStatus(t("播放可用，但没有录音权限时不会驱动马达","Player works but haptics need audio permission"));}
            updateDashboard();
        }
    }
    private void startPlayback(){
        stop();loading=true;
        try{
            MediaPlayer mp=new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setHapticChannelsMuted(false).build());
            mp.setDataSource(this,file);mp.setVolume(tune.speaker/100f,tune.speaker/100f);
            player=mp;
            mp.setOnPreparedListener(m->{
                loading=false;prepared=true;
                if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
                    attachVisualizer(m.getAudioSessionId());
                attachNative(m.getAudioSessionId());
                engine.setEnabled(!nativeActive() && visualizer!=null);
                m.start();spectrum.setPlaying(true);
                playButton.setText(t("暂停","Pause"));
                notifyStatus(visualizer!=null
                   ?t("实时频谱 · 触觉映射已启动","Live spectrum · Haptic mapping active")
                   :t("音频播放中，但频谱采集不可用","Playing, but spectrum capture is unavailable"));
            });
            mp.setOnCompletionListener(m->{stop();notifyStatus(t("播放完毕","Finished"));});
            mp.setOnErrorListener((m,what,extra)->{stop();notifyStatus("MediaPlayer error " + what + "/"+extra);return true;});
            mp.prepareAsync();
        }catch(Exception e){stop();notifyStatus(t("音频打开失败：","Audio error: ")+e.getMessage());}
    }
    private void attachNative(int session){
        if(!useNative||!engine.features.nativeAudio)return;
        try{nativeEffect=HapticGenerator.create(session);
            if(nativeEffect!=null && nativeEffect.setEnabled(true)!=0){nativeEffect.release();nativeEffect=null;}
        }catch(Exception ignored){if(nativeEffect!=null)nativeEffect.release();nativeEffect=null;}
    }
    private boolean nativeActive(){return nativeEffect!=null;}
    private void attachVisualizer(int session){
        try{
            visualizer=new Visualizer(session);
            int[] range=Visualizer.getCaptureSizeRange();
            int cap=Math.min(range[1],1024);
            visualizer.setCaptureSize(cap);
            int hz=Math.min(Visualizer.getMaxCaptureRate(),18000);
            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener(){
                private short[] waveformBuffer=new short[0];
                @Override public void onWaveFormDataCapture(Visualizer v,byte[] waveform,int samplingRate){
                    if(waveform==null||waveform.length==0||PlaybackCaptureService.running)return;
                    if(waveformBuffer.length!=waveform.length)waveformBuffer=new short[waveform.length];
                    for(int k=0;k<waveform.length;k++) waveformBuffer[k]=(short)(((waveform[k]&0xff)-128)<<8);
                    if(!nativeActive())engine.onPcm(waveformBuffer,waveformBuffer.length,Math.max(4000,samplingRate/1000),tune);
                }
                @Override public void onFftDataCapture(Visualizer v,byte[] fft,int samplingRate){
                    if(fft==null||fft.length<64)return;
                    float[] bars=new float[72];float lowSum=0,allSum=0;
                    double nyquist=(samplingRate/1000.0)*.5;
                    int n=fft.length;
                    for(int j=0;j<bars.length;j++){
                        double freqLo=45*Math.pow(13000.0/45.0,j/72.0);
                        double freqHi=45*Math.pow(13000.0/45.0,(j+1)/72.0);
                        int lo=Math.max(1,(int)(freqLo/nyquist*(n/2)));
                        int hi=Math.min(n/2-1,Math.max(lo+1,(int)(freqHi/nyquist*(n/2))));
                        float max=0;
                        for(int k=lo;k<=hi;k++){
                            int re=fft[2*k],im=fft[2*k+1];
                            float level=(float)Math.sqrt(re*re+im*im)/181f;
                            if(level>max)max=level;
                        }
                        bars[j]=(float)Math.min(1,Math.sqrt(max)*1.3f);
                        allSum+=bars[j];if(j<30)lowSum+=bars[j];
                    }
                    float rms=Math.min(1,allSum/72f);
                    float bass=Math.min(1,lowSum/30f);
                    ui.post(()->{
                        if(player!=null&&player.isPlaying()&&!PlaybackCaptureService.running){
                            spectrum.pushSpectrum(bars,rms);
                            // Waveform callback owns the PCM-derived haptic envelope; FFT only drives UI.
                        }
                    });
                }
            },hz,true,true);
            visualizer.setEnabled(true);
        }catch(Exception e){
            if(visualizer!=null){try{visualizer.release();}catch(Exception ignored){}visualizer=null;}
            notifyStatus(t("无法连接音频频谱：","Spectrum unavailable: ")+e.getMessage());
        }
    }
    private void stop(){
        engine.setEnabled(false);MiHapticProbe.stop();
        if(visualizer!=null){try{visualizer.setEnabled(false);visualizer.release();}catch(Exception ignored){}visualizer=null;}
        if(nativeEffect!=null){try{nativeEffect.setEnabled(false);nativeEffect.release();}catch(Exception ignored){}nativeEffect=null;}
        if(player!=null){try{player.reset();player.release();}catch(Exception ignored){}player=null;}
        prepared=false;loading=false;spectrum.setPlaying(false);
        updatePlaybackRoute();
    }
    private final Runnable volumeTick=new Runnable(){
        @Override public void run(){updateVolumeControls();ui.postDelayed(this,700);}
    };
    @Override protected void onResume(){super.onResume();
        ui.removeCallbacks(volumeTick);ui.postDelayed(volumeTick,700);
        PlaybackCaptureService.listener=new PlaybackCaptureService.Listener(){
            @Override public void onCaptureFrame(AudioAnalysis.Frame frame,int pulses){
                if(spectrum!=null){spectrum.setPlaying(true);spectrum.pushSpectrum(frame.bars,frame.rms);}
                if(inputStatus!=null)inputStatus.setText(t("音频信号：","Audio signal: ")+Math.round(frame.rms*100)+"%");
                if(motorStatus!=null)motorStatus.setText((fifoPreferred?t("实际提交 FIFO 段数：","FIFO frames completed: "):t("兼容振动指令：","Compat haptic commands: "))+pulses+(fifoPreferred?"  ·  "+t("丢弃旧帧：","Dropped stale: ")+PlaybackCaptureService.nativeDropped:""));
                updateVolumeControls();
            }
            @Override public void onCaptureState(String status,boolean active){
                requestingCapture=false;notifyStatus(status);updateDashboard();
                if(!active&&spectrum!=null&&player==null)spectrum.setPlaying(false);
            }
        };
        if(engine!=null)updateCapabilityButtons();updateVolumeControls();
    }
    @Override protected void onPause(){ui.removeCallbacks(volumeTick);super.onPause();}
    @Override protected void onStop(){if(!PlaybackCaptureService.running)stop();super.onStop();}
    @Override protected void onDestroy(){
        if(PlaybackCaptureService.listener!=null)PlaybackCaptureService.listener=null;
        RootFifoTester.cancel();stop();Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);super.onDestroy();
    }
}