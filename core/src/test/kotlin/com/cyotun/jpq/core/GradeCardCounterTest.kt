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
}
