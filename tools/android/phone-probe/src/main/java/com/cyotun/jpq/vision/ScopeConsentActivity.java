package com.cyotun.jpq.vision;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

/** Separate consent task keeps the real fixture task selectable by the standard picker. */
public final class ScopeConsentActivity extends Activity {
    private static final int REQUEST=8023;
    private final Handler main=new Handler(Looper.getMainLooper());
    private PendingIntent resultTo;
    private boolean delivered;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(!getIntent().getBooleanExtra(ScopeProbeActivity.OPT_IN,false)) { finishAndRemoveTask(); return; }
        resultTo=Build.VERSION.SDK_INT>=33 ? getIntent().getParcelableExtra("resultTo",PendingIntent.class)
            : getIntent().getParcelableExtra("resultTo");
        if(resultTo==null || state!=null) { deliver(RESULT_CANCELED,null); finishAndRemoveTask(); return; }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(getIntent().getBooleanExtra("portrait",false)
            ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        try {
            startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),REQUEST);
            main.postDelayed(() -> {
                deliver(RESULT_CANCELED,null); finishActivity(REQUEST); finishAndRemoveTask();
            },90000);
        } catch(Exception error) {
            android.util.Log.w("JPQScopeProbe","consent_task_failed:"+error.getClass().getSimpleName());
            deliver(RESULT_CANCELED,null); finishAndRemoveTask();
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=REQUEST) return;
        deliver(result,data); finishAndRemoveTask();
    }
    private void deliver(int result,Intent data) {
        if(delivered) return; delivered=true; main.removeCallbacksAndMessages(null);
        if(resultTo!=null) try { resultTo.send(this,result,data); }
        catch(PendingIntent.CanceledException ignored) { android.util.Log.i("JPQScopeProbe","consent_owner_gone_no_projection"); }
    }
    @Override protected void onDestroy() { deliver(RESULT_CANCELED,null); super.onDestroy(); }
}
