package com.cyotun.jpq.vision;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import java.io.IOException;
import java.io.InputStream;

/** Displays one bundled card-recognition fixture for capture-to-model input checks. */
public final class CardFixtureProbeActivity extends Activity {
    static final String OPT_IN="cardFixtureProbe";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable timeout= this::finish;
    private ImageView image;
    private int pulse;
    private final Paint pulsePaint=new Paint();
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if(image==null || isFinishing()) return;
            pulse++; image.invalidate(); main.postDelayed(this,250);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(!applyRequest(getIntent())) { finish(); return; }
        image=new ImageView(this) {
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                // A tiny test-only clock in the far corner keeps real Surface
                // buffers changing; the card detector excludes this region.
                pulsePaint.setColor((pulse&1)==0 ? Color.BLUE : Color.GREEN);
                canvas.drawRect(getWidth()-8,0,getWidth(),8,pulsePaint);
            }
        };
        image.setBackgroundColor(Color.BLACK);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(false);
        setContentView(image,new android.view.ViewGroup.LayoutParams(-1,-1));
        Integer index=getIntent().hasExtra("caseIndex") ? getIntent().getIntExtra("caseIndex",0) : null;
        if(index==null) { finish(); return; }
        loadFixture(index);
        immersive();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if(!applyRequest(intent)) { finish(); return; }
        setIntent(intent);
        immersive();
    }

    private boolean applyRequest(Intent intent) {
        Integer index=intent.hasExtra("caseIndex") ? intent.getIntExtra("caseIndex",0) : null;
        Integer seconds=intent.hasExtra("timeoutSeconds") ? intent.getIntExtra("timeoutSeconds",0) : null;
        CardFixtureProbeRequest request=CardFixtureProbeRequest.parse(
            intent.getBooleanExtra(OPT_IN,false),index,intent.getStringExtra("orientation"),seconds);
        if(request==null) return false;
        setRequestedOrientation(request.portrait ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        if(image!=null) loadFixture(request.caseIndex);
        main.removeCallbacks(timeout);
        main.postDelayed(timeout,request.timeoutSeconds*1000L);
        return true;
    }

    private void loadFixture(int caseIndex) {
        Bitmap bitmap=null;
        try(InputStream stream=getAssets().open("case"+caseIndex+".jpg")) {
            BitmapFactory.Options options=new BitmapFactory.Options();
            options.inScaled=false;
            bitmap=BitmapFactory.decodeStream(stream,null,options);
        } catch(IOException | RuntimeException ignored) {
            finish(); return;
        }
        if(bitmap==null) { finish(); return; }
        image.setImageBitmap(bitmap);
    }

    private void immersive() {
        if(Build.VERSION.SDK_INT>=30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().getInsetsController().hide(
                android.view.WindowInsets.Type.statusBars()|android.view.WindowInsets.Type.navigationBars());
            getWindow().getInsetsController().setSystemBarsBehavior(
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        if(Build.VERSION.SDK_INT>=28) {
            WindowManager.LayoutParams attrs=getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }
    }

    @Override protected void onResume() { super.onResume(); immersive(); main.post(tick); }
    @Override protected void onPause() { main.removeCallbacks(tick); super.onPause(); }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        immersive();
    }
    @Override protected void onDestroy() { main.removeCallbacks(timeout); main.removeCallbacks(tick); super.onDestroy(); }
}
