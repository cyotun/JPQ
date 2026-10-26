package com.cyotun.jpq.core

/**
 * Permits a bounded consumer-surface repair after a real task visibility/geometry break.
 * This is an input repair only: it never repairs, clears or supplies evidence to a ledger.
 * Keep one instance per capture session, and serialize calls with the capture session guard.
 * Reattaching the existing projection cannot select a different task.
 */
class TaskResumeRecoveryPolicy {
    enum class Content {
        PORTRAIT_OR_NULL,
        UNKNOWN_EMPTY,
        TABLE_EMPTY,
        /** Direct hand/play evidence, or a recognised lobby/result/dealing/tribute scene. */
        GAME_CONTENT,
        /** Partial/adjusting input or another inconclusive transition. */
        OTHER,
    }

    /** Pass this single-use token to [takeRequest] immediately before the queued Android repair. */
    @ConsistentCopyVisibility
    data class Request internal constructor(
        val sequence: Int,
        val visibilityEpoch: Long,
        val roundId: String,
        val issuedAtMs: Long,
    )

    private data class Episode(
        val startedAtMs: Long,
        val roundId: String,
        var minimumEpoch: Long,
        var sourceFenceMs: Long,
        var emptySinceMs: Long? = null,
        var emptySamples: Int = 0,
    )

    private var episode: Episode? = null
    private var mayArm = true
    private var latestEpoch = -1L
    private var lastNowMs = -1L
    private var lastSourceTimeMs = -1L
    private var lastRequestTimeMs: Long? = null
    private var latestRequest: Request? = null
    private var requests = 0

    val exhausted: Boolean get() = requests >= MAX_REQUESTS

    /**
     * Call only for an actual visibility or capture-geometry discontinuity, AFTER the gap
     * has put an existing round into RECOVERING. Never call for scene/hand transitions.
     * The epoch must have advanced. Repeated callbacks retain the original deadline and
     * budget; a completed/expired episode cannot rearm until fresh game content is seen.
     * When rebinding from the first frame of an already-proven newer capture epoch,
     * pass that frame's original capture time as [sourceFenceMs], while [timeMs] remains
     * the current call time. The caller must retain the actual discontinuity proof.
     */
    fun onDiscontinuity(
        timeMs: Long,
        visibilityEpoch: Long,
        phase: RoundPhase,
        roundId: String?,
        sourceFenceMs: Long = timeMs,
    ): Boolean {
        if (sourceFenceMs < 0L || sourceFenceMs > timeMs) return false
        if (!advanceClock(timeMs) || visibilityEpoch <= latestEpoch) return false
        latestEpoch = visibilityEpoch
        latestRequest = null
        val active = episode
        if (active != null) {
            if (!eligible(active, timeMs, phase, roundId)) {
                closeEpisode()
                return false
            }
            active.minimumEpoch = visibilityEpoch
            active.sourceFenceMs = sourceFenceMs
            resetPersistence(active)
            return true
        }
        if (!mayArm || exhausted || phase != RoundPhase.RECOVERING || roundId.isNullOrBlank()) return false
        mayArm = false
        episode = Episode(timeMs, roundId, visibilityEpoch, sourceFenceMs)
        return true
    }

    /**
     * Use monotonic elapsed time for both times. [capturedTimeMs] is the original capture
     * time, never delivery/inference completion time. Even portrait/null early returns
     * must call this method. Old-epoch, pre-break and duplicate sources cannot vote or
     * cancel an episode. Slow inference may conservatively cancel on genuine content,
     * but can neither vote for a repair nor rearm the policy from a stale source.
     * GAME_CONTENT must be reported before accepting a genuine frame into the observer.
     */
    fun observe(
        timeMs: Long,
        capturedTimeMs: Long,
        visibilityEpoch: Long,
        phase: RoundPhase,
        roundId: String?,
        content: Content,
    ): Request? {
        if (!advanceClock(timeMs)) return null
        val active = episode
        if (active != null && !eligible(active, timeMs, phase, roundId)) closeEpisode()

        if (visibilityEpoch < latestEpoch || capturedTimeMs < 0L || capturedTimeMs > timeMs ||
            capturedTimeMs <= lastSourceTimeMs) return null
        // A newer epoch is accepted only after an explicit discontinuity callback.
        if (visibilityEpoch != latestEpoch) return null
        val current = episode
        if (current != null && capturedTimeMs < current.sourceFenceMs) return null
        lastSourceTimeMs = capturedTimeMs
        val fresh = timeMs - capturedTimeMs <= MAX_SOURCE_AGE_MS

        if (content == Content.GAME_CONTENT) {
            closeEpisode()
            if (fresh) mayArm = true
            return null
        }
        if (current == null) return null
        if (content == Content.OTHER) {
            // A partial composition frame cannot authorise a repair or erase
            // the original discontinuity. Start a fresh empty streak, keeping
            // the fixed episode deadline and the session request budget.
            resetPersistence(current)
            latestRequest = null
            return null
        }
        if (!fresh) return null
        val emptySince = current.emptySinceMs ?: capturedTimeMs.also { current.emptySinceMs = it }
        current.emptySamples++
        if (exhausted || current.emptySamples < MIN_EMPTY_SAMPLES ||
            capturedTimeMs - emptySince < EMPTY_PERSISTENCE_MS) return null
        val previousRequest = lastRequestTimeMs
        if (previousRequest != null && timeMs - previousRequest < REQUEST_COOLDOWN_MS) return null

        requests++
        lastRequestTimeMs = timeMs
        resetPersistence(current)
        return Request(requests, visibilityEpoch, current.roundId, timeMs).also { latestRequest = it }
    }

    /**
     * Recheck under the session guard when the queued callback actually executes.
     * The caller must ALSO recheck capture-session identity, APP_TASK/BETA, visibility,
     * projection/reader identity and the same recognizer instance. No new consent or
     * projection, automatic game interaction, or ledger reset is authorised by this token.
     */
    fun takeRequest(
        request: Request,
        timeMs: Long,
        visibilityEpoch: Long,
        phase: RoundPhase,
        roundId: String?,
    ): Boolean {
        if (!advanceClock(timeMs)) return false
        val active = episode ?: return false
        if (!eligible(active, timeMs, phase, roundId)) {
            closeEpisode()
            return false
        }
        val accepted = request == latestRequest && request.visibilityEpoch == visibilityEpoch &&
            visibilityEpoch == active.minimumEpoch && timeMs >= request.issuedAtMs &&
            timeMs - request.issuedAtMs <= REQUEST_VALIDITY_MS
        if (accepted) latestRequest = null
        return accepted
    }

    private fun eligible(active: Episode, timeMs: Long, phase: RoundPhase, roundId: String?): Boolean =
        phase == RoundPhase.RECOVERING && roundId == active.roundId &&
            timeMs >= active.startedAtMs && timeMs - active.startedAtMs < EPISODE_LIFETIME_MS

    private fun advanceClock(timeMs: Long): Boolean {
        if (timeMs < 0L || timeMs < lastNowMs) {
            closeEpisode()
            return false
        }
        lastNowMs = timeMs
        return true
    }

    private fun closeEpisode() {
        episode = null
        latestRequest = null
    }

    private fun resetPersistence(active: Episode) {
        active.emptySinceMs = null
        active.emptySamples = 0
    }

    companion object {
        /**
         * Use the detected scene and canonical hand/owned groups, never raw model specks
         * or the latched teammate phase. Unowned detections do not prove usable input.
         */
        fun contentFor(detectedScene: TableScene, hand: List<PlayGlyph>, ownership: OwnedPlays): Content = when {
            hand.any { it.confidence >= .65f } || ownership.seats.values.any { cards ->
                cards.any { it.confidence >= .65f }
            } || detectedScene in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.DEALING,
                TableScene.TRIBUTE,TableScene.TEAMMATE) -> Content.GAME_CONTENT
            detectedScene==TableScene.TABLE -> Content.TABLE_EMPTY
            detectedScene==TableScene.UNKNOWN -> Content.UNKNOWN_EMPTY
            else -> Content.OTHER
        }
        private const val EMPTY_PERSISTENCE_MS = 8_000L
        private const val REQUEST_COOLDOWN_MS = 15_000L
        private const val EPISODE_LIFETIME_MS = 60_000L
        private const val MAX_SOURCE_AGE_MS = 1_000L
        private const val REQUEST_VALIDITY_MS = 2_000L
        private const val MIN_EMPTY_SAMPLES = 2
        private const val MAX_REQUESTS = 2
    }
}
