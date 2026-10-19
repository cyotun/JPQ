package com.cyotun.jpq

import android.os.Handler
import android.os.Looper
import com.cyotun.jpq.core.*
import android.graphics.Rect
import java.util.concurrent.atomic.AtomicBoolean

data class CaptureMask(val rectangle: Rect,val screenWidth: Int,val screenHeight: Int)

object AppState {
    @Volatile private var captureGapPending=false
    private val roundPresentationGuard=Any()
    private val roundPresentation=ConfirmedRoundPresentation()
    /** Freeze the confirmed display before the queued evidence gap is processed. */
    fun markCaptureUnobserved() { displayFrame(); captureGapPending=true; notifyChanged() }
    fun captureGapCheckpointed() { captureGapPending=false; notifyChanged() }
    private fun displayState(snapshot: RoundSnapshot): RoundDisplayState = synchronized(roundPresentationGuard) {
        val pool=remainingPoolFor(snapshot)
        val projected=remainingOutsidePool(snapshot,pool)
        roundPresentation.accept(snapshot,projected,captureGapPending,running,!running || recognitionReady,pool!=null)
    }
    /** One read supplies both history and numbers for the entire overlay draw. */
    fun displayFrame(): RoundDisplayState = synchronized(roundPresentationGuard) { displayState(ledger.snapshot()) }
    fun displaySnapshot(snapshot: RoundSnapshot?=null): RoundSnapshot =
        if(snapshot==null) displayFrame().snapshot else displayState(snapshot).snapshot
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
    fun displayRemaining(snapshot: RoundSnapshot): Map<Rank,Int>? {
        // Rendered snapshots may carry the display-only RECOVERING phase.
        // Feeding that phase into the teammate tracker would erase its votes.
        val state=if(running) displayFrame() else displayState(snapshot)
        return state.remaining.takeIf { state.snapshot.roundId==snapshot.roundId }
    }
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
    private val counterGuard=Any()
    private val counterPresentation=GradeCounterPresentation()
    @Volatile var displayCounterState=GradeCounterState()
        private set
    fun publishCounterState(value: GradeCounterState) {
        val changed=synchronized(counterGuard) {
            val display=counterPresentation.accept(value)
            val different=counterState!=value || displayCounterState!=display
            counterState=value; displayCounterState=display
            different
        }
        if(changed) notifyChanged()
    }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<() -> Unit>()
    private val notificationPending=AtomicBoolean(false)
    @Synchronized fun beginOverlayOcclusion(owner: Any) { overlayOcclusions.add(owner); adjusting=overlayOcclusions.isNotEmpty() }
    @Synchronized fun endOverlayOcclusion(owner: Any) { overlayOcclusions.remove(owner); adjusting=overlayOcclusions.isNotEmpty() }
    fun subscribe(listener: () -> Unit) { listeners.add(listener) }
    fun unsubscribe(listener: () -> Unit) { listeners.remove(listener) }
    fun notifyChanged() {
        // Publish each confirmed ledger/pool transaction even if the overlay is
        // currently hidden. UI redraw timing cannot decide what was confirmed.
        if(running) displayFrame() else synchronized(roundPresentationGuard) {
            roundPresentation.accept(ledger.snapshot(),null,running=false)
        }
        if(notificationPending.compareAndSet(false,true)) main.post {
            notificationPending.set(false)
            listeners.toList().forEach { it() }
        }
    }
}
