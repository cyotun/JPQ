package com.cyotun.jpq.core

/**
 * Retains a strong, directly observed own play while the live hand detector is
 * temporarily missing other cards. It confirms the retained play only after
 * the hand later presents the exact rank difference and the same own glyph
 * slots are still visible.
 *
 * The proof never invents a play from a hand-count difference alone: every
 * candidate card comes from strong, directly observed own glyphs.
 */
class OwnDelayedHandDifferenceProof {
    private data class OwnEventVersion(val eventId: String,val sequence: Long,val cards: List<String>)

    private data class Version(
        val roundId: String,
        val knownHand: List<String>,
        val selfPlayed: Int,
        val ownEvents: List<OwnEventVersion>
    )

    private data class Candidate(
        val version: Version,
        val cards: List<String>,
        val ranks: List<String>,
        val anchor: ObservedPlay,
        var latestDirect: ObservedPlay,
        val firstTime: Long,
        var lastDirectTime: Long,
        var directFrames: Int,
        var ready: Boolean = false,
        var confirmHandRanks: List<String>? = null,
        var confirmPlay: ObservedPlay? = null,
        var confirmFirstTime: Long = -1L,
        var confirmLastTime: Long = -1L,
        var confirmFrames: Int = 0
    )

    private var version: Version? = null
    private var candidate: Candidate? = null
    private var deliveredVersion: Version? = null
    private var lastObservationTime = -1L

    fun clear() {
        version = null
        candidate = null
        deliveredVersion = null
        lastObservationTime = -1L
    }

    /**
     * Returns the original exact own observation after it has remained
     * stable for three frames spanning 180 ms, followed by two fresh frames
     * whose hand rank difference equals that observation. The direct evidence
     * and the delayed confirmation must fit inside a 2 s window.
     */
    fun observe(
        frame: TableFrame,
        snapshot: RoundSnapshot,
        knownOwnHand: List<Card>
    ): ObservedPlay? {
        if (frame.timeMs <= lastObservationTime) {
            candidate = null
            return null
        }
        lastObservationTime = frame.timeMs

        val roundId = snapshot.roundId
        if (roundId == null || snapshot.remaining == null || snapshot.phase != RoundPhase.ACTIVE ||
            frame.scene != TableScene.TABLE || frame.selfFinished || knownOwnHand.isEmpty() ||
            snapshot.played(Seat.SELF) + knownOwnHand.size != 27) {
            resetVersion()
            return null
        }

        val handKey = cardKey(knownOwnHand) ?: run {
            resetVersion()
            return null
        }
        val ownEvents = snapshot.plays[Seat.SELF].orEmpty().map { event ->
            OwnEventVersion(event.eventId,event.sequence,cardKey(event.cards) ?: emptyList())
        }
        val nextVersion = Version(roundId,handKey,snapshot.played(Seat.SELF),ownEvents)
        if (version != nextVersion) {
            version = nextVersion
            candidate = null
            deliveredVersion = null
        }
        if (deliveredVersion == nextVersion) return null

        if (!cleanFrame(frame)) {
            candidate = null
            return null
        }
        val observedHand = frame.hand
        val observedRanks = observedHand?.let(::rankKey)
        val knownRanks = rankKey(knownOwnHand)
        if (observedHand.isNullOrEmpty() || observedRanks == null ||
            !isStrictSubMultiset(observedRanks,knownRanks)) {
            candidate = null
            return null
        }
        val rankDifference = subtract(knownRanks,observedRanks) ?: run {
            candidate = null
            return null
        }
        // If the original hand reappears, it cancels the pending interpretation.
        if (rankDifference.isEmpty()) {
            candidate = null
            return null
        }

        val play = frame.plays[Seat.SELF]
        if (play == null || !strong(play,minimumCards = 1)) {
            candidate = null
            return null
        }

        val current = candidate
        if (current != null && current.version == nextVersion &&
            frame.timeMs - current.firstTime <= MAX_CANDIDATE_SPAN_MS &&
            frame.timeMs > current.lastDirectTime && trackedSubset(play,current.anchor)) {
            if (!current.ready) {
                val directCards = cardKey(play.cards)
                if (play.cards.size >= MIN_DIRECT_CARDS && directCards == current.cards &&
                    sameSlots(play,current.latestDirect) &&
                    frame.timeMs - current.lastDirectTime <= MAX_FRAME_GAP_MS) {
                    current.directFrames++
                    current.lastDirectTime = frame.timeMs
                    current.latestDirect = copyPlay(play)
                    if (current.directFrames >= REQUIRED_DIRECT_FRAMES &&
                        frame.timeMs - current.firstTime >= MIN_DIRECT_SPAN_MS) current.ready = true
                } else {
                    candidate = seed(nextVersion,frame,play,knownOwnHand,knownRanks,rankDifference)
                    return null
                }
                return null
            }

            if (rankDifference == current.ranks) {
                val previousPlay = current.confirmPlay
                if (previousPlay == null || current.confirmHandRanks != observedRanks ||
                    frame.timeMs - current.confirmLastTime > MAX_FRAME_GAP_MS ||
                    !sameSlots(play,previousPlay)) {
                    current.confirmHandRanks = observedRanks.toList()
                    current.confirmPlay = copyPlay(play)
                    current.confirmFirstTime = frame.timeMs
                    current.confirmLastTime = frame.timeMs
                    current.confirmFrames = 1
                    return null
                }
                current.confirmFrames++
                current.confirmLastTime = frame.timeMs
                current.confirmPlay = copyPlay(play)
                if (current.confirmFrames < REQUIRED_CONFIRM_FRAMES ||
                    frame.timeMs - current.confirmFirstTime < MIN_CONFIRM_SPAN_MS) return null

                deliveredVersion = nextVersion
                candidate = null
                return copyPlay(current.anchor)
            }

            // Keep the mature direct evidence through a partial hand dropout,
            // but discard any incomplete exact-difference confirmation votes.
            current.confirmHandRanks = null
            current.confirmPlay = null
            current.confirmFirstTime = -1L
            current.confirmLastTime = -1L
            current.confirmFrames = 0
            return null
        }

        candidate = seed(nextVersion,frame,play,knownOwnHand,knownRanks,rankDifference)
        return null
    }

    private fun seed(
        version: Version,
        frame: TableFrame,
        play: ObservedPlay,
        knownHand: List<Card>,
        knownRanks: List<String>,
        handDifference: List<String>
    ): Candidate? {
        if (play.cards.size < MIN_DIRECT_CARDS) return null
        val cards = cardKey(play.cards) ?: return null
        if (subtract(cardKey(knownHand) ?: return null,cards) == null) return null
        val ranks = rankKey(play.cards)
        // The current hand is allowed to miss unrelated cards. This path is
        // only for cases where its difference is not already the exact play.
        // The directly observed cards must actually be absent from the
        // partial hand. Other missing cards may remain unexplained.
        if (!isStrictSubMultiset(ranks,knownRanks) || subtract(handDifference,ranks) == null) return null
        return Candidate(version,cards,ranks,copyPlay(play),copyPlay(play),frame.timeMs,
            frame.timeMs,1)
    }

    private fun resetVersion() {
        version = null
        candidate = null
        deliveredVersion = null
    }

    private fun cleanFrame(frame: TableFrame): Boolean =
        frame.scene == TableScene.TABLE && !frame.captureGap && !frame.ambiguousPresentation &&
            frame.incompleteReason == null && frame.localizedOverlaySeats.isEmpty()

    private fun strong(play: ObservedPlay,minimumCards: Int): Boolean =
        play.regionObserved && play.confidence in MIN_CONFIDENCE..1f &&
            play.cards.size >= minimumCards && play.cards.size == play.glyphs.size &&
            cardKey(play.cards) != null && cardKey(play.cards) == cardKey(play.glyphs.map { it.card }) &&
            play.glyphs.all { it.confidence in MIN_CONFIDENCE..1f && validBox(it.box) } &&
            play.glyphs.indices.all { i -> (i + 1 until play.glyphs.size).none { j ->
                val a = play.glyphs[i].box
                val b = play.glyphs[j].box
                kotlin.math.abs(a.cx - b.cx) <= minOf(a.width,b.width) * SAME_SLOT_CENTER_TOLERANCE &&
                    kotlin.math.abs(a.cy - b.cy) <= minOf(a.height,b.height) * SAME_SLOT_CENTER_TOLERANCE
            } }

    private fun copyPlay(play: ObservedPlay) = play.copy(
        cards = play.cards.toList(),
        glyphs = play.glyphs.toList()
    )

    private fun cardKey(cards: List<Card>): List<String>? =
        cards.map { it.colorKey() }.sorted().takeIf { cards.isNotEmpty() }

    private fun rankKey(cards: List<Card>): List<String> = cards.map { it.rank.name }.sorted()

    private fun subtract(known: List<String>,observed: List<String>): List<String>? {
        val remaining = known.toMutableList()
        for (card in observed) {
            val index = remaining.indexOf(card)
            if (index < 0) return null
            remaining.removeAt(index)
        }
        return remaining.sorted()
    }

    private fun isStrictSubMultiset(candidate: List<String>,source: List<String>): Boolean =
        candidate.size < source.size && subtract(source,candidate) != null

    private fun sameSlots(current: ObservedPlay,anchor: ObservedPlay): Boolean {
        if (current.glyphs.size != anchor.glyphs.size) return false
        val remaining = anchor.glyphs.toMutableList()
        for (glyph in current.glyphs) {
            val index = remaining.indexOfFirst { old -> sameSlot(glyph,old) }
            if (index < 0) return false
            remaining.removeAt(index)
        }
        return remaining.isEmpty()
    }

    /** Current visible glyphs must be a nonempty, identity-preserving subset of the direct anchor. */
    private fun trackedSubset(current: ObservedPlay,anchor: ObservedPlay): Boolean {
        if (current.glyphs.isEmpty() || current.glyphs.size > anchor.glyphs.size) return false
        val remaining = anchor.glyphs.toMutableList()
        for (glyph in current.glyphs) {
            val index = remaining.indexOfFirst { old -> sameSlot(glyph,old) }
            if (index < 0) return false
            remaining.removeAt(index)
        }
        return true
    }

    private fun sameSlot(current: PlayGlyph,anchor: PlayGlyph): Boolean =
        current.card.colorKey() == anchor.card.colorKey() &&
            kotlin.math.abs(current.box.cx - anchor.box.cx) <= minOf(current.box.width,anchor.box.width) * SLOT_CENTER_TOLERANCE &&
            kotlin.math.abs(current.box.cy - anchor.box.cy) <= minOf(current.box.height,anchor.box.height) * SLOT_CENTER_TOLERANCE &&
            maxOf(current.box.width,anchor.box.width) <= minOf(current.box.width,anchor.box.width) * MAX_SIZE_RATIO &&
            maxOf(current.box.height,anchor.box.height) <= minOf(current.box.height,anchor.box.height) * MAX_SIZE_RATIO

    private fun validBox(box: GlyphBox): Boolean =
        listOf(box.left,box.top,box.right,box.bottom).all { it.isFinite() && it in 0f..1f } &&
            box.width > 0f && box.height > 0f

    private companion object {
        const val MIN_CONFIDENCE = .80f
        const val MIN_DIRECT_CARDS = 3
        const val REQUIRED_DIRECT_FRAMES = 3
        const val REQUIRED_CONFIRM_FRAMES = 2
        const val MIN_DIRECT_SPAN_MS = 180L
        const val MIN_CONFIRM_SPAN_MS = 180L
        const val MAX_CANDIDATE_SPAN_MS = 2000L
        const val MAX_FRAME_GAP_MS = 500L
        const val SAME_SLOT_CENTER_TOLERANCE = .10f
        const val SLOT_CENTER_TOLERANCE = .35f
        const val MAX_SIZE_RATIO = 1.4f
    }
}
