package com.cyotun.jpq.core

import kotlin.test.*

class DealingBoundaryRegressionTest {
    private val baseline=Rank.entries.filter { !it.isJoker }.flatMap { listOf(Card(it,Suit.RED),Card(it,Suit.BLACK)) }+Card(Rank.SMALL,Suit.NONE)
    private fun empty()=Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }
    private fun started(): Pair<RoundLedger,RoundObserver> {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger)
        observer.observe(TableFrame(0,TableScene.LOBBY,null))
        for(t in 60L..240L step 60) observer.observe(TableFrame(t,TableScene.TABLE,baseline,empty()))
        val play=ObservedPlay(listOf(Card(Rank.K,Suit.RED)),.9f)
        for(t in 300L..540L step 60) observer.observe(TableFrame(t,TableScene.TABLE,baseline,empty()+(Seat.NEXT to play)))
        assertEquals(1,ledger.snapshot().played(Seat.NEXT))
        return ledger to observer
    }
    @Test fun doublingBadgesCannotResetTheSameOpeningAfterOtherPlayersHavePlayed() {
        val (ledger,observer)=started(); val before=ledger.snapshot()
        for(t in 600L..1140L step 60) observer.observe(TableFrame(t,TableScene.DEALING,baseline,empty()))
        assertEquals(before.roundId,ledger.snapshot().roundId)
        assertEquals(before.plays,ledger.snapshot().plays)
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
    }
    @Test fun aMidroundPartialHandCannotSupplyANewDealBoundary() {
        val (ledger,observer)=started(); val id=ledger.snapshot().roundId
        // Recorded false DEALING at 1689455199 has 26 cards and an own ace on the table.
        val own=Card(Rank.A,Suit.RED); val hand=baseline.toMutableList().apply { remove(own) }
        val p=ObservedPlay(listOf(own),.9f)
        for(t in 600L..1140L step 60) observer.observe(TableFrame(t,TableScene.DEALING,hand,empty()+(Seat.SELF to p)))
        assertEquals(id,ledger.snapshot().roundId)
        assertEquals(1,ledger.snapshot().played(Seat.NEXT))
        assertEquals(1,ledger.snapshot().played(Seat.SELF))
    }
    @Test fun onlyStableDifferentCompleteHandAndObservedClearTableReplaceAnActiveRound() {
        val (ledger,observer)=started(); val id=ledger.snapshot().roundId
        val different=baseline.toMutableList().apply { remove(Card(Rank.SMALL,Suit.NONE)); add(Card(Rank.BIG,Suit.NONE)) }
        observer.observe(TableFrame(600,TableScene.DEALING,different,empty(),dealingChoicesObserved=true))
        assertEquals(id,ledger.snapshot().roundId)
        observer.observe(TableFrame(660,TableScene.TABLE,baseline,empty()))
        for(t in 720L..1020L step 60) observer.observe(TableFrame(t,TableScene.DEALING,different,empty(),dealingChoicesObserved=true))
        assertNotEquals(id,ledger.snapshot().roundId)
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
        assertEquals(different.groupingBy { it }.eachCount(),ledger.initialHand().groupingBy { it }.eachCount())
    }
    @Test fun unobservedOrOccupiedTableDoesNotConfirmANewDeal() {
        for(table in listOf(empty().mapValues { it.value.copy(regionObserved=false) },empty()+(Seat.NEXT to ObservedPlay(listOf(Card(Rank.K,Suit.RED)),.9f)))) {
            val (ledger,observer)=started(); val id=ledger.snapshot().roundId
            val different=baseline.toMutableList().apply { remove(Card(Rank.SMALL,Suit.NONE)); add(Card(Rank.BIG,Suit.NONE)) }
            for(t in 600L..1020L step 60) observer.observe(TableFrame(t,TableScene.DEALING,different,table,dealingChoicesObserved=true))
            assertEquals(id,ledger.snapshot().roundId)
            assertEquals(1,ledger.snapshot().played(Seat.NEXT))
        }
    }
    @Test fun confirmedSameOpeningLatchesItsStageBeforeLaterDetectorVariation() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger)
        observer.observe(TableFrame(0,TableScene.LOBBY,null))
        for(t in 60L..240L step 60) observer.observe(TableFrame(t,TableScene.TABLE,baseline,empty()))
        val id=ledger.snapshot().roundId
        observer.observe(TableFrame(300,TableScene.DEALING,baseline,empty()))
        val flicker=baseline.toMutableList().apply { remove(Card(Rank.SMALL,Suit.NONE)); add(Card(Rank.BIG,Suit.NONE)) }
        for(t in 360L..1080L step 60) observer.observe(TableFrame(t,TableScene.DEALING,flicker,empty()))
        assertEquals(id,ledger.snapshot().roundId)
        assertEquals(baseline,ledger.initialHand())
    }
    @Test fun threeRepeatedDifferentHandReadingsWithoutObservedChoiceControlsCannotReplaceTheRound() {
        val (ledger,observer)=started(); val before=ledger.snapshot()
        val aliased=baseline.toMutableList().apply { remove(Card(Rank.J,Suit.RED)); add(Card(Rank.J,Suit.BLACK)) }
        for(t in 600L..1020L step 60) observer.observe(TableFrame(t,TableScene.DEALING,aliased,empty()))
        assertEquals(before,ledger.snapshot())
        assertEquals(baseline,ledger.initialHand())
    }
}
