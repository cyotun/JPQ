package com.cyotun.jpq.core

import kotlin.test.*

/** Physical pools are constructed before rendering; rendered substitutions never supply labels. */
class SuitRegressionTest {
    private val suits = listOf(Suit.SPADE, Suit.HEART, Suit.CLUB, Suit.DIAMOND)
    private val deck = Rank.entries.flatMap { rank ->
        (if (rank.isJoker) listOf(Suit.NONE) else suits).flatMap { suit -> List(2) { Card(rank, suit) } }
    }
    private val sequence = listOf(Rank.A, Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.SIX,
        Rank.SEVEN, Rank.EIGHT, Rank.NINE, Rank.TEN, Rank.J, Rank.Q, Rank.K, Rank.A)

    private fun consumedLeaving(remaining: List<Card>): List<Card> {
        val left = remaining.toMutableList()
        return deck.filter { !left.remove(it) }.also { assertTrue(left.isEmpty()) }
    }

    @Test fun capacityValidSubstitutionFacesPreserveAllFortyActualNaturalWindows() {
        var compared = 0
        for (suit in suits) for (start in 0..9) {
            val run = sequence.subList(start, start + 5).map { Card(it, suit) }
            val consumed = consumedLeaving(run)
            for (grade in Rank.entries.filterNot { it.isJoker }) {
                val physicalWild = Card(grade, Suit.HEART)
                if (physicalWild !in consumed) continue
                // Every replacement targets one actual remaining natural card, leaving
                // the rendered two-copy capacity valid. This avoids passing merely
                // because an invalid rendered capacity produces UNKNOWN.
                for (declared in run) {
                    val rendered = consumed.toMutableList()
                    assertTrue(rendered.remove(physicalWild)); rendered.add(declared)
                    assertTrue(rendered.groupingBy { it }.eachCount().values.all { it <= 2 })
                    val withGrade = StraightFlushEvaluator.evaluate(rendered, setOf(grade), true)
                    assertNotEquals(StraightFlushState.IMPOSSIBLE, withGrade.state,
                        "Known grade=$grade, suit=$suit, start=$start, declared=$declared")
                    val naturalOnly = StraightFlushEvaluator.evaluate(rendered, emptySet(), true, 1)
                    assertNotEquals(StraightFlushState.IMPOSSIBLE, naturalOnly.state,
                        "Restoration grade=$grade, suit=$suit, start=$start, declared=$declared")
                    compared++
                }
            }
        }
        assertEquals(2600, compared)
    }

    @Test fun unknownGradeFallbackScopeCannotBeReportedAsAWildcardWitness() {
        val remainder = listOf(Rank.A, Rank.TWO, Rank.THREE, Rank.FOUR).map { Card(it, Suit.CLUB) } +
            Card(Rank.SEVEN, Suit.HEART)
        val consumed = consumedLeaving(remainder)
        val qualified = StraightFlushEvaluator.evaluate(consumed, setOf(Rank.SEVEN), true)
        assertEquals(StraightFlushState.POSSIBLE, qualified.state)
        assertEquals(1, qualified.witness?.wildcardsNeeded)
        val fallback = StraightFlushEvaluator.evaluate(consumed, emptySet(), true)
        assertEquals(StraightFlushState.IMPOSSIBLE, fallback.state)
        assertNull(fallback.witness)
        val uncertainRendering = StraightFlushEvaluator.evaluate(consumed, emptySet(), true, 1)
        assertEquals(StraightFlushState.UNKNOWN, uncertainRendering.state)
        assertEquals("rendered_substitution_uncertain", uncertainRendering.reason)
        assertNull(uncertainRendering.witness)
    }

    @Test fun visibilityGapPreservesAcceptedSuitRecordsButRestartsAnUnacceptedEvent() {
        fun neutral(cards: List<Card>) = cards.map { it.copy(suit = when (it.suit) {
            Suit.HEART, Suit.DIAMOND -> Suit.RED
            Suit.SPADE, Suit.CLUB -> Suit.BLACK
            else -> it.suit
        }) }
        fun group(cards: List<Card>) = SuitedGroup(cards, cards.indices.map { index ->
            GlyphBox(index * 32f, 100f, index * 32f + 20f, 120f)
        })
        val initial = deck.take(27)
        val first = PlayEvent("visibility-round", "first", 1, Seat.PREVIOUS,
            listOf(Card(Rank.FIVE, Suit.RED)))
        val later = PlayEvent("visibility-round", "later", 2, Seat.PREVIOUS,
            listOf(Card(Rank.SIX, Suit.BLACK)))
        fun snapshot(events: List<PlayEvent>) = RoundSnapshot("visibility-round", RoundPhase.ACTIVE,
            null, Seat.entries.associateWith { seat -> events.filter { it.seat == seat } }, null)
        val tracker = StraightFlushEvidenceTracker()
        fun update(time: Long, events: List<PlayEvent>, cards: List<Card>) = tracker.update(time,
            snapshot(events), neutral(initial), group(initial), mapOf(Seat.PREVIOUS to group(cards)),
            WildcardRuleEvidence(emptySet(), true))
        listOf(0L, 250L, 500L).forEach {
            update(it, listOf(first), listOf(Card(Rank.FIVE, Suit.HEART)))
        }
        listOf(750L, 1000L).forEach {
            update(it, listOf(first, later), listOf(Card(Rank.SIX, Suit.CLUB)))
        }
        tracker.clearCandidateVotes()
        assertEquals(initial, tracker.initialCards)
        assertEquals(listOf(Card(Rank.FIVE, Suit.HEART)), tracker.eventCards["first"])
        update(1250, listOf(first, later), listOf(Card(Rank.SIX, Suit.CLUB)))
        update(1500, listOf(first, later), listOf(Card(Rank.SIX, Suit.CLUB)))
        assertEquals(listOf(Card(Rank.SIX, Suit.BLACK)), tracker.eventCards["later"])
        update(1750, listOf(first, later), listOf(Card(Rank.SIX, Suit.CLUB)))
        assertEquals(initial + Card(Rank.FIVE, Suit.HEART) + Card(Rank.SIX, Suit.CLUB), tracker.consumed)
    }

    @Test fun visibilityGapPreservesAcceptedGradeButRequiresFreshVotesForAnotherRank() {
        val tracker = WildcardRuleTracker()
        fun observe(time: Long, rank: Rank) = tracker.observe(time, time,
            WildcardMode.SINGLE, setOf(rank))
        listOf(0L, 250L, 500L).forEach { observe(it, Rank.SEVEN) }
        assertEquals(setOf(Rank.SEVEN), tracker.observedRanks)
        observe(750, Rank.J); observe(1000, Rank.J)
        tracker.clearCandidateVotes()
        val firstFresh = observe(1250, Rank.J)
        val secondFresh = observe(1500, Rank.J)
        assertTrue(firstFresh.confirmed); assertTrue(secondFresh.confirmed)
        assertEquals(setOf(Rank.SEVEN), firstFresh.ranks)
        assertEquals(setOf(Rank.SEVEN), tracker.observedRanks)
        assertFalse(observe(1750, Rank.J).confirmed)
        assertEquals(setOf(Rank.SEVEN, Rank.J), tracker.observedRanks)
    }

    private fun colorOnly(cards: List<Card>) = cards.map { it.copy(suit = when (it.suit) {
        Suit.HEART, Suit.DIAMOND -> Suit.RED
        Suit.SPADE, Suit.CLUB -> Suit.BLACK
        else -> it.suit
    }) }
    private fun ownGroup(cards: List<Card>) = SuitedGroup(cards, cards.indices.map { index ->
        GlyphBox(index * 32f, 100f, index * 32f + 20f, 120f)
    })
    private fun ownEvent(id: String, cards: List<Card>, seat: Seat = Seat.SELF, sequence: Long = 1) =
        PlayEvent("quick-play", id, sequence, seat, colorOnly(cards))
    private fun ownRound(events: List<PlayEvent> = emptyList(), phase: RoundPhase = RoundPhase.ACTIVE) =
        RoundSnapshot("quick-play", phase, null,
            Seat.entries.associateWith { seat -> events.filter { it.seat == seat } }, null)
    private fun updateOwn(tracker: StraightFlushEvidenceTracker, time: Long, initial: List<Card>,
        current: List<Card>, events: List<PlayEvent> = emptyList(), phase: RoundPhase = RoundPhase.ACTIVE,
        others: Map<Seat, SuitedGroup> = emptyMap()) = tracker.update(time, ownRound(events, phase),
        colorOnly(initial), ownGroup(current), others, WildcardRuleEvidence(emptySet(), true))
    private fun sameCards(expected: List<Card>, actual: List<Card>) =
        assertEquals(expected.groupingBy { it }.eachCount(), actual.groupingBy { it }.eachCount())

    @Test fun fastFirstPlayStillRefinesRemainingFlowersWithoutConsumingSelfTwice() {
        val initial = deck.take(27)
        val first = Card(Rank.A, Suit.HEART)
        val current = initial.toMutableList().apply { assertTrue(remove(first)) }
        val outside = ownEvent("outside", listOf(Card(Rank.FIVE, Suit.CLUB)), Seat.PREVIOUS, 2)
        val events = listOf(ownEvent("self", listOf(first)), outside)
        val tracker = StraightFlushEvidenceTracker()
        updateOwn(tracker, 0, initial, current, events)
        updateOwn(tracker, 250, initial, current, events)
        sameCards(colorOnly(initial), tracker.initialCards)
        updateOwn(tracker, 500, initial, current, events)
        sameCards(current + colorOnly(listOf(first)), tracker.initialCards)
        sameCards(colorOnly(initial), colorOnly(tracker.initialCards))
        assertEquals(27, tracker.initialCards.size)
        assertEquals(28, tracker.consumed.size)
        assertEquals(setOf("outside"), tracker.eventCards.keys)
        assertEquals(listOf(Card(Rank.A, Suit.RED)), events.first().cards)
    }

    @Test fun reducedUnknownHandCannotErasePreviouslyAcceptedPhysicalSuits() {
        val initial = deck.take(27)
        val tracker = StraightFlushEvidenceTracker()
        listOf(0L, 250L, 500L).forEach { updateOwn(tracker, it, initial, initial) }
        val first = Card(Rank.A, Suit.HEART)
        val current = initial.toMutableList().apply { assertTrue(remove(first)) }
        listOf(750L, 1000L, 1250L).forEach {
            updateOwn(tracker, it, initial, colorOnly(current), listOf(ownEvent("self", listOf(first))))
        }
        sameCards(initial, tracker.initialCards)
        sameCards(initial, tracker.consumed)
    }

    @Test fun contradictoryReducedHandRevokesOnlyItsRankColorPoolPermanently() {
        val initial = deck.take(27)
        val tracker = StraightFlushEvidenceTracker()
        listOf(0L, 250L, 500L).forEach { updateOwn(tracker, it, initial, initial) }
        val first = Card(Rank.A, Suit.HEART)
        val current = initial.toMutableList().apply { assertTrue(remove(first)) }
        val conflicting = current.toMutableList().apply {
            this[indexOf(Card(Rank.Q, Suit.HEART))] = Card(Rank.Q, Suit.DIAMOND)
        }
        val events = listOf(ownEvent("self", listOf(first)))
        listOf(750L, 1000L, 1250L).forEach { updateOwn(tracker, it, initial, conflicting, events) }
        val qualified=initial.map { card ->
            if(card.rank==Rank.Q && card.suit in setOf(Suit.HEART,Suit.DIAMOND)) card.copy(suit=Suit.RED) else card
        }
        sameCards(qualified, tracker.initialCards)
        sameCards(initial.filter { it.rank!=Rank.Q || it.suit in setOf(Suit.SPADE,Suit.CLUB) },
            tracker.initialCards.filter { it.rank!=Rank.Q || it.suit in setOf(Suit.SPADE,Suit.CLUB) })
        listOf(1500L, 1750L, 2000L).forEach { updateOwn(tracker, it, initial, current, events) }
        sameCards(qualified, tracker.initialCards)
        sameCards(colorOnly(initial), colorOnly(tracker.initialCards))
        assertEquals(27, tracker.consumed.size)
    }

    @Test fun malformedReconstructionInterruptsReducedHandVotes() {
        val initial = deck.take(27)
        val first = Card(Rank.A, Suit.HEART)
        val current = initial.toMutableList().apply { assertTrue(remove(first)) }
        val wrongPoint = current.toMutableList().apply {
            this[indexOf(Card(Rank.Q, Suit.HEART))] = Card(Rank.J, Suit.HEART)
        }
        val events = listOf(ownEvent("self", listOf(first)))
        for (invalid in listOf(initial, wrongPoint)) {
            val tracker = StraightFlushEvidenceTracker()
            updateOwn(tracker, 0, initial, current, events)
            updateOwn(tracker, 250, initial, current, events)
            updateOwn(tracker, 500, initial, invalid, events)
            updateOwn(tracker, 750, initial, current, events)
            updateOwn(tracker, 1000, initial, current, events)
            sameCards(colorOnly(initial), tracker.initialCards)
            updateOwn(tracker, 1250, initial, current, events)
            sameCards(current + colorOnly(listOf(first)), tracker.initialCards)
        }
    }

    @Test fun aDifferentCommittedSelfIdentityRestartsEvenAnIdenticalCurrentHandVote() {
        val initial = deck.take(27)
        val first = Card(Rank.A, Suit.HEART)
        val current = initial.toMutableList().apply { assertTrue(remove(first)) }
        val tracker = StraightFlushEvidenceTracker()
        val original = listOf(ownEvent("original", listOf(first)))
        val different = listOf(ownEvent("different", listOf(first)))
        updateOwn(tracker, 0, initial, current, original)
        updateOwn(tracker, 250, initial, current, original)
        updateOwn(tracker, 500, initial, current, different)
        updateOwn(tracker, 750, initial, current, different)
        sameCards(colorOnly(initial), tracker.initialCards)
        updateOwn(tracker, 1000, initial, current, different)
        sameCards(current + colorOnly(listOf(first)), tracker.initialCards)
    }

    @Test fun ownCompletionCannotFinishPendingOwnVotesWhileOutsideSuitVotesContinue() {
        val initial = deck.take(27)
        val first = Card(Rank.A, Suit.HEART)
        val current = initial.toMutableList().apply { assertTrue(remove(first)) }
        val self = ownEvent("self", listOf(first))
        val tracker = StraightFlushEvidenceTracker()
        updateOwn(tracker, 0, initial, current, listOf(self))
        updateOwn(tracker, 250, initial, current, listOf(self))
        val outsideCard = Card(Rank.FIVE, Suit.CLUB)
        val outside = ownEvent("outside", listOf(outsideCard), Seat.OPPOSITE, 2)
        listOf(500L, 750L, 1000L).forEach { updateOwn(tracker, it, initial, current,
            listOf(self, outside), RoundPhase.SELF_FINISHED, mapOf(Seat.OPPOSITE to ownGroup(listOf(outsideCard)))) }
        sameCards(colorOnly(initial), tracker.initialCards)
        assertEquals(listOf(outsideCard), tracker.eventCards["outside"])
        sameCards(colorOnly(initial) + outsideCard, tracker.consumed)
    }
}
