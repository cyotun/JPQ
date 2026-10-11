package com.cyotun.jpq

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.graphics.PixelFormat
import android.os.*
import android.view.WindowManager
import com.cyotun.jpq.vision.*

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var overlay: OverlayController? = null
    private var visualContext: Context? = null
    private var lastFrame = 0L
    private var lastFrameError=0L
    @Volatile private var closed = false
    private var recognizer: FrameRecognizer = AwaitingValidatedAdapter()
    private val main = Handler(Looper.getMainLooper())
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { shutdown("屏幕共享已停止，请重新开始记牌") }
        override fun onCapturedContentResize(width: Int, height: Int) { resize(width,height) }
        override fun onCapturedContentVisibilityChanged(visible: Boolean) {
            if (!visible && AppState.ledger.snapshot().remaining != null) {
                AppState.ledger.invalidate("游戏画面被遮挡，本局无法完整观察")
                AppState.notifyChanged()
            }
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { shutdown("记牌已停止"); return START_NOT_STICKY }
        if (intent?.action == "CONFIG") { overlay?.refresh(); return START_NOT_STICKY }
        if (projection != null || intent == null) return START_NOT_STICKY
        try {
            val data=if(Build.VERSION.SDK_INT>=33) intent.getParcelableExtra("data",Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("data")
            require(data!=null)
            createForeground()
            visualContext=if(Build.VERSION.SDK_INT>=30) {
                val deviceDisplay=(getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY)
                require(deviceDisplay!=null) { "Main display unavailable" }
                createDisplayContext(deviceDisplay).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null)
            } else this
            thread=HandlerThread("jpq-capture").also { it.start() }; worker=Handler(thread!!.looper)
            AppState.ledger.waitForNext()
            recognizer=TencentRecognizer(this)
            android.util.Log.i("JPQCapture","local_recognizer_ready")
            projection=(getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(intent.getIntExtra("code",Activity.RESULT_CANCELED),data)
            projection!!.registerCallback(callback,main)
            val dimensions = screenSize()
            reader=newReader(dimensions.first,dimensions.second)
            display=projection!!.createVirtualDisplay("JPQCapture",dimensions.first,dimensions.second,resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,worker)
            AppState.running=true; AppState.capturedFrames=0; AppState.recognitionReady=recognizer.ready
            AppState.message="进入游戏房间后，会自动开始记牌"
            overlay=OverlayController(visualContext!!).also { it.show() }
            AppState.notifyChanged()
        } catch (error: Exception) {
            android.util.Log.e("JPQCapture","startup_failed",error)
            shutdown("暂时无法启动记牌，请停止后重新开始")
        } catch(error: LinkageError) {
            android.util.Log.e("JPQCapture","recognizer_component_failed",error)
            shutdown("本机识别组件未能启动，请稍后重试")
        }
        return START_NOT_STICKY
    }
    private fun createForeground() {
        val manager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("capture","记牌服务",NotificationManager.IMPORTANCE_LOW))
        val stop=PendingIntent.getService(this,2,Intent(this,CaptureService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val n=Notification.Builder(this,"capture").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("记牌器运行中")
            .setContentText("只在本机读取画面；可随时停止").setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null,"停止记牌",stop).build()).build()
        if(Build.VERSION.SDK_INT>=29) startForeground(11,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(11,n)
    }
    private fun newReader(width: Int,height: Int): ImageReader {
        AppState.capturedWidth=width; AppState.capturedHeight=height
        android.util.Log.i("JPQCapture","capture_size=${width}x$height")
        return ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2).also { next ->
            next.setOnImageAvailableListener({ source ->
                val image=runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    val now=SystemClock.elapsedRealtime()
                    if(now-lastFrame>=120 && !closed) {
                        lastFrame=now; recognizer.observe(image); AppState.capturedFrames++
                        if(AppState.capturedFrames % 8L==0L) AppState.notifyChanged()
                    }
                } catch (error: Exception) {
                    val now=SystemClock.elapsedRealtime()
                    if(now-lastFrameError>5000) { lastFrameError=now; android.util.Log.e("JPQCapture","frame_failed",error) }
                    AppState.ledger.invalidate("识别暂时不可用，等待下一局"); AppState.notifyChanged()
                } finally { image.close() }
            },worker)
        }
    }
    private fun resize(width: Int,height: Int) {
        if(width<=0 || height<=0 || display==null || closed) return
        if(width==AppState.capturedWidth && height==AppState.capturedHeight) return
        worker?.post {
            if(closed) return@post
            try {
                val old=reader; val next=newReader(width,height)
                display?.resize(width,height,resources.configuration.densityDpi)
                display?.surface=next.surface; reader=next; old?.close()
                main.post { overlay?.refresh() }
            } catch (_: Exception) { main.post { shutdown("画面尺寸变化，需重新开始记牌") } }
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if(Build.VERSION.SDK_INT<34) screenSize().let { resize(it.first,it.second) }
        overlay?.refresh()
    }
    @Suppress("DEPRECATION") private fun screenSize(): Pair<Int,Int> {
        val wm=(visualContext ?: this).getSystemService(WINDOW_SERVICE) as WindowManager
        return if(Build.VERSION.SDK_INT>=30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
    }
    private fun shutdown(message: String) {
        if(closed) return
        closed=true; AppState.running=false; AppState.message=message
        com.cyotun.jpq.updates.UpdateManager.scheduleDownload(this)
        if(AppState.ledger.snapshot().remaining!=null) AppState.ledger.invalidate(message)
        overlay?.close(); overlay=null
        visualContext=null
        projection?.unregisterCallback(callback); display?.release(); display=null
        reader?.close(); reader=null; projection?.stop(); projection=null
        worker?.post { recognizer.close(); thread?.quitSafely() }
        stopForeground(STOP_FOREGROUND_REMOVE); AppState.notifyChanged(); stopSelf()
    }
    override fun onDestroy() { shutdown("记牌已停止"); super.onDestroy() }
}
