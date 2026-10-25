package com.cyotun.jpq.core

/**
 * Confirms a directly visible own play from the exact difference between a
 * previously owned hand and a smaller, currently observed hand. This proof is
 * deliberately limited to clean ACTIVE/TABLE frames and never fills in cards.
 */
class OwnHandDifferenceProof {
    private data class Version(
        val roundId: String,
        val handKey: List<String>,
        val ownEvents: List<PlayEvent>
    )

    private data class Candidate(
        val version: Version,
        val cardsKey: List<String>,
        val handKey: List<String>,
        val anchor: ObservedPlay,
        var latest: ObservedPlay,
        val firstTime: Long,
        var lastTime: Long,
        var frames: Int
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
     * Returns the exact current SELF observation after three stable, fresh
     * frames prove `knownOwnHand - frame.hand == plays[SELF]`.
     */
    fun observe(frame: TableFrame, snapshot: RoundSnapshot, knownOwnHand: List<Card>): ObservedPlay? {
        if (frame.timeMs <= lastObservationTime) {
            candidate = null
            return null
        }
        lastObservationTime = frame.timeMs
        val roundId = snapshot.roundId
        if (roundId == null || snapshot.remaining==null || snapshot.phase != RoundPhase.ACTIVE ||
            frame.scene != TableScene.TABLE || frame.selfFinished) {
            candidate = null
            version = null
            deliveredVersion = null
            return null
        }

        val handKey = key(knownOwnHand) ?: run {
            candidate = null
            version = null
            deliveredVersion = null
            return null
        }
        if (knownOwnHand.isEmpty() || snapshot.played(Seat.SELF) + knownOwnHand.size != 27) {
            candidate = null
            version = null
            deliveredVersion = null
            return null
        }
        val ownEvents = snapshot.plays[Seat.SELF].orEmpty().map { event ->
            event.copy(cards = event.cards.toList())
        }
        val nextVersion = Version(roundId, handKey, ownEvents)
        if (version != nextVersion) {
            version = nextVersion
            candidate = null
            deliveredVersion = null
        }
        if (deliveredVersion == nextVersion) {
            candidate = null
            return null
        }
        val play = frame.plays[Seat.SELF]
        val observedHand = frame.hand
        if (!cleanFrame(frame) || observedHand == null || observedHand.isEmpty() || play == null ||
            !strong(play) || play.cards.isEmpty()) {
            candidate = null
            return null
        }

        // The remaining hand can have a red/black classifier flicker on an
        // unrelated unplayed card. It only corroborates rank quantities here;
        // every played identity must still exactly belong to the known hand.
        val knownRanks=knownOwnHand.map { it.rank.name }.sorted()
        val observedHandKey = observedHand.map { it.rank.name }.sorted()
        if (!isStrictSubMultiset(observedHandKey, knownRanks)) {
            candidate = null
            return null
        }
        val expectedPlay = subtract(knownRanks, observedHandKey) ?: run {
            candidate = null
            return null
        }
        val observedPlayKey = key(play.cards)
        if (observedPlayKey == null || play.cards.map { it.rank.name }.sorted()!=expectedPlay ||
            subtract(handKey,observedPlayKey)==null ||
            key(play.glyphs.map { it.card }) != observedPlayKey) {
            candidate = null
            return null
        }

        val current = candidate
        if (current == null || current.version != nextVersion || current.cardsKey != observedPlayKey ||
            current.handKey != observedHandKey || frame.timeMs <= current.lastTime ||
            frame.timeMs - current.lastTime > MAX_FRAME_GAP_MS ||
            frame.timeMs - current.firstTime > MAX_CANDIDATE_SPAN_MS ||
            !sameSlots(play, current.anchor) || !sameSlots(play,current.latest)) {
            candidate = Candidate(nextVersion, observedPlayKey, observedHandKey,
                copyPlay(play),copyPlay(play), frame.timeMs, frame.timeMs, 1)
            return null
        }
        current.frames++
        current.lastTime = frame.timeMs
        current.latest=copyPlay(play)
        if (current.frames < REQUIRED_FRAMES || frame.timeMs - current.firstTime < MIN_SPAN_MS) return null

        deliveredVersion = nextVersion
        candidate = null
        return copyPlay(play)
    }

    private fun cleanFrame(frame: TableFrame): Boolean =
        !frame.captureGap && !frame.ambiguousPresentation && frame.incompleteReason == null &&
            frame.localizedOverlaySeats.isEmpty()

    private fun strong(play: ObservedPlay): Boolean =
        play.regionObserved && play.confidence in MIN_CONFIDENCE..1f &&
            play.cards.isNotEmpty() && play.cards.size == play.glyphs.size &&
            key(play.cards) != null && key(play.cards) == key(play.glyphs.map { it.card }) &&
            play.glyphs.all { it.confidence in MIN_CONFIDENCE..1f && validBox(it.box) } &&
            play.glyphs.indices.all { i -> (i + 1 until play.glyphs.size).none { j ->
                val a = play.glyphs[i].box
                val b = play.glyphs[j].box
                kotlin.math.abs(a.cx - b.cx) <= minOf(a.width, b.width) * SAME_SLOT_CENTER_TOLERANCE &&
                    kotlin.math.abs(a.cy - b.cy) <= minOf(a.height, b.height) * SAME_SLOT_CENTER_TOLERANCE
            } }

    private fun copyPlay(play: ObservedPlay) = play.copy(
        cards = play.cards.toList(),
        glyphs = play.glyphs.toList()
    )

    private fun key(cards: List<Card>): List<String>? =
        cards.map { it.colorKey() }.sorted().takeIf { cards.isNotEmpty() }

    private fun subtract(known: List<String>, observed: List<String>): List<String>? {
        val remaining = known.toMutableList()
        for (card in observed) {
            val index = remaining.indexOf(card)
            if (index < 0) return null
            remaining.removeAt(index)
        }
        return remaining.takeIf { it.isNotEmpty() }?.sorted()
    }

    private fun isStrictSubMultiset(candidate: List<String>, source: List<String>): Boolean =
        candidate.size < source.size && subtract(source, candidate) != null

    private fun sameSlots(current: ObservedPlay, anchor: ObservedPlay): Boolean {
        val remaining = anchor.glyphs.toMutableList()
        for (glyph in current.glyphs) {
            val index = remaining.indexOfFirst { old ->
                glyph.card.colorKey() == old.card.colorKey() &&
                    kotlin.math.abs(glyph.box.cx - old.box.cx) <= minOf(glyph.box.width, old.box.width) * SLOT_CENTER_TOLERANCE &&
                    kotlin.math.abs(glyph.box.cy - old.box.cy) <= minOf(glyph.box.height, old.box.height) * SLOT_CENTER_TOLERANCE &&
                    maxOf(glyph.box.width, old.box.width) <= minOf(glyph.box.width, old.box.width) * MAX_SIZE_RATIO &&
                    maxOf(glyph.box.height, old.box.height) <= minOf(glyph.box.height, old.box.height) * MAX_SIZE_RATIO
            }
            if (index < 0) return false
            remaining.removeAt(index)
        }
        return remaining.isEmpty()
    }

    private fun validBox(box: GlyphBox): Boolean =
        listOf(box.left, box.top, box.right, box.bottom).all { it.isFinite() && it in 0f..1f } &&
            box.width > 0f && box.height > 0f

    private companion object {
        const val MIN_CONFIDENCE = .80f
        const val REQUIRED_FRAMES = 3
        const val MIN_SPAN_MS = 180L
        const val MAX_FRAME_GAP_MS = 500L
        const val MAX_CANDIDATE_SPAN_MS = 1500L
        const val SAME_SLOT_CENTER_TOLERANCE = .10f
        const val SLOT_CENTER_TOLERANCE = .35f
        const val MAX_SIZE_RATIO = 1.4f
    }
}
