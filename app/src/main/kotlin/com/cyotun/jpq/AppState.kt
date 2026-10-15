package com.cyotun.jpq

import android.os.Handler
import android.os.Looper
import com.cyotun.jpq.core.*
import android.graphics.Rect
import java.util.concurrent.atomic.AtomicBoolean

data class CaptureMask(val rectangle: Rect,val screenWidth: Int,val screenHeight: Int)

object AppState {
    @Volatile private var captureGapPending=false
    /** The UI must stop presenting exact counts before a queued vision gap is processed. */
    fun markCaptureUnobserved() { captureGapPending=true; notifyChanged() }
    fun captureGapCheckpointed() { captureGapPending=false; notifyChanged() }
    fun displaySnapshot(snapshot: RoundSnapshot=ledger.snapshot()): RoundSnapshot = when {
        running && !recognitionReady -> snapshot.copy(phase=RoundPhase.WAITING,remaining=null,reason=null)
        captureGapPending && snapshot.remaining!=null -> snapshot.copy(phase=RoundPhase.RECOVERING,
            remaining=null,reason="正在重新核对牌面")
        else -> snapshot
    }
    @Volatile var adjusting=false
        private set
    private val overlayOcclusions=linkedSetOf<Any>()
    @Volatile var barMask: CaptureMask?=null
    @Volatile var adjustmentMask: CaptureMask?=null
    @Volatile var gradeTipMask: CaptureMask?=null
    /** Full-display normalized region, independent of the reduced capture resolution. */
    @Volatile var gradeRegion: GlyphBox?=null
    private val remainingPoolGuard=Any()
    private val remainingPoolTracker=RemainingPoolTracker()
    @Volatile private var remainingPoolEpoch=0L
    fun observeTeammatePool(time: Long,epoch: Long,snapshot: RoundSnapshot,hand: List<PlayGlyph>?): Boolean {
        val changed=synchronized(remainingPoolGuard) {
            if(epoch<remainingPoolEpoch) return@synchronized false
            val before=remainingPoolTracker.projection(snapshot,remainingPoolEpoch)
            remainingPoolEpoch=epoch
            val after=remainingPoolTracker.observe(time,epoch,snapshot,hand?.map { it.card })
            before!=after
        }
        if(changed) notifyChanged()
        return changed
    }
    fun remainingPoolFor(snapshot: RoundSnapshot,epoch: Long=remainingPoolEpoch): RemainingPoolExclusion? =
        synchronized(remainingPoolGuard) {
            // Only an observed frame may advance the epoch. In particular, a
            // retired suit-analysis read after stop cannot poison a new session.
            if(epoch!=remainingPoolEpoch) null else remainingPoolTracker.projection(snapshot,epoch)
        }
    fun displayRemaining(snapshot: RoundSnapshot): Map<Rank,Int>? =
        if(captureGapPending) null else remainingOutsidePool(snapshot,remainingPoolFor(snapshot))
    fun resetRemainingPool() = synchronized(remainingPoolGuard) { remainingPoolTracker.reset(); remainingPoolEpoch=0L }
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
    @Volatile var straightFlushCandidateRanks = Rank.entries.filterNot { it.isJoker }.toSet()
        private set
    fun publishStraightFlush(result: StraightFlushResult,includesWildcards: Boolean) {
        if(straightFlushState!=result.state || straightFlushIncludesWildcards!=includesWildcards ||
            straightFlushCandidateRanks!=result.candidateRanks) {
            straightFlushState=result.state
            straightFlushIncludesWildcards=includesWildcards
            straightFlushCandidateRanks=result.candidateRanks.toSet()
            notifyChanged()
        }
    }
    @Volatile var counterState=GradeCounterState()
        private set
    fun publishCounterState(value: GradeCounterState) {
        if(counterState!=value) { counterState=value; notifyChanged() }
    }
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
