package com.cyotun.jpq.core

/**
 * Produces one proof of a new OPPOSITE play from a complete teammate hand
 * shrinking by exactly the cards directly visible in OPPOSITE's play region.
 * The caller still owns ledger mutation and must recheck the event version.
 */
class TeammateHandPlayProofTracker {
    private data class Version(val roundId: String, val events: List<String>)
    private data class DeskEvidence(val play: ObservedPlay, val time: Long)
    private data class Hand(val cards: List<Card>, val key: String)

    private var version: Version? = null
    private var lastTime: Long? = null
    private var seed: Hand? = null
    private var seedVotes = 0
    private var seedFirstSeen: Long? = null
    private var seedLastSeen: Long? = null
    private var shrink: Hand? = null
    private var shrinkVotes = 0
    private var shrinkFirstSeen: Long? = null
    private var shrinkLastSeen: Long? = null
    private var shrinkDeskEvidence: DeskEvidence? = null
    private var deskEvidence: DeskEvidence? = null
    private var delivered = false

    fun reset() {
        version = null
        lastTime = null
        clearProofState()
    }

    /** Read-only checkpoint of the last independently confirmed complete hand.
     * Partial subset readings do not replace this seed or certify a play. */
    fun checkpointHand(snapshot: RoundSnapshot): List<Card>? {
        val roundId=snapshot.roundId ?: return null
        val remaining=snapshot.remaining ?: return null
        val currentVersion=Version(roundId,snapshot.plays[Seat.OPPOSITE].orEmpty().map {
            it.eventId+":"+it.sequence+":"+cardsKey(it.cards)
        })
        val known=seed ?: return null
        val first=seedFirstSeen ?: return null
        val last=seedLastSeen ?: return null
        if(snapshot.phase!=RoundPhase.SELF_FINISHED || version!=currentVersion || delivered ||
            snapshot.played(Seat.OPPOSITE)>=27 || known.cards.size!=27-snapshot.played(Seat.OPPOSITE) ||
            seedVotes<REQUIRED_VOTES || last-first<MIN_VOTE_SPAN_MS || !validHand(known.cards,remaining)) return null
        return known.cards.toList()
    }

    fun observe(frame: TableFrame, snapshot: RoundSnapshot): ObservedPlay? {
        val roundId = snapshot.roundId ?: run { reset(); return null }
        val nextVersion = Version(roundId, snapshot.plays[Seat.OPPOSITE].orEmpty().map {
            it.eventId + ":" + it.sequence + ":" + cardsKey(it.cards)
        })
        if (version != nextVersion) {
            clearProofState()
            lastTime = null
            version = nextVersion
        }

        val priorTime = lastTime
        if (priorTime != null && frame.timeMs == priorTime) return null
        if (priorTime != null && frame.timeMs < priorTime) {
            clearProofState()
            lastTime = frame.timeMs
            return null
        }
        lastTime = frame.timeMs
        if (priorTime != null && frame.timeMs - priorTime > MAX_GAP_MS) {
            clearProofState()
            return null
        }

        val handCards = frame.teammateHand
        val eligible = snapshot.phase == RoundPhase.SELF_FINISHED &&
            snapshot.remaining != null &&
            snapshot.played(Seat.OPPOSITE) < 27 &&
            frame.scene == TableScene.TEAMMATE &&
            !frame.captureGap &&
            (frame.incompleteReason == null || frame.ambiguousPresentation) &&
            handCards != null &&
            frame.teammateHandConfidence >= MIN_CONFIDENCE
        if (!eligible) {
            clearProofState()
            return null
        }

        val hand = handCards!!.toList()
        val expectedSize = 27 - snapshot.played(Seat.OPPOSITE)
        if (!validHand(hand, snapshot.remaining!!)) {
            clearProofState()
            return null
        }

        val visiblePlay = frame.plays[Seat.OPPOSITE]
        val strongDesk = visiblePlay?.takeIf { isStrongCompletePlay(it) }
        if (visiblePlay == null || !visiblePlay.regionObserved || visiblePlay.cards.isEmpty() ||
            visiblePlay.glyphs.size != visiblePlay.cards.size ||
            !sameCards(visiblePlay.cards, visiblePlay.glyphs.map { it.card })) {
            // An empty, missing, partial, or unassigned region breaks the
            // correspondence; it cannot borrow an earlier table observation.
            deskEvidence = null
            resetShrink()
        } else if (strongDesk != null) {
            deskEvidence = DeskEvidence(strongDesk, frame.timeMs)
        }

        val current = Hand(hand, cardsKey(hand))
        val baseline = seed
        if (baseline == null) {
            if (hand.size != expectedSize) return null
            seed = current
            seedVotes = 1
            seedFirstSeen = frame.timeMs
            seedLastSeen = frame.timeMs
            return null
        }

        if (current.key == baseline.key) {
            if (hand.size != expectedSize) {
                clearProofState()
                return null
            }
            resetShrink()
            seedVotes++
            seedLastSeen = frame.timeMs
            return null
        }

        val removed = multisetDifference(baseline.cards, current.cards)
        if (removed == null || removed.isEmpty() || baseline.cards.size != expectedSize ||
            hand.size != expectedSize - removed.size) {
            // A changed complete hand that is not a strict subset may become a
            // new baseline, but it needs its own two independent votes.
            if (hand.size != expectedSize) {
                clearProofState()
                return null
            }
            resetProofForNewSeed(current, frame.timeMs)
            return null
        }

        val seedTime = seedLastSeen
        val seedFirst = seedFirstSeen
        if (seedVotes < REQUIRED_VOTES || seedFirst == null ||
            seedLastSeen!! - seedFirst < MIN_VOTE_SPAN_MS || seedTime == null ||
            (shrink == null && frame.timeMs - seedTime !in 0L..MAX_GAP_MS)) {
            resetShrink()
            return null
        }

        val currentPlay = visiblePlay ?: run { resetShrink(); return null }
        if (!sameCards(currentPlay.cards, removed)) {
            resetShrink()
            return null
        }

        val evidence = deskEvidence
        val matchingEvidence = when {
            strongDesk != null && sameCards(strongDesk.cards, removed) ->
                DeskEvidence(strongDesk, frame.timeMs)
            evidence != null &&
                frame.timeMs - evidence.time in 0L..MAX_GAP_MS &&
                sameCards(evidence.play.cards, removed) &&
                sameGeometry(currentPlay.glyphs, evidence.play.glyphs) ->
                evidence
            else -> null
        }
        if (matchingEvidence == null) {
            resetShrink()
            return null
        }

        if (shrink?.key != current.key) {
            beginShrink(current, frame.timeMs, matchingEvidence)
            return null
        }

        val anchoredDesk = shrinkDeskEvidence
        if (anchoredDesk == null || frame.timeMs - anchoredDesk.time !in 0L..MAX_GAP_MS ||
            !sameCards(anchoredDesk.play.cards, removed) ||
            !sameGeometry(currentPlay.glyphs, anchoredDesk.play.glyphs)) {
            // A moved or replaced table group starts a new independent proof;
            // it cannot be treated as the earlier direct observation.
            if (strongDesk != null && sameCards(strongDesk.cards, removed))
                beginShrink(current, frame.timeMs, DeskEvidence(strongDesk, frame.timeMs))
            else resetShrink()
            return null
        }

        val previousShrinkTime = shrinkLastSeen ?: frame.timeMs
        if (frame.timeMs - previousShrinkTime !in 0L..MAX_GAP_MS ||
            frame.timeMs - (shrinkFirstSeen ?: frame.timeMs) > MAX_GAP_MS) {
            resetShrink()
            return null
        }
        shrinkVotes++
        shrinkLastSeen = frame.timeMs
        if (shrinkVotes < REQUIRED_VOTES || delivered ||
            frame.timeMs - (shrinkFirstSeen ?: frame.timeMs) < MIN_VOTE_SPAN_MS) return null

        delivered = true
        return anchoredDesk.play.copy(
            cards = anchoredDesk.play.cards.toList(),
            glyphs = anchoredDesk.play.glyphs.toList(),
        )
    }

    private fun beginShrink(hand: Hand, timeMs: Long, evidence: DeskEvidence) {
        shrink = hand
        shrinkVotes = 1
        shrinkFirstSeen = timeMs
        shrinkLastSeen = timeMs
        shrinkDeskEvidence = evidence
    }

    private fun resetProofForNewSeed(hand: Hand, timeMs: Long) {
        seed = hand
        seedVotes = 1
        seedFirstSeen = timeMs
        seedLastSeen = timeMs
        resetShrink()
        deskEvidence = null
        delivered = false
    }

    private fun resetShrink() {
        shrink = null
        shrinkVotes = 0
        shrinkFirstSeen = null
        shrinkLastSeen = null
        shrinkDeskEvidence = null
    }

    private fun clearProofState() {
        seed = null
        seedVotes = 0
        seedFirstSeen = null
        seedLastSeen = null
        resetShrink()
        deskEvidence = null
        delivered = false
    }

    private fun isStrongCompletePlay(play: ObservedPlay): Boolean =
        play.regionObserved && play.cards.isNotEmpty() && play.confidence >= MIN_CONFIDENCE &&
            play.cards.size == play.glyphs.size &&
            sameCards(play.cards, play.glyphs.map { it.card }) &&
            play.glyphs.all { it.confidence >= MIN_CONFIDENCE && validBox(it.box) }

    private fun validBox(box: GlyphBox): Boolean =
        box.left.isFinite() && box.top.isFinite() && box.right.isFinite() && box.bottom.isFinite() &&
            box.width > 0f && box.height > 0f

    private fun validHand(cards: List<Card>, remaining: Map<Rank, Int>): Boolean {
        if (cards.groupingBy { it.rank }.eachCount().any { (rank, count) ->
                count > minOf(rank.capacity, remaining[rank] ?: 0)
            }) return false
        return cards.groupingBy { it.colorKey() }.eachCount().all { (key, count) ->
            count <= if (':' in key) 4 else 2
        }
    }

    private fun sameCards(first: List<Card>, second: List<Card>): Boolean =
        first.size == second.size && cardsKey(first) == cardsKey(second)

    private fun cardsKey(cards: List<Card>): String =
        cards.map { it.colorKey() }.sorted().joinToString(",")

    private fun multisetDifference(full: List<Card>, subset: List<Card>): List<Card>? {
        val rest = full.toMutableList()
        for (card in subset) {
            val index = rest.indexOfFirst { it.colorKey() == card.colorKey() }
            if (index < 0) return null
            rest.removeAt(index)
        }
        return rest
    }

    private fun sameGeometry(current: List<PlayGlyph>, tracked: List<PlayGlyph>): Boolean {
        if (current.size != tracked.size || current.isEmpty()) return false
        val available = current.toMutableList()
        return tracked.all { old ->
            val match = available.indexOfFirst { fresh ->
                fresh.card.colorKey() == old.card.colorKey() &&
                    validBox(fresh.box) && validBox(old.box) &&
                    kotlin.math.abs(fresh.box.cx - old.box.cx) <= minOf(fresh.box.width, old.box.width) * .35f &&
                    kotlin.math.abs(fresh.box.cy - old.box.cy) <= minOf(fresh.box.height, old.box.height) * .35f &&
                    maxOf(fresh.box.width, old.box.width) <= minOf(fresh.box.width, old.box.width) * 1.4f &&
                    maxOf(fresh.box.height, old.box.height) <= minOf(fresh.box.height, old.box.height) * 1.4f
            }
            if (match < 0) false else { available.removeAt(match); true }
        }
    }

    private companion object {
        const val REQUIRED_VOTES = 2
        const val MIN_CONFIDENCE = .80f
        const val MIN_VOTE_SPAN_MS = 100L
        const val MAX_GAP_MS = 500L
    }
}
