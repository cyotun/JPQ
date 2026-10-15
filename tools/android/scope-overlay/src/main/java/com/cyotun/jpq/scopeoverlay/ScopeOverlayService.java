package com.cyotun.jpq.scopeoverlay;

import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.res.Configuration;
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
    @Override public IBinder onBind(Intent intent) {
        if(intent==null || !intent.getBooleanExtra("scopeProbe",false)) return null;
        String requested=intent.getStringExtra("runId");
        if(requested==null || requested.isEmpty() || session!=null) return null;
        session=requested;
        android.view.Display primary=getSystemService(DisplayManager.class).getDisplay(android.view.Display.DEFAULT_DISPLAY);
        if(primary==null) return null;
        visualContext=createDisplayContext(primary);
        if(android.os.Build.VERSION.SDK_INT>=30)
            visualContext=visualContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
        windows=visualContext.getSystemService(WindowManager.class);
        startedAt=lastHeartbeat=SystemClock.elapsedRealtime();
        main.postDelayed(this::watchdog,500);
        return control.getBinder();
    }
    private boolean command(Message message) {
        if(closed || session==null || !session.equals(message.getData().getString("runId"))) return true;
        // The signature permission protects binding; also reject messenger handles
        // forwarded to any UID other than the designated test APK.
        String[] packages=getPackageManager().getPackagesForUid(message.sendingUid);
        if(packages==null || !java.util.Arrays.asList(packages).contains("com.cyotun.jpq.tests")) return true;
        lastHeartbeat=SystemClock.elapsedRealtime();
        if(message.replyTo!=null) client=message.replyTo;
        if(message.what==4) { close(); return true; }
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
        if(!Settings.canDrawOverlays(this)) { report("permission_missing"); return; }
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
            overlay.post(() -> report(null));
        } catch(Exception error) { report(error.getClass().getSimpleName()); close(); }
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
        Message message=Message.obtain(null,10); message.setData(data);
        try { client.send(message); } catch(RemoteException ignored) { close(); }
    }
    private void watchdog() {
        if(closed) return;
        long now=SystemClock.elapsedRealtime();
        if(now-lastHeartbeat>=3000 || now-startedAt>=90000) { close(); return; }
        main.postDelayed(this::watchdog,500);
    }
    @Override public void onConfigurationChanged(Configuration next) { super.onConfigurationChanged(next); if(attached && !closed) showOrPosition(); }
    @Override public boolean onUnbind(Intent intent) { close(); return false; }
    private void close() {
        if(closed) return; closed=true; main.removeCallbacksAndMessages(null);
        if(attached) try { windows.removeViewImmediate(overlay); } catch(Exception ignored) {}
        attached=false; overlay=null; report("overlay_stopped"); stopSelf();
    }
    @Override public void onDestroy() { close(); super.onDestroy(); }
}
