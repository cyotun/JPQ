package com.cyotun.jpq.scopeoverlay;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.Context;
import android.content.res.Configuration;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/** Signature-bound, opt-in colour overlay. No capture, storage, network or game control. */
public final class ScopeOverlayService extends Service {
    private static final String TAG="ScopeProbeService";
    public static final String START_ACTION="com.cyotun.jpq.scopeoverlay.START_FOREGROUND_PROBE";
    private static final String CHANNEL_ID="scope_probe";
    private static final int NOTIFICATION_ID=721;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Messenger control=new Messenger(new Handler(Looper.getMainLooper(),this::command));
    private Messenger client;
    private WindowManager windows;
    private Context visualContext;
    private View overlay;
    private String session;
    private boolean attached,closed;
    private long lastHeartbeat,startedAt;
    private int draws;
    private int lastSenderUid=Integer.MIN_VALUE;
    private Boolean lastSenderAllowed;
    private Boolean lastReportedVisible;
    private int lastReportedDrawCount=-1;
    private boolean foregroundStarted;
    private String foregroundRunId;
    private final Runnable watchdogTask=this::watchdog;

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null || !START_ACTION.equals(intent.getAction()) ||
            !intent.getBooleanExtra("scopeProbe",false) || !intent.getBooleanExtra("selfForeground",false)) {
            android.util.Log.w(TAG,"start_rejected reason=invalid_opt_in");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String requested=intent.getStringExtra("runId");
        if(requested==null || requested.isEmpty() ||
            (foregroundRunId!=null && !foregroundRunId.equals(requested))) {
            android.util.Log.w(TAG,"start_rejected reason=run_id_invalid_or_active");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if(foregroundStarted) return START_NOT_STICKY;
        try {
            NotificationManager manager=getSystemService(NotificationManager.class);
            if(android.os.Build.VERSION.SDK_INT>=26) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,"实验悬浮层测试",
                    NotificationManager.IMPORTANCE_LOW));
            }
            Notification notification=new Notification.Builder(this,CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("实验悬浮层测试运行中")
                .setContentText("临时遮挡对照；结束后自动停止")
                .setOngoing(true).build();
            if(android.os.Build.VERSION.SDK_INT>=34) {
                startForeground(NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
            } else {
                startForeground(NOTIFICATION_ID,notification);
            }
            foregroundStarted=true; foregroundRunId=requested;
            startedAt=lastHeartbeat=SystemClock.elapsedRealtime();
            android.util.Log.i(TAG,"foreground_started run_timeout_ms=90000 type="+
                (android.os.Build.VERSION.SDK_INT>=34?"shortService":"standard"));
            main.postDelayed(watchdogTask,500);
        } catch(RuntimeException error) {
            android.util.Log.e(TAG,"foreground_start_failed type="+error.getClass().getSimpleName(),error);
            close("foreground_start_failed");
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        if(intent==null || !intent.getBooleanExtra("scopeProbe",false)) {
            android.util.Log.w(TAG,"bind_rejected reason=opt_in_missing"); return null;
        }
        String requested=intent.getStringExtra("runId");
        if(requested==null || requested.isEmpty() || session!=null) {
            android.util.Log.w(TAG,"bind_rejected reason="+(session!=null?"session_active":"run_id_missing")); return null;
        }
        session=requested;
        android.view.Display primary=getSystemService(DisplayManager.class).getDisplay(android.view.Display.DEFAULT_DISPLAY);
        if(primary==null) { android.util.Log.e(TAG,"bind_failed reason=default_display_missing"); return null; }
        visualContext=createDisplayContext(primary);
        if(android.os.Build.VERSION.SDK_INT>=30)
            visualContext=visualContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
        windows=visualContext.getSystemService(WindowManager.class);
        long now=SystemClock.elapsedRealtime();
        if(startedAt==0L) startedAt=now;
        lastHeartbeat=now;
        android.util.Log.i(TAG,"bind_accepted owner_uid="+android.os.Process.myUid()+" sdk="+android.os.Build.VERSION.SDK_INT);
        if(!foregroundStarted) main.postDelayed(watchdogTask,500);
        return control.getBinder();
    }
    private boolean command(Message message) {
        if(closed || session==null || !session.equals(message.getData().getString("runId"))) return true;
        // The signature permission protects binding; also reject messenger handles
        // forwarded to any UID other than the existing test APK or this helper.
        String[] packages=getPackageManager().getPackagesForUid(message.sendingUid);
        boolean allowed=packages!=null && (java.util.Arrays.asList(packages).contains("com.cyotun.jpq.tests") ||
            java.util.Arrays.asList(packages).contains("com.cyotun.jpq.scopeoverlay"));
        if(message.sendingUid!=lastSenderUid || lastSenderAllowed==null || allowed!=lastSenderAllowed) {
            android.util.Log.i(TAG,"sender uid="+message.sendingUid+" owner_uid="+android.os.Process.myUid()+
                " packages="+(packages==null?"none":java.util.Arrays.toString(packages))+" allowed="+allowed+" what="+message.what);
            lastSenderUid=message.sendingUid; lastSenderAllowed=allowed;
        }
        if(!allowed) return true;
        lastHeartbeat=SystemClock.elapsedRealtime();
        if(message.replyTo!=null) client=message.replyTo;
        if(message.what==4) { close("client_stop"); return true; }
        if(message.what==1 || message.what==2) showOrPosition();
        else if(message.what==3) report(null);
        return true;
    }
    @SuppressWarnings("deprecation") private Rect screen() {
        if(android.os.Build.VERSION.SDK_INT>=30) return windows.getMaximumWindowMetrics().getBounds();
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics(); windows.getDefaultDisplay().getRealMetrics(metrics);
        return new Rect(0,0,metrics.widthPixels,metrics.heightPixels);
    }
    private void showOrPosition() {
        if(!Settings.canDrawOverlays(this)) {
            android.util.Log.e(TAG,"show_failed reason=overlay_permission_missing"); report("permission_missing"); return;
        }
        try {
            Rect screen=screen();
            WindowManager.LayoutParams params=new WindowManager.LayoutParams((int)(screen.width()*.25f),(int)(screen.height()*.14f),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
            params.gravity=Gravity.TOP|Gravity.LEFT; params.alpha=1f;
            params.x=(int)(screen.width()*.30f); params.y=(int)(screen.height()*.28f);
            params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            if(overlay==null) overlay=new View(visualContext) {
                private final Paint border=new Paint();
                @Override protected void onDraw(Canvas canvas) {
                    canvas.drawColor(Color.MAGENTA); border.setStyle(Paint.Style.STROKE); border.setColor(Color.BLACK); border.setStrokeWidth(4);
                    canvas.drawRect(2,2,getWidth()-2,getHeight()-2,border); draws++; post(() -> report(null));
                }
            };
            overlay.setContentDescription("scope-probe-external-magenta-overlay");
            if(!attached) { windows.addView(overlay,params); attached=true; }
            else windows.updateViewLayout(overlay,params);
            android.util.Log.i(TAG,"show_requested attached="+attached+" size="+params.width+"x"+params.height+
                " position="+params.x+","+params.y);
            overlay.post(() -> report(null));
        } catch(Exception error) {
            android.util.Log.e(TAG,"show_failed type="+error.getClass().getSimpleName(),error);
            report(error.getClass().getSimpleName()); close("show_exception");
        }
    }
    private void report(String error) {
        if(client==null) return;
        Bundle data=new Bundle(); data.putString("runId",session); data.putString("ownerPackage",getPackageName());
        data.putInt("ownerUid",android.os.Process.myUid()); data.putInt("drawCount",draws);
        boolean shown=attached && overlay!=null && overlay.isShown(); data.putBoolean("visible",shown);
        data.putInt("displayId",shown && overlay.getDisplay()!=null ? overlay.getDisplay().getDisplayId() : -1);
        if(shown) {
            int[] location=new int[2]; overlay.getLocationOnScreen(location);
            data.putIntArray("bounds",new int[]{location[0],location[1],location[0]+overlay.getWidth(),location[1]+overlay.getHeight()});
        }
        if(error!=null) data.putString("error",error);
        if(error!=null || lastReportedVisible==null || shown!=lastReportedVisible || draws!=lastReportedDrawCount) {
            android.util.Log.i(TAG,"report visible="+shown+" draws="+draws+" display="+data.getInt("displayId")+
                " error="+(error==null?"none":error));
            lastReportedVisible=shown; lastReportedDrawCount=draws;
        }
        Message message=Message.obtain(null,10); message.setData(data);
        try { client.send(message); } catch(RemoteException errorSending) {
            android.util.Log.w(TAG,"report_send_failed type="+errorSending.getClass().getSimpleName()); close("report_send_failed");
        }
    }
    private void watchdog() {
        if(closed) return;
        long now=SystemClock.elapsedRealtime();
        // The self-launched foreground mode can outlive a paused launcher; its 90s deadline bounds it.
        if(!foregroundStarted && now-lastHeartbeat>=3000) { close("heartbeat_watchdog"); return; }
        if(now-startedAt>=90000) { close("service_timeout"); return; }
        main.postDelayed(this::watchdog,500);
    }
    @Override public void onConfigurationChanged(Configuration next) { super.onConfigurationChanged(next); if(attached && !closed) showOrPosition(); }
    @Override public boolean onUnbind(Intent intent) { close("unbind"); return false; }
    private void close(String reason) {
        if(closed) return; closed=true; main.removeCallbacks(watchdogTask);
        android.util.Log.i(TAG,"close reason="+reason+" attached="+attached+" draws="+draws);
        if(attached) try { windows.removeViewImmediate(overlay); } catch(Exception ignored) {}
        attached=false; overlay=null; report("overlay_stopped");
        if(foregroundStarted) {
            try { stopForeground(STOP_FOREGROUND_REMOVE); }
            catch(RuntimeException error) { android.util.Log.w(TAG,"stop_foreground_failed type="+error.getClass().getSimpleName()); }
            foregroundStarted=false;
        }
        stopSelf();
    }
    @Override public void onDestroy() { close("destroy"); super.onDestroy(); }
}
