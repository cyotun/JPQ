package com.cyotun.jpq.core

/**
 * A capture fault must reach its recognizer before a queued latest-frame drain can
 * accept replacement pixels. Call every method under the capture session guard.
 * Both the posted fault callback and frame consumer take the same pending record.
 */
class CaptureGapFence<T : Any> {
    data class Gap(val timeMs: Long,val reason: String,val inputDiscontinuity: Boolean)

    private class Pending<T>(val generation: Long,val owner: T,val gap: Gap)
    private var pending: Pending<T>?=null
    private class SourceCutoff<T>(val generation: Long,val owner: T,val timeMs: Long)
    private var sourceCutoff: SourceCutoff<T>?=null

    fun mark(generation: Long,owner: T,timeMs: Long,reason: String,inputDiscontinuity: Boolean) {
        val previous=pending?.takeIf { it.generation==generation && it.owner===owner }?.gap
        pending=Pending(generation,owner,Gap(previous?.let { minOf(it.timeMs,timeMs) } ?: timeMs,
            reason,inputDiscontinuity || previous?.inputDiscontinuity==true))
        val cutoff=sourceCutoff?.takeIf { it.generation==generation && it.owner===owner }?.timeMs
        sourceCutoff=SourceCutoff(generation,owner,cutoff?.let { maxOf(it,timeMs) } ?: timeMs)
    }

    fun take(generation: Long,owner: T): Gap? {
        val current=pending ?: return null
        if(current.generation!=generation || current.owner!==owner) return null
        pending=null
        return current.gap
    }

    /** Keep rejecting pre-fault pixels after the pending checkpoint has been consumed. */
    fun acceptsSource(generation: Long,owner: T,capturedTimeMs: Long): Boolean {
        val cutoff=sourceCutoff?.takeIf { it.generation==generation && it.owner===owner } ?: return true
        return capturedTimeMs>cutoff.timeMs
    }

    fun clear() { pending=null; sourceCutoff=null }

    companion object {
        /** Recovery hides its pool but still owns a round that a real input break must fence. */
        fun hasTrackedRound(phase: RoundPhase,roundId: String?): Boolean =
            !roundId.isNullOrBlank() && phase in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED,RoundPhase.RECOVERING)
    }
}
