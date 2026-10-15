package com.cyotun.jpq.vision;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** Explicit local-only scope experiment. Never opens or selects a game. */
public final class ScopeProbeActivity extends Activity {
    public static final String OPT_IN="scopeProbe";
    private static final int CONSENT=8021;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Button start;
    private boolean consentPending;
    private PendingIntent consentResult;
    private int timeoutSeconds=45;
    private long serviceLaunchAt=-1;
    private static volatile Rect patternScreenRect;
    private static volatile int patternRotation;
    private static volatile boolean resumed,focused;
    static Rect patternRect() { Rect rect=patternScreenRect; return rect==null ? null : new Rect(rect); }
    static int patternRotation() { return patternRotation; }
    static boolean isFixtureForeground() { return resumed && focused; }
    private final Runnable consentTimeout=() -> {
        if(consentPending) {
            consentPending=false;
            if(consentResult!=null) { consentResult.cancel(); consentResult=null; }
            if(start!=null) { start.setEnabled(true); start.setText("授权超时，重新开始实验"); }
        }
    };
    private final Runnable refreshStatus=new Runnable() {
        @Override public void run() {
            if(!resumed || isFinishing()) return;
            if(start!=null && !consentPending && !ScopeProbeService.isRunning() &&
                (serviceLaunchAt<0 || SystemClock.elapsedRealtime()-serviceLaunchAt>=3000)) {
                start.setEnabled(true); start.setText("开始单应用共享实验");
            }
            main.postDelayed(this,250);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(!getIntent().getBooleanExtra(OPT_IN,false)) { finish(); return; }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyOrientation(getIntent());
        immersive();
        FrameLayout root=new FrameLayout(this);
        root.addView(new Pattern(this),new FrameLayout.LayoutParams(-1,-1));
        LinearLayout buttons=new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER); buttons.setOrientation(LinearLayout.HORIZONTAL);
        start=new Button(this); start.setText("开始单应用共享实验");
        start.setContentDescription("scope-probe-start"); start.setOnClickListener(v -> requestConsent());
        Button stop=new Button(this); stop.setText("停止实验");
        stop.setContentDescription("scope-probe-stop");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this,ScopeProbeService.class));
            start.setEnabled(true); start.setText("开始单应用共享实验");
        });
        buttons.addView(start,new LinearLayout.LayoutParams(-2,-2));
        buttons.addView(stop,new LinearLayout.LayoutParams(-2,-2));
        FrameLayout.LayoutParams bottom=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        bottom.bottomMargin=(int)(8*getResources().getDisplayMetrics().density);
        root.addView(buttons,bottom); setContentView(root);
        if(getIntent().getBooleanExtra("requestConsent",false)) main.postDelayed(this::requestConsent,650);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if(!intent.getBooleanExtra(OPT_IN,false)) { finish(); return; }
        applyOrientation(intent);
        if(intent.getBooleanExtra("requestConsent",false)) main.postDelayed(this::requestConsent,650);
    }

    private void applyOrientation(Intent intent) {
        timeoutSeconds=Math.max(15,Math.min(90,intent.getIntExtra("timeoutSeconds",45)));
        setRequestedOrientation("portrait".equals(intent.getStringExtra("orientation"))
            ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    private void requestConsent() {
        if(isFinishing() || !isFixtureForeground() || consentPending || ScopeProbeService.isRunning()) return;
        consentPending=true; start.setEnabled(false); start.setText("请在系统窗口选择记牌器手机测试");
        android.util.Log.i("JPQScopeProbe","standard_user_choice_consent_requested");
        // The picker hides its own task. Request in another task so this actual
        // fixture remains a selectable recent task; still use standard user choice.
        consentResult=createPendingResult(CONSENT,new Intent().setPackage(getPackageName()),
            PendingIntent.FLAG_ONE_SHOT|PendingIntent.FLAG_CANCEL_CURRENT|PendingIntent.FLAG_MUTABLE);
        try {
            startActivity(new Intent(this,ScopeConsentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(OPT_IN,true).putExtra("resultTo",consentResult)
                .putExtra("portrait",getRequestedOrientation()==ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            main.postDelayed(consentTimeout,95000);
        } catch(Exception error) {
            consentPending=false; consentResult.cancel(); consentResult=null; start.setEnabled(true);
            android.util.Log.w("JPQScopeProbe","consent_task_launch_failed:"+error.getClass().getSimpleName());
        }
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=CONSENT) return;
        boolean expected=consentPending;
        main.removeCallbacks(consentTimeout); consentPending=false;
        consentResult=null;
        if(!expected || isFinishing() || isDestroyed()) {
            android.util.Log.i("JPQScopeProbe","late_consent_result_discarded_no_projection"); return;
        }
        if(result!=RESULT_OK || data==null) {
            android.util.Log.i("JPQScopeProbe","consent_denied_no_projection_no_png");
            start.setEnabled(true); start.setText("已取消授权，未采集画面"); return;
        }
        Intent service=new Intent(this,ScopeProbeService.class).setAction(ScopeProbeService.ACTION_START)
            .putExtra(OPT_IN,true).putExtra("resultCode",result).putExtra("resultData",data)
            .putExtra("timeoutSeconds",timeoutSeconds);
        serviceLaunchAt=SystemClock.elapsedRealtime();
        if(Build.VERSION.SDK_INT>=26) startForegroundService(service); else startService(service);
        start.setText("实验运行中，将自动停止");
    }

    @SuppressWarnings("deprecation") private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if(Build.VERSION.SDK_INT>=28) getWindow().getAttributes().layoutInDisplayCutoutMode=
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
    }
    @Override protected void onResume() {
        super.onResume(); resumed=true; focused=getWindow().getDecorView().hasWindowFocus(); main.post(refreshStatus);
    }
    @Override protected void onPause() { resumed=false; focused=false; main.removeCallbacks(refreshStatus); super.onPause(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); focused=focus; if(focus) immersive(); }
    @Override public void onConfigurationChanged(Configuration next) { super.onConfigurationChanged(next); immersive(); }
    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if(consentResult!=null) { consentResult.cancel(); consentResult=null; }
        resumed=false; focused=false; patternScreenRect=null;
        if(isFinishing()) stopService(new Intent(this,ScopeProbeService.class));
        super.onDestroy();
    }

    /** Exact colours and relative marker geometry are checked before any PNG is saved. */
    private static final class Pattern extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private int frame;
        Pattern(android.content.Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            float w=getWidth(),h=getHeight(); canvas.drawColor(Color.GREEN);
            int[] location=new int[2]; getLocationOnScreen(location);
            patternScreenRect=new Rect(location[0],location[1],location[0]+getWidth(),location[1]+getHeight());
            if(getDisplay()!=null) patternRotation=getDisplay().getRotation();
            paint.setColor(Color.BLUE); canvas.drawRect(w*.06f,h*.18f,w*.12f,h*.32f,paint);
            paint.setColor(Color.rgb(255,153,0)); canvas.drawRect(w*.82f,h*.48f,w*.88f,h*.62f,paint);
            frame=(frame+1)&65535;
            for(int bit=0;bit<16;bit++) {
                paint.setColor((frame&(1<<bit))==0 ? Color.BLACK : Color.YELLOW);
                canvas.drawRect(w*(.10f+bit*.05f)+2,h*.67f,w*(.15f+bit*.05f)-2,h*.71f,paint);
            }
            paint.setColor(Color.WHITE); paint.setTextSize(Math.min(w,h)*.036f);
            canvas.drawText("SCOPE PROBE — 仅使用记牌器手机测试",w*.06f,h*.07f,paint);
            canvas.drawText("蓝/橙定位块 + 动态计数 " + frame,w*.06f,h*.125f,paint);
            postInvalidateDelayed(250);
        }
    }
}
