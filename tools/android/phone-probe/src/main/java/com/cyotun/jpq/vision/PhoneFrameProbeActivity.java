package com.cyotun.jpq.vision;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Local-only dynamic test pattern for projection rotation; never a game screen. */
public final class PhoneFrameProbeActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextView counter;
    private int frame;
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            counter.setText("测试画面 " + (++frame));
            main.postDelayed(this,250);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyOrientation(getIntent());
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(19,65,58));
        TextView title=new TextView(this);
        title.setText("屏幕采集恢复测试\n不进入真实对局");
        title.setTextColor(Color.WHITE); title.setTextSize(24); title.setGravity(Gravity.CENTER);
        root.addView(title);
        LinearLayout colors=new LinearLayout(this);
        int[] sample={Color.RED,Color.GREEN,Color.BLUE};
        for(int color:sample) {
            TextView patch=new TextView(this); patch.setBackgroundColor(color);
            colors.addView(patch,new LinearLayout.LayoutParams(80,60));
        }
        root.addView(colors);
        counter=new TextView(this); counter.setTextColor(Color.WHITE);
        counter.setTextSize(18); counter.setGravity(Gravity.CENTER);
        root.addView(counter); setContentView(root);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); applyOrientation(intent);
    }
    private void applyOrientation(Intent intent) {
        setRequestedOrientation("portrait".equals(intent.getStringExtra("orientation"))
            ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }
    @Override protected void onResume() { super.onResume(); main.post(tick); }
    @Override protected void onPause() { main.removeCallbacks(tick); super.onPause(); }
    @Override protected void onDestroy() { main.removeCallbacks(tick); super.onDestroy(); }
}
