package com.cyotun.jpq.scopeoverlay;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.util.UUID;

/** Explicit, time-limited launcher for the separate-UID overlay capture probe. */
public final class ScopeOverlayLauncherActivity extends Activity {
    private static final String TAG="ScopeProbeLauncher";
    private static final String START_ACTION="com.cyotun.jpq.scopeoverlay.START_FOREGROUND_PROBE";
    private static final long HEARTBEAT_MS=800;
    private static final long MAX_RUN_MS=75_000;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final String runId=UUID.randomUUID().toString();
    private Messenger service;
    private boolean bound,closed,movedToBack,foregroundStartRequested;
    private boolean lastVisible;
    private int lastDrawCount=-1;

    private final Messenger client=new Messenger(new Handler(Looper.getMainLooper(),message -> {
        Bundle data=message.getData();
        if(closed || !runId.equals(data.getString("runId"))) return true;
        boolean visible=data.getBoolean("visible",false);
        int drawCount=data.getInt("drawCount",0);
        String error=data.getString("error");
        if(visible!=lastVisible || drawCount!=lastDrawCount || error!=null) {
            android.util.Log.i(TAG,"service_report visible="+visible+" draws="+drawCount+
                " display="+data.getInt("displayId",-1)+" error="+(error==null?"none":error));
            lastVisible=visible; lastDrawCount=drawCount;
        }
        if(error!=null) { finishProbe("service_error:"+error); return true; }
        if(visible && !movedToBack) {
            movedToBack=true;
            boolean moved=moveTaskToBack(true);
            android.util.Log.i(TAG,"move_task_to_back result="+moved);
        }
        return true;
    }));

    private final Runnable heartbeat= new Runnable() {
        @Override public void run() {
            if(closed) return;
            if(!send(3)) { finishProbe("heartbeat_send_failed"); return; }
            main.postDelayed(this,HEARTBEAT_MS);
        }
    };

    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            android.util.Log.i(TAG,"service_connected binder="+(binder!=null));
            if(closed || binder==null) { finishProbe("null_service_binder"); return; }
            service=new Messenger(binder);
            if(!send(1)) { finishProbe("show_send_failed"); return; }
            main.postDelayed(heartbeat,HEARTBEAT_MS);
        }
        @Override public void onServiceDisconnected(ComponentName name) { finishProbe("service_disconnected"); }
        @Override public void onBindingDied(ComponentName name) { finishProbe("binding_died"); }
        @Override public void onNullBinding(ComponentName name) { finishProbe("null_binding"); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if(!getIntent().getBooleanExtra("overlayProbe",false)) {
            android.util.Log.i(TAG,"finish reason=opt_in_missing"); finish(); return;
        }
        android.util.Log.i(TAG,"probe_start timeout_ms="+MAX_RUN_MS);
        main.postDelayed(() -> finishProbe("timeout"),MAX_RUN_MS);
        Intent bind=new Intent().setComponent(new ComponentName(this,ScopeOverlayService.class))
            .putExtra("scopeProbe",true).putExtra("runId",runId);
        try {
            Intent start=new Intent(this,ScopeOverlayService.class).setAction(START_ACTION)
                .putExtra("scopeProbe",true).putExtra("selfForeground",true).putExtra("runId",runId);
            if(android.os.Build.VERSION.SDK_INT>=26) startForegroundService(start); else startService(start);
            foregroundStartRequested=true;
            android.util.Log.i(TAG,"foreground_start_requested type="+
                (android.os.Build.VERSION.SDK_INT>=34?"shortService":"standard"));
            bound=bindService(bind,connection,Context.BIND_AUTO_CREATE);
            android.util.Log.i(TAG,"bind_requested result="+bound);
            if(!bound) finishProbe("bind_returned_false");
        } catch(RuntimeException error) {
            android.util.Log.w(TAG,"bind_failed type="+error.getClass().getSimpleName());
            finishProbe("bind_exception");
        }
    }

    private boolean send(int what) {
        if(service==null) return false;
        Message message=Message.obtain(null,what);
        Bundle data=new Bundle(); data.putString("runId",runId); message.setData(data); message.replyTo=client;
        try { service.send(message); return true; }
        catch(RemoteException error) {
            android.util.Log.w(TAG,"send_failed what="+what+" type="+error.getClass().getSimpleName()); return false;
        }
    }

    private void finishProbe(String reason) {
        if(closed) return;
        android.util.Log.i(TAG,"finish reason="+reason);
        closed=true; main.removeCallbacksAndMessages(null);
        if(service!=null) send(4);
        service=null;
        if(bound) {
            try { unbindService(connection); } catch(IllegalArgumentException ignored) {}
            bound=false;
        }
        stopStartedService();
        finish();
    }

    @Override protected void onDestroy() {
        if(!closed) {
            android.util.Log.i(TAG,"finish reason=activity_destroyed");
            closed=true; main.removeCallbacksAndMessages(null);
            if(service!=null) send(4);
            service=null;
            if(bound) {
                try { unbindService(connection); } catch(IllegalArgumentException ignored) {}
                bound=false;
            }
            stopStartedService();
        }
        super.onDestroy();
    }

    private void stopStartedService() {
        if(!foregroundStartRequested) return;
        try { stopService(new Intent(this,ScopeOverlayService.class)); }
        catch(RuntimeException error) {
            android.util.Log.w(TAG,"stop_service_failed type="+error.getClass().getSimpleName());
        }
        foregroundStartRequested=false;
    }
}
