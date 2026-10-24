package com.cyotun.jpq.core

import kotlin.test.*

class CountAnchoredRepeatedSingleProofTest {
    private val seat = Seat.OPPOSITE
    private val blackSix = Card(Rank.SIX, Suit.BLACK)
    private val spadeSix = Card(Rank.SIX, Suit.SPADE)
    private val box = GlyphBox(.47f, .16f, .49f, .20f)
    private val eventId = "r:2"

    private fun snapshot(
        event: String = eventId,
        phase: RoundPhase = RoundPhase.ACTIVE,
        lastCard: Card = blackSix,
        played: Int = 24,
        remaining: Map<Rank, Int>? = Rank.entries.associateWith { 4 },
    ): RoundSnapshot {
        val old = PlayEvent("r", "r:1", 1, seat, List((played - 1).coerceAtLeast(0)) { Card(Rank.A, Suit.BLACK) })
        val latest = PlayEvent("r", event, 2, seat, listOf(lastCard))
        val plays = if (played >= 1) mapOf(seat to listOf(old, latest)) else mapOf(seat to emptyList())
        return RoundSnapshot("r", phase, remaining, plays, null)
    }

    private fun blind(time: Long, count: Int = 3, source: Long = time, scene: TableScene = TableScene.TABLE) =
        frame(time, ObservedPlay(emptyList(), .99f, emptyList(), regionObserved = false), count, source, scene)

    private fun direct(time: Long, suit: Suit = Suit.SPADE, count: Int = 3, source: Long = time,
                       scene: TableScene = TableScene.TABLE, glyphBox: GlyphBox = box) =
        frame(time, play(time, suit, glyphBox), count, source, scene)

    private fun frame(time: Long, play: ObservedPlay?, count: Int = 3, source: Long = time,
                      scene: TableScene = TableScene.TABLE, captureGap: Boolean = false,
                      ambiguous: Boolean = false, incomplete: String? = null,
                      targetSeat: Seat = seat) = TableFrame(
        timeMs = time,
        scene = scene,
        hand = null,
        plays = mapOf(targetSeat to play),
        captureGap = captureGap,
        ambiguousPresentation = ambiguous,
        incompleteReason = incomplete,
        remainingHandCounts = mapOf(targetSeat to RemainingHandCount(count, source)),
    )

    private fun play(time: Long, suit: Suit = Suit.SPADE, glyphBox: GlyphBox = box): ObservedPlay {
        val physical = Card(Rank.SIX, suit)
        return ObservedPlay(
            cards = listOf(blackSix),
            confidence = .95f,
            glyphs = listOf(PlayGlyph(blackSix, .94f, glyphBox)),
            singleCardIdentity = SingleCardIdentity(physical, .86f, .15f, time),
        )
    }

    private fun baseline(proof: CountAnchoredRepeatedSingleProof, state: RoundSnapshot = snapshot()) {
        assertNull(proof.observe(blind(1000), state, seat, eventId))
        assertNull(proof.observe(blind(1300), state, seat, eventId))
    }

    @Test fun directSecondSingleAndTwoPostAppearanceCountsProveOnePlay() {
        val proof = CountAnchoredRepeatedSingleProof()
        baseline(proof)
        assertNull(proof.observe(direct(1500, source = 1300), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(1600, source = 1300), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(1700, count = 2), snapshot(), seat, eventId))
        val result = proof.observe(direct(1800, count = 2), snapshot(), seat, eventId)
        assertEquals(listOf(blackSix), result?.cards)
        assertEquals(spadeSix, result?.singleCardIdentity?.card)
        assertNull(proof.observe(direct(1900, count = 2), snapshot(), seat, eventId))
    }

    @Test fun freshSeatBadgeCanAnchorAcrossSixSecondsOfBlindForegroundFrames() {
        val proof = CountAnchoredRepeatedSingleProof()
        for (time in 1000L..7000L step 500L) {
            assertNull(proof.observe(blind(time, count = 3), snapshot(), seat, eventId))
        }
        assertNull(proof.observe(direct(7100, source = 7000), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(7200, source = 7000), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(7300, count = 2), snapshot(), seat, eventId))
        assertNotNull(proof.observe(direct(7400, count = 2), snapshot(), seat, eventId))
    }

    @Test fun firstTargetCountMayShareTheFirstDirectFrameTimestamp() {
        val proof = CountAnchoredRepeatedSingleProof()
        baseline(proof)
        assertNull(proof.observe(direct(1500, count = 2), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(1600, count = 2), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(1700, count = 2), snapshot(), seat, eventId))
        assertNotNull(proof.observe(direct(1800, count = 2), snapshot(), seat, eventId))
    }

    @Test fun noFreshBaselineCannotStartCandidate() {
        val proof = CountAnchoredRepeatedSingleProof()
        assertNull(proof.observe(blind(1000, source = 900), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(1200, source = 900), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(1300, source = 900), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(1400, count = 2), snapshot(), seat, eventId))
    }

    @Test fun repeatedOldCountSourceDoesNotMakeTwoBaselineVotes() {
        val proof = CountAnchoredRepeatedSingleProof()
        assertNull(proof.observe(blind(1000, source = 1000), snapshot(), seat, eventId))
        assertNull(proof.observe(blind(1300, source = 1000), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(1500, source = 1000), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(1600, source = 1000), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(1700, count = 2), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(1800, count = 2), snapshot(), seat, eventId))
    }

    @Test fun baselineMustBeRecentAndSourceCadenceContinuous() {
        val proof = CountAnchoredRepeatedSingleProof()
        assertNull(proof.observe(blind(1000), snapshot(), seat, eventId))
        assertNull(proof.observe(blind(3000), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(3300, source = 3000), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(3400, source = 3000), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(3500, count = 2), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(3600, count = 2), snapshot(), seat, eventId))
    }

    @Test fun badgeDropBeforeDirectCandidateCannotIdentifyALaterCard() {
        val proof = CountAnchoredRepeatedSingleProof()
        baseline(proof)
        assertNull(proof.observe(blind(1400, count = 2), snapshot(), seat, eventId))
        repeat(5) { i ->
            val time = 1500L + i * 100L
            assertNull(proof.observe(direct(time, count = 2), snapshot(), seat, eventId))
        }
    }

    @Test fun onePostCountSourceIsInsufficientEvenAcrossManyDirectFrames() {
        val proof = CountAnchoredRepeatedSingleProof()
        baseline(proof)
        assertNull(proof.observe(direct(1500, source = 1300), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(1600, source = 1300), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(1700, count = 2), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(1800, count = 2, source = 1700), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(1900, count = 2, source = 1700), snapshot(), seat, eventId))
    }

    @Test fun cachedIdentityTimestampDoesNotCountAsAnotherDirectVote() {
        val proof = CountAnchoredRepeatedSingleProof()
        baseline(proof)
        assertNull(proof.observe(direct(1500, source = 1300), snapshot(), seat, eventId))
        val cached = play(1500)
        assertNull(proof.observe(frame(1600, cached, count = 3, source = 1300), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(1700, count = 2), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(1800, count = 2), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(1900, count = 2), snapshot(), seat, eventId))
    }

    @Test fun persistentOldSingleWithoutARealCountDropIsNotAPlay() {
        val proof = CountAnchoredRepeatedSingleProof()
        baseline(proof)
        assertNull(proof.observe(direct(1500, suit = Suit.CLUB, source = 1300), snapshot(), seat, eventId))
        repeat(8) { i ->
            val time = 1600L + i * 100L
            assertNull(proof.observe(direct(time, suit = Suit.CLUB, source = time), snapshot(), seat, eventId))
        }
    }

    @Test fun anInterveningDifferentFaceCannotDonateItsBadgeDropToTheOldSingle() {
        val proof=CountAnchoredRepeatedSingleProof(); baseline(proof)
        val ace=Card(Rank.A,Suit.BLACK)
        val other=ObservedPlay(listOf(ace),.95f,listOf(PlayGlyph(ace,.95f,box)))
        assertNull(proof.observe(frame(1400,other),snapshot(),seat,eventId))
        assertNull(proof.observe(direct(1500),snapshot(),seat,eventId))
        for(time in 1600L..2100L step 100L)
            assertNull(proof.observe(direct(time,count=2),snapshot(),seat,eventId))
    }

    @Test fun invalidCandidateIdentityConfidenceOrGeometryBreaksProof() {
        val weakIdentity = play(1600).copy(singleCardIdentity = SingleCardIdentity(spadeSix, .79f, .15f, 1600))
        val noIdentity = play(1600).copy(singleCardIdentity = null)
        val unobserved = play(1600).copy(regionObserved = false)
        val weakGroup = play(1600).copy(confidence = .79f)
        val drift = play(1600, glyphBox = GlyphBox(.57f, .16f, .59f, .20f))
        for (bad in listOf(weakIdentity, noIdentity, unobserved, weakGroup, drift)) {
            val proof = CountAnchoredRepeatedSingleProof()
            baseline(proof)
            assertNull(proof.observe(direct(1500, source = 1300), snapshot(), seat, eventId))
            assertNull(proof.observe(frame(1600, bad, source = 1300), snapshot(), seat, eventId))
            assertNull(proof.observe(direct(1700, count = 2), snapshot(), seat, eventId))
            assertNull(proof.observe(direct(1800, count = 2), snapshot(), seat, eventId))
        }
    }

    @Test fun globalGapAmbiguityOrIncompleteFrameClearsEvidence() {
        val invalidFrames = listOf(
            frame(1600, null, count = 3, captureGap = true),
            frame(1600, null, count = 3, ambiguous = true),
            frame(1600, null, count = 3, incomplete = "masked"),
        )
        for (invalid in invalidFrames) {
            val proof = CountAnchoredRepeatedSingleProof()
            baseline(proof)
            assertNull(proof.observe(direct(1500, source = 1300), snapshot(), seat, eventId))
            assertNull(proof.observe(invalid, snapshot(), seat, eventId))
            repeat(4) { i ->
                val time = 1700L + i * 100L
                assertNull(proof.observe(direct(time, count = if (i >= 2) 2 else 3), snapshot(), seat, eventId))
            }
        }
    }

    @Test fun candidateCannotCrossSceneOrSnapshotVersionChange() {
        run {
            val proof = CountAnchoredRepeatedSingleProof()
            baseline(proof)
            assertNull(proof.observe(direct(1500, source = 1300), snapshot(), seat, eventId))
            assertNull(proof.observe(direct(1600, count = 3, scene = TableScene.TEAMMATE), snapshot(), seat, eventId))
            assertNull(proof.observe(direct(1700, count = 2, scene = TableScene.TEAMMATE), snapshot(), seat, eventId))
        }
        run {
            val proof = CountAnchoredRepeatedSingleProof()
            baseline(proof)
            assertNull(proof.observe(direct(1500, source = 1300), snapshot(), seat, eventId))
            val changed = snapshot(event = "r:3", played = 25)
            assertNull(proof.observe(direct(1600, count = 3), changed, seat, "r:3"))
            assertNull(proof.observe(direct(1700, count = 2), changed, seat, "r:3"))
        }
        run {
            val proof = CountAnchoredRepeatedSingleProof()
            baseline(proof)
            assertNull(proof.observe(direct(1500, source = 1300), snapshot(), seat, eventId))
            val selfFinished = snapshot(phase = RoundPhase.SELF_FINISHED)
            assertNull(proof.observe(direct(1600, count = 3), selfFinished, seat, eventId))
            assertNull(proof.observe(direct(1700, count = 2), selfFinished, seat, eventId))
        }
    }

    @Test fun invalidCountZeroAndSelfSeatCannotProvePlay() {
        val proof = CountAnchoredRepeatedSingleProof()
        baseline(proof)
        assertNull(proof.observe(direct(1500, source = 1300), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(1600, count = 0), snapshot(), seat, eventId))
        repeat(4) { i -> assertNull(proof.observe(direct(1700L + i * 100L, count = 2), snapshot(), seat, eventId)) }

        val self = CountAnchoredRepeatedSingleProof()
        val selfEvent = PlayEvent("r", "self:1", 1, Seat.SELF, listOf(blackSix))
        val selfSnapshot = RoundSnapshot("r", RoundPhase.ACTIVE, Rank.entries.associateWith { 4 },
            mapOf(Seat.SELF to listOf(selfEvent)), null)
        assertNull(self.observe(frame(1000, play(1000), targetSeat = Seat.SELF), selfSnapshot, Seat.SELF, "self:1"))

        val noCardsLeft = CountAnchoredRepeatedSingleProof()
        val exhausted = snapshot(played = 27)
        assertNull(noCardsLeft.observe(direct(1000), exhausted, seat, eventId))
    }

    @Test fun outOfOrderTimeAndStaleSourceCannotBeReplayedAfterClear() {
        val proof = CountAnchoredRepeatedSingleProof()
        baseline(proof)
        assertNull(proof.observe(direct(1500, source = 1300), snapshot(), seat, eventId))
        assertNull(proof.observe(direct(1400, source = 1300), snapshot(), seat, eventId))
        proof.clear()
        assertNull(proof.observe(blind(1600, source = 1300), snapshot(), seat, eventId))
        assertNull(proof.observe(blind(1700, source = 1300), snapshot(), seat, eventId))
        repeat(4) { i -> assertNull(proof.observe(direct(1800L + i * 100L, count = 2), snapshot(), seat, eventId)) }
    }

    @Test fun selfFinishedPhaseIsAllowedButFinishedPhaseIsNot() {
        val proof = CountAnchoredRepeatedSingleProof()
        val selfFinished = snapshot(phase = RoundPhase.SELF_FINISHED)
        baseline(proof, selfFinished)
        assertNull(proof.observe(direct(1500, source = 1300), selfFinished, seat, eventId))
        assertNull(proof.observe(direct(1600, source = 1300), selfFinished, seat, eventId))
        assertNull(proof.observe(direct(1700, count = 2), selfFinished, seat, eventId))
        assertNotNull(proof.observe(direct(1800, count = 2), selfFinished, seat, eventId))

        val finished = CountAnchoredRepeatedSingleProof()
        val ended = snapshot(phase = RoundPhase.FINISHED)
        assertNull(finished.observe(direct(1000), ended, seat, eventId))
    }
}
