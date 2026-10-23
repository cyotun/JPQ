package com.cyotun.jpq.vision

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Session-local metadata only. The main thread can report even while a worker is stuck. */
class CapturePipelineHealth(context: Context, val generation: Long) : AutoCloseable {
    private val enabled=context.getSharedPreferences("jpq-diagnostics",Context.MODE_PRIVATE).getBoolean("enabled",false)
    private val id="${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}"
    private val started=SystemClock.elapsedRealtime()
    private val healthFile=java.io.File(context.filesDir,"sessions/capture-$id/capture-health.jsonl")
    private val io=if(enabled) com.cyotun.jpq.EvidenceExecutor("jpq-capture-health") else null
    private val writePending=java.util.concurrent.atomic.AtomicBoolean(false)
    private var closed=false
    private var callbacks=0L
    private var acquired=0L
    private var acquireEmpty=0L
    private var acquireFailures=0L
    private var offers=0L
    private var consumeStarts=0L
    private var consumeEnds=0L
    private var lastCallback=0L
    private var lastAcquire=0L
    private var lastOffer=0L
    private var lastConsumeStart=0L
    private var lastConsumeEnd=0L
    private var lastDetectStart=0L
    private var lastDetectEnd=0L
    private var nullBitmapFrames=0L
    private var lastNullBitmap=0L
    private var lastAcquireWarning=0L
    private var lastStack=0L
    private var captureStage="awaiting_image"
    private var captureStageSince=started
    private var visionStage="awaiting_frame"
    private var visionStageSince=started
    private var lastWorkStage="none"
    private var lastWorkStageSince=started
    private var acquireError: String?=null

    @Synchronized fun imageCallback() {
        if(closed) return
        callbacks++; lastCallback=SystemClock.elapsedRealtime()
        captureStage="acquire_start"; captureStageSince=lastCallback
    }
    @Synchronized fun imageAcquired(present: Boolean) {
        if(closed) return
        val now=SystemClock.elapsedRealtime()
        if(present) { acquired++; lastAcquire=now } else acquireEmpty++
        captureStage=if(present) "acquired" else "acquire_empty"; captureStageSince=now
    }
    /** Acquisition failures are observable without changing the ledger or capture lifecycle. */
    fun acquireFailed(error: Exception) {
        val warning=synchronized(this) {
            if(closed) return
            val now=SystemClock.elapsedRealtime()
            acquireFailures++; acquireError=error.javaClass.simpleName
            captureStage="acquire_failed"; captureStageSince=now
            if(lastAcquireWarning==0L || now-lastAcquireWarning>=5000L) {
                lastAcquireWarning=now
                "image_acquire_failed generation=$generation healthId=$id failures=$acquireFailures"
            } else null
        }
        if(warning!=null) Log.w("JPQCapture",warning,error)
    }
    @Synchronized fun captureStage(value: String) {
        if(closed) return
        captureStage=value; captureStageSince=SystemClock.elapsedRealtime()
    }
    @Synchronized fun offered() {
        if(closed) return
        offers++; lastOffer=SystemClock.elapsedRealtime()
        captureStage="offered"; captureStageSince=lastOffer
    }
    @Synchronized fun consumeStarted() {
        if(closed) return
        consumeStarts++; lastConsumeStart=SystemClock.elapsedRealtime()
        visionStage="consume_start"; visionStageSince=lastConsumeStart
    }
    @Synchronized fun consumeEnded() {
        if(closed) return
        consumeEnds++; lastConsumeEnd=SystemClock.elapsedRealtime()
        visionStage="consume_end"; visionStageSince=lastConsumeEnd
    }
    @Synchronized fun visionStage(value: String) {
        if(closed) return
        val now=SystemClock.elapsedRealtime()
        visionStage=value; visionStageSince=now
        lastWorkStage=value; lastWorkStageSince=now
        if(value=="detect_start") lastDetectStart=now
        if(value=="detect_end") lastDetectEnd=now
        if(value=="null_bitmap") { nullBitmapFrames++; lastNullBitmap=now }
    }

    /** Called independently from main. No worker locks, pixels, OCR text or ledger access. */
    fun report(sourceWidth: Int,sourceHeight: Int,capturedWidth: Int,capturedHeight: Int,
               capturedVisible: Boolean,requestedVisible: Boolean,recognitionReady: Boolean,
               visibilityEpoch: Long,captureThread: Thread?,visionThread: Thread?) {
        if(!enabled) return
        val now=SystemClock.elapsedRealtime()
        val record: JSONObject
        val includeStacks: Boolean
        synchronized(this) {
            if(closed) return
            val captureBusy=captureStage in setOf("acquire_start","copy_start") && now-captureStageSince>=5000L
            val visionBusy=(consumeStarts>consumeEnds && now-lastConsumeStart>=5000L) ||
                (visionStage=="recognizer_init_start" && now-visionStageSince>=5000L)
            val supplyGap=recognitionReady && capturedVisible && now-(lastCallback.takeIf { it>0 } ?: started)>=5000L
            val consumeGap=offers>0 && now-(lastConsumeEnd.takeIf { it>0 } ?: started)>=5000L
            includeStacks=(captureBusy || visionBusy || supplyGap || consumeGap) &&
                (lastStack==0L || now-lastStack>=30000L)
            if(includeStacks) lastStack=now
            record=JSONObject().put("type","capture_health").put("healthId",id)
                .put("generation",generation).put("elapsedMs",now).put("startedMs",started)
                .put("sourceWidth",sourceWidth).put("sourceHeight",sourceHeight)
                .put("captureWidth",capturedWidth).put("captureHeight",capturedHeight)
                .put("capturedVisible",capturedVisible).put("requestedVisible",requestedVisible)
                .put("recognitionReady",recognitionReady).put("visibilityEpoch",visibilityEpoch)
                .put("callbacks",callbacks).put("acquired",acquired).put("acquireEmpty",acquireEmpty)
                .put("acquireFailures",acquireFailures).put("acquireError",acquireError ?: JSONObject.NULL)
                .put("offers",offers).put("consumeStarts",consumeStarts).put("consumeEnds",consumeEnds)
                .put("lastCallbackMs",lastCallback).put("lastAcquireMs",lastAcquire).put("lastOfferMs",lastOffer)
                .put("lastConsumeStartMs",lastConsumeStart).put("lastConsumeEndMs",lastConsumeEnd)
                .put("lastDetectStartMs",lastDetectStart).put("lastDetectEndMs",lastDetectEnd)
                .put("nullBitmapFrames",nullBitmapFrames).put("lastNullBitmapMs",lastNullBitmap)
                .put("captureStage",captureStage).put("captureStageSinceMs",captureStageSince)
                .put("visionStage",visionStage).put("visionStageSinceMs",visionStageSince)
                .put("lastWorkStage",lastWorkStage).put("lastWorkStageSinceMs",lastWorkStageSince)
                .put("captureThreadAlive",captureThread?.isAlive ?: false)
                .put("visionThreadAlive",visionThread?.isAlive ?: false)
                .put("captureBusy",captureBusy).put("visionBusy",visionBusy)
                .put("supplyGap",supplyGap).put("consumeGap",consumeGap)
        }
        record.put("wallTimeMs",System.currentTimeMillis())
        val stacks=if(includeStacks) listOfNotNull(captureThread,visionThread).distinct().map { thread ->
            val stack=runCatching { thread.stackTrace.take(12).map { it.toString() } }.getOrDefault(emptyList())
            JSONObject().put("thread",thread.name).put("state",thread.state.name).put("stack",JSONArray(stack))
        } else emptyList()
        record.put("stacks",JSONArray(stacks))
        Log.i("JPQCapture",record.toString())
        persist(record.toString())
    }

    /** Metadata also reaches the existing local ZIP; no phone connection is needed to retain it. */
    private fun persist(value: String) {
        val writer=io ?: return
        if(!writePending.compareAndSet(false,true)) return
        try {
            writer.execute {
                try {
                    healthFile.parentFile!!.mkdirs()
                    if(healthFile.length()>4L*1024*1024) {
                        val previous=java.io.File(healthFile.parentFile,"capture-health.previous.jsonl")
                        previous.delete(); healthFile.renameTo(previous)
                    }
                    healthFile.appendText(value+"\n",Charsets.UTF_8)
                } catch(_: Exception) { Log.w("JPQCapture","local_health_metadata_unavailable") }
                finally { writePending.set(false) }
            }
        } catch(_: java.util.concurrent.RejectedExecutionException) { writePending.set(false) }
    }

    @Synchronized override fun close() { closed=true; io?.shutdown() }
}
