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
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;
import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int REQUEST_FILE=91,REQUEST_RECORD=92,REQUEST_EXPORT=93,REQUEST_SHIZUKU=94;
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
    private boolean prepared, loading, running, useEnvelope=true, useNative=false;
    private int preset=0;
    private TextView message, trackLabel, statusLabel, presetLabel;
    private Button playButton, envelopeButton, nativeButton, shizukuButton;
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
    }
    private void loadPrefs(){
        android.content.SharedPreferences p=getSharedPreferences("tune",MODE_PRIVATE);
        preset=p.getInt("preset",0); if(preset<0||preset>3)preset=0;
        tune=preset==3?new HapticEngine.Tune():HapticEngine.preset(preset);
        if(preset==3){
            tune.gain=p.getInt("gain",32);tune.bass=p.getInt("bass",55);
            tune.gate=p.getInt("gate",20);tune.smoothing=p.getInt("smooth",55);
            tune.pulse=p.getInt("pulse",35);tune.frequency=p.getInt("freq",130);
            tune.speaker=p.getInt("speaker",35);
        }
        useEnvelope=p.getBoolean("envelope",true);
        useNative=p.getBoolean("native",false);
    }
    private void savePrefs(){getSharedPreferences("tune",MODE_PRIVATE).edit()
        .putInt("preset",preset).putInt("gain",tune.gain)
        .putInt("bass",tune.bass).putInt("gate",tune.gate)
        .putInt("smooth",tune.smoothing).putInt("pulse",tune.pulse)
        .putInt("freq",tune.frequency).putInt("speaker",tune.speaker)
        .putBoolean("envelope",useEnvelope).putBoolean("native",useNative).apply();}
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
        ScrollView scroller=new ScrollView(this);scroller.setBackgroundColor(BACK);scroller.setFillViewport(true);
        LinearLayout page=column();page.setPadding(d(20),d(25),d(20),d(38));scroller.addView(page);
        TextView logo=text("S / S",15,ACCENT,true);logo.setLetterSpacing(.3f);page.addView(logo);
        TextView name=text("SURFACE SPEAKER",26,TEXT,true);name.setLetterSpacing(.04f);page.addView(name);
        TextView subtitle=text(t("把节奏传递给每一个平面","Turn rhythm into surface resonance"),12,MUTED,false);
        subtitle.setPadding(0,d(2),0,d(8));page.addView(subtitle);

        spectrum=new SpectrumView(this);page.addView(spectrum,lp(-1,285));
        statusLabel=text(t("就绪 · 马达能力检测完成","Ready · Haptic capabilities checked"),12,ACCENT,false);
        statusLabel.setGravity(Gravity.CENTER);page.addView(statusLabel);

        LinearLayout playback=card(page);
        header(playback,t("本地播放","LOCAL PLAYER"),t("仅访问你选择的音频文件","Only the selected audio file is accessed"));
        trackLabel=text(t("尚未选择音乐","No track selected"),13,TEXT,false);trackLabel.setMaxLines(2);trackLabel.setPadding(0,0,0,d(10));playback.addView(trackLabel);
        LinearLayout row=row();
        Button choose=subtle(t("选择音乐","Select music"));row.addView(choose,new LinearLayout.LayoutParams(0,d(48),1));
        choose.setOnClickListener(v->openFile());
        playButton=button(t("开始播放","Play"));LinearLayout.LayoutParams c=new LinearLayout.LayoutParams(0,d(48),1);c.leftMargin=d(10);row.addView(playButton,c);
        disabled(playButton,false);playButton.setOnClickListener(v->playPause());
        playback.addView(row);
        Button stop=subtle(t("停止播放 / 立即停止马达","Stop playback / stop motor"));
        LinearLayout.LayoutParams st=lp(-1,45);st.topMargin=d(9);playback.addView(stop,st);stop.setOnClickListener(v->stop());

        LinearLayout profiles=card(page);
        header(profiles,t("触觉模板","HAPTIC PRESETS"),t("修改任何参数将自动切换到自定义模板","Changing a control switches to Custom"));
        LinearLayout a=row(),b=row();profiles.addView(a);LinearLayout.LayoutParams gap=lp(-1,-2);gap.topMargin=d(9);profiles.addView(b,gap);
        String[] pNames={t("平衡","Balanced"),t("低频增强","Deep Bass"),t("精细触感","Detail"),t("自定义","Custom")};
        for(int i=0;i<4;i++){
            final int idx=i;Button x=subtle(pNames[i]);presetButtons[i]=x;
            LinearLayout dest=i<2?a:b;
            LinearLayout.LayoutParams param=new LinearLayout.LayoutParams(0,d(44),1);
            if((i%2)==1)param.leftMargin=d(8);
            dest.addView(x,param);x.setOnClickListener(v->selectPreset(idx));
        }
        presetLabel=text("",12,MUTED,false);presetLabel.setPadding(0,d(10),0,0);profiles.addView(presetLabel);

        LinearLayout adjust=card(page);
        header(adjust,t("精细调音","FINE TUNING"),t("软件音频映射，并非直接修改驱动寄存器","Audio-to-haptic mapping; not driver register editing"));
        slider(adjust,t("振动增益","Haptic gain"),0,65,tune.gain,false, v->tune.gain=v);
        slider(adjust,t("低频权重","Bass weighting"),0,100,tune.bass,false,v->tune.bass=v);
        slider(adjust,t("触发阈值","Noise gate"),0,70,tune.gate,false,v->tune.gate=v);
        slider(adjust,t("平滑程度","Smoothing"),0,100,tune.smoothing,false,v->tune.smoothing=v);
        slider(adjust,t("振动脉冲时长","Pulse duration (ms)"),20,65,tune.pulse,false,v->tune.pulse=v);
        slider(adjust,t("马达输出频率","Motor frequency (Hz)"),
            engine.features.envelope?(int)Math.ceil(engine.features.minHz):40,
            engine.features.envelope?(int)Math.floor(engine.features.maxHz):500,
            tune.frequency,true,v->tune.frequency=v);
        slider(adjust,t("扬声器音量","Speaker volume"),0,100,tune.speaker,false,v->{tune.speaker=v;if(player!=null)player.setVolume(v/100f,v/100f);});

        LinearLayout routes=card(page);
        header(routes,t("输出引擎","OUTPUT ROUTING"),t("硬件不支持的引擎自动停用并显示灰色","Unavailable hardware routes are disabled"));
        envelopeButton=subtle("");routes.addView(envelopeButton,lp(-1,48));
        envelopeButton.setOnClickListener(v->{useEnvelope=!useEnvelope;savePrefs();updateCapabilityButtons();});
        nativeButton=subtle("");LinearLayout.LayoutParams nr=lp(-1,48);nr.topMargin=d(8);routes.addView(nativeButton,nr);
        nativeButton.setOnClickListener(v->{useNative=!useNative;savePrefs();updateCapabilityButtons();});

        LinearLayout probe=card(page);
        header(probe,t("驱动与权限","DRIVER & ACCESS"),t("仅只读诊断；不修改系统、内核或马达参数","Read-only diagnostics; no system or driver writes"));
        Button capabilities=subtle(t("刷新能力列表","Refresh capabilities"));probe.addView(capabilities,lp(-1,48));
        capabilities.setOnClickListener(v->showCapabilities());
        LinearLayout access=row();LinearLayout.LayoutParams ar=lp(-1,48);ar.topMargin=d(10);probe.addView(access,ar);
        shizukuButton=subtle("Shizuku");access.addView(shizukuButton,new LinearLayout.LayoutParams(0,d(48),1));
        shizukuButton.setOnClickListener(v->runShizukuScan());
        Button rootButton=subtle("Root / su");LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,d(48),1);rp.leftMargin=d(8);access.addView(rootButton,rp);
        rootButton.setOnClickListener(v->runRootScan());
        Button shortTest=subtle(t("低强度短振动测试","Low-strength motor test"));LinearLayout.LayoutParams tl=lp(-1,48);tl.topMargin=d(10);probe.addView(shortTest,tl);
        disabled(shortTest,engine.features.present);shortTest.setOnClickListener(v->engine.test(tune,useEnvelope));
        Button vendorTest=subtle(t("厂商 HE 短脉冲测试 (120ms)","Vendor HE test (120ms)"));LinearLayout.LayoutParams vt=lp(-1,48);vt.topMargin=d(8);probe.addView(vendorTest,vt);
        disabled(vendorTest,engine.features.vendorClass);vendorTest.setOnClickListener(v->{
            new Thread(()->{String s=MiHapticProbe.shortTest();ui.post(()->notifyStatus(s));},"vendor-test").start();
        });
        Button export=subtle(t("导出硬件报告","Export hardware report"));LinearLayout.LayoutParams et=lp(-1,48);et.topMargin=d(8);probe.addView(export,et);
        export.setOnClickListener(v->exportReport());
        message=text(t("本应用不会改变 AVB、SELinux 或系统分区。","No AVB, SELinux or system partition changes."),12,MUTED,false);
        message.setTextIsSelectable(true);message.setPadding(0,d(14),0,0);probe.addView(message);
        TextView foot=text(t("实验性质 · 强度从低开始 · 长时间连续振动请及时停止","Experimental · Start low · Stop sustained vibration if warm"),11,DISABLED,false);
        LinearLayout.LayoutParams fl=lp(-1,-2);fl.topMargin=d(20);page.addView(foot,fl);
        setContentView(scroller);
        refreshSliders();refreshPresets();
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
                if(fromUser){change.changed(val);preset=3;savePrefs();refreshPresets();}
            }
            @Override public void onStartTrackingTouch(SeekBar s){}
            @Override public void onStopTrackingTouch(SeekBar s){}
        });
    }
    private void refreshSliders(){
        int[] values={tune.gain,tune.bass,tune.gate,tune.smoothing,tune.pulse,tune.frequency,tune.speaker};
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
            ui.post(()->{report="Root\n"+result;notifyStatus(t("Root 检测结束，可以导出报告","Root probe complete; report available"));});
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
        i.putExtra(Intent.EXTRA_TITLE,"surface-speaker-v0.3-diagnostics.txt");
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
        if(result!=RESULT_OK || data==null || data.getData()==null)return;
        if(req==REQUEST_FILE){
            stop();file=data.getData();
            try{getContentResolver().takePersistableUriPermission(file,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            selectedName=file.getLastPathSegment();
            trackLabel.setText(selectedName==null?t("已选择文件","File selected"):selectedName);
            disabled(playButton,false);
            notifyStatus(t("已加载文件 · 开始播放前会请求音频可视化权限","Track selected · Visualization permission is requested on playback"));
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
            if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startPlayback();
            else {
                startPlayback();
                notifyStatus(t("可播放音乐，但音频可视化及软件振动同步已停用","Music available; spectrum and reactive haptics disabled"));
            }
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
                @Override public void onWaveFormDataCapture(Visualizer v,byte[] waveform,int samplingRate){ }
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
                        if(player!=null&&player.isPlaying()){
                            spectrum.pushSpectrum(bars,rms);
                            if(!nativeActive())engine.onAudio(rms,bass,tune,useEnvelope);
                        }
                    });
                }
            },hz,false,true);
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
        if(playButton!=null)playButton.setText(t("开始播放","Play"));
    }
    @Override protected void onStop(){stop();super.onStop();}
    @Override protected void onDestroy(){stop();Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);super.onDestroy();}
}
