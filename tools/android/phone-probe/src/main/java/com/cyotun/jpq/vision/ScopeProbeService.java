package com.cyotun.jpq.vision;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import android.view.WindowManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Local opt-in experiment, separate from the production counter and its capture. */
public final class ScopeProbeService extends Service {
    public static final String ACTION_START="com.cyotun.jpq.tests.SCOPE_START";
    private static final String ACTION_STOP="com.cyotun.jpq.tests.SCOPE_STOP";
    private static final String CHANNEL="jpq-scope-probe";
    private static final String OVERLAY_PACKAGE="com.cyotun.jpq.scopeoverlay";
    private static volatile boolean running;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Object sessionLock=new Object();
    private final Object outputLock=new Object();
    private final AtomicBoolean released=new AtomicBoolean(false);
    private final AtomicInteger overlayDraws=new AtomicInteger();
    private final Set<Integer> baselineCounters=new HashSet<>();
    private final Set<Integer> afterCounters=new HashSet<>();
    private final Set<String> savedStages=new HashSet<>();
    private final ScopeProbeEvidence scopeEvidence=new ScopeProbeEvidence();
    private ScopeProbeWatchdog watchdog;
    private HandlerThread captureThread;
    private Handler capture;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private WindowManager windows;
    private Messenger overlayControl;
    private boolean overlayBound;
    private int overlayUid=-1;
    private volatile Rect overlayRect;
    private volatile OverlayPlacement overlayPlacement;
    private int placementSequence;
    private volatile boolean overlayAdded;
    private volatile long lastOverlayReportAt;
    private boolean overlayAttempted;
    private boolean initialized;
    private long startedAt,lastSampleAt;
    private int density,generation,sampleCount,patternCount,unknownCount,baselineCount,afterCount;
    private int pngCount,resizeCount,displayCreations,lastWidth,lastHeight;
    private File directory;
    private BufferedWriter events;
    private String runId;
    private String stopReason="not_started";
    private boolean capturedVisible=true;
    private final Messenger overlayReplies=new Messenger(new Handler(Looper.getMainLooper(),this::externalOverlayState));
    private final ServiceConnection overlayConnection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder service) {
            if(released.get()) return;
            try {
                overlayUid=getPackageManager().getApplicationInfo(OVERLAY_PACKAGE,0).uid;
                if(overlayUid==android.os.Process.myUid()) throw new IllegalStateException("overlay_requires_separate_uid");
                overlayControl=new Messenger(service); sendOverlay(1);
            } catch(Exception error) { fail("external_overlay_bind",error); }
        }
        @Override public void onServiceDisconnected(ComponentName name) { overlayControl=null; end("external_overlay_disconnected"); }
        @Override public void onNullBinding(ComponentName name) { end("external_overlay_null_binding"); }
        @Override public void onBindingDied(ComponentName name) { end("external_overlay_binding_died"); }
    };
    private final MediaProjection.Callback projectionCallback=new MediaProjection.Callback() {
        @Override public void onStop() { end("projection_stopped"); }
        @Override public void onCapturedContentResize(int width,int height) {
            event("captured_resize","width",width,"height",height,"rotation",ScopeProbeActivity.patternRotation());
            if(released.get()) return;
            synchronized(sessionLock) {
                if(display!=null && (width!=lastWidth || height!=lastHeight)) {
                    try { resize(width,height); } catch(Exception error) { fail("resize",error); }
                }
            }
            main.postDelayed(ScopeProbeService.this::positionOverlay,200);
        }
        @Override public void onCapturedContentVisibilityChanged(boolean visible) {
            capturedVisible=visible;
            event("captured_visibility","visible",visible);
            if(!visible) main.postDelayed(() -> {
                if(!capturedVisible) end("captured_content_not_visible");
            },1000);
        }
    };

    public static boolean isRunning() { return running; }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null || !intent.getBooleanExtra(ScopeProbeActivity.OPT_IN,false)) {
            if(!initialized) stopSelf(); return START_NOT_STICKY;
        }
        if(ACTION_STOP.equals(intent.getAction())) { end("notification_stop"); return START_NOT_STICKY; }
        if(initialized || !ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        initialized=true;
        try {
            int result=intent.getIntExtra("resultCode",Activity.RESULT_CANCELED);
            @SuppressWarnings("deprecation") Intent data=Build.VERSION.SDK_INT>=33
                ? intent.getParcelableExtra("resultData",Intent.class) : intent.getParcelableExtra("resultData");
            if(result!=Activity.RESULT_OK || data==null) { end("invalid_or_denied_consent"); return START_NOT_STICKY; }
            runId=String.valueOf(System.currentTimeMillis());
            File external=getExternalFilesDir(null);
            if(external==null) throw new IllegalStateException("external_files_unavailable");
            File parent=new File(external,"scope-probe");
            directory=new File(parent,runId);
            if(!directory.mkdirs() && !directory.isDirectory()) throw new IllegalStateException("output_directory");
            events=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(directory,"events.jsonl")),StandardCharsets.UTF_8));
            startedAt=SystemClock.elapsedRealtime(); watchdog=new ScopeProbeWatchdog(startedAt);
            showForegroundNotification();
            captureThread=new HandlerThread("JPQScopeProbe"); captureThread.start(); capture=new Handler(captureThread.getLooper());
            android.view.Display primary=getSystemService(DisplayManager.class).getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if(primary==null) throw new IllegalStateException("primary_display_unavailable");
            Context visual=createDisplayContext(primary);
            if(Build.VERSION.SDK_INT>=30) visual=visual.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
            windows=visual.getSystemService(WindowManager.class); density=visual.getResources().getDisplayMetrics().densityDpi;
            projection=getSystemService(MediaProjectionManager.class).getMediaProjection(result,data);
            projection.registerCallback(projectionCallback,main);
            Rect screen=screenBounds();
            synchronized(sessionLock) { createDisplay(screen.width(),screen.height()); }
            running=true;
            int seconds=Math.max(15,Math.min(90,intent.getIntExtra("timeoutSeconds",45)));
            event("session_started","timeoutSeconds",seconds,"captureSelection","user_choice_unknown",
                "displayCreations",displayCreations,"outputDirectory",directory.getAbsolutePath(),
                "notice","Frame arrival and consent do not establish capture scope.");
            main.postDelayed(() -> end("timeout"),seconds*1000L);
            main.postDelayed(this::maybeAddOverlay,1500);
            main.postDelayed(this::checkProgress,250);
        } catch(Exception error) { fail("start",error); }
        return START_NOT_STICKY;
    }

    private void checkProgress() {
        if(released.get()) return;
        String reason;
        synchronized(sessionLock) {
            reason=watchdog.stopReason(SystemClock.elapsedRealtime(),ScopeProbeActivity.isFixtureForeground());
        }
        if(reason==null && overlayAdded && SystemClock.elapsedRealtime()-lastOverlayReportAt>=1500)
            reason="external_overlay_heartbeat_lost";
        if(reason!=null) end(reason);
        else { sendOverlay(3); if(!released.get()) main.postDelayed(this::checkProgress,250); }
    }

    private void showForegroundNotification() {
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"本机共享范围实验",NotificationManager.IMPORTANCE_LOW));
        Intent stop=new Intent(this,ScopeProbeService.class).setAction(ACTION_STOP).putExtra(ScopeProbeActivity.OPT_IN,true);
        PendingIntent pending=PendingIntent.getService(this,8022,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("记牌器手机测试：共享范围实验").setContentText("仅验证专用测试图，将自动停止")
            .setOngoing(true).addAction(new Notification.Action.Builder(null,"停止",pending).build()).build();
        if(Build.VERSION.SDK_INT>=29) startForeground(8022,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(8022,notification);
    }

    private void createDisplay(int width,int height) {
        validateSize(width,height);
        if(display!=null || displayCreations!=0) throw new IllegalStateException("one_display_per_session");
        reader=newReader(width,height);
        display=projection.createVirtualDisplay("JPQScopeProbe",width,height,density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,capture);
        displayCreations++; lastWidth=width; lastHeight=height;
        event("display_created","width",width,"height",height,"density",density,"generation",generation);
    }
    private ImageReader newReader(int width,int height) {
        int readerGeneration=++generation;
        ImageReader next=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3);
        next.setOnImageAvailableListener(r -> sample(r,readerGeneration),capture);
        return next;
    }
    private void resize(int width,int height) {
        validateSize(width,height);
        scopeEvidence.invalidateOverlay(); overlayPlacement=null;
        ImageReader previous=reader;
        ImageReader next=newReader(width,height);
        try {
            display.setSurface(null);
            display.resize(width,height,density);
            display.setSurface(next.getSurface()); reader=next;
        } catch(Exception error) {
            next.setOnImageAvailableListener(null,null); next.close(); throw error;
        }
        lastWidth=width; lastHeight=height; resizeCount++;
        previous.setOnImageAvailableListener(null,null); previous.close();
        event("display_resized","width",width,"height",height,"density",density,"generation",generation,
            "displayCreations",displayCreations,"physicalPatternRect",rect(ScopeProbeActivity.patternRect()),
            "rotation",ScopeProbeActivity.patternRotation());
    }
    private static void validateSize(int width,int height) {
        if(width<=0 || height<=0 || width>8192 || height>8192 || (long)width*height>20000000L)
            throw new IllegalArgumentException("capture_dimensions");
    }

    private void maybeAddOverlay() {
        if(released.get() || overlayAttempted) return;
        synchronized(sessionLock) {
            if(!scopeEvidence.hasBaseline()) { main.postDelayed(this::maybeAddOverlay,300); return; }
        }
        overlayAttempted=true;
        try {
            Intent companion=new Intent().setComponent(new ComponentName(OVERLAY_PACKAGE,OVERLAY_PACKAGE+".ScopeOverlayService"))
                .putExtra("scopeProbe",true).putExtra("runId",runId);
            overlayBound=bindService(companion,overlayConnection,BIND_AUTO_CREATE);
            event("external_overlay_requested","ownerPackage",OVERLAY_PACKAGE,"bound",overlayBound);
            if(!overlayBound) end("external_overlay_not_installed_or_unavailable");
        } catch(Exception error) { fail("external_overlay",error); }
    }
    private void sendOverlay(int command) {
        if(overlayControl==null) return;
        Message message=Message.obtain(null,command); Bundle data=new Bundle(); data.putString("runId",runId);
        message.setData(data); message.replyTo=overlayReplies;
        try { overlayControl.send(message); }
        catch(android.os.RemoteException error) { if(!released.get()) fail("external_overlay_ipc",error); }
    }
    private void positionOverlay() {
        if(released.get() || !overlayAdded) return;
        synchronized(sessionLock) { scopeEvidence.invalidateOverlay(); overlayPlacement=null; }
        sendOverlay(2);
    }
    private boolean externalOverlayState(Message message) {
        if(released.get() || message.what!=10 || message.sendingUid!=overlayUid) return true;
        Bundle data=message.getData();
        if(!runId.equals(data.getString("runId")) || !OVERLAY_PACKAGE.equals(data.getString("ownerPackage")) ||
            data.getInt("ownerUid",-1)!=overlayUid || overlayUid==android.os.Process.myUid()) return true;
        String error=data.getString("error");
        if(error!=null) { event("overlay_not_added","reason",error,"ownerPackage",OVERLAY_PACKAGE); end("external_overlay_error"); return true; }
        int[] bounds=data.getIntArray("bounds");
        if(!data.getBoolean("visible",false) || data.getInt("displayId",-1)!=android.view.Display.DEFAULT_DISPLAY ||
            bounds==null || bounds.length!=4) {
            synchronized(sessionLock) { scopeEvidence.invalidateOverlay(); overlayPlacement=null; }
            end("external_overlay_not_visible_on_primary"); return true;
        }
        Rect measured=new Rect(bounds[0],bounds[1],bounds[2],bounds[3]);
        if(measured.isEmpty()) return true;
        lastOverlayReportAt=SystemClock.elapsedRealtime();
        overlayAdded=true; overlayRect=measured; overlayDraws.set(data.getInt("drawCount",0));
        Rect pattern=ScopeProbeActivity.patternRect();
        if(pattern==null || pattern.isEmpty() || !pattern.contains(overlayRect)) {
            synchronized(sessionLock) { scopeEvidence.invalidateOverlay(); overlayPlacement=null; }
            return true;
        }
        OverlayPlacement previous=overlayPlacement;
        int rotation=ScopeProbeActivity.patternRotation();
        if(previous==null || !previous.covered.equals(overlayRect) || !previous.pattern.equals(pattern) || previous.rotation!=rotation) {
            synchronized(sessionLock) { scopeEvidence.invalidateOverlay(); }
            overlayPlacement=new OverlayPlacement(overlayRect,pattern,rotation,++placementSequence,SystemClock.elapsedRealtime());
        }
        event("overlay_geometry","screenRect",rect(overlayRect),"physicalPatternRect",rect(ScopeProbeActivity.patternRect()),
            "ownerPackage",OVERLAY_PACKAGE,"ownerUid",overlayUid,"fixtureUid",android.os.Process.myUid(),
            "isShown",true,"drawCount",overlayDraws.get(),"rotation",ScopeProbeActivity.patternRotation());
        return true;
    }
    @Override public void onConfigurationChanged(Configuration next) {
        super.onConfigurationChanged(next); main.postDelayed(this::positionOverlay,200);
    }
    @SuppressWarnings("deprecation") private Rect screenBounds() {
        if(Build.VERSION.SDK_INT>=30) return windows.getMaximumWindowMetrics().getBounds();
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();
        windows.getDefaultDisplay().getRealMetrics(metrics); return new Rect(0,0,metrics.widthPixels,metrics.heightPixels);
    }

    private void sample(ImageReader source,int readerGeneration) {
        synchronized(sessionLock) {
            Image image=null;
            try {
                if(released.get() || readerGeneration!=generation) return;
                image=source.acquireLatestImage(); if(image==null) return;
                long now=SystemClock.elapsedRealtime();
                if(now-lastSampleAt<300) return;
                lastSampleAt=now; sampleCount++;
                FramePixels pixels=new FramePixels(image);
                Stats stats=analyze(pixels);
                OverlayPlacement placement=overlayPlacement;
                Rect currentPattern=ScopeProbeActivity.patternRect();
                boolean after=overlayAdded && placement!=null && now-placement.since>=600 &&
                    now-lastOverlayReportAt<=500 &&
                    placement.pattern.equals(currentPattern) && placement.rotation==ScopeProbeActivity.patternRotation();
                boolean ownForeground=ScopeProbeActivity.isFixtureForeground();
                stats.fixture=stats.fixture && ownForeground;
                JSONObject sample=json("sample",sampleCount,"width",image.getWidth(),"height",image.getHeight(),
                    "imageTimestampNs",image.getTimestamp(),"fixtureMatched",stats.fixture,"decodedCounter",stats.counter,
                    "ownFixtureForeground",ownForeground,
                    "greenRatio",stats.green,"blueRatio",stats.blue,"orangeRatio",stats.orange,"magentaRatio",stats.magenta,
                    "blueBounds",rect(stats.blueRect),"orangeBounds",rect(stats.orangeRect),
                    "contentRect",stats.content==null ? JSONObject.NULL : new JSONArray(stats.content),
                    "overlayAdded",overlayAdded,"overlaySettled",after,"overlayDraws",overlayDraws.get(),
                    "overlayScreenRect",rect(overlayRect),"physicalPatternRect",rect(ScopeProbeActivity.patternRect()),
                    "rotation",ScopeProbeActivity.patternRotation(),"generation",generation);
                if(stats.fixture) {
                    watchdog.knownPattern(now,stats.counter,image.getTimestamp()); patternCount++;
                    if(!overlayAdded && scopeEvidence.baseline(stats.counter,image.getTimestamp())) {
                        baselineCount++; baselineCounters.add(stats.counter);
                    }
                    if(after && overlayDraws.get()>0) {
                        afterCount++; afterCounters.add(stats.counter);
                        double[] area=overlayArea(stats,pixels,placement);
                        if(area!=null) {
                            put(sample,"overlayRegionGreenRatio",area[0]); put(sample,"overlayRegionMagentaRatio",area[1]);
                            String geometry=readerGeneration+":"+image.getWidth()+"x"+image.getHeight()+":"+placement.sequence;
                            scopeEvidence.overlay(stats.counter,image.getTimestamp(),geometry,
                                area[1]>=.60 && stats.magenta>=.003,area[0]>=.90 && stats.magenta<=.0001);
                            put(sample,"evidenceGeometry",geometry);
                        }
                    }
                    String stage=(!overlayAdded ? "baseline" : after ? "overlay" : "transition")+
                        "-"+image.getWidth()+"x"+image.getHeight()+"-"+(after ? Math.min(afterCount,3) : Math.min(baselineCount,2));
                    if(pngCount<12 && !savedStages.contains(stage) && (!overlayAdded || after)) {
                        String name=String.format(java.util.Locale.ROOT,"frame-%03d-%s.png",++pngCount,stage);
                        saveKnownFixture(pixels,new File(directory,name)); savedStages.add(stage); put(sample,"png",name);
                    }
                } else {
                    unknownCount++; put(sample,"png",JSONObject.NULL);
                    // Unknown applications and denied/blank output never reach a PNG file.
                    // The main-thread watchdog also handles a complete lack of image callbacks.
                }
                event("frame",sample);
            } catch(Exception error) {
                if(!released.get() && readerGeneration==generation) {
                    event("frame_error","class",error.getClass().getSimpleName());
                    main.post(() -> end("frame_error"));
                }
            } finally { if(image!=null) image.close(); }
        }
    }

    private static final class OverlayPlacement {
        final Rect covered,pattern;
        final int rotation,sequence;
        final long since;
        OverlayPlacement(Rect covered,Rect pattern,int rotation,int sequence,long since) {
            this.covered=new Rect(covered); this.pattern=new Rect(pattern);
            this.rotation=rotation; this.sequence=sequence; this.since=since;
        }
    }

    private static final class FramePixels {
        final int width,height,pixelStride,rowStride,offset;
        final ByteBuffer buffer;
        FramePixels(Image image) {
            width=image.getWidth(); height=image.getHeight(); Image.Plane plane=image.getPlanes()[0];
            pixelStride=plane.getPixelStride(); rowStride=plane.getRowStride(); buffer=plane.getBuffer().duplicate(); offset=buffer.position();
            if(pixelStride<4) throw new IllegalStateException("unsupported_pixel_stride");
        }
        int at(int x,int y) {
            if(x<0 || y<0 || x>=width || y>=height) return 0;
            int i=offset+y*rowStride+x*pixelStride;
            if(i<offset || i+3>=buffer.limit()) return 0;
            return ((buffer.get(i+3)&255)<<24)|((buffer.get(i)&255)<<16)|((buffer.get(i+1)&255)<<8)|(buffer.get(i+2)&255);
        }
    }
    private static final class Stats {
        double green,blue,orange,magenta;
        Rect blueRect,orangeRect;
        double[] content;
        boolean fixture;
        int counter=-1;
    }
    private static boolean green(int p) { return (p>>16&255)<=30 && (p>>8&255)>=235 && (p&255)<=30; }
    private static boolean blue(int p) { return (p>>16&255)<=30 && (p>>8&255)<=60 && (p&255)>=230; }
    private static boolean orange(int p) { return (p>>16&255)>=230 && (p>>8&255)>=110 && (p>>8&255)<=190 && (p&255)<=40; }
    private static boolean magenta(int p) { return ScopeProbeColors.magenta(p); }
    private static void include(Rect rect,int x,int y,int step) {
        if(rect.isEmpty()) rect.set(x,y,x+step,y+step); else rect.union(x,y,x+step,y+step);
    }
    private static Stats analyze(FramePixels pixels) {
        Stats stats=new Stats(); int step=Math.max(1,Math.min(pixels.width,pixels.height)/160);
        int total=0,g=0,b=0,o=0,m=0; Rect br=new Rect(),or=new Rect();
        for(int y=step/2;y<pixels.height;y+=step) for(int x=step/2;x<pixels.width;x+=step) {
            int p=pixels.at(x,y); total++;
            if(green(p)) g++;
            if(blue(p)) { b++; include(br,x,y,step); }
            if(orange(p)) { o++; include(or,x,y,step); }
            if(magenta(p)) m++;
        }
        stats.green=g/(double)total; stats.blue=b/(double)total; stats.orange=o/(double)total; stats.magenta=m/(double)total;
        stats.blueRect=br; stats.orangeRect=or;
        if(stats.green<.55 || stats.blue<.003 || stats.orange<.003 || br.isEmpty() || or.isEmpty()) return stats;
        double dx=or.exactCenterX()-br.exactCenterX(),dy=or.exactCenterY()-br.exactCenterY();
        if(dx<=0 || dy<=0) return stats;
        double cw=dx/.76,ch=dy/.30;
        if(!near(br.width(),cw*.06,.25) || !near(or.width(),cw*.06,.25) ||
            !near(br.height(),ch*.14,.22) || !near(or.height(),ch*.14,.22)) return stats;
        double left=br.exactCenterX()-cw*.09,top=br.exactCenterY()-ch*.25;
        if(left<-step*3 || top<-step*3 || left+cw>pixels.width+step*3 || top+ch>pixels.height+step*3 ||
            cw*ch<pixels.width*(double)pixels.height*.60) return stats;
        stats.content=new double[]{left,top,left+cw,top+ch};
        double[][] points={{.2,.16},{.7,.16},{.2,.5},{.7,.5},{.5,.58}};
        int clean=0;
        for(double[] point:points) if(green(pixels.at((int)(left+cw*point[0]),(int)(top+ch*point[1])))) clean++;
        if(clean<4) return stats;
        int counter=0;
        for(int bit=0;bit<16;bit++) {
            int p=pixels.at((int)(left+cw*(.125+bit*.05)),(int)(top+ch*.69));
            int r=p>>16&255,gg=p>>8&255,bb=p&255;
            if(r>=230 && gg>=230 && bb<=40) counter|=1<<bit;
            else if(r>35 || gg>35 || bb>35) return stats;
        }
        if(counter==0) return stats;
        stats.counter=counter; stats.fixture=true; return stats;
    }
    private static boolean near(double actual,double expected,double tolerance) {
        return expected>2 && Math.abs(actual-expected)<=Math.max(3,expected*tolerance);
    }
    private double[] overlayArea(Stats stats,FramePixels pixels,OverlayPlacement placement) {
        Rect screen=placement.pattern,covered=placement.covered;
        if(stats.content==null) return null;
        double[] c=stats.content; double sx=screen.width()/(c[2]-c[0]),sy=screen.height()/(c[3]-c[1]);
        double left=c[0]+(covered.left-screen.left)/sx,top=c[1]+(covered.top-screen.top)/sy;
        double right=c[0]+(covered.right-screen.left)/sx,bottom=c[1]+(covered.bottom-screen.top)/sy;
        if(left<0 || top<0 || right>pixels.width || bottom>pixels.height || right-left<10 || bottom-top<10) return null;
        int count=0,greenCount=0,magentaCount=0;
        for(int yy=1;yy<10;yy++) for(int xx=1;xx<10;xx++) {
            int p=pixels.at((int)(left+(right-left)*xx/10),(int)(top+(bottom-top)*yy/10)); count++;
            if(green(p)) greenCount++; if(magenta(p)) magentaCount++;
        }
        return new double[]{greenCount/(double)count,magentaCount/(double)count};
    }
    private void saveKnownFixture(FramePixels pixels,File file) throws java.io.IOException {
        Bitmap bitmap=Bitmap.createBitmap(pixels.width,pixels.height,Bitmap.Config.ARGB_8888);
        try {
            int[] row=new int[pixels.width];
            for(int y=0;y<pixels.height;y++) {
                for(int x=0;x<pixels.width;x++) row[x]=pixels.at(x,y);
                bitmap.setPixels(row,0,pixels.width,0,y,pixels.width,1);
            }
            try(FileOutputStream out=new FileOutputStream(file)) {
                if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out)) throw new java.io.IOException("png_compress");
            }
        } finally { bitmap.recycle(); }
    }

    private void fail(String stage,Exception error) { event("error","stage",stage,"class",error.getClass().getSimpleName()); end(stage+"_error"); }
    private void end(String reason) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post(() -> end(reason)); return; }
        if(!released.compareAndSet(false,true)) return;
        running=false; stopReason=reason; main.removeCallbacksAndMessages(null);
        synchronized(sessionLock) {
            event("session_stopping","reason",reason);
            if(display!=null) { try { display.setSurface(null); display.release(); } catch(Exception ignored) {} display=null; }
            if(reader!=null) { try { reader.setOnImageAvailableListener(null,null); reader.close(); } catch(Exception ignored) {} reader=null; }
            if(projection!=null) {
                try { projection.unregisterCallback(projectionCallback); } catch(Exception ignored) {}
                try { projection.stop(); } catch(Exception ignored) {} projection=null;
            }
            sendOverlay(4);
            if(overlayBound) { try { unbindService(overlayConnection); } catch(Exception ignored) {} overlayBound=false; }
            overlayControl=null; overlayAdded=false; overlayPlacement=null;
            writeSummary();
        }
        if(captureThread!=null) { captureThread.quitSafely(); captureThread=null; }
        synchronized(outputLock) { if(events!=null) { try { events.close(); } catch(Exception ignored) {} events=null; } }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    private void writeSummary() {
        if(directory==null) return;
        String classification=scopeEvidence.classificationForStop(stopReason);
        JSONObject summary=json("runId",runId,"stopReason",stopReason,"elapsedMs",SystemClock.elapsedRealtime()-startedAt,
            "classification",classification,"captureSelection","user_choice_unknown","displayCreations",displayCreations,
            "lastGeometryPixelEvidence",scopeEvidence.classification(),
            "fixturePackage",getPackageName(),"fixtureUid",android.os.Process.myUid(),"overlayPackage",OVERLAY_PACKAGE,"overlayUid",overlayUid,
            "resizeEvents",resizeCount,"samples",sampleCount,"fixtureFrames",patternCount,"unknownFrames",unknownCount,
            "baselineFrames",baselineCount,"baselineDistinctCounters",baselineCounters.size(),"afterOverlayFrames",afterCount,
            "afterDistinctCounters",afterCounters.size(),"overlayPixelsPresentFrames",scopeEvidence.presentFrames(),
            "underlyingGreenFrames",scopeEvidence.absentFrames(),"presentDistinctCounters",scopeEvidence.presentDistinctCounters(),
            "absentDistinctCounters",scopeEvidence.absentDistinctCounters(),"evidenceGeometry",scopeEvidence.geometry(),
            "overlayDraws",overlayDraws.get(),"overlayScreenRect",rect(overlayRect),"pngCount",pngCount,
            "requiresIndependentPhysicalOverlayScreenshot",true,
            "notice","NOT_OBSERVED is pixel evidence only; independently verify magenta is visible on the physical screen. No game was opened or selected by this probe.");
        try(FileOutputStream out=new FileOutputStream(new File(directory,"summary.json"))) {
            out.write(summary.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch(Exception error) { event("summary_error","class",error.getClass().getSimpleName()); }
    }
    @Override public void onDestroy() { end("service_destroyed"); super.onDestroy(); }
    private void event(String type,Object... values) {
        JSONObject data=values.length==1 && values[0] instanceof JSONObject ? (JSONObject)values[0] : json(values);
        put(data,"type",type); put(data,"elapsedMs",SystemClock.elapsedRealtime()); put(data,"runId",runId);
        android.util.Log.i("JPQScopeProbe",data.toString());
        synchronized(outputLock) {
            if(events!=null) try { events.write(data.toString()); events.newLine(); events.flush(); } catch(Exception ignored) {}
        }
    }
    private static JSONObject json(Object... values) {
        JSONObject result=new JSONObject();
        for(int i=0;i+1<values.length;i+=2) put(result,String.valueOf(values[i]),values[i+1]);
        return result;
    }
    private static void put(JSONObject object,String key,Object value) {
        try { object.put(key,value==null ? JSONObject.NULL : value); } catch(JSONException error) { throw new IllegalArgumentException(error); }
    }
    private static Object rect(Rect rect) {
        return rect==null ? JSONObject.NULL : new JSONArray(java.util.Arrays.asList(rect.left,rect.top,rect.right,rect.bottom));
    }
}
