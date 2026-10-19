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

    @Test fun aProvenGoldHeartSurvivesLaterSuitAbstentionOnTheSamePresentation() {
        val tracker=StraightFlushEvidenceTracker()
        val cards=listOf(Card(Rank.TWO,Suit.HEART),Card(Rank.SIX,Suit.CLUB))
        val snap=snapshot(listOf(event("gold",cards)))
        vote(tracker,0,snap,groups=mapOf(Seat.PREVIOUS to group(cards).copy(physicalWildcards=listOf(true,null))))
        vote(tracker,750,snap,groups=mapOf(Seat.PREVIOUS to group(neutral(cards))))
        assertEquals(cards,tracker.eventCards.getValue("gold"))
        assertEquals(listOf(true,null),tracker.physicalEventCards.getValue("gold").map { it.wildcard })
    }

    @Test fun refiningAnotherCardsSuitDoesNotEraseAProvenGoldHeart() {
        val tracker=StraightFlushEvidenceTracker()
        val partial=listOf(Card(Rank.TWO,Suit.HEART),Card(Rank.SIX,Suit.BLACK))
        val exact=listOf(partial[0],Card(Rank.SIX,Suit.CLUB))
        val snap=snapshot(listOf(event("gold",exact)))
        vote(tracker,0,snap,groups=mapOf(Seat.PREVIOUS to group(partial).copy(physicalWildcards=listOf(true,null))))
        vote(tracker,750,snap,groups=mapOf(Seat.PREVIOUS to group(exact)))
        assertEquals(exact,tracker.eventCards.getValue("gold"))
        assertEquals(listOf(true,null),tracker.physicalEventCards.getValue("gold").map { it.wildcard })
    }

    @Test fun aMarkerConflictStillWithdrawsProofWhenAnotherCardsSuitIsRefined() {
        val tracker=StraightFlushEvidenceTracker()
        val partial=listOf(Card(Rank.TWO,Suit.HEART),Card(Rank.SIX,Suit.BLACK))
        val exact=listOf(partial[0],Card(Rank.SIX,Suit.CLUB))
        val snap=snapshot(listOf(event("gold",exact)))
        vote(tracker,0,snap,groups=mapOf(Seat.PREVIOUS to group(partial).copy(physicalWildcards=listOf(true,null))))
        vote(tracker,750,snap,groups=mapOf(Seat.PREVIOUS to group(exact).copy(physicalWildcards=listOf(false,null))))
        assertTrue(tracker.physicalEventCards.getValue("gold").all { it.wildcard==null })
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

    @Test fun expandedColorVersionDoesNotInheritEarlierPhysicalClaims() {
        val tracker = StraightFlushEvidenceTracker()
        val old = listOf(Card(Rank.FIVE, Suit.HEART))
        vote(tracker, snapshot = snapshot(listOf(event("a", old))), groups = mapOf(Seat.PREVIOUS to group(old)))
        val expandedColors = old + Card(Rank.SIX, Suit.CLUB)
        val snap = snapshot(listOf(event("a", expandedColors)))
        // Even a strict color expansion must prove its own full presentation.
        // A former heart cannot be assigned to a new unknown red slot.
        val partial = listOf(Card(Rank.FIVE, Suit.RED), Card(Rank.SIX, Suit.CLUB))
        update(tracker, 750, snap, groups = mapOf(Seat.PREVIOUS to group(partial)))
        assertEquals(neutral(initial) + neutral(expandedColors), tracker.consumed)
        update(tracker, 1000, snap, groups = mapOf(Seat.PREVIOUS to group(partial)))
        update(tracker, 1250, snap, groups = mapOf(Seat.PREVIOUS to group(partial)))
        assertEquals(neutral(initial) + partial, tracker.consumed)
        assertEquals(29, tracker.consumed.size)
        vote(tracker,1500,snap,groups=mapOf(Seat.PREVIOUS to group(expandedColors)))
        assertEquals(neutral(initial)+expandedColors,tracker.consumed)
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

    @Test fun eventSuitConflictKeepsOtherRanksAndTheirPairedMarkersUntilItsColorVersionChanges() {
        val cards=listOf(Card(Rank.NINE,Suit.DIAMOND),Card(Rank.TEN,Suit.HEART),
            Card(Rank.J,Suit.SPADE),Card(Rank.Q,Suit.CLUB),Card(Rank.K,Suit.DIAMOND))
        val excluded=(cards.map { it.rank }+Rank.A).toSet()
        val baseline=deck.filter { it.rank !in excluded }.take(27)
        val ledger=RoundLedger().apply {
            begin("r1",neutral(baseline))
            commit(PlayEvent("r1","run",1,Seat.NEXT,neutral(cards)))
        }
        val tracker=StraightFlushEvidenceTracker()
        val read=group(cards).copy(physicalWildcards=listOf(false,true,false,false,false))
        vote(tracker,snapshot=ledger.snapshot(),groups=mapOf(Seat.NEXT to read),baseline=baseline)
        val grade=WildcardRuleEvidence(setOf(Rank.TEN),true)
        fun counter()=GradeCardCounter.evaluate(ledger.snapshot(),tracker.initialCards,tracker.physicalEventCards,
            grade,faces=TableCardFaces.ORIGINAL)
        val before=counter()
        assertEquals(1,before.heartCount?.exact); assertEquals(6,before.ordinaryCounts[Rank.TEN]?.exact)

        val changed=read.copy(cards=listOf(Card(Rank.NINE,Suit.HEART))+cards.drop(1),physicalWildcards=emptyList())
        val reordered=SuitedGroup(changed.cards.reversed(),changed.boxes.reversed())
        vote(tracker,750,ledger.snapshot(),groups=mapOf(Seat.NEXT to reordered),baseline=baseline)
        val expected=listOf(Card(Rank.NINE,Suit.RED))+cards.drop(1)
        assertEquals(expected,tracker.eventCards.getValue("run"))
        assertEquals(listOf(null,true,false,false,false),tracker.physicalEventCards.getValue("run").map { it.wildcard })
        assertEquals(before,counter())
        vote(tracker,1500,ledger.snapshot(),groups=mapOf(Seat.NEXT to read),baseline=baseline)
        assertEquals(expected,tracker.eventCards.getValue("run"))
        assertNull(tracker.physicalEventCards.getValue("run").first().wildcard)

        val expanded=cards+Card(Rank.A,Suit.SPADE)
        ledger.replaceLatestObservedPresentation("run",neutral(expanded))
        val complete=group(expanded)
        update(tracker,2250,ledger.snapshot(),groups=mapOf(Seat.NEXT to complete),baseline=baseline)
        assertEquals(neutral(expanded),tracker.eventCards.getValue("run"))
        assertTrue(tracker.physicalEventCards.getValue("run").all { it.wildcard==null })
        update(tracker,2500,ledger.snapshot(),groups=mapOf(Seat.NEXT to complete),baseline=baseline)
        assertEquals(neutral(expanded),tracker.eventCards.getValue("run"))
        update(tracker,2750,ledger.snapshot(),groups=mapOf(Seat.NEXT to complete),baseline=baseline)
        assertEquals(expanded,tracker.eventCards.getValue("run"))
        // Fresh full-group suit proof may establish the new version; the old
        // TEN marker cannot be inherited even at the same numerical index.
        assertTrue(tracker.physicalEventCards.getValue("run").all { it.wildcard==null })
        assertEquals(before,counter())
    }

    @Test fun repeatedRedRanksCannotTransferMarkersBetweenDifferentSuits() {
        val tracker=StraightFlushEvidenceTracker()
        val cards=listOf(Card(Rank.TEN,Suit.HEART),Card(Rank.TEN,Suit.DIAMOND),Card(Rank.NINE,Suit.DIAMOND))
        val snap=snapshot(listOf(event("duplicates",cards,Seat.NEXT)))
        val read=group(cards).copy(physicalWildcards=listOf(true,false,false))
        vote(tracker,snapshot=snap,groups=mapOf(Seat.NEXT to read))
        // Caller order must not change the sorted card/marker slots.
        val reversed=SuitedGroup(read.cards.reversed(),read.boxes.reversed())
        vote(tracker,750,snap,groups=mapOf(Seat.NEXT to reversed))
        assertEquals(listOf(true,false,false),tracker.physicalEventCards.getValue("duplicates").map { it.wildcard })

        val swapped=group(listOf(cards[1],cards[0],Card(Rank.NINE,Suit.HEART)))
        vote(tracker,1500,snap,groups=mapOf(Seat.NEXT to swapped))
        assertEquals(listOf(cards[1],cards[0],Card(Rank.NINE,Suit.RED)),tracker.eventCards.getValue("duplicates"))
        assertTrue(tracker.physicalEventCards.getValue("duplicates").all { it.wildcard==null })
        val freshMarkers=swapped.copy(physicalWildcards=listOf(false,true,true))
        vote(tracker,2250,snap,groups=mapOf(Seat.NEXT to freshMarkers))
        assertEquals(listOf(false,true,null),tracker.physicalEventCards.getValue("duplicates").map { it.wildcard })
        assertEquals(Suit.DIAMOND,tracker.physicalEventCards.getValue("duplicates")[0].rendered.suit)
        assertEquals(Suit.HEART,tracker.physicalEventCards.getValue("duplicates")[1].rendered.suit)
    }

    @Test fun changedEventColorsDiscardOldSuitConflictsAndMarkersBeforeNewVotes() {
        val tracker=StraightFlushEvidenceTracker()
        val cards=listOf(Card(Rank.NINE,Suit.DIAMOND),Card(Rank.TEN,Suit.HEART))
        val snap=snapshot(listOf(event("versioned",cards)))
        val read=group(cards).copy(physicalWildcards=listOf(false,true))
        vote(tracker,snapshot=snap,groups=mapOf(Seat.PREVIOUS to read))
        val conflicting=read.copy(cards=listOf(Card(Rank.NINE,Suit.HEART),cards[1]))
        vote(tracker,750,snap,groups=mapOf(Seat.PREVIOUS to conflicting))
        assertEquals(Suit.RED,tracker.eventCards.getValue("versioned").first().suit)

        val changed=listOf(cards[0],Card(Rank.TEN,Suit.CLUB))
        val next=snapshot(listOf(event("versioned",changed)))
        update(tracker,1500,next,groups=mapOf(Seat.PREVIOUS to group(changed)))
        assertEquals(neutral(changed),tracker.eventCards.getValue("versioned"))
        assertTrue(tracker.physicalEventCards.getValue("versioned").all { it.wildcard==null })
        update(tracker,1750,next,groups=mapOf(Seat.PREVIOUS to group(changed)))
        assertEquals(neutral(changed),tracker.eventCards.getValue("versioned"))
        update(tracker,2000,next,groups=mapOf(Seat.PREVIOUS to group(changed)))
        assertEquals(changed,tracker.eventCards.getValue("versioned"))
        assertTrue(tracker.physicalEventCards.getValue("versioned").all { it.wildcard==null })
    }

    @Test fun removingAnEventDiscardsItsColorConflictsAndRequiresNewProofIfItsIdReturns() {
        val tracker=StraightFlushEvidenceTracker()
        val cards=listOf(Card(Rank.NINE,Suit.DIAMOND),Card(Rank.TEN,Suit.HEART))
        val snap=snapshot(listOf(event("removed",cards)))
        vote(tracker,snapshot=snap,groups=mapOf(Seat.PREVIOUS to group(cards)))
        vote(tracker,750,snap,groups=mapOf(Seat.PREVIOUS to group(listOf(Card(Rank.NINE,Suit.HEART),cards[1]))))
        assertEquals(Suit.RED,tracker.eventCards.getValue("removed").first().suit)
        update(tracker,1500,snapshot())
        assertTrue(tracker.eventCards.isEmpty()); assertTrue(tracker.physicalEventCards.isEmpty())
        update(tracker,1750,snap,groups=mapOf(Seat.PREVIOUS to group(cards)))
        assertEquals(neutral(cards),tracker.eventCards.getValue("removed"))
        update(tracker,2000,snap,groups=mapOf(Seat.PREVIOUS to group(cards)))
        assertEquals(neutral(cards),tracker.eventCards.getValue("removed"))
        update(tracker,2250,snap,groups=mapOf(Seat.PREVIOUS to group(cards)))
        assertEquals(cards,tracker.eventCards.getValue("removed"))
    }

    @Test fun expandedColorVersionNeedsThreeFreshFramesBeforeAcceptingItsSuits() {
        val tracker = StraightFlushEvidenceTracker()
        val old = listOf(Card(Rank.FIVE, Suit.HEART))
        vote(tracker, snapshot = snapshot(listOf(event("a", old))), groups = mapOf(Seat.PREVIOUS to group(old)))
        val expanded = listOf(Card(Rank.FIVE, Suit.DIAMOND), Card(Rank.SIX, Suit.CLUB))
        val snap=snapshot(listOf(event("a",expanded)))
        update(tracker,750,snap,groups=mapOf(Seat.PREVIOUS to group(expanded)))
        assertEquals(neutral(initial) + neutral(expanded), tracker.consumed)
        update(tracker,1000,snap,groups=mapOf(Seat.PREVIOUS to group(expanded)))
        assertEquals(neutral(initial) + neutral(expanded), tracker.consumed)
        update(tracker,1250,snap,groups=mapOf(Seat.PREVIOUS to group(expanded)))
        assertEquals(neutral(initial)+expanded,tracker.consumed)
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

    @Test fun conflictingInitialSuitEvidenceRevokesOnlyItsRankColorPoolForTheRound() {
        val tracker = StraightFlushEvidenceTracker()
        vote(tracker, own = group(initial))
        val altered = initial.toMutableList().apply { this[6] = Card(Rank.A, Suit.DIAMOND) }
        vote(tracker, 750, own = group(altered))
        val expected=initial.map { if(it.colorKey()=="A:RED") Card(it.rank,Suit.RED) else it }
        assertEquals(expected, tracker.consumed)
        assertEquals(27,tracker.initialCards.size)
        assertEquals(neutral(initial),neutral(tracker.initialCards))
        vote(tracker, 1500, own = group(initial))
        assertEquals(expected, tracker.consumed)
        vote(tracker, 2250, own = group(altered))
        assertEquals(expected, tracker.consumed)
    }

    @Test fun redNineConflictAfterOwnPlayKeepsProvedGradeTenCountsAndResetsWithTheRound() {
        // Live round13018801-1: SELF's new event rebuilt the initial hand,
        // then a stable red-nine suit flip revoked unrelated grade-ten proof.
        val tens=listOf(Card(Rank.TEN,Suit.HEART),Card(Rank.TEN,Suit.DIAMOND),
            Card(Rank.TEN,Suit.SPADE),Card(Rank.TEN,Suit.CLUB))
        val nines=listOf(Card(Rank.NINE,Suit.DIAMOND),Card(Rank.NINE,Suit.DIAMOND),Card(Rank.NINE,Suit.CLUB))
        val baseline=tens+nines+deck.filter { !it.rank.isJoker && it.rank !in setOf(Rank.TEN,Rank.NINE) }.take(20)
        val ledger=RoundLedger().apply { begin("r1",neutral(baseline)) }
        val tracker=StraightFlushEvidenceTracker()
        vote(tracker,snapshot=ledger.snapshot(),own=group(baseline),baseline=baseline)
        val grade=WildcardRuleEvidence(setOf(Rank.TEN),true)
        fun counter()=GradeCardCounter.evaluate(ledger.snapshot(),tracker.initialCards,tracker.physicalEventCards,
            grade,faces=TableCardFaces.ORIGINAL)
        val before=counter()
        assertEquals(1,before.heartCount?.exact)
        assertEquals(3,before.ordinaryCounts[Rank.TEN]?.exact)

        val played=listOf(tens[1],tens[3])
        ledger.commit(PlayEvent("r1","own-23",23,Seat.SELF,neutral(played)))
        val remaining=baseline.toMutableList().apply { played.forEach { remove(it) } }
        val changed=remaining.toMutableList().apply {
            this[indexOf(Card(Rank.NINE,Suit.DIAMOND))]=Card(Rank.NINE,Suit.HEART)
        }
        vote(tracker,750,ledger.snapshot(),own=group(changed),baseline=baseline)
        assertEquals(List(2) { Card(Rank.NINE,Suit.RED) },tracker.initialCards.filter { it.colorKey()=="NINE:RED" })
        assertEquals(listOf(Card(Rank.NINE,Suit.CLUB)),tracker.initialCards.filter { it.colorKey()=="NINE:BLACK" })
        assertEquals(tens.groupingBy { it }.eachCount(),tracker.initialCards.filter { it.rank==Rank.TEN }.groupingBy { it }.eachCount())
        assertEquals(27,tracker.initialCards.size)
        assertEquals(baseline.map { it.colorKey() }.sorted(),tracker.initialCards.map { it.colorKey() }.sorted())
        assertEquals(neutral(baseline),ledger.initialHand())
        assertEquals(before,counter())

        // Correct-looking later frames cannot silently choose either side of
        // the contradiction again within this round.
        vote(tracker,1500,ledger.snapshot(),own=group(remaining),baseline=baseline)
        assertTrue(tracker.initialCards.filter { it.colorKey()=="NINE:RED" }.all { it.suit==Suit.RED })
        assertEquals(before,counter())

        ledger.waitForNext(); ledger.begin("r2",neutral(baseline))
        vote(tracker,2250,ledger.snapshot(),own=group(baseline),baseline=baseline)
        assertEquals(baseline,tracker.initialCards)
        assertEquals(1,counter().heartCount?.exact)
        assertEquals(3,counter().ordinaryCounts[Rank.TEN]?.exact)
    }

    @Test fun revokedInitialPoolStillAllowsOtherPoolsToGainProofAndDetectTheirOwnConflicts() {
        val tracker=StraightFlushEvidenceTracker()
        val partial=initial.map { if(it.colorKey()=="K:RED") Card(it.rank,Suit.RED) else it }
        vote(tracker,own=group(partial))
        val changed=initial.toMutableList().apply { this[6]=Card(Rank.A,Suit.DIAMOND) }
        vote(tracker,750,own=group(changed))
        val onlyRedAceRevoked=initial.map { if(it.colorKey()=="A:RED") Card(it.rank,Suit.RED) else it }
        assertEquals(onlyRedAceRevoked,tracker.initialCards)

        val secondConflict=initial.toMutableList().apply {
            this[indexOf(Card(Rank.K,Suit.HEART))]=Card(Rank.K,Suit.DIAMOND)
        }
        vote(tracker,1500,own=group(secondConflict))
        val bothRevoked=initial.map { if(it.colorKey() in setOf("A:RED","K:RED")) Card(it.rank,Suit.RED) else it }
        assertEquals(bothRevoked,tracker.initialCards)
        tracker.reset()
        vote(tracker,2250,own=group(initial))
        assertEquals(initial,tracker.initialCards)
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
    @Test fun teammateRedNineConflictKeepsGradeTenProofUntilTheHandVersionChanges() {
        val baseline=deck.filter { it.rank !in setOf(Rank.TEN,Rank.NINE) }.take(27)
        val seed=listOf(Card(Rank.TEN,Suit.HEART),Card(Rank.NINE,Suit.DIAMOND),Card(Rank.NINE,Suit.CLUB))
        val available=deck.toMutableList().apply { (baseline+seed).forEach { remove(it) } }
        val hand=seed+available.filter { it.rank !in setOf(Rank.TEN,Rank.NINE) }.take(24)
        val ledger=RoundLedger().apply { begin("r1",neutral(baseline)); selfFinished() }
        val grade=WildcardRuleEvidence(setOf(Rank.TEN),true)
        val first=RemainingPoolExclusion("r1",1,emptyList(),neutral(hand))
        val tracker=StraightFlushEvidenceTracker()
        fun observe(time: Long,cards: List<Card>,version: RemainingPoolExclusion?) =
            tracker.update(time,ledger.snapshot(),neutral(baseline),group(cards),emptyMap(),grade,version)
        fun voteHand(start: Long,cards: List<Card>,version: RemainingPoolExclusion) {
            listOf(start,start+250,start+500).forEach { observe(it,cards,version) }
        }
        fun counter(version: RemainingPoolExclusion)=GradeCardCounter.evaluate(ledger.snapshot(),tracker.initialCards,
            tracker.physicalEventCards,grade,version.copy(cards=tracker.teammateCards),TableCardFaces.ORIGINAL)
        voteHand(0,hand,first)
        val before=counter(first)
        assertEquals(1,before.heartCount?.exact); assertEquals(6,before.ordinaryCounts[Rank.TEN]?.exact)

        val changed=hand.toMutableList().apply { this[1]=Card(Rank.NINE,Suit.HEART) }
        voteHand(750,changed,first)
        assertEquals(listOf(Card(Rank.NINE,Suit.RED)),tracker.teammateCards.filter { it.colorKey()=="NINE:RED" })
        assertEquals(listOf(Card(Rank.NINE,Suit.CLUB)),tracker.teammateCards.filter { it.colorKey()=="NINE:BLACK" })
        assertEquals(before,counter(first))
        assertEquals(neutral(baseline),tracker.initialCards)
        voteHand(1500,hand,first)
        assertEquals(Suit.RED,tracker.teammateCards.single { it.colorKey()=="NINE:RED" }.suit)
        assertEquals(before,counter(first))

        val played=hand.last()
        ledger.commit(PlayEvent("r1","teammate-new-hand",1,Seat.OPPOSITE,neutral(listOf(played))))
        val remaining=hand.dropLast(1)
        val next=first.copy(teammateEvents=ledger.snapshot().plays[Seat.OPPOSITE].orEmpty(),cards=neutral(remaining))
        observe(2250,remaining,next)
        assertEquals(neutral(remaining),tracker.teammateCards)
        observe(2500,remaining,next)
        assertEquals(neutral(remaining),tracker.teammateCards)
        observe(2750,remaining,next)
        assertEquals(remaining,tracker.teammateCards)
        assertEquals(1,counter(next).heartCount?.exact); assertEquals(6,counter(next).ordinaryCounts[Rank.TEN]?.exact)

        voteHand(3000,remaining.toMutableList().apply { this[1]=Card(Rank.NINE,Suit.HEART) },next)
        assertEquals(Suit.RED,tracker.teammateCards.single { it.colorKey()=="NINE:RED" }.suit)
        observe(3750,remaining,null)
        assertTrue(tracker.teammateCards.isEmpty())
        voteHand(4000,remaining,next)
        assertEquals(remaining,tracker.teammateCards)
        tracker.reset()
        voteHand(4750,remaining,next)
        assertEquals(remaining,tracker.teammateCards)
    }

    @Test fun teammateHandSuitProofHasItsOwnVersionAndNeverRewritesOwnInitial() {
        val ledger=RoundLedger(); ledger.begin("r1",initial)
        ledger.commit(event("teammate-before",deck.drop(27).take(23),Seat.OPPOSITE))
        val snap=ledger.selfFinished()
        val hand=deck.drop(50).take(4)
        val excluded=RemainingPoolExclusion("r1",1,snap.plays[Seat.OPPOSITE].orEmpty(),neutral(hand))
        val tracker=StraightFlushEvidenceTracker()
        listOf(0L,250L).forEach { tracker.update(it,snap,neutral(initial),group(hand),emptyMap(),rule,excluded) }
        assertEquals(neutral(hand),tracker.teammateCards)
        tracker.update(500,snap,neutral(initial),group(hand),emptyMap(),rule,excluded)
        assertEquals(hand,tracker.teammateCards)
        assertEquals(neutral(initial),tracker.initialCards)
        assertEquals(54,tracker.consumed.size)
        tracker.clearCandidateVotes()
        tracker.update(750,snap,neutral(initial),null,emptyMap(),rule,excluded)
        assertEquals(hand,tracker.teammateCards)
        val recovery=snap.copy(phase=RoundPhase.RECOVERING,remaining=null)
        assertEquals(StraightFlushState.UNKNOWN,tracker.update(1000,recovery,neutral(initial),null,emptyMap(),rule).state)
        tracker.update(1250,snap,neutral(initial),null,emptyMap(),rule,excluded)
        assertEquals(hand,tracker.teammateCards)
        tracker.update(1500,snap,neutral(initial),null,emptyMap(),rule,null)
        assertTrue(tracker.teammateCards.isEmpty())
        assertEquals(50,tracker.consumed.size)
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
