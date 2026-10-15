package com.cyotun.jpq.core

import kotlin.test.*

class GradeCardCounterTest {
    private val rule=WildcardRuleEvidence(setOf(Rank.EIGHT),true)
    private fun baseline(exclude: Set<Rank> = setOf(Rank.EIGHT,Rank.K)) = Rank.entries.filter {
        !it.isJoker && it !in exclude
    }.flatMap { rank -> listOf(Suit.SPADE,Suit.HEART,Suit.CLUB,Suit.DIAMOND).flatMap { suit ->
        List(2) { Card(rank,suit) }
    } }.take(27)
    private fun result(ledger: RoundLedger,events: Map<String,List<PhysicalPlayedCard>> = emptyMap(),
        initial: List<Card> = ledger.initialHand(),mode: WildcardRuleEvidence=rule)=
        GradeCardCounter.evaluate(ledger.snapshot(),initial,events,mode)

    @Test fun physicalHeartsAreSeparatedFromTheOtherSixGradeCards() {
        val ledger=RoundLedger(); ledger.begin("g",listOf(Card(Rank.EIGHT,Suit.HEART),Card(Rank.EIGHT,Suit.CLUB))+baseline().take(25))
        val state=result(ledger)
        assertEquals(1,state.heartCount?.exact); assertEquals(5,state.ordinaryCounts[Rank.EIGHT]?.exact)
        assertEquals(ledger.snapshot().remaining!![Rank.EIGHT],state.heartCount!!.exact!!+state.ordinaryCounts[Rank.EIGHT]!!.exact!!)
        ledger.commit(PlayEvent("g","self",0,Seat.SELF,listOf(Card(Rank.EIGHT,Suit.HEART))))
        assertEquals(state,result(ledger)) // Immutable initial hand is counted once.
    }
    @Test fun anUncertainRedInitialCardNeverPretendsToBeAHeart() {
        val ledger=RoundLedger(); ledger.begin("g",baseline().take(26)+Card(Rank.EIGHT,Suit.RED))
        val state=result(ledger)
        assertEquals(GradeCountBounds(1,2),state.heartCount)
        assertEquals(GradeCountBounds(5,6),state.ordinaryCounts[Rank.EIGHT])
        assertNull(state.heartCount!!.exact)
    }
    @Test fun aSingleGradeHeartCannotBeReplacedByAnotherRank() {
        val ledger=RoundLedger(); ledger.begin("g",baseline())
        ledger.commit(PlayEvent("g","single",0,Seat.PREVIOUS,listOf(Card(Rank.EIGHT,Suit.HEART))))
        val state=result(ledger)
        assertEquals(1,state.heartCount?.exact); assertEquals(6,state.ordinaryCounts[Rank.EIGHT]?.exact)
    }
    @Test fun aProvenGoldHeartConsumesThePhysicalGradeInsteadOfItsDeclaredPoint() {
        val ledger=RoundLedger(); ledger.begin("g",baseline())
        val display=listOf(Card(Rank.K,Suit.SPADE),Card(Rank.K,Suit.CLUB))
        ledger.commit(PlayEvent("g","pair",0,Seat.NEXT,display))
        val state=result(ledger,mapOf("pair" to listOf(PhysicalPlayedCard(display[0],true),PhysicalPlayedCard(display[1],false))))
        assertEquals(1,state.heartCount?.exact); assertEquals(6,state.ordinaryCounts[Rank.EIGHT]?.exact)
        assertEquals(2,ledger.snapshot().played(Seat.NEXT))
    }
    @Test fun aMultiCardPresentationWithoutPhysicalIdentityKeepsSafeBounds() {
        val ledger=RoundLedger(); ledger.begin("g",baseline())
        ledger.commit(PlayEvent("g","pair",0,Seat.NEXT,listOf(Card(Rank.K,Suit.SPADE),Card(Rank.K,Suit.CLUB))))
        assertEquals(GradeCountBounds(0,2),result(ledger).heartCount)
    }
    @Test fun doubleGradesHaveFourPhysicalHeartsAndTwoIndependentSixCardPools() {
        val ledger=RoundLedger(); ledger.begin("g",baseline(setOf(Rank.EIGHT,Rank.K)))
        val state=result(ledger,mode=WildcardRuleEvidence(setOf(Rank.EIGHT,Rank.K),true))
        assertEquals(4,state.heartCount?.exact)
        assertEquals(setOf(Rank.EIGHT,Rank.K),state.gradeRanks)
        assertTrue(state.ordinaryCounts.values.all { it?.exact==6 })
    }
    @Test fun unconfirmedRulesAndIncompatiblePhysicalClaimsDoNotPublishGuessedNumbers() {
        val ledger=RoundLedger(); ledger.begin("g",baseline())
        assertTrue(result(ledger,mode=WildcardRuleEvidence(null,false)).gradeRanks.isEmpty())
        val invalid=baseline().take(24)+List(3) { Card(Rank.EIGHT,Suit.HEART) }
        val state=result(ledger,initial=invalid)
        assertNull(state.heartCount); assertEquals("physical_evidence_conflict",state.reason)
    }

    @Test fun ownPlayKeepsTheAcceptedCounterVersionWithoutAnEmptyInterimState() {
        val ledger=RoundLedger(); ledger.begin("g",baseline())
        val projection=GradeCounterProjection.create(ledger.snapshot(),ledger.initialHand(),emptyMap(),rule)
        ledger.commit(PlayEvent("g","self",0,Seat.SELF,listOf(ledger.initialHand().first())))
        assertSame(projection,projection.rebase(ledger.snapshot(),rule))
        assertEquals(2,projection.rebase(ledger.snapshot(),rule)!!.state.heartCount?.exact)
        assertSame(projection,projection.rebase(ledger.selfFinished(),rule))
    }

    @Test fun newOpponentSingleIsAppliedImmediatelyUsingAcceptedPhysicalEvidence() {
        val ledger=RoundLedger(); ledger.begin("g",baseline())
        val display=listOf(Card(Rank.K,Suit.HEART),Card(Rank.K,Suit.CLUB))
        ledger.commit(PlayEvent("g","pair",0,Seat.NEXT,display))
        val evidence=mapOf("pair" to listOf(PhysicalPlayedCard(display[0],true),PhysicalPlayedCard(display[1],false)))
        val projection=GradeCounterProjection.create(ledger.snapshot(),ledger.initialHand(),evidence,rule)
        assertEquals(1,projection.state.heartCount?.exact)
        ledger.commit(PlayEvent("g","single",1,Seat.PREVIOUS,listOf(Card(Rank.EIGHT,Suit.HEART))))
        val updated=assertNotNull(projection.rebase(ledger.snapshot(),rule))
        assertEquals(0,updated.state.heartCount?.exact)
        assertEquals(6,updated.state.ordinaryCounts[Rank.EIGHT]?.exact)
        assertSame(updated,updated.rebase(ledger.snapshot(),rule))
    }

    @Test fun newUnprovedOpponentCombinationCannotReuseAnOldExactHeartCount() {
        val ledger=RoundLedger(); ledger.begin("g",baseline())
        val projection=GradeCounterProjection.create(ledger.snapshot(),ledger.initialHand(),emptyMap(),rule)
        assertEquals(2,projection.state.heartCount?.exact)
        ledger.commit(PlayEvent("g","pair",0,Seat.NEXT,listOf(Card(Rank.K,Suit.SPADE),Card(Rank.K,Suit.CLUB))))
        val updated=assertNotNull(projection.rebase(ledger.snapshot(),rule))
        assertEquals(GradeCountBounds(0,2),updated.state.heartCount)
        assertEquals("physical_identity_uncertain",updated.state.reason)
    }

    @Test fun newRuleOrRoundCannotReuseAcceptedPhysicalCounterEvidence() {
        val ledger=RoundLedger(); ledger.begin("g",baseline())
        val projection=GradeCounterProjection.create(ledger.snapshot(),ledger.initialHand(),emptyMap(),rule)
        assertNull(projection.rebase(ledger.snapshot(),WildcardRuleEvidence(setOf(Rank.K),true)))
        assertNull(projection.rebase(ledger.snapshot(),WildcardRuleEvidence(null,false)))
        assertNull(projection.rebase(ledger.snapshot().copy(roundId="new"),rule))
        assertNull(projection.rebase(ledger.snapshot().copy(phase=RoundPhase.UNAVAILABLE),rule))
    }

    @Test fun confirmedTeammatePhysicalHandLeavesOnlyOpponentsGradeCards() {
        val ledger=RoundLedger(); ledger.begin("g",baseline()); ledger.selfFinished()
        val available=Rank.entries.flatMap { rank ->
            (if(rank.isJoker) listOf(Suit.NONE) else listOf(Suit.SPADE,Suit.HEART,Suit.CLUB,Suit.DIAMOND))
                .flatMap { suit -> List(2) { Card(rank,suit) } }
        }.toMutableList()
        ledger.initialHand().forEach { available.remove(it) }
        val selected=listOf(Card(Rank.EIGHT,Suit.HEART),Card(Rank.EIGHT,Suit.CLUB))
        selected.forEach { available.remove(it) }
        val hand=selected+available.take(25)
        val exclusion=RemainingPoolExclusion("g",1,emptyList(),hand)
        val projection=GradeCounterProjection.create(ledger.snapshot(),ledger.initialHand(),emptyMap(),rule,exclusion)
        assertEquals(1,projection.state.heartCount?.exact)
        assertEquals(5,projection.state.ordinaryCounts[Rank.EIGHT]?.exact)
        ledger.commit(PlayEvent("g","teammate-single",0,Seat.OPPOSITE,listOf(selected.first())))
        val after=exclusion.copy(teammateEvents=ledger.snapshot().plays[Seat.OPPOSITE].orEmpty(),cards=hand.drop(1))
        val updated=assertNotNull(projection.rebase(ledger.snapshot(),rule,after))
        assertEquals(projection.state,updated.state)
        assertEquals(27,ledger.initialHand().size)
    }

    @Test fun aLedgerRebaseRetainsProvedTeammateSuitsWhenTheFastRankChannelAbstains() {
        val ledger=RoundLedger(); ledger.begin("g",baseline()); ledger.selfFinished()
        // Only the hand's grade card matters to this bound; the other cards
        // are selected from a disjoint physical deck after removing initial.
        val available=Rank.entries.flatMap { rank ->
            (if(rank.isJoker) listOf(Suit.NONE) else listOf(Suit.SPADE,Suit.HEART,Suit.CLUB,Suit.DIAMOND))
                .flatMap { suit -> List(2) { Card(rank,suit) } }
        }.toMutableList()
        ledger.initialHand().forEach { available.remove(it) }
        val heart=Card(Rank.EIGHT,Suit.HEART); available.remove(heart)
        val hand=listOf(heart)+available.filter { it.rank!=Rank.EIGHT }.take(26)
        val proved=RemainingPoolExclusion("g",1,emptyList(),hand)
        val projection=GradeCounterProjection.create(ledger.snapshot(),ledger.initialHand(),emptyMap(),rule,proved)
        val colorOnly=proved.copy(cards=hand.map { card -> Card(card.rank,when(card.suit) {
            Suit.HEART,Suit.DIAMOND -> Suit.RED
            Suit.SPADE,Suit.CLUB -> Suit.BLACK
            else -> card.suit
        }) })
        assertSame(projection,projection.rebase(ledger.snapshot(),rule,colorOnly))
        assertEquals(1,projection.rebase(ledger.snapshot(),rule,colorOnly)!!.state.heartCount?.exact)
    }
}
