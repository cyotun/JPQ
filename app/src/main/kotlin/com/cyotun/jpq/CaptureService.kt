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
    @Volatile private var reader: ImageReader? = null
    private var worker: Handler? = null
    private var overlay: OverlayController? = null
    private var visualContext: Context? = null
    private var lastFrame = 0L
    private var lastFrameError=0L
    private var lastStartId=0
    @Volatile private var closed = false
    @Volatile private var session = 0L
    @Volatile private var recognizer: FrameRecognizer = AwaitingValidatedAdapter()
    private val main = Handler(Looper.getMainLooper())
    private var callback: MediaProjection.Callback? = null
    private fun projectionCallback(generation: Long) = object : MediaProjection.Callback() {
        override fun onStop() { if(generation==session && !closed) shutdown("屏幕共享已停止，请重新开始记牌") }
        override fun onCapturedContentResize(width: Int, height: Int) { if(generation==session) resize(width,height) }
        override fun onCapturedContentVisibilityChanged(visible: Boolean) {
            if (generation==session && !closed && !visible && AppState.ledger.snapshot().remaining != null) {
                AppState.ledger.invalidate("游戏画面被遮挡，本局无法完整观察")
                AppState.notifyChanged()
            }
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId=startId
        if (intent?.action == "STOP") { shutdown("记牌已停止"); return START_NOT_STICKY }
        if (intent?.action == "CONFIG") {
            val config=ConfigStore(this).load()
            android.util.Log.i("JPQCapture","display_config history=${config.showHistory} reset=${config.showReset} doubleTap=${config.doubleTapHide}")
            overlay?.refresh(); return START_NOT_STICKY
        }
        if (projection != null || intent == null) return START_NOT_STICKY
        closed=false
        val generation=++session
        android.util.Log.i("JPQCapture","session_start generation=$generation")
        lastFrame=0L; lastFrameError=0L
        AppState.recognitionReady=false
        try {
            val data=if(Build.VERSION.SDK_INT>=33) intent.getParcelableExtra("data",Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("data")
            require(data!=null)
            createForeground()
            visualContext=if(Build.VERSION.SDK_INT>=30) {
                val deviceDisplay=(getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY)
                require(deviceDisplay!=null) { "Main display unavailable" }
                createDisplayContext(deviceDisplay).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null)
            } else this
            worker=VisionRuntime.handler
            worker!!.post {
                if(generation==session && !closed) try {
                    // Old session observations/close must finish before clearing its ledger.
                    AppState.ledger.waitForNext()
                    val prepared=TencentRecognizer(this)
                    if(generation!=session || closed) { prepared.close(); return@post }
                    recognizer=prepared
                    AppState.recognitionReady=true; AppState.notifyChanged()
                    android.util.Log.i("JPQCapture","local_recognizer_ready")
                } catch(error: Exception) { initializationFailed(generation,error) }
                  catch(error: LinkageError) { initializationFailed(generation,error) }
            }
            projection=(getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(intent.getIntExtra("code",Activity.RESULT_CANCELED),data)
            callback=projectionCallback(generation).also { projection!!.registerCallback(it,main) }
            val dimensions = screenSize()
            reader=newReader(dimensions.first,dimensions.second,generation)
            display=projection!!.createVirtualDisplay("JPQCapture",AppState.capturedWidth,AppState.capturedHeight,resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,worker)
            AppState.running=true; AppState.capturedFrames=0
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
    private fun initializationFailed(generation: Long,error: Throwable) {
        android.util.Log.e("JPQCapture","recognizer_init_failed",error)
        main.post { if(generation==session && !closed) shutdown("本地识别未能准备好，请重新开始") }
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
    private fun newReader(width: Int,height: Int,generation: Long): ImageReader {
        AppState.sourceWidth=width; AppState.sourceHeight=height
        val scale=minOf(1f,1280f/maxOf(width,height))
        val outputWidth=(width*scale).toInt().coerceAtLeast(1); val outputHeight=(height*scale).toInt().coerceAtLeast(1)
        AppState.capturedWidth=outputWidth; AppState.capturedHeight=outputHeight
        android.util.Log.i("JPQCapture","source_size=${width}x$height capture_size=${outputWidth}x$outputHeight")
        return ImageReader.newInstance(outputWidth,outputHeight,PixelFormat.RGBA_8888,2).also { next ->
            next.setOnImageAvailableListener({ source ->
                val image=runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    val now=SystemClock.elapsedRealtime()
                    if(now-lastFrame>=67 && generation==session && source===reader && !closed && AppState.recognitionReady) {
                        lastFrame=now; recognizer.observe(image)
                        if(generation==session && !closed) {
                            AppState.capturedFrames++
                            if(AppState.capturedFrames % 8L==0L) AppState.notifyChanged()
                        }
                    }
                } catch (error: Exception) {
                    val now=SystemClock.elapsedRealtime()
                    if(now-lastFrameError>5000) { lastFrameError=now; android.util.Log.e("JPQCapture","frame_failed",error) }
                    if(generation==session && !closed) { AppState.ledger.invalidate("识别暂时不可用，等待下一局"); AppState.notifyChanged() }
                } finally { image.close() }
            },worker)
        }
    }
    private fun resize(width: Int,height: Int) {
        if(width<=0 || height<=0 || display==null || closed) return
        if(width==AppState.sourceWidth && height==AppState.sourceHeight) return
        // Projection callbacks and teardown run on main. Retire the old reader only
        // after any image already being observed on the vision worker is released.
        var next: ImageReader?=null
        try {
            val old=reader
            next=newReader(width,height,session)
            display!!.resize(AppState.capturedWidth,AppState.capturedHeight,resources.configuration.densityDpi)
            display!!.surface=next.surface; reader=next
            old?.setOnImageAvailableListener(null,null)
            worker?.post { old?.close() }
            overlay?.refresh()
        } catch (error: Exception) {
            if(next!==reader) next?.close()
            android.util.Log.e("JPQCapture","resize_failed",error)
            shutdown("画面尺寸变化，需重新开始记牌")
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
        closed=true; ++session
        android.util.Log.i("JPQCapture","session_stop reason=$message")
        AppState.running=false; AppState.recognitionReady=false; AppState.message=message
        com.cyotun.jpq.updates.UpdateManager.scheduleDownload(this)
        if(AppState.ledger.snapshot().remaining!=null) AppState.ledger.invalidate(message)
        overlay?.close(); overlay=null
        visualContext=null
        callback?.let { projection?.unregisterCallback(it) }; callback=null; display?.release(); display=null
        val previousReader=reader
        previousReader?.setOnImageAvailableListener(null,null); reader=null
        projection?.stop(); projection=null
        val previousRecognizer=recognizer
        recognizer=AwaitingValidatedAdapter()
        worker?.post {
            runCatching { previousReader?.close() }.onFailure { android.util.Log.e("JPQCapture","reader_close_failed",it) }
            runCatching { previousRecognizer.close() }.onFailure { android.util.Log.e("JPQCapture","recognizer_close_failed",it) }
            VisionRuntime.trimWhenIdle()
        }
        stopForeground(STOP_FOREGROUND_REMOVE); AppState.notifyChanged()
        if(lastStartId>0) stopSelf(lastStartId) else stopSelf()
    }
    override fun onDestroy() { shutdown("记牌已停止"); super.onDestroy() }
}
