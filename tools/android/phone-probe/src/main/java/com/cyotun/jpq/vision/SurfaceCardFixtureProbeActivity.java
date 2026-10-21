package com.cyotun.jpq.vision;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.IOException;
import java.io.InputStream;

/** SurfaceView-backed card fixture for exercising real surface recreation and capture. */
public final class SurfaceCardFixtureProbeActivity extends Activity implements SurfaceHolder.Callback {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable timeout=this::finish;
    private final Paint bitmapPaint=new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint clockPaint=new Paint();
    private final Runnable frameLoop=new Runnable() {
        @Override public void run() {
            if(!surfaceReady || isFinishing()) return;
            drawFrame();
            frame++;
            main.postDelayed(this,250);
        }
    };

    private FrameLayout root;
    private SurfaceView surfaceView;
    private Bitmap fixture;
    private SurfaceHolder drawingHolder;
    private int caseIndex;
    private int frame;
    private boolean surfaceReady;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        CardFixtureProbeRequest request=parse(getIntent());
        if(request==null) { finish(); return; }
        caseIndex=request.caseIndex;
        setRequestedOrientation(request.portrait ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        resetTimeout(request.timeoutSeconds);
        fixture=loadFixture(caseIndex);
        if(fixture==null) { finish(); return; }
        root=new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root,new FrameLayout.LayoutParams(-1,-1));
        addSurfaceView();
        immersive();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        CardFixtureProbeRequest request=parse(intent);
        if(request==null) { finish(); return; }
        Bitmap replacement=request.caseIndex==caseIndex ? fixture : loadFixture(request.caseIndex);
        if(replacement==null) { finish(); return; }
        if(replacement!=fixture && fixture!=null) fixture.recycle();
        fixture=replacement;
        caseIndex=request.caseIndex;
        setRequestedOrientation(request.portrait ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        resetTimeout(request.timeoutSeconds);
        setIntent(intent);
        if(intent.getBooleanExtra("recreateSurface",false)) recreateSurfaceView();
        else drawFrame();
        immersive();
    }

    private CardFixtureProbeRequest parse(Intent intent) {
        Integer index=intent.hasExtra("caseIndex") ? intent.getIntExtra("caseIndex",0) : null;
        Integer seconds=intent.hasExtra("timeoutSeconds") ? intent.getIntExtra("timeoutSeconds",0) : null;
        return CardFixtureProbeRequest.parse(intent.getBooleanExtra("cardFixtureProbe",false),index,
            intent.getStringExtra("orientation"),seconds);
    }

    private void resetTimeout(int seconds) {
        main.removeCallbacks(timeout);
        main.postDelayed(timeout,seconds*1000L);
    }

    private Bitmap loadFixture(int index) {
        try(InputStream stream=getAssets().open("case"+index+".jpg")) {
            BitmapFactory.Options options=new BitmapFactory.Options();
            options.inScaled=false;
            return BitmapFactory.decodeStream(stream,null,options);
        } catch(IOException | RuntimeException ignored) {
            return null;
        }
    }

    private void addSurfaceView() {
        surfaceView=new SurfaceView(this);
        surfaceView.setZOrderOnTop(false);
        surfaceView.getHolder().setFormat(PixelFormat.OPAQUE);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView,new FrameLayout.LayoutParams(-1,-1));
    }

    private void recreateSurfaceView() {
        main.removeCallbacks(frameLoop);
        surfaceReady=false;
        drawingHolder=null;
        SurfaceView old=surfaceView;
        if(old!=null) {
            root.removeView(old);
            old.getHolder().removeCallback(this);
        }
        addSurfaceView();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if(surfaceView==null || holder!=surfaceView.getHolder()) return;
        drawingHolder=holder;
        surfaceReady=true;
        frame=0;
        main.removeCallbacks(frameLoop);
        main.post(frameLoop);
    }

    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) {
        if(surfaceView!=null && holder==surfaceView.getHolder()) drawFrame();
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if(holder==drawingHolder) {
            drawingHolder=null;
            surfaceReady=false;
            main.removeCallbacks(frameLoop);
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        immersive();
    }

    private void drawFrame() {
        SurfaceView view=surfaceView;
        Bitmap bitmap=fixture;
        if(!surfaceReady || view==null || bitmap==null) return;
        SurfaceHolder holder=view.getHolder();
        if(holder!=drawingHolder || !holder.getSurface().isValid()) return;
        Canvas canvas=null;
        try {
            canvas=holder.lockCanvas();
            if(canvas==null) return;
            canvas.drawColor(Color.BLACK);
            float scale=Math.min(canvas.getWidth()/(float)bitmap.getWidth(),
                canvas.getHeight()/(float)bitmap.getHeight());
            float width=bitmap.getWidth()*scale;
            float height=bitmap.getHeight()*scale;
            float left=(canvas.getWidth()-width)/2f;
            float top=(canvas.getHeight()-height)/2f;
            canvas.drawBitmap(bitmap,null,new RectF(left,top,left+width,top+height),bitmapPaint);
            clockPaint.setColor((frame&1)==0 ? Color.BLUE : Color.GREEN);
            canvas.drawRect(canvas.getWidth()-8,0,canvas.getWidth(),8,clockPaint);
        } catch(IllegalArgumentException | IllegalStateException ignored) {
            // Surface teardown can race a posted frame; the callback stops the loop.
        } finally {
            if(canvas!=null) {
                try { holder.unlockCanvasAndPost(canvas); }
                catch(IllegalArgumentException | IllegalStateException ignored) { }
            }
        }
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

    @Override protected void onResume() {
        super.onResume();
        immersive();
        if(surfaceReady) {
            main.removeCallbacks(frameLoop);
            main.post(frameLoop);
        }
    }

    @Override protected void onPause() {
        main.removeCallbacks(frameLoop);
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(timeout);
        main.removeCallbacks(frameLoop);
        surfaceReady=false;
        if(surfaceView!=null) surfaceView.getHolder().removeCallback(this);
        if(fixture!=null) { fixture.recycle(); fixture=null; }
        super.onDestroy();
    }
}
