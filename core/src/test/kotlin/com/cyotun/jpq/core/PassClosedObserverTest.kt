package com.cyotun.jpq.core

import kotlin.test.*

class PassClosedObserverTest {
    private val old=Card(Rank.TWO,Suit.BLACK)
    private val group=listOf(Card(Rank.A,Suit.BLACK),old,Card(Rank.Q,Suit.RED),Card(Rank.FOUR,Suit.BLACK),Card(Rank.FIVE,Suit.BLACK))
    private val other=Card(Rank.THREE,Suit.RED)
    private val next=Card(Rank.K,Suit.BLACK)
    private fun run(passes: Boolean,oldSlotRetained: Boolean=false): RoundSnapshot {
        val deck=Rank.entries.flatMap { rank -> if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
            else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } } }.toMutableList()
        (group+old+other+next).forEach { assertTrue(deck.remove(it)) }
        val hand=deck.take(27); val ledger=RoundLedger(); ledger.begin("pass-round",hand)
        val observer=RoundObserver(ledger)
        val empty=Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }
        val oldBox=GlyphBox(.49f,.15f,.51f,.20f)
        fun play(cards: List<Card>,oldSingle: Boolean=false)=ObservedPlay(cards,.96f,cards.mapIndexed { i,c ->
            PlayGlyph(c,.96f,if(oldSingle || (oldSlotRetained && c==old)) oldBox
                else GlyphBox(.40f+i*.025f,.15f,.42f+i*.025f,.20f)) })
        for(t in 1000L..1600L step 200) observer.observe(TableFrame(t,TableScene.TABLE,hand,empty+(Seat.OPPOSITE to play(listOf(old),true))))
        for(t in 1800L..2400L step 200) observer.observe(TableFrame(t,TableScene.TABLE,hand,
            empty+(Seat.OPPOSITE to play(listOf(old),true))+(Seat.PREVIOUS to play(listOf(other)))))
        assertEquals(1,ledger.snapshot().played(Seat.OPPOSITE))
        for(t in 2600L..3000L step 200) observer.observe(TableFrame(t,TableScene.TABLE,hand,
            empty+(Seat.OPPOSITE to ObservedPlay(emptyList(),1f,regionObserved=false)),
            observedPasses=if(passes) mapOf(Seat.OPPOSITE to t) else emptyMap()))
        for(t in 3200L..4200L step 200) observer.observe(TableFrame(t,TableScene.TABLE,hand,
            empty+(Seat.OPPOSITE to play(group))))
        if(passes && !oldSlotRetained) {
            assertEquals(6,ledger.snapshot().played(Seat.OPPOSITE))
            assertEquals(listOf(old),ledger.snapshot().plays.getValue(Seat.OPPOSITE).first().cards)
            for(t in 4400L..5000L step 200) observer.observe(TableFrame(t,TableScene.TABLE,hand,
                empty+(Seat.OPPOSITE to play(group))+(Seat.NEXT to play(listOf(next)))))
            assertEquals(1,ledger.snapshot().played(Seat.NEXT))
            assertEquals(4,ledger.snapshot().plays.values.sumOf { it.size })
            assertEquals(73,ledger.snapshot().remaining!!.values.sum())
        }
        if(!passes) {
            assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
            assertTrue(observer.diagnosticStatus().pendingReviewSeats.contains(Seat.OPPOSITE))
        }
        return ledger.snapshot()
    }
    @Test fun passedOldSingleDoesNotFreezeAllSeatsWhenALaterStraightContainsAnotherTwo() {
        assertEquals(RoundPhase.ACTIVE,run(true).phase)
    }
    @Test fun missingPassEvidenceRetainsTheExistingStrictSupersetSafetyCheck() {
        val result=run(false)
        assertEquals(RoundPhase.ACTIVE,result.phase)
        assertEquals(1,result.played(Seat.OPPOSITE))
    }
    @Test fun passTextCannotTurnAnUnchangedOldPhysicalSlotIntoANewWholePlay() {
        val result=run(true,true)
        assertEquals(1,result.played(Seat.OPPOSITE))
    }
}
