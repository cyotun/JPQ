package com.cyotun.jpq

import android.os.Handler
import android.os.Looper
import com.cyotun.jpq.core.*
import android.graphics.Rect
import java.util.concurrent.atomic.AtomicBoolean

data class CaptureMask(val rectangle: Rect,val screenWidth: Int,val screenHeight: Int)

object AppState {
    @Volatile var adjusting=false
        private set
    private val overlayOcclusions=linkedSetOf<Any>()
    @Volatile var barMask: CaptureMask?=null
    @Volatile var adjustmentMask: CaptureMask?=null
    @Volatile var gradeTipMask: CaptureMask?=null
    /** Full-display normalized region, independent of the reduced capture resolution. */
    @Volatile var gradeRegion: GlyphBox?=null
    val ledger = RoundLedger()
    @Volatile var running = false
    @Volatile var capturedFrames = 0L
    @Volatile var capturedWidth = 0
    @Volatile var capturedHeight = 0
    @Volatile var sourceWidth = 0
    @Volatile var sourceHeight = 0
    @Volatile var message = "等待开始记牌"
    @Volatile var recognitionReady = false
    @Volatile var straightFlushState = StraightFlushState.UNKNOWN
    @Volatile var straightFlushIncludesWildcards = false
    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<() -> Unit>()
    private val notificationPending=AtomicBoolean(false)
    @Synchronized fun beginOverlayOcclusion(owner: Any) { overlayOcclusions.add(owner); adjusting=overlayOcclusions.isNotEmpty() }
    @Synchronized fun endOverlayOcclusion(owner: Any) { overlayOcclusions.remove(owner); adjusting=overlayOcclusions.isNotEmpty() }
    fun subscribe(listener: () -> Unit) { listeners.add(listener) }
    fun unsubscribe(listener: () -> Unit) { listeners.remove(listener) }
    fun notifyChanged() {
        if(notificationPending.compareAndSet(false,true)) main.post {
            notificationPending.set(false)
            listeners.toList().forEach { it() }
        }
    }
}
