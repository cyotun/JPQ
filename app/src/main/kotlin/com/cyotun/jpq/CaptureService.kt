package com.cyotun.jpq

import com.cyotun.jpq.core.StraightFlushState

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
import com.cyotun.jpq.core.LatestFrameDispatcher
import com.cyotun.jpq.core.CaptureScope
import com.cyotun.jpq.core.CaptureInputPolicy
import com.cyotun.jpq.core.CaptureContentGeometry

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null
    private var worker: Handler? = null
    private var captureWorker: CaptureWorker?=null
    private var dispatcher: LatestFrameDispatcher<CapturedFrame>?=null
    private var pipelineHealth: CapturePipelineHealth?=null
    private var healthTick: Runnable?=null
    private var overlay: OverlayController? = null
    private var visualContext: Context? = null
    private var lastFrame = 0L
    @Volatile private var lastFrameError=0L
    private var lastStartId=0
    private var referenceCapture=true
    private var captureScope=CaptureScope.DISPLAY
    @Volatile private var contentGeometry=CaptureContentGeometry(CaptureScope.DISPLAY)
    private val lifecycle=CaptureLifecycle()
    private val closed get()=lifecycle.closed
    private val session get()=lifecycle.generation
    private val sessionGuard=Any()
    @Volatile private var capturedVisible=true
    @Volatile private var requestedVisible=true
    @Volatile private var visibilityEpoch=0L
    @Volatile private var recognizer: FrameRecognizer = AwaitingValidatedAdapter()
    private val main = Handler(Looper.getMainLooper())
    private var callback: MediaProjection.Callback? = null
    private var taskSurfaceRefresh: Runnable?=null
    private var taskSurfaceRecoveryId=0
    @Volatile private var taskSurfaceAwaitingFrame=0
    private fun projectionCallback(generation: Long) = object : MediaProjection.Callback() {
        override fun onStop() { shutdown("屏幕共享已停止，请重新开始记牌",generation) }
        override fun onCapturedContentResize(width: Int, height: Int) { if(generation==session) resize(width,height) }
        override fun onCapturedContentVisibilityChanged(visible: Boolean) {
            synchronized(sessionGuard) {
                if(generation!=session || closed || visible==requestedVisible) return
                requestedVisible=visible
                capturedVisible=false; visibilityEpoch++
                android.util.Log.i("JPQCapture","content_visible=$visible epoch=$visibilityEpoch")
                if(visible) {
                    // Retire buffers queued while content was invisible before accepting
                    // a new visible epoch. Image timestamps need not share our clock.
                    restoreVisibility(generation,visibilityEpoch)
                }
                if(!visible && AppState.ledger.snapshot().remaining!=null) {
                    AppState.markCaptureUnobserved()
                    val currentRecognizer=recognizer
                    val lostAt=SystemClock.elapsedRealtime()
                    worker?.post {
                        synchronized(sessionGuard) {
                            if(generation==session && !closed && recognizer===currentRecognizer) {
                                currentRecognizer.observationGap(lostAt,"正在重新核对牌面，恢复后继续记牌")
                                AppState.captureGapCheckpointed()
                            }
                        }
                    }
                }
            }
        }
    }
    override fun onBind(intent: Intent?) = null
    private fun restoreVisibility(generation: Long,restoringEpoch: Long) {
        val capture=captureWorker ?: return
        val source=reader ?: return
        val health=pipelineHealth?.takeIf { it.generation==generation }
        capture.handler.post {
            if(generation!=session || closed || !requestedVisible || restoringEpoch!=visibilityEpoch ||
                capture!==captureWorker || source!==reader) return@post
            while(true) {
                val stale=try { source.acquireLatestImage().also { health?.imageAcquired(it!=null) } } catch(error: Exception) {
                    health?.acquireFailed(error); null
                } ?: break
                stale.close()
            }
            synchronized(sessionGuard) {
                if(generation==session && !closed && requestedVisible && restoringEpoch==visibilityEpoch &&
                    capture===captureWorker && source===reader) {
                    visibilityEpoch++; capturedVisible=true
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId=startId
        if (intent?.action == "STOP") {
            shutdown("记牌已停止")
            // A duplicate STOP may arrive after this session was already retired.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == "CONFIG") {
            val config=ConfigStore(this).load()
            android.util.Log.i("JPQCapture","display_config history=${config.showHistory} reset=${config.showReset} doubleTap=${config.doubleTapHide}")
            overlay?.refresh()
            if(projection==null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if(intent?.action=="LAB_REFRESH_TASK_SURFACE") {
            // Explicit emulator experiment only. Never reset a real match or the
            // stable display capture in response to this diagnostic action.
            if(BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE && captureScope==CaptureScope.APP_TASK &&
                AppState.ledger.snapshot().roundId==null &&
                listOf(Build.FINGERPRINT,Build.MODEL,Build.PRODUCT).any {
                    it.contains("emulator",true) || it.contains("sdk_gphone",true) || it.contains("generic",true)
                }) refreshTaskSurface()
            if(projection==null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY }
        AppState.captureGapCheckpointed()
        capturedVisible=true; requestedVisible=true; visibilityEpoch=0L
        taskSurfaceRecoveryId=0; taskSurfaceAwaitingFrame=0
        referenceCapture=getSharedPreferences("jpq-runtime",MODE_PRIVATE).getString("captureProfile","reference")!="adaptive_scaled"
        val generation=lifecycle.begin()
        android.util.Log.i("JPQCapture","session_start generation=$generation")
        lastFrame=0L; lastFrameError=0L
        AppState.recognitionReady=false
        try {
            val requestedScope=intent.getStringExtra(ProjectionConsentRequest.SCOPE_EXTRA) ?: CaptureScope.DISPLAY.name
            captureScope=CaptureScope.valueOf(requestedScope)
            check(CaptureInputPolicy.acceptsScope(captureScope,BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE,Build.VERSION.SDK_INT))
            check(!BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE || captureScope==CaptureScope.APP_TASK) {
                "Candidate capture must use explicit app-only consent"
            }
            AppState.captureScope=captureScope
            contentGeometry=CaptureContentGeometry(captureScope)
            android.util.Log.i("JPQCapture","capture_scope=${captureScope.name} package=$packageName")
            val data=if(Build.VERSION.SDK_INT>=33) intent.getParcelableExtra("data",Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("data")
            require(intent.getIntExtra("code",Activity.RESULT_CANCELED)==Activity.RESULT_OK && data!=null) { "Screen capture permission was not granted" }
            createForeground()
            visualContext=if(Build.VERSION.SDK_INT>=30) {
                val deviceDisplay=(getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY)
                require(deviceDisplay!=null) { "Main display unavailable" }
                createDisplayContext(deviceDisplay).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null)
            } else this
            val visionWorker=VisionRuntime.handler
            worker=visionWorker
            captureWorker=CaptureWorker(applicationContext)
            val health=CapturePipelineHealth(applicationContext,generation).also { pipelineHealth=it }
            startHealthReports(generation,health)
            dispatcher=LatestFrameDispatcher({ visionWorker.post(it) },{ frame ->
                health.consumeStarted()
                try {
                    if(generation==session && !closed && capturedVisible && frame.visibilityEpoch==visibilityEpoch && AppState.recognitionReady) {
                        recognizer.observe(frame)
                        if(generation==session && !closed) {
                            AppState.capturedFrames++
                            if(AppState.capturedFrames % 8L==0L) AppState.notifyChanged()
                        }
                    } else {
                        health.visionStage("discarded_gate")
                    }
                } finally { health.consumeEnded() }
            },{ error -> frameFailed(generation,error) })
            visionWorker.post {
                if(generation==session && !closed) try {
                    // Old session observations/close must finish before clearing its ledger.
                    AppState.ledger.waitForNext()
                    health.visionStage("recognizer_init_start")
                    val prepared=TencentRecognizer(this,{ generation==session && !closed },
                        { visibilityEpoch },sessionGuard,health::visionStage,{ capturedVisible },{
                            main.post {
                                if(generation==session && !closed && BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE &&
                                    captureScope==CaptureScope.APP_TASK && AppState.ledger.snapshot().roundId==null)
                                    refreshTaskSurface()
                            }
                        })
                    health.visionStage("recognizer_init_end")
                    // Publish on main, serialized with shutdown. A stopped initialization
                    // must never overwrite the next session or escape its close chain.
                    main.post {
                        if(generation!=session || closed) {
                            VisionRuntime.handler.post { prepared.close() }
                        } else {
                            recognizer=prepared
                            AppState.recognitionReady=true; AppState.notifyChanged()
                            android.util.Log.i("JPQCapture","local_recognizer_ready")
                        }
                    }
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
    /** Independent metadata reporting; this observer never changes capture or the round. */
    private fun startHealthReports(generation: Long,health: CapturePipelineHealth) {
        healthTick?.let { main.removeCallbacks(it) }
        val task=object : Runnable {
            override fun run() {
                if(generation!=session || closed || health!==pipelineHealth) return
                health.report(AppState.sourceWidth,AppState.sourceHeight,AppState.capturedWidth,AppState.capturedHeight,
                    capturedVisible,requestedVisible,AppState.recognitionReady,visibilityEpoch,
                    captureWorker?.handler?.looper?.thread,worker?.looper?.thread)
                if(generation==session && !closed && health===pipelineHealth) main.postDelayed(this,2500L)
            }
        }
        healthTick=task
        main.postDelayed(task,2500L)
    }
    private fun initializationFailed(generation: Long,error: Throwable) {
        android.util.Log.e("JPQCapture","recognizer_init_failed",error)
        main.post { if(generation==session && !closed) shutdown("本地识别未能准备好，请重新开始") }
    }
    private fun frameFailed(generation: Long,error: Exception) {
        val now=SystemClock.elapsedRealtime()
        if(now-lastFrameError>5000) { lastFrameError=now; android.util.Log.e("JPQCapture","frame_failed",error) }
        if(generation==session && !closed) {
            AppState.markCaptureUnobserved()
            val currentRecognizer=recognizer
            worker?.post {
                synchronized(sessionGuard) {
                    if(generation==session && !closed && recognizer===currentRecognizer) {
                        currentRecognizer.observationGap(now,"识别暂时中断，正在重新核对牌面")
                        AppState.captureGapCheckpointed()
                    }
                }
            }
        }
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
        // Match the reference APK: one fixed landscape canvas at physical resolution.
        // Portrait/landscape source transitions are composed by MediaProjection;
        // resizing and letterboxing for inference happen after capture.
        val dimensions=CaptureInputPolicy.dimensions(width,height,captureScope,referenceCapture)
        val outputWidth=dimensions.width
        val outputHeight=dimensions.height
        AppState.capturedWidth=outputWidth; AppState.capturedHeight=outputHeight
        android.util.Log.i("JPQCapture","source_size=${width}x$height capture_size=${outputWidth}x$outputHeight profile=${if(referenceCapture) "reference" else "adaptive_scaled"}")
        val capture=checkNotNull(captureWorker)
        val delivery=checkNotNull(dispatcher)
        val health=pipelineHealth?.takeIf { it.generation==generation }
        return ImageReader.newInstance(outputWidth,outputHeight,PixelFormat.RGBA_8888,3).also { next ->
            next.setOnImageAvailableListener({ source ->
                val epoch=visibilityEpoch
                health?.imageCallback()
                val image=try { source.acquireLatestImage().also { health?.imageAcquired(it!=null) } }
                    catch(error: Exception) {
                        health?.acquireFailed(error)
                        return@setOnImageAvailableListener
                    } ?: return@setOnImageAvailableListener
                try {
                    val now=SystemClock.elapsedRealtime()
                    if(now-lastFrame>=33 && generation==session && source===reader && !closed && capturedVisible && epoch==visibilityEpoch &&
                        contentGeometry.accepts(image.width,image.height) && AppState.recognitionReady) {
                        lastFrame=now
                        val recoveryId=taskSurfaceAwaitingFrame
                        if(recoveryId!=0) {
                            taskSurfaceAwaitingFrame=0
                            recognizer.startupCaptureSurface("first_frame",generation,recoveryId,epoch,image.timestamp)
                        }
                        val masks=if(!CaptureInputPolicy.usesScreenOcclusions(captureScope)) emptyList() else
                            listOfNotNull(AppState.barMask,AppState.adjustmentMask,AppState.gradeTipMask).map {
                            CaptureMask(android.graphics.Rect(it.rectangle),it.screenWidth,it.screenHeight)
                        }
                        health?.captureStage("copy_start")
                        val frame=capture.capture(image,AppState.sourceWidth,AppState.sourceHeight,
                            CaptureInputPolicy.pausesForOverlayInteraction(captureScope,AppState.adjusting),masks,now).also {
                            it.visibilityEpoch=epoch
                        }
                        health?.captureStage("copy_end")
                        health?.offered()
                        delivery.offer(frame)
                    } else {
                        health?.captureStage("discarded_gate")
                    }
                } catch (error: Exception) {
                    health?.captureStage("copy_failed")
                    frameFailed(generation,error)
                } finally {
                    image.close()
                    health?.captureStage("image_closed")
                }
            },capture.handler)
        }
    }
    private fun resize(width: Int,height: Int) {
        if(width<=0 || height<=0 || display==null || closed) return
        if(width==AppState.sourceWidth && height==AppState.sourceHeight) {
            contentGeometry.confirm(width,height); return
        }
        if(captureScope==CaptureScope.DISPLAY && referenceCapture && maxOf(width,height)==AppState.capturedWidth && minOf(width,height)==AppState.capturedHeight) {
            AppState.sourceWidth=width; AppState.sourceHeight=height
            android.util.Log.i("JPQCapture","source_orientation=${width}x$height fixed_capture=${AppState.capturedWidth}x${AppState.capturedHeight}")
            overlay?.refresh(); return
        }
        // Projection callbacks and teardown run on main. Retire the old reader only
        // after any image already being copied on the capture worker is released.
        var next: ImageReader?=null
        try {
            if(captureScope==CaptureScope.APP_TASK) {
                synchronized(sessionGuard) {
                    capturedVisible=false; visibilityEpoch++
                    if(AppState.ledger.snapshot().remaining!=null) {
                        AppState.markCaptureUnobserved()
                        val currentRecognizer=recognizer
                        val resizeGeneration=session
                        val lostAt=SystemClock.elapsedRealtime()
                        worker?.post {
                            synchronized(sessionGuard) {
                                if(resizeGeneration==session && !closed && recognizer===currentRecognizer) {
                                    currentRecognizer.observationGap(lostAt,"共享游戏画面尺寸变化，恢复后继续记牌")
                                    AppState.captureGapCheckpointed()
                                }
                            }
                        }
                    }
                }
            }
            val old=reader
            next=newReader(width,height,session)
            display!!.resize(AppState.capturedWidth,AppState.capturedHeight,resources.configuration.densityDpi)
            display!!.surface=next.surface; reader=next
            contentGeometry.confirm(width,height)
            old?.setOnImageAvailableListener(null,null)
            captureWorker?.handler?.post { old?.close() }
            if(requestedVisible && !capturedVisible) restoreVisibility(session,visibilityEpoch)
            overlay?.refresh()
        } catch (error: Exception) {
            if(next!==reader) next?.close()
            android.util.Log.e("JPQCapture","resize_failed",error)
            shutdown("画面尺寸变化，需重新开始记牌")
        }
    }
    /** Reattach a consumer to the existing projection; no token reuse or second display. */
    private fun refreshTaskSurface() {
        if(!BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE || captureScope!=CaptureScope.APP_TASK) return
        val currentDisplay=display ?: return
        val old=reader ?: return
        val capture=captureWorker ?: return
        if(closed || taskSurfaceRefresh!=null) return
        val generation=session
        synchronized(sessionGuard) {
            val round=AppState.ledger.snapshot()
            if(closed || round.roundId!=null || round.phase!=com.cyotun.jpq.core.RoundPhase.WAITING) return
            capturedVisible=false; visibilityEpoch++
        }
        val recoveryId=++taskSurfaceRecoveryId
        taskSurfaceAwaitingFrame=0
        try {
            currentDisplay.surface=null
            recognizer.startupCaptureSurface("detached",generation,recoveryId,visibilityEpoch)
            android.util.Log.i("JPQCapture","task_surface_detached generation=$generation")
            val restore=Runnable {
                taskSurfaceRefresh=null
                if(generation!=session || closed || display!==currentDisplay || reader!==old) return@Runnable
                try {
                    old.setOnImageAvailableListener(null,null)
                    capture.handler.post { old.close() }
                    val next=newReader(AppState.sourceWidth,AppState.sourceHeight,generation)
                    reader=next
                    currentDisplay.surface=next.surface
                    taskSurfaceAwaitingFrame=recoveryId
                    recognizer.startupCaptureSurface("reattached",generation,recoveryId,visibilityEpoch)
                    if(requestedVisible) restoreVisibility(generation,visibilityEpoch)
                    android.util.Log.i("JPQCapture","task_surface_reattached generation=$generation")
                } catch(error: Exception) {
                    android.util.Log.e("JPQCapture","task_surface_refresh_failed",error)
                    shutdown("采集检查未能恢复，请重新开始记牌",generation)
                }
            }
            taskSurfaceRefresh=restore
            // Give the system a separate composition traversal with no consumer.
            main.postDelayed(restore,350)
        } catch(error: Exception) {
            android.util.Log.e("JPQCapture","task_surface_detach_failed",error)
            shutdown("采集检查未能恢复，请重新开始记牌",generation)
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
    private fun shutdown(message: String,expectedGeneration: Long=session) {
        synchronized(sessionGuard) {
            if(!lifecycle.close(expectedGeneration)) return
        }
        android.util.Log.i("JPQCapture","session_stop reason=$message")
        taskSurfaceRefresh?.let { main.removeCallbacks(it) }; taskSurfaceRefresh=null
        taskSurfaceAwaitingFrame=0
        // Detach all ownership first. Neither a failed release nor an old callback
        // can retain these resources or reach a replacement session's resources.
        val previousProjection=projection; projection=null
        val previousCallback=callback; callback=null
        val previousDisplay=display; display=null
        val previousReader=reader; reader=null
        val previousCapture=captureWorker; captureWorker=null
        val previousDispatcher=dispatcher; dispatcher=null
        val previousOverlay=overlay; overlay=null
        val previousHealth=pipelineHealth; pipelineHealth=null
        val previousTick=healthTick; healthTick=null
        val previousRecognizer=recognizer
        recognizer=AwaitingValidatedAdapter()
        val previousWorker=worker; worker=null
        visualContext=null
        val cleanup=CaptureCleanup { name,error -> android.util.Log.e("JPQCapture","${name}_close_failed",error) }
        cleanup.release("projection_callback") { previousCallback?.let { previousProjection?.unregisterCallback(it) } }
        cleanup.release("display") { previousDisplay?.release() }
        cleanup.release("projection") { previousProjection?.stop() }
        cleanup.release("foreground") { stopForeground(STOP_FOREGROUND_REMOVE) }
        cleanup.release("notification") { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(11) }
        cleanup.release("reader_listener") { previousReader?.setOnImageAvailableListener(null,null) }
        cleanup.release("dispatcher") { previousDispatcher?.close() }
        // A capture callback may still be copying an image. Close its reader on
        // its own worker after that callback, even if another release failed.
        cleanup.release("capture_worker") {
            if(previousCapture==null) cleanup.release("reader") { previousReader?.close() }
            else previousCapture.closeAfter { cleanup.release("reader") { previousReader?.close() } }
        }
        cleanup.release("overlay") { previousOverlay?.close() }
        cleanup.release("health_tick") { previousTick?.let { main.removeCallbacks(it) } }
        cleanup.release("health") { previousHealth?.close() }
        cleanup.release("recognizer") {
            if(previousWorker!=null) previousWorker.post {
                cleanup.release("recognizer_worker") { previousRecognizer.close() }
                cleanup.release("vision_trim") { VisionRuntime.trimWhenIdle() }
            }
        }
        cleanup.release("app_state") {
            AppState.running=false; AppState.recognitionReady=false; AppState.message=message
            AppState.captureScope=CaptureScope.DISPLAY
            if(AppState.ledger.snapshot().remaining!=null) AppState.ledger.invalidate(message)
            AppState.captureGapCheckpointed()
            AppState.publishStraightFlush(com.cyotun.jpq.core.StraightFlushResult(StraightFlushState.UNKNOWN),false)
            AppState.publishCounterState(com.cyotun.jpq.core.GradeCounterState())
            AppState.gradeRegion=null; AppState.gradeTipMask=null
            AppState.resetRemainingPool()
            AppState.notifyChanged()
        }
        cleanup.release("service") { if(lastStartId>0) stopSelf(lastStartId) else stopSelf() }
        cleanup.release("update_schedule") { com.cyotun.jpq.updates.UpdateManager.scheduleDownload(this) }
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Home/Back and moveTaskToBack keep foreground capture running. Swiping
        // this app out of Recents is an explicit close and must stop sharing.
        shutdown("记牌器已关闭，屏幕共享已停止")
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() { shutdown("记牌已停止"); super.onDestroy() }
}
