package com.cyotun.jpq.vision

import android.graphics.Bitmap
import com.cyotun.jpq.core.GlyphBox
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Bitmap belongs exclusively to one inference until close returns it to capture's bounded pool. */
class CapturedFrame(val bitmap: Bitmap?,val width: Int,val height: Int,
                    val sourceWidth: Int,val sourceHeight: Int,val adjusting: Boolean,
                    val imageTimestampNs: Long,val capturedElapsedMs: Long,val preparedElapsedMs: Long,
                    val masks: List<GlyphBox>,val probeFolder: File?,val completeDisplay: Boolean,
                    private val release: (Bitmap)->Unit): AutoCloseable {
    var visibilityEpoch=0L
        internal set
    private val released=AtomicBoolean(false)
    override fun close() { if(released.compareAndSet(false,true)) bitmap?.let(release) }
}

interface FrameRecognizer : AutoCloseable {
    val ready: Boolean
    /** The dispatcher owns the frame and releases it after this call. No Image crosses threads. */
    fun observe(captured: CapturedFrame)
    /** Delivered on the vision owner thread; captures already queued in the old epoch are retired. */
    fun observationGap(timeMs: Long,reason: String) = Unit
    override fun close()
}

/** Safe boundary until Tencent game assets and whole-round recognition pass. */
class AwaitingValidatedAdapter : FrameRecognizer {
    override val ready = false
    override fun observe(captured: CapturedFrame) = Unit
    override fun close() = Unit
}
