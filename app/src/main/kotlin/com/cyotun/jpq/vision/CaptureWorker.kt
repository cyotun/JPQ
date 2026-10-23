package com.cyotun.jpq.vision

import android.content.Context
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.cyotun.jpq.CaptureMask

/** A session owns its capture thread, converter and bitmap pool independently of the model worker. */
class CaptureWorker(private val context: Context) {
    private val lease=com.cyotun.jpq.EvidenceWork.writer()
    private val thread=HandlerThread("jpq-capture",android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    val handler=Handler(thread.looper)
    private var producer: CaptureFrameProducer?=null

    fun capture(image: Image,sourceWidth: Int,sourceHeight: Int,adjusting: Boolean,
                masks: List<CaptureMask>,timeMs: Long): CapturedFrame {
        check(Looper.myLooper()==handler.looper)
        val active=producer ?: CaptureFrameProducer(context).also { producer=it }
        return active.capture(image,sourceWidth,sourceHeight,adjusting,masks,timeMs)
    }
    fun closeAfter(retireReader: ()->Unit) {
        handler.post {
            try { retireReader() } finally {
                try { producer?.close(); producer=null } finally { lease.close(); thread.quitSafely() }
            }
        }
    }
}
