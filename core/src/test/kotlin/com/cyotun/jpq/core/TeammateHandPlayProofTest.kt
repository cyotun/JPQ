package com.cyotun.jpq.core

import kotlin.test.*

class TeammateHandPlayProofTest {
    private val fullDeck = Rank.entries.flatMap { rank ->
        if (rank.isJoker) List(rank.capacity) { Card(rank, Suit.NONE) }
        else List(4) { Card(rank, Suit.RED) } + List(4) { Card(rank, Suit.BLACK) }
    }

    private fun snapshot(seedSize: Int, roundId: String = "round-1", oldOppCards: List<Card> = emptyList()): RoundSnapshot {
        val played = 27 - seedSize
        val cards = if (oldOppCards.isNotEmpty()) oldOppCards else fullDeck.take(played)
        val events = cards.mapIndexed { index, card ->
            PlayEvent(roundId, "opp-$index", index.toLong(), Seat.OPPOSITE, listOf(card))
        }
        return RoundSnapshot(
            roundId,
            RoundPhase.SELF_FINISHED,
            Rank.entries.associateWith { it.capacity },
            Seat.entries.associateWith { seat -> if (seat == Seat.OPPOSITE) events else emptyList() },
            null,
        )
    }

    private fun play(cards: List<Card>, confidence: Float = .90f, x: Float = .42f): ObservedPlay {
        val glyphs = cards.mapIndexed { index, card ->
            val left = x + index * .025f
            PlayGlyph(card, confidence, GlyphBox(left, .18f, left + .02f, .23f))
        }
        return ObservedPlay(cards, confidence, glyphs)
    }

    private fun observe(
        tracker: TeammateHandPlayProofTracker,
        snapshot: RoundSnapshot,
        time: Long,
        hand: List<Card>?,
        desk: ObservedPlay?,
        handConfidence: Float = .90f,
        scene: TableScene = TableScene.TEAMMATE,
        captureGap: Boolean = false,
        incompleteReason: String? = null,
        ambiguous: Boolean = false,
    ): ObservedPlay? = tracker.observe(
        TableFrame(
            timeMs = time,
            scene = scene,
            hand = null,
            plays = mapOf(Seat.OPPOSITE to desk),
            incompleteReason = incompleteReason,
            ambiguousPresentation = ambiguous,
            captureGap = captureGap,
            teammateHand = hand,
            teammateHandConfidence = handConfidence,
        ),
        snapshot,
    )

    @Test fun sameVisibleBigCanProveANewBigWhenTheCompleteTeammateHandShrinks() {
        val tracker = TeammateHandPlayProofTracker()
        val snapshot = snapshot(3)
        val big = Card(Rank.BIG, Suit.NONE)
        val seed = listOf(big, Card(Rank.A, Suit.RED), Card(Rank.K, Suit.BLACK))
        val smaller = seed.drop(1)
        val oldVisibleBig = play(listOf(big))

        assertNull(observe(tracker, snapshot, 1000, seed, oldVisibleBig))
        assertNull(observe(tracker, snapshot, 1116, seed, oldVisibleBig))
        assertNull(observe(tracker, snapshot, 1249, smaller, oldVisibleBig))
        val proof = observe(tracker, snapshot, 1370, smaller, oldVisibleBig)

        assertEquals(listOf(big), proof?.cards)
        assertEquals(oldVisibleBig.glyphs, proof?.glyphs)
        assertNull(observe(tracker, snapshot, 1500, smaller, oldVisibleBig))
    }

    @Test fun completeSingleThreeThenEmptyHandMayReuseTheRecentSameGeometryStrongDeskRead() {
        val tracker = TeammateHandPlayProofTracker()
        val snapshot = snapshot(1)
        val three = Card(Rank.THREE, Suit.RED)
        val seed = listOf(three)
        val direct = play(seed, .87f)

        assertNull(observe(tracker, snapshot, 1641624535, seed, direct))
        assertNull(observe(tracker, snapshot, 1641624651, seed, direct))
        assertNull(observe(tracker, snapshot, 1641624784, emptyList(), direct))
        val lowSameGeometry = play(seed, .604f)
        val proof = observe(tracker, snapshot, 1641624917, emptyList(), lowSameGeometry)

        assertEquals(direct.cards, proof?.cards)
        assertEquals(direct.glyphs, proof?.glyphs)
    }

    @Test fun completeFourCardHandAndMatchingDeskProveTheLastPlayAcross329Ms() {
        val tracker = TeammateHandPlayProofTracker()
        val snapshot = snapshot(4)
        val seed = listOf(
            Card(Rank.EIGHT, Suit.BLACK), Card(Rank.EIGHT, Suit.RED),
            Card(Rank.EIGHT, Suit.RED), Card(Rank.J, Suit.RED),
        )
        val direct = play(seed, .857f)

        assertNull(observe(tracker, snapshot, 1690868334, seed, direct))
        assertNull(observe(tracker, snapshot, 1690868567, seed, direct))
        assertNull(observe(tracker, snapshot, 1690868837, emptyList(), play(seed, .834f)))
        val proof = observe(tracker, snapshot, 1690869166, emptyList(), direct)

        assertEquals(direct.cards, proof?.cards)
        assertEquals(play(seed, .834f).glyphs, proof?.glyphs)
    }

    @Test fun highFrameRateShrinkVotesAccumulateUntilTheirTotalSpanReaches100Ms() {
        val tracker = TeammateHandPlayProofTracker()
        val snapshot = snapshot(1)
        val three = Card(Rank.THREE, Suit.RED)
        val seed = listOf(three)
        val desk = play(seed)

        assertNull(observe(tracker, snapshot, 0, seed, desk))
        assertNull(observe(tracker, snapshot, 66, seed, desk))
        assertNull(observe(tracker, snapshot, 132, seed, desk))
        assertNull(observe(tracker, snapshot, 198, emptyList(), desk))
        assertNull(observe(tracker, snapshot, 264, emptyList(), desk))
        assertEquals(desk.cards, observe(tracker, snapshot, 330, emptyList(), desk)?.cards)
    }

    @Test fun oneSeedVoteOrOneShrinkVoteIsInsufficient() {
        val seed = listOf(Card(Rank.THREE, Suit.RED))
        val snapshot = snapshot(seed.size)
        val desk = play(seed)

        val oneSeed = TeammateHandPlayProofTracker()
        assertNull(observe(oneSeed, snapshot, 0, seed, desk))
        assertNull(observe(oneSeed, snapshot, 133, emptyList(), desk))
        assertNull(observe(oneSeed, snapshot, 266, emptyList(), desk))

        val oneShrink = TeammateHandPlayProofTracker()
        assertNull(observe(oneShrink, snapshot, 1000, seed, desk))
        assertNull(observe(oneShrink, snapshot, 1116, seed, desk))
        assertNull(observe(oneShrink, snapshot, 1249, emptyList(), desk))
    }

    @Test fun aGapUnknownOrNonTeammateFrameBreaksTheProofSequence() {
        val seed = listOf(Card(Rank.THREE, Suit.RED))
        val snapshot = snapshot(seed.size)
        val desk = play(seed)
        for (breakFrame in listOf(
            { t: Long -> Triple(TableScene.UNKNOWN, false, null as String?) },
            { t: Long -> Triple(TableScene.TEAMMATE, true, null as String?) },
            { t: Long -> Triple(TableScene.TABLE, false, null as String?) },
            { t: Long -> Triple(TableScene.TEAMMATE, false, "incomplete") },
        )) {
            val tracker = TeammateHandPlayProofTracker()
            assertNull(observe(tracker, snapshot, 0, seed, desk))
            assertNull(observe(tracker, snapshot, 116, seed, desk))
            val (scene, gap, reason) = breakFrame(200)
            assertNull(observe(tracker, snapshot, 200, emptyList(), desk, scene = scene,
                captureGap = gap, incompleteReason = reason))
            assertNull(observe(tracker, snapshot, 333, emptyList(), desk))
        }
    }

    @Test fun mismatchedOrMovedDeskAndAHandThatIsNotASubsetCannotProveAPlay() {
        val seed = listOf(Card(Rank.THREE, Suit.RED))
        val snapshot = snapshot(seed.size)
        val tracker = TeammateHandPlayProofTracker()
        val desk = play(seed)
        assertNull(observe(tracker, snapshot, 0, seed, desk))
        assertNull(observe(tracker, snapshot, 116, seed, desk))
        assertNull(observe(tracker, snapshot, 249, emptyList(), play(listOf(Card(Rank.FOUR, Suit.RED)))))
        assertNull(observe(tracker, snapshot, 382, emptyList(), desk))

        tracker.reset()
        assertNull(observe(tracker, snapshot, 1000, seed, desk))
        assertNull(observe(tracker, snapshot, 1116, seed, desk))
        assertNull(observe(tracker, snapshot, 1249, emptyList(), desk))
        assertNull(observe(tracker, snapshot, 1382, emptyList(), play(seed, .604f, x = .62f)))
        assertNull(observe(tracker, snapshot, 1515, emptyList(), play(seed, .604f, x = .62f)))

        tracker.reset()
        assertNull(observe(tracker, snapshot, 2000, seed, desk))
        assertNull(observe(tracker, snapshot, 2116, seed, desk))
        assertNull(observe(tracker, snapshot, 2249, listOf(Card(Rank.FOUR, Suit.RED)), play(seed)))
    }

    @Test fun weakSeedOrUnobservedEmptyBoardCannotBorrowOldEvidence() {
        val seed = listOf(Card(Rank.THREE, Suit.RED))
        val snapshot = snapshot(seed.size)
        val tracker = TeammateHandPlayProofTracker()
        val desk = play(seed)
        assertNull(observe(tracker, snapshot, 0, seed, desk, handConfidence = .79f))
        assertNull(observe(tracker, snapshot, 116, seed, desk))
        assertNull(observe(tracker, snapshot, 249, emptyList(), desk))
        assertNull(observe(tracker, snapshot, 382, emptyList(), null))
    }

    @Test fun duplicateTimestampsAndChangedOpponentEventVersionDoNotReuseVotes() {
        val seed = listOf(Card(Rank.THREE, Suit.RED))
        val snapshot = snapshot(seed.size)
        val tracker = TeammateHandPlayProofTracker()
        val desk = play(seed)
        assertNull(observe(tracker, snapshot, 0, seed, desk))
        assertNull(observe(tracker, snapshot, 116, seed, desk))
        assertNull(observe(tracker, snapshot, 249, emptyList(), desk))
        assertNull(observe(tracker, snapshot, 249, emptyList(), desk))
        assertEquals(desk.cards, observe(tracker, snapshot, 382, emptyList(), desk)?.cards)

        val changedVersion = snapshot.copy(
            plays = snapshot.plays + (Seat.OPPOSITE to snapshot.plays[Seat.OPPOSITE].orEmpty() +
                PlayEvent("round-1", "next-opp", 100, Seat.OPPOSITE, listOf(Card(Rank.FOUR, Suit.RED)))),
        )
        assertNull(observe(tracker, changedVersion, 500, emptyList(), desk))
    }

    @Test fun ambiguousOtherGlyphsAreAllowedOnlyWhenOppositeGroupIsDirectAndComplete() {
        val seed = listOf(Card(Rank.THREE, Suit.RED))
        val snapshot = snapshot(seed.size)
        val tracker = TeammateHandPlayProofTracker()
        val desk = play(seed)
        assertNull(observe(tracker, snapshot, 0, seed, desk, incompleteReason = "归属待核对", ambiguous = true))
        assertNull(observe(tracker, snapshot, 116, seed, desk, incompleteReason = "归属待核对", ambiguous = true))
        assertNull(observe(tracker, snapshot, 249, emptyList(), desk, incompleteReason = "归属待核对", ambiguous = true))
        assertEquals(desk.cards, observe(tracker, snapshot, 382, emptyList(), desk,
            incompleteReason = "归属待核对", ambiguous = true)?.cards)
    }
}
