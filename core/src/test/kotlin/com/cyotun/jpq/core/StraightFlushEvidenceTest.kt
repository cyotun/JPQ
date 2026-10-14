package com.cyotun.jpq.core

import kotlin.test.*

class StraightFlushEvidenceTest {
    private val deck = Rank.entries.flatMap { rank ->
        (if (rank.isJoker) listOf(Suit.NONE) else listOf(Suit.SPADE, Suit.HEART, Suit.CLUB, Suit.DIAMOND))
            .flatMap { suit -> List(2) { Card(rank, suit) } }
    }
    private val initial = deck.take(27)
    private val rule = WildcardRuleEvidence(emptySet(), true)
    private fun neutral(cards: List<Card>) = cards.map { card ->
        Card(card.rank, when (card.suit) {
            Suit.HEART, Suit.DIAMOND, Suit.RED -> Suit.RED
            Suit.SPADE, Suit.CLUB, Suit.BLACK -> Suit.BLACK
            Suit.NONE -> Suit.NONE
        })
    }
    private fun group(cards: List<Card>, offset: Float = 0f, size: Float = 20f) =
        SuitedGroup(cards, cards.indices.map { index ->
            val cx = index * 32f + 20f + offset
            GlyphBox(cx - size / 2, 100f - size / 2, cx + size / 2, 100f + size / 2)
        })
    private fun event(id: String, cards: List<Card>, seat: Seat = Seat.PREVIOUS,
        sequence: Long = 1, round: String = "r1") = PlayEvent(round, id, sequence, seat, neutral(cards))
    private fun snapshot(events: List<PlayEvent> = emptyList(), phase: RoundPhase = RoundPhase.ACTIVE,
        round: String? = "r1") = RoundSnapshot(round, phase, null,
        Seat.entries.associateWith { seat -> events.filter { it.seat == seat } }, null)
    private fun update(tracker: StraightFlushEvidenceTracker, time: Long, snapshot: RoundSnapshot = snapshot(),
        own: SuitedGroup? = null, groups: Map<Seat, SuitedGroup> = emptyMap(),
        baseline: List<Card> = initial) = tracker.update(time, snapshot, neutral(baseline), own, groups, rule)
    private fun vote(tracker: StraightFlushEvidenceTracker, start: Long = 0,
        snapshot: RoundSnapshot = snapshot(), own: SuitedGroup? = null,
        groups: Map<Seat, SuitedGroup> = emptyMap(), baseline: List<Card> = initial) {
        listOf(start, start + 250, start + 500).forEach { update(tracker, it, snapshot, own, groups, baseline) }
    }

    @Test fun reportedLiveRoundFourFourFourThenFourFourZeroUsesEachSeatsCapacity() {
        // e37 round1681225336-4, 21:54:05 on2026-10-14: a previous
        // UNKNOWN rendered-substitution result persisted while every seat had4.
        // SELF_FINISHED is authoritative; SELF event history may total23, not27.
        val outside=deck.drop(27)
        fun remainingHands(counts: List<Int>): RoundSnapshot {
            val seats=listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT)
            val events=seats.mapIndexed { index,seat -> event("played-$seat",outside.drop(index*27).take(27-counts[index]),seat,index+1L) }+
                event("own-before-reveal",initial.take(23),Seat.SELF,4)
            return snapshot(events,RoundPhase.SELF_FINISHED)
        }
        val twelve=remainingHands(listOf(4,4,4))
        assertEquals(listOf(4,4,4),straightFlushRemainingHandSizes(twelve))
        assertEquals("insufficient_seat_hand",straightFlushHandCapacityExclusion(twelve)?.reason)
        assertEquals(StraightFlushState.IMPOSSIBLE,
            StraightFlushEvidenceTracker().update(0,twelve,neutral(initial),null,emptyMap(),WildcardRuleEvidence(null,false,4)).state)
        val eight=remainingHands(listOf(0,4,4))
        assertEquals(listOf(0,4,4),straightFlushRemainingHandSizes(eight))
        assertEquals(StraightFlushState.IMPOSSIBLE,straightFlushHandCapacityExclusion(eight)?.state)
        assertEquals(StraightFlushState.IMPOSSIBLE,straightFlushHandCapacityExclusion(eight.copy(phase=RoundPhase.FINISHED))?.state)
        assertNull(straightFlushHandCapacityExclusion(eight.copy(phase=RoundPhase.WAITING,roundId=null)))
        assertNull(straightFlushHandCapacityExclusion(eight.copy(phase=RoundPhase.UNAVAILABLE)))
    }

    @Test fun jokerRemainderCannotKeepAnEightCardHandLitWhenOnlyFourOrdinaryCardsRemain() {
        val outside=deck.drop(27)
        val events=listOf(event("previous",outside.take(27),Seat.PREVIOUS),
            event("opposite",outside.drop(27).take(27),Seat.OPPOSITE,2),
            event("next",outside.drop(54).take(19),Seat.NEXT,3))
        val remaining=Rank.entries.associateWith { rank -> when(rank) {
            Rank.BIG,Rank.SMALL -> 2
            Rank.TWO -> 4
            else -> 0
        } }
        val snap=snapshot(events,RoundPhase.SELF_FINISHED).copy(remaining=remaining)
        assertEquals(listOf(0,0,8),straightFlushRemainingHandSizes(snap))
        assertEquals("insufficient_ordinary_cards",straightFlushHandCapacityExclusion(snap)?.reason)
        // A malformed or incomplete rank map cannot establish this proof.
        assertNull(straightFlushHandCapacityExclusion(snap.copy(remaining=remaining.filterKeys { it!=Rank.BIG })))
    }

    @Test fun initial27IsNeutralUntilThreeDistinctFramesSpanHalfASecond() {
        val tracker = StraightFlushEvidenceTracker()
        val own = group(initial)
        update(tracker, 0, own = own)
        update(tracker, 250, own = own)
        assertEquals(neutral(initial), tracker.consumed)
        update(tracker, 250, own = own) // Repeated frame is not a vote.
        update(tracker, 499, own = own)
        assertEquals(neutral(initial), tracker.consumed)
        update(tracker, 500, own = own)
        assertEquals(initial, tracker.consumed)
    }

    @Test fun physicalWildcardProofStabilizesWithItsCardAndSurvivesAnAbstention() {
        val tracker=StraightFlushEvidenceTracker()
        val cards=listOf(Card(Rank.K,Suit.HEART),Card(Rank.K,Suit.SPADE))
        val snap=snapshot(listOf(event("pair",cards)))
        val read=group(cards).copy(physicalWildcards=listOf(true,false))
        update(tracker,0,snap,groups=mapOf(Seat.PREVIOUS to read))
        update(tracker,250,snap,groups=mapOf(Seat.PREVIOUS to read))
        assertTrue(tracker.physicalEventCards.getValue("pair").all { it.wildcard==null })
        // Input order changes without moving a card or its marker.
        val reversed=SuitedGroup(read.cards.reversed(),read.boxes.reversed(),read.physicalWildcards.reversed())
        update(tracker,500,snap,groups=mapOf(Seat.PREVIOUS to reversed))
        assertEquals(listOf(true,false),tracker.physicalEventCards.getValue("pair").map { it.wildcard })
        vote(tracker,750,snap,groups=mapOf(Seat.PREVIOUS to read.copy(physicalWildcards=emptyList())))
        assertEquals(listOf(true,false),tracker.physicalEventCards.getValue("pair").map { it.wildcard })
    }

    @Test fun contradictoryPhysicalIdentityIsWithdrawnUntilANewRound() {
        val tracker=StraightFlushEvidenceTracker()
        val cards=listOf(Card(Rank.K,Suit.HEART),Card(Rank.K,Suit.SPADE))
        val snap=snapshot(listOf(event("pair",cards)))
        val read=group(cards).copy(physicalWildcards=listOf(true,false))
        vote(tracker,0,snap,groups=mapOf(Seat.PREVIOUS to read))
        vote(tracker,750,snap,groups=mapOf(Seat.PREVIOUS to read.copy(physicalWildcards=listOf(false,false))))
        assertTrue(tracker.physicalEventCards.getValue("pair").all { it.wildcard==null })
        vote(tracker,1500,snap,groups=mapOf(Seat.PREVIOUS to read))
        assertTrue(tracker.physicalEventCards.getValue("pair").all { it.wildcard==null })
        vote(tracker,2250,snapshot(listOf(event("pair",cards,round="r2")),round="r2"),groups=mapOf(Seat.PREVIOUS to read))
        assertEquals(listOf(true,false),tracker.physicalEventCards.getValue("pair").map { it.wildcard })
    }

    @Test fun ownPlaysAreNotConsumedAgainAndCannotRefineTheInitialHand() {
        val tracker = StraightFlushEvidenceTracker()
        vote(tracker, own = group(initial))
        val self = event("self", listOf(initial.first()), Seat.SELF)
        val altered = initial.toMutableList().apply { this[6] = Card(Rank.A, Suit.DIAMOND) }
        vote(tracker, 750, snapshot(listOf(self)), group(altered))
        assertEquals(initial, tracker.consumed)
        assertEquals(27, tracker.consumed.size)
    }

    @Test fun teammateRevealNeverBecomesOurBaselineAndOtherSeatsContinueAfterSelfFinish() {
        val tracker = StraightFlushEvidenceTracker()
        update(tracker, 0)
        val played = listOf(Card(Rank.FOUR, Suit.HEART))
        val other = event("opposite", played, Seat.OPPOSITE)
        vote(tracker, 250, snapshot(listOf(other), RoundPhase.SELF_FINISHED), group(initial),
            mapOf(Seat.OPPOSITE to group(played)))
        assertEquals(neutral(initial) + played, tracker.consumed)
        assertEquals(28, tracker.consumed.size)
    }

    @Test fun repeatedSnapshotsAndSeatSelfInputNeverDoubleCountCards() {
        val tracker = StraightFlushEvidenceTracker()
        val cards = listOf(Card(Rank.FIVE, Suit.CLUB), Card(Rank.SIX, Suit.SPADE))
        val snap = snapshot(listOf(event("a", cards)))
        val groups = mapOf(Seat.PREVIOUS to group(cards), Seat.SELF to group(deck.takeLast(20)))
        vote(tracker, snapshot = snap, groups = groups)
        vote(tracker, 750, snap, groups = groups)
        assertEquals(neutral(initial) + cards, tracker.consumed)
        assertEquals(29, tracker.consumed.size)
    }

    @Test fun exportedAnnotationsUseEventIdentityNeutralFallbackAndIndependentCopies() {
        val tracker = StraightFlushEvidenceTracker()
        val cards = listOf(Card(Rank.FIVE, Suit.HEART), Card(Rank.SIX, Suit.CLUB))
        val played = event("physical-evidence", cards)
        val self = event("self", listOf(initial.first()), Seat.SELF, sequence = 2)
        update(tracker, 0, snapshot(listOf(played, self)))
        assertEquals(neutral(initial), tracker.initialCards)
        assertEquals(mapOf(played.eventId to neutral(cards)), tracker.eventCards)
        vote(tracker, 250, snapshot(listOf(played, self)), groups = mapOf(Seat.PREVIOUS to group(cards)))
        assertEquals(mapOf(played.eventId to cards), tracker.eventCards)
        val exportedInitial = tracker.initialCards as MutableList<Card>
        val exportedCards = tracker.eventCards.getValue(played.eventId) as MutableList<Card>
        exportedInitial.clear(); exportedCards.clear()
        assertEquals(neutral(initial), tracker.initialCards)
        assertEquals(cards, tracker.eventCards[played.eventId])
        tracker.reset()
        assertTrue(tracker.initialCards.isEmpty()); assertTrue(tracker.eventCards.isEmpty())
    }

    @Test fun aNewEventNeedsItsOwnVotesEvenWhenCardsAndGeometryAreIdentical() {
        val tracker = StraightFlushEvidenceTracker()
        val cards = listOf(Card(Rank.FIVE, Suit.CLUB))
        val first = event("first", cards)
        vote(tracker, snapshot = snapshot(listOf(first)), groups = mapOf(Seat.PREVIOUS to group(cards)))
        val second = event("second", cards, sequence = 2)
        val snap = snapshot(listOf(first, second))
        update(tracker, 750, snap, groups = mapOf(Seat.PREVIOUS to group(cards)))
        assertEquals(neutral(initial) + cards + neutral(cards), tracker.consumed)
        update(tracker, 1000, snap, groups = mapOf(Seat.PREVIOUS to group(cards)))
        assertEquals(neutral(initial) + cards + neutral(cards), tracker.consumed)
        update(tracker, 1250, snap, groups = mapOf(Seat.PREVIOUS to group(cards)))
        assertEquals(neutral(initial) + cards + cards, tracker.consumed)
    }

    @Test fun expandingPresentationPreservesIdentityCountAndEarlierPhysicalClaims() {
        val tracker = StraightFlushEvidenceTracker()
        val old = listOf(Card(Rank.FIVE, Suit.HEART))
        vote(tracker, snapshot = snapshot(listOf(event("a", old))), groups = mapOf(Seat.PREVIOUS to group(old)))
        val expandedColors = old + Card(Rank.SIX, Suit.CLUB)
        val snap = snapshot(listOf(event("a", expandedColors)))
        // A fresh abstention about the original card must not erase its already proven heart.
        val partial = listOf(Card(Rank.FIVE, Suit.RED), Card(Rank.SIX, Suit.CLUB))
        update(tracker, 750, snap, groups = mapOf(Seat.PREVIOUS to group(partial)))
        assertEquals(neutral(initial) + neutral(expandedColors), tracker.consumed)
        update(tracker, 1000, snap, groups = mapOf(Seat.PREVIOUS to group(partial)))
        update(tracker, 1250, snap, groups = mapOf(Seat.PREVIOUS to group(partial)))
        assertEquals(neutral(initial) + expandedColors, tracker.consumed)
        assertEquals(29, tracker.consumed.size)
    }

    @Test fun conflictingStableSuitsNeutralizeAnEventPermanentlyWithinItsRound() {
        val tracker = StraightFlushEvidenceTracker()
        val hearts = listOf(Card(Rank.FIVE, Suit.HEART))
        val diamonds = listOf(Card(Rank.FIVE, Suit.DIAMOND))
        val snap = snapshot(listOf(event("a", hearts)))
        vote(tracker, snapshot = snap, groups = mapOf(Seat.PREVIOUS to group(hearts)))
        vote(tracker, 750, snap, groups = mapOf(Seat.PREVIOUS to group(diamonds)))
        assertEquals(neutral(initial) + neutral(hearts), tracker.consumed)
        vote(tracker, 1500, snap, groups = mapOf(Seat.PREVIOUS to group(hearts)))
        assertEquals(neutral(initial) + neutral(hearts), tracker.consumed)
    }

    @Test fun expansionCannotSilentlyReplaceAnEarlierHeartWithADiamond() {
        val tracker = StraightFlushEvidenceTracker()
        val old = listOf(Card(Rank.FIVE, Suit.HEART))
        vote(tracker, snapshot = snapshot(listOf(event("a", old))), groups = mapOf(Seat.PREVIOUS to group(old)))
        val expanded = listOf(Card(Rank.FIVE, Suit.DIAMOND), Card(Rank.SIX, Suit.CLUB))
        vote(tracker, 750, snapshot(listOf(event("a", expanded))), groups = mapOf(Seat.PREVIOUS to group(expanded)))
        assertEquals(neutral(initial) + neutral(expanded), tracker.consumed)
    }

    @Test fun stablePartialAnnotationsCanMergeWithoutLosingDifferentEarlierExactCards() {
        val tracker = StraightFlushEvidenceTracker()
        val cards = listOf(Card(Rank.FIVE, Suit.HEART), Card(Rank.SIX, Suit.CLUB))
        val snap = snapshot(listOf(event("a", cards)))
        val first = listOf(cards[0], Card(Rank.SIX, Suit.BLACK))
        val second = listOf(Card(Rank.FIVE, Suit.RED), cards[1])
        vote(tracker, snapshot = snap, groups = mapOf(Seat.PREVIOUS to group(first)))
        vote(tracker, 750, snap, groups = mapOf(Seat.PREVIOUS to group(second)))
        assertEquals(neutral(initial) + cards, tracker.consumed)
    }

    @Test fun conflictingInitialSuitEvidenceDropsSuitClaimsButNotIts27RankColorCards() {
        val tracker = StraightFlushEvidenceTracker()
        vote(tracker, own = group(initial))
        val altered = initial.toMutableList().apply { this[6] = Card(Rank.A, Suit.DIAMOND) }
        vote(tracker, 750, own = group(altered))
        assertEquals(neutral(initial), tracker.consumed)
        vote(tracker, 1500, own = group(initial))
        assertEquals(neutral(initial), tracker.consumed)
    }

    @Test fun mismatchedInitialBaselineCannotBeReplacedWithinTheRound() {
        val tracker = StraightFlushEvidenceTracker()
        vote(tracker, own = group(initial))
        val changed = deck.takeLast(27)
        val result = update(tracker, 750, own = group(changed), baseline = changed)
        assertEquals("initial_hand_missing", result.reason)
        assertEquals(initial, tracker.consumed)
    }

    @Test fun aNewRoundAndInactivePhasesDiscardAnnotationsConflictsAndVotes() {
        val tracker = StraightFlushEvidenceTracker()
        vote(tracker, own = group(initial))
        update(tracker, 750, snapshot(phase = RoundPhase.FINISHED))
        assertTrue(tracker.consumed.isEmpty())
        val next = deck.takeLast(27)
        update(tracker, 1000, snapshot(round = "r2"), own = group(next), baseline = next)
        assertEquals(neutral(next), tracker.consumed)
        update(tracker, 1250, snapshot(phase = RoundPhase.UNAVAILABLE, round = "r2"), baseline = next)
        assertTrue(tracker.consumed.isEmpty())
    }

    @Test fun absentMismatchedAndMalformedGroupsInterruptVotes() {
        val cards = listOf(Card(Rank.FIVE, Suit.HEART))
        val snap = snapshot(listOf(event("a", cards)))
        val interruptions = listOf<Map<Seat, SuitedGroup>>(
            emptyMap(),
            mapOf(Seat.PREVIOUS to group(emptyList())),
            mapOf(Seat.PREVIOUS to group(listOf(Card(Rank.SIX, Suit.HEART)))),
            mapOf(Seat.PREVIOUS to SuitedGroup(cards, emptyList())),
            mapOf(Seat.PREVIOUS to SuitedGroup(cards, listOf(GlyphBox(Float.NaN, 0f, 20f, 20f)))),
            mapOf(Seat.PREVIOUS to SuitedGroup(cards, listOf(GlyphBox(20f, 0f, 0f, 20f))))
        )
        for (interruption in interruptions) {
            val tracker = StraightFlushEvidenceTracker()
            update(tracker, 0, snap, groups = mapOf(Seat.PREVIOUS to group(cards)))
            update(tracker, 250, snap, groups = mapOf(Seat.PREVIOUS to group(cards)))
            update(tracker, 400, snap, groups = interruption)
            update(tracker, 500, snap, groups = mapOf(Seat.PREVIOUS to group(cards)))
            update(tracker, 750, snap, groups = mapOf(Seat.PREVIOUS to group(cards)))
            assertEquals(neutral(initial) + neutral(cards), tracker.consumed)
            update(tracker, 1000, snap, groups = mapOf(Seat.PREVIOUS to group(cards)))
            assertEquals(neutral(initial) + cards, tracker.consumed)
        }
    }

    @Test fun translationScaleAndLongFrameGapsRestartVotes() {
        val cards = listOf(Card(Rank.FIVE, Suit.CLUB))
        val snap = snapshot(listOf(event("a", cards)))
        for ((changed, nextTime) in listOf(group(cards, 30f) to 500L, group(cards, size = 40f) to 500L,
            group(cards) to 1501L)) {
            val tracker = StraightFlushEvidenceTracker()
            update(tracker, 0, snap, groups = mapOf(Seat.PREVIOUS to group(cards)))
            update(tracker, 250, snap, groups = mapOf(Seat.PREVIOUS to group(cards)))
            update(tracker, nextTime, snap, groups = mapOf(Seat.PREVIOUS to changed))
            update(tracker, nextTime + 250, snap, groups = mapOf(Seat.PREVIOUS to changed))
            assertEquals(neutral(initial) + neutral(cards), tracker.consumed)
            update(tracker, nextTime + 500, snap, groups = mapOf(Seat.PREVIOUS to changed))
            assertEquals(neutral(initial) + cards, tracker.consumed)
        }
    }

    @Test fun reorderedPairedInputUsesGeometryInsteadOfTheCallerListOrder() {
        val tracker = StraightFlushEvidenceTracker()
        val cards = listOf(Card(Rank.FIVE, Suit.HEART), Card(Rank.SIX, Suit.SPADE))
        val original = group(cards)
        val reversed = SuitedGroup(original.cards.reversed(), original.boxes.reversed())
        val snap = snapshot(listOf(event("a", cards)))
        update(tracker, 0, snap, groups = mapOf(Seat.PREVIOUS to original))
        update(tracker, 250, snap, groups = mapOf(Seat.PREVIOUS to reversed))
        update(tracker, 500, snap, groups = mapOf(Seat.PREVIOUS to original))
        assertEquals(neutral(initial) + cards, tracker.consumed)
    }
}

class WildcardRuleTrackerTest {
    private val single = WildcardMode.SINGLE
    private val double = WildcardMode.DOUBLE
    private val grade = setOf(Rank.SEVEN)
    private fun observe(tracker: WildcardRuleTracker, time: Long, mode: WildcardMode = single,
        ranks: Set<Rank> = grade, ocr: Long? = time) = tracker.observe(time, ocr, mode, ranks)
    private fun confirm(tracker: WildcardRuleTracker, start: Long = 0, mode: WildcardMode = single,
        ranks: Set<Rank> = grade): WildcardRuleEvidence {
        observe(tracker, start, mode, ranks); observe(tracker, start + 250, mode, ranks)
        return observe(tracker, start + 500, mode, ranks)
    }

    @Test fun confirmsOneOrTwoEffectiveRanksAfterDistinctStableEvidence() {
        val tracker = WildcardRuleTracker()
        assertFalse(observe(tracker, 0).confirmed)
        assertFalse(observe(tracker, 250).confirmed)
        assertEquals(WildcardRuleEvidence(grade, true), observe(tracker, 500))
        val two = setOf(Rank.SEVEN, Rank.J)
        assertEquals(WildcardRuleEvidence(two, true), confirm(WildcardRuleTracker(), mode = double, ranks = two))
    }

    @Test fun oneOcrSnapshotRepeatedAcrossNewFramesCannotConfirmAMode() {
        val tracker = WildcardRuleTracker()
        listOf(0L, 250L, 500L, 750L, 1000L).forEach { assertFalse(observe(tracker, it, ocr = 0).confirmed) }
        assertFalse(observe(tracker, 1250).confirmed)
        assertTrue(observe(tracker, 1500).confirmed)
    }

    @Test fun repeatedOrOlderFramesCannotAccumulateRankOrModeVotes() {
        val tracker = WildcardRuleTracker()
        observe(tracker, 0)
        repeat(10) { assertFalse(observe(tracker, 0).confirmed) }
        observe(tracker, 250)
        assertFalse(observe(tracker, 100, double, setOf(Rank.FIVE, Rank.SIX)).confirmed)
        assertEquals(WildcardRuleEvidence(grade, true), observe(tracker, 500))
    }

    @Test fun futureStaleMissingAndUnknownModeOcrNeverInventARule() {
        for (ocr in listOf<Long?>(null, 10000, -5000)) {
            val tracker = WildcardRuleTracker()
            listOf(0L, 250L, 500L, 750L).forEach { assertFalse(observe(tracker, it, ocr = ocr).confirmed) }
        }
        assertFalse(confirm(WildcardRuleTracker(), mode = WildcardMode.UNKNOWN).confirmed)
    }

    @Test fun missingCandidateRankBreaksItsConfirmationSequence() {
        val tracker = WildcardRuleTracker()
        observe(tracker, 0); observe(tracker, 250)
        observe(tracker, 400, ranks = emptySet())
        assertFalse(observe(tracker, 500).confirmed)
        assertFalse(observe(tracker, 750).confirmed)
        assertEquals(WildcardRuleEvidence(grade, true), observe(tracker, 1000))
    }

    @Test fun aLongCandidateRankGapNeedsThreeNewFrames() {
        val tracker = WildcardRuleTracker()
        observe(tracker, 0); observe(tracker, 250)
        assertFalse(observe(tracker, 1501).confirmed)
        assertFalse(observe(tracker, 1751).confirmed)
        assertEquals(WildcardRuleEvidence(grade, true), observe(tracker, 2001))
    }

    @Test fun longOcrGapsNeedFreshModeVotesInsteadOfAccumulatingOldOnes() {
        val tracker = WildcardRuleTracker()
        observe(tracker, 0); observe(tracker, 250)
        assertFalse(observe(tracker, 2501).confirmed)
        assertFalse(observe(tracker, 2751).confirmed)
        assertEquals(WildcardRuleEvidence(grade, true), observe(tracker, 3001))
    }

    @Test fun aConfirmedRoundRankSurvivesLeavingTheVisibleHand() {
        val tracker = WildcardRuleTracker()
        confirm(tracker)
        assertEquals(WildcardRuleEvidence(grade, true), observe(tracker, 750, ranks = emptySet(), ocr = null))
        assertEquals(WildcardRuleEvidence(grade, true), observe(tracker, 4000, ranks = emptySet(), ocr = null))
    }

    @Test fun changingModeImmediatelyWithdrawsTheOldRuleAndNeedsNewCompleteEvidence() {
        val tracker = WildcardRuleTracker()
        confirm(tracker)
        val two = setOf(Rank.FIVE, Rank.SIX)
        assertFalse(observe(tracker, 750, double, two).confirmed)
        assertFalse(observe(tracker, 1000, double, two).confirmed)
        assertEquals(WildcardRuleEvidence(two, true), observe(tracker, 1250, double, two))
        assertFalse(observe(tracker, 1500, single, grade).confirmed)
        assertFalse(observe(tracker, 1750, single, grade).confirmed)
        assertEquals(WildcardRuleEvidence(grade, true), observe(tracker, 2000, single, grade))
    }

    @Test fun aDistinctUnknownModeReadingWithdrawsPriorModeUntilReconfirmed() {
        val tracker = WildcardRuleTracker()
        confirm(tracker)
        assertFalse(observe(tracker, 750, WildcardMode.UNKNOWN).confirmed)
        assertFalse(observe(tracker, 1000).confirmed)
        assertFalse(observe(tracker, 1250).confirmed)
        assertEquals(WildcardRuleEvidence(grade, true), observe(tracker, 1500))
    }

    @Test fun extraOrInsufficientConfirmedRanksKeepTheRuleUnknown() {
        val two = setOf(Rank.FIVE, Rank.SIX)
        assertFalse(confirm(WildcardRuleTracker(), ranks = two).confirmed)
        assertFalse(confirm(WildcardRuleTracker(), mode = double, ranks = grade).confirmed)
        val tracker = WildcardRuleTracker()
        confirm(tracker)
        observe(tracker, 750, ranks = setOf(Rank.J)); observe(tracker, 1000, ranks = setOf(Rank.J))
        assertFalse(observe(tracker, 1250, ranks = setOf(Rank.J)).confirmed)
    }

    @Test fun jokerMarkersDoNotBecomeWildcardRanksAndResetClearsRoundEvidence() {
        assertFalse(confirm(WildcardRuleTracker(), ranks = setOf(Rank.BIG, Rank.SMALL)).confirmed)
        val tracker = WildcardRuleTracker()
        confirm(tracker); tracker.reset()
        assertFalse(observe(tracker, 0).confirmed)
        assertFalse(observe(tracker, 250).confirmed)
        assertEquals(WildcardRuleEvidence(grade, true), observe(tracker, 500))
    }
}
