package com.cyotun.jpq.core

import kotlin.test.*

class RemainingPoolTest {
    private val deck=Rank.entries.flatMap { rank ->
        (if(rank.isJoker) listOf(Suit.NONE) else listOf(Suit.SPADE,Suit.HEART,Suit.CLUB,Suit.DIAMOND))
            .flatMap { suit -> List(2) { Card(rank,suit) } }
    }
    private fun ledger(): RoundLedger = RoundLedger().apply {
        begin("pool",deck.take(27))
        commit(PlayEvent("pool","teammate-before",0,Seat.OPPOSITE,deck.drop(27).take(23)))
        selfFinished()
    }
    private val hand get()=deck.drop(50).take(4)
    private fun confirm(tracker: RemainingPoolTracker,snapshot: RoundSnapshot,epoch: Long=1,start: Long=0,
        cards: List<Card> = hand): RemainingPoolExclusion {
        assertNull(tracker.observe(start,epoch,snapshot,cards))
        assertNull(tracker.observe(start+250,epoch,snapshot,cards))
        return assertNotNull(tracker.observe(start+500,epoch,snapshot,cards))
    }

    @Test fun confirmedCurrentHandIsSubtractedOnceAndNeverMutatesLedger() {
        val ledger=ledger(); val before=ledger.snapshot(); val initial=ledger.initialHand()
        val tracker=RemainingPoolTracker(); val exclusion=confirm(tracker,before)
        val outside=assertNotNull(remainingOutsidePool(before,exclusion))
        assertEquals(before.remaining!!.values.sum()-4,outside.values.sum())
        assertEquals(54,outside.values.sum())
        repeat(8) { i ->
            val unchanged=tracker.observe(750+i*250L,1,before,hand.reversed())
            assertEquals(outside,remainingOutsidePool(before,unchanged))
        }
        assertEquals(before,ledger.snapshot()); assertEquals(initial,ledger.initialHand())
    }

    @Test fun incompleteRepeatedAndSeparatedFramesCannotConfirmAHand() {
        val tracker=RemainingPoolTracker(); val snapshot=ledger().snapshot()
        assertNull(tracker.observe(0,1,snapshot,hand))
        repeat(6) { assertNull(tracker.observe(0,1,snapshot,hand)) }
        assertNull(tracker.observe(250,1,snapshot,hand.take(3)))
        assertNull(tracker.observe(500,1,snapshot,hand))
        assertNull(tracker.observe(1751,1,snapshot,hand))
        assertNull(tracker.observe(2001,1,snapshot,hand))
        assertNotNull(tracker.observe(2251,1,snapshot,hand))
    }

    @Test fun unknownFramesKeepOnlyTheProvedEventAndVisibilityVersion() {
        val tracker=RemainingPoolTracker(); val snapshot=ledger().snapshot()
        val exclusion=confirm(tracker,snapshot)
        assertEquals(exclusion,tracker.observe(750,1,snapshot,null))
        assertEquals(exclusion,tracker.observe(1000,1,snapshot,hand.take(2)))
        assertNull(tracker.projection(snapshot,2))
        assertNull(tracker.projection(snapshot.copy(roundId="next"),2))
    }

    @Test fun aNewNaturalTeammateSingleUpdatesWithoutDoubleCountingItsPlay() {
        val ledger=ledger(); val tracker=RemainingPoolTracker(); val before=ledger.snapshot()
        val exclusion=confirm(tracker,before)
        val old=remainingOutsidePool(before,exclusion)
        ledger.commit(PlayEvent("pool","single",1,Seat.OPPOSITE,listOf(hand.first())))
        assertFalse(exclusion.matches(ledger.snapshot()))
        val updated=assertNotNull(tracker.projection(ledger.snapshot(),1))
        assertEquals(3,updated.cards.size)
        assertEquals(old,remainingOutsidePool(ledger.snapshot(),updated))
        assertEquals(old,remainingOutsidePool(ledger.snapshot(),tracker.observe(750,1,ledger.snapshot(),null)))
    }

    @Test fun aNewCombinationCannotSubtractAnUnprovedPhysicalWildcard() {
        val ledger=ledger(); val tracker=RemainingPoolTracker()
        confirm(tracker,ledger.snapshot())
        ledger.commit(PlayEvent("pool","pair",1,Seat.OPPOSITE,hand.take(2)))
        assertNull(tracker.projection(ledger.snapshot(),1))
        assertNull(tracker.observe(750,1,ledger.snapshot(),null))
        val fresh=confirm(tracker,ledger.snapshot(),start=1000,cards=hand.drop(2))
        assertEquals(2,fresh.cards.size)
        assertEquals(54,remainingOutsidePool(ledger.snapshot(),fresh)!!.values.sum())
    }

    @Test fun activeAndEndedRoundsNeverTreatBottomCardsAsATeammateReveal() {
        val tracker=RemainingPoolTracker(); val snapshot=ledger().snapshot()
        for(phase in listOf(RoundPhase.ACTIVE,RoundPhase.FINISHED,RoundPhase.WAITING,RoundPhase.UNAVAILABLE)) {
            listOf(0L,250L,500L).forEach { assertNull(tracker.observe(it,1,snapshot.copy(phase=phase),hand)) }
        }
    }

    @Test fun recoveryKeepsCheckpointButPublishesNoExactPoolUntilSameVersionResumes() {
        val tracker=RemainingPoolTracker(); val snapshot=ledger().snapshot()
        val proved=confirm(tracker,snapshot)
        val recovery=snapshot.copy(phase=RoundPhase.RECOVERING,remaining=null)
        assertNull(tracker.projection(recovery,1)); assertNull(remainingOutsidePool(recovery,proved))
        assertEquals(proved,tracker.projection(snapshot,1))
    }

    @Test fun exclusionRemovesTeammateCapacityFromStraightFlushPossibility() {
        val snapshot=ledger().snapshot()
        val exclusion=confirm(RemainingPoolTracker(),snapshot)
        assertEquals(listOf(27,0,27),straightFlushRemainingHandSizes(snapshot,exclusion))
        val plays=snapshot.plays.toMutableMap().apply {
            put(Seat.PREVIOUS,listOf(PlayEvent("pool","prev",1,Seat.PREVIOUS,deck.take(23))))
            put(Seat.NEXT,listOf(PlayEvent("pool","next",2,Seat.NEXT,deck.take(23))))
        }
        val late=snapshot.copy(plays=plays,remaining=null)
        val lateCounts=Rank.entries.associateWith { rank ->
            (hand+deck.takeLast(8)).count { it.rank==rank }
        }
        val live=late.copy(remaining=lateCounts)
        assertEquals(listOf(4,0,4),straightFlushRemainingHandSizes(live,exclusion))
        assertEquals("insufficient_seat_hand",straightFlushHandCapacityExclusion(live,exclusion)?.reason)
    }
}
