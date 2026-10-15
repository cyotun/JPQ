package com.cyotun.jpq.vision;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
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
    private static volatile boolean running;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Object sessionLock=new Object();
    private final Object outputLock=new Object();
    private final AtomicBoolean released=new AtomicBoolean(false);
    private final AtomicInteger overlayDraws=new AtomicInteger();
    private final Set<Integer> baselineCounters=new HashSet<>();
    private final Set<Integer> afterCounters=new HashSet<>();
    private final Set<String> savedStages=new HashSet<>();
    private HandlerThread captureThread;
    private Handler capture;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private WindowManager windows;
    private View overlay;
    private WindowManager.LayoutParams overlayParams;
    private volatile Rect overlayRect;
    private volatile boolean overlayAdded;
    private boolean overlayAttempted;
    private boolean initialized;
    private long startedAt,lastSampleAt,lastKnownPatternAt,overlayAddedAt;
    private int density,generation,sampleCount,patternCount,unknownCount,baselineCount,afterCount;
    private int presentCount,absentCount,pngCount,resizeCount,displayCreations,lastWidth,lastHeight;
    private File directory;
    private BufferedWriter events;
    private String runId;
    private String stopReason="not_started";
    private boolean capturedVisible=true;
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
            startedAt=lastKnownPatternAt=SystemClock.elapsedRealtime();
            showForegroundNotification();
            captureThread=new HandlerThread("JPQScopeProbe"); captureThread.start(); capture=new Handler(captureThread.getLooper());
            windows=getSystemService(WindowManager.class); density=getResources().getDisplayMetrics().densityDpi;
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
        } catch(Exception error) { fail("start",error); }
        return START_NOT_STICKY;
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
            if(baselineCount<2 || baselineCounters.size()<2) { main.postDelayed(this::maybeAddOverlay,300); return; }
        }
        overlayAttempted=true;
        if(!Settings.canDrawOverlays(this)) {
            event("overlay_not_added","reason","permission_missing","note","No permission screen was opened."); return;
        }
        try {
            overlay=new View(this) {
                @Override protected void onDraw(Canvas canvas) {
                    canvas.drawColor(Color.MAGENTA);
                    Paint border=new Paint(); border.setStyle(Paint.Style.STROKE); border.setColor(Color.BLACK); border.setStrokeWidth(4);
                    canvas.drawRect(2,2,getWidth()-2,getHeight()-2,border); overlayDraws.incrementAndGet();
                }
            };
            overlay.setContentDescription("scope-probe-magenta-overlay");
            overlayParams=new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            overlayParams.gravity=Gravity.TOP|Gravity.LEFT; overlayParams.alpha=1f;
            if(Build.VERSION.SDK_INT>=28) overlayParams.layoutInDisplayCutoutMode=
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            assignOverlayPosition(); windows.addView(overlay,overlayParams); overlayAdded=true;
            overlayAddedAt=SystemClock.elapsedRealtime();
            overlay.post(this::recordOverlayBounds);
            event("overlay_added","colour","#FF00FF","alpha",1,"permissionGranted",true);
        } catch(Exception error) { event("overlay_error","class",error.getClass().getSimpleName()); }
    }
    private void assignOverlayPosition() {
        Rect screen=screenBounds();
        overlayParams.x=(int)(screen.width()*.30f); overlayParams.y=(int)(screen.height()*.28f);
        overlayParams.width=(int)(screen.width()*.25f); overlayParams.height=(int)(screen.height()*.14f);
    }
    private void positionOverlay() {
        if(released.get() || !overlayAdded || overlay==null) return;
        try { assignOverlayPosition(); windows.updateViewLayout(overlay,overlayParams); overlay.post(this::recordOverlayBounds); }
        catch(Exception error) { event("overlay_position_error","class",error.getClass().getSimpleName()); }
    }
    private void recordOverlayBounds() {
        if(!overlayAdded || overlay==null) return;
        int[] location=new int[2]; overlay.getLocationOnScreen(location);
        overlayRect=new Rect(location[0],location[1],location[0]+overlay.getWidth(),location[1]+overlay.getHeight());
        event("overlay_geometry","screenRect",rect(overlayRect),"physicalPatternRect",rect(ScopeProbeActivity.patternRect()),
            "isShown",overlay.isShown(),"drawCount",overlayDraws.get(),"rotation",ScopeProbeActivity.patternRotation());
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
                Stats stats=analyze(pixels); boolean after=overlayAdded && now-overlayAddedAt>=600;
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
                    lastKnownPatternAt=now; patternCount++;
                    if(!overlayAdded) { baselineCount++; baselineCounters.add(stats.counter); }
                    if(after && overlayDraws.get()>0) {
                        afterCount++; afterCounters.add(stats.counter);
                        double[] area=overlayArea(stats,pixels);
                        if(area!=null) {
                            put(sample,"overlayRegionGreenRatio",area[0]); put(sample,"overlayRegionMagentaRatio",area[1]);
                            if(area[1]>=.60 && stats.magenta>=.003) presentCount++;
                            else if(area[0]>=.90 && stats.magenta<=.0001) absentCount++;
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
                    if(now-lastKnownPatternAt>5000) main.post(() -> end("fixture_not_visible_or_unrecognized"));
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
    private static boolean magenta(int p) { return (p>>16&255)>=230 && (p>>8&255)<=50 && (p&255)>=230; }
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
    private double[] overlayArea(Stats stats,FramePixels pixels) {
        Rect screen=ScopeProbeActivity.patternRect(),covered=overlayRect;
        if(screen==null || covered==null || screen.isEmpty() || !screen.contains(covered) || stats.content==null) return null;
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
            if(overlayAdded && overlay!=null) try { windows.removeViewImmediate(overlay); } catch(Exception ignored) {}
            overlayAdded=false; overlay=null;
            writeSummary();
        }
        if(captureThread!=null) { captureThread.quitSafely(); captureThread=null; }
        synchronized(outputLock) { if(events!=null) { try { events.close(); } catch(Exception ignored) {} events=null; } }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    private void writeSummary() {
        if(directory==null) return;
        String classification="INCONCLUSIVE";
        if(baselineCount>=2 && baselineCounters.size()>=2 && afterCount>=3 && afterCounters.size()>=2 && overlayDraws.get()>0) {
            if(presentCount>=3 && absentCount==0) classification="OVERLAY_PIXELS_PRESENT";
            else if(absentCount>=3 && presentCount==0) classification="OVERLAY_PIXELS_NOT_OBSERVED";
        }
        JSONObject summary=json("runId",runId,"stopReason",stopReason,"elapsedMs",SystemClock.elapsedRealtime()-startedAt,
            "classification",classification,"captureSelection","user_choice_unknown","displayCreations",displayCreations,
            "resizeEvents",resizeCount,"samples",sampleCount,"fixtureFrames",patternCount,"unknownFrames",unknownCount,
            "baselineFrames",baselineCount,"baselineDistinctCounters",baselineCounters.size(),"afterOverlayFrames",afterCount,
            "afterDistinctCounters",afterCounters.size(),"overlayPixelsPresentFrames",presentCount,"underlyingGreenFrames",absentCount,
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
