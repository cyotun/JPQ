package com.cyotun.jpq.core

/** Detects positive evidence that a cached own-finish cue is stale. */
class OwnCompletionCueProof {
    /**
     * A clean, nonempty lower hand with the exact rank multiset still belongs
     * to SELF. This contradicts a finish cue even when the screen classifier
     * has switched to TEAMMATE or the cue itself is still asserted.
     *
     * Suits are intentionally ignored because their colors can flicker while
     * the same physical hand remains visible.
     */
    fun exactOwnHandStillVisible(frame: TableFrame, knownOwnHand: List<Card>): Boolean {
        if (knownOwnHand.isEmpty() || !validRanks(knownOwnHand) ||
            frame.scene !in setOf(TableScene.TABLE, TableScene.TEAMMATE) ||
            frame.captureGap || frame.ambiguousPresentation || frame.incompleteReason != null ||
            frame.localizedOverlaySeats.isNotEmpty()) return false

        val visibleHand = when (frame.scene) {
            TableScene.TABLE -> frame.hand
            TableScene.TEAMMATE -> frame.teammateHand.takeIf { frame.teammateHandConfidence >= MIN_HAND_CONFIDENCE }
            else -> null
        } ?: return false

        return visibleHand.isNotEmpty() && validRanks(visibleHand) && rankCounts(visibleHand) == rankCounts(knownOwnHand)
    }

    private fun validRanks(cards: List<Card>) = cards.groupingBy { it.rank }.eachCount()
        .all { (rank, count) -> count <= rank.capacity }

    private fun rankCounts(cards: List<Card>) = cards.groupingBy { it.rank }.eachCount()

    private companion object {
        const val MIN_HAND_CONFIDENCE = .65f
    }
}
