package com.cyotun.jpq.vision;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** GLES2-backed fixture for stressing a real GL surface/context lifecycle. */
public final class GlCardFixtureProbeActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable timeout=this::finish;
    private FrameLayout root;
    private GLSurfaceView glView;
    private FixtureRenderer renderer;
    private int caseIndex;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        CardFixtureProbeRequest request=parse(getIntent());
        if(request==null) { finish(); return; }
        caseIndex=request.caseIndex;
        setRequestedOrientation(request.portrait ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        resetTimeout(request.timeoutSeconds);
        Bitmap image=loadFixture(caseIndex);
        if(image==null) { finish(); return; }
        root=new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root,new FrameLayout.LayoutParams(-1,-1));
        installSurface(image);
        immersive();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        CardFixtureProbeRequest request=parse(intent);
        if(request==null) { finish(); return; }
        boolean recreate=intent.getBooleanExtra("recreateSurface",false);
        Bitmap replacement=null;
        if(recreate || request.caseIndex!=caseIndex) replacement=loadFixture(request.caseIndex);
        if((recreate || request.caseIndex!=caseIndex) && replacement==null) { finish(); return; }
        caseIndex=request.caseIndex;
        setRequestedOrientation(request.portrait ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        resetTimeout(request.timeoutSeconds);
        setIntent(intent);
        if(recreate) {
            removeSurface();
            installSurface(replacement);
        } else if(replacement!=null) {
            renderer.setImage(replacement);
        }
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

    private void installSurface(Bitmap image) {
        glView=new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        renderer=new FixtureRenderer();
        renderer.setImage(image);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(glView,new FrameLayout.LayoutParams(-1,-1));
    }

    private void removeSurface() {
        GLSurfaceView old=glView;
        FixtureRenderer oldRenderer=renderer;
        glView=null;
        renderer=null;
        if(old!=null) {
            old.onPause();
            root.removeView(old);
        }
        if(oldRenderer!=null) oldRenderer.release();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        immersive();
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
        if(glView!=null) glView.onResume();
    }

    @Override protected void onPause() {
        if(glView!=null) glView.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(timeout);
        removeSurface();
        super.onDestroy();
    }

    private static final class FixtureRenderer implements GLSurfaceView.Renderer {
        private static final String VERTEX_SHADER=
            "attribute vec2 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord;"+
            "void main(){gl_Position=vec4(aPosition,0.0,1.0);vTexCoord=aTexCoord;}";
        private static final String FRAGMENT_SHADER=
            "precision mediump float; varying vec2 vTexCoord; uniform sampler2D uTexture;"+
            "void main(){gl_FragColor=texture2D(uTexture,vTexCoord);}";
        private final FloatBuffer vertices=ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        private Bitmap image;
        private int imageWidth,imageHeight,viewWidth,viewHeight,program,texture;
        private boolean imageDirty=true,green;

        synchronized void setImage(Bitmap replacement) {
            if(image!=null && image!=replacement && !image.isRecycled()) image.recycle();
            image=replacement;
            imageWidth=replacement.getWidth();
            imageHeight=replacement.getHeight();
            imageDirty=true;
        }

        @Override public void onSurfaceCreated(GL10 unused,EGLConfig config) {
            GLES20.glClearColor(0f,0f,0f,1f);
            program=createProgram(VERTEX_SHADER,FRAGMENT_SHADER);
            int[] ids=new int[1];
            GLES20.glGenTextures(1,ids,0);
            texture=ids[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            imageDirty=true;
        }

        @Override public void onSurfaceChanged(GL10 unused,int width,int height) {
            viewWidth=width;
            viewHeight=height;
            GLES20.glViewport(0,0,width,height);
        }

        @Override public void onDrawFrame(GL10 unused) {
            synchronized(this) {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                if(image!=null && viewWidth>0 && viewHeight>0 && program!=0) {
                    if(imageDirty) {
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,image,0);
                        imageDirty=false;
                    }
                    drawImage();
                }
                green=!green;
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                GLES20.glScissor(Math.max(0,viewWidth-8),Math.max(0,viewHeight-8),8,8);
                GLES20.glClearColor(green?0f:0f,green?1f:0f,green?0f:1f,1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                GLES20.glClearColor(0f,0f,0f,1f);
            }
            // Leave time for a sampled Image to reach inference while software
            // GLES and lossless diagnostic PNG writes share a constrained host.
            // The clock still forces fresh buffers; production capture is unchanged.
            try { Thread.sleep(1000L); }
            catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }

        private void drawImage() {
            float scale=Math.min(viewWidth/(float)imageWidth,viewHeight/(float)imageHeight);
            float halfWidth=imageWidth*scale/viewWidth;
            float halfHeight=imageHeight*scale/viewHeight;
            // GLUtils preserves bitmap row order: map screen top to texture v=0.
            float[] data={-halfWidth,-halfHeight,0f,1f, halfWidth,-halfHeight,1f,1f,
                -halfWidth,halfHeight,0f,0f, halfWidth,halfHeight,1f,0f};
            vertices.position(0);vertices.put(data).position(0);
            GLES20.glUseProgram(program);
            int position=GLES20.glGetAttribLocation(program,"aPosition");
            int texCoord=GLES20.glGetAttribLocation(program,"aTexCoord");
            vertices.position(0);
            GLES20.glEnableVertexAttribArray(position);
            GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,16,vertices);
            vertices.position(2);
            GLES20.glEnableVertexAttribArray(texCoord);
            GLES20.glVertexAttribPointer(texCoord,2,GLES20.GL_FLOAT,false,16,vertices);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uTexture"),0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
            GLES20.glDisableVertexAttribArray(position);
            GLES20.glDisableVertexAttribArray(texCoord);
        }

        synchronized void release() {
            if(image!=null && !image.isRecycled()) image.recycle();
            image=null;
        }

        private static int createProgram(String vertexSource,String fragmentSource) {
            int vertex=compileShader(GLES20.GL_VERTEX_SHADER,vertexSource);
            int fragment=compileShader(GLES20.GL_FRAGMENT_SHADER,fragmentSource);
            int result=GLES20.glCreateProgram();
            GLES20.glAttachShader(result,vertex);
            GLES20.glAttachShader(result,fragment);
            GLES20.glLinkProgram(result);
            int[] status=new int[1];
            GLES20.glGetProgramiv(result,GLES20.GL_LINK_STATUS,status,0);
            if(status[0]==0) { GLES20.glDeleteProgram(result); return 0; }
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            return result;
        }

        private static int compileShader(int type,String source) {
            int shader=GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader,source);
            GLES20.glCompileShader(shader);
            int[] status=new int[1];
            GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,status,0);
            if(status[0]==0) { GLES20.glDeleteShader(shader); return 0; }
            return shader;
        }
    }
}
