package com.cyotun.jpq.vision

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock

/** The model is prepared before consent and used on the same worker, including GPU calls. */
object VisionRuntime {
    private val thread=HandlerThread("jpq-vision").apply { start() }
    val handler=Handler(thread.looper)
    private var detector: LocalCardDetector?=null
    private var power: android.os.PowerManager?=null
    private const val idleDelayMs=120000L
    private var lastAccess=0L
    private var trimGeneration=0L
    private var trimTask: Runnable?=null
    fun warm(context: Context) { handler.post { runCatching { getDetector(context.applicationContext) }.onFailure { android.util.Log.e("JPQCapture","warmup_failed",it) } } }
    fun getDetector(context: Context): LocalCardDetector {
        check(android.os.Looper.myLooper()==handler.looper)
        power=context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val prepared=detector ?: run {
            val start=SystemClock.elapsedRealtime()
            LocalCardDetector(context.applicationContext).also {
                detector=it
                android.util.Log.i("JPQCapture","model_ready backend=${it.backend} initMs=${SystemClock.elapsedRealtime()-start}")
            }
        }
        lastAccess=SystemClock.elapsedRealtime()
        scheduleTrim(idleDelayMs)
        return prepared
    }
    fun trimWhenIdle() {
        if(Looper.myLooper()!=handler.looper) { handler.post { trimWhenIdle() }; return }
        lastAccess=SystemClock.elapsedRealtime()
        scheduleTrim(idleDelayMs)
    }
    private fun scheduleTrim(delay: Long) {
        trimTask?.let { handler.removeCallbacks(it) }
        val generation=++trimGeneration
        val task=Runnable {
            if(generation!=trimGeneration) return@Runnable
            trimTask=null
            if(com.cyotun.jpq.AppState.running) return@Runnable
            val remaining=idleDelayMs-(SystemClock.elapsedRealtime()-lastAccess)
            if(remaining>0) { scheduleTrim(remaining); return@Runnable }
            if(detector?.backend=="GPU" && power?.isInteractive!=true) { scheduleTrim(idleDelayMs); return@Runnable }
            val expired=detector; detector=null
            runCatching { expired?.close() }.onFailure { android.util.Log.e("JPQCapture","idle_model_close_failed",it) }
        }
        trimTask=task
        handler.postDelayed(task,delay)
    }
}
