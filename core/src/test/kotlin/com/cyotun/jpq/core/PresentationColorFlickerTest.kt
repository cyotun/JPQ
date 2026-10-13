package com.cyotun.jpq.core

import kotlin.test.*

class PresentationColorFlickerTest {
    private val baseline=Rank.entries.filter { it!=Rank.SIX && !it.isJoker }.flatMap { r ->
        listOf(Suit.RED,Suit.BLACK).flatMap { s -> List(4) { Card(r,s) } }
    }.take(27)
    private fun play(suit: Suit)=List(2) { i -> PlayGlyph(Card(Rank.SIX,suit),.89f,
        GlyphBox(.465f+i*.025f,.158f,.479f+i*.025f,.205f)) }.let { ObservedPlay(it.map { p -> p.card },.89f,it) }
    private fun show(observer: RoundObserver,time: Long,p: ObservedPlay?)=
        observer.observe(TableFrame(time,TableScene.TABLE,null,mapOf(Seat.OPPOSITE to p)))
    private fun setup(): Pair<RoundLedger,RoundObserver> {
        val ledger=RoundLedger(); ledger.begin("color-effect",baseline)
        return ledger to RoundObserver(ledger)
    }
    @Test fun aReturningConfirmedPairWithChangedColorsIsNotAnotherPlay() {
        val (ledger,observer)=setup()
        for(t in 1000L..1240L step 60) show(observer,t,play(Suit.BLACK))
        // Actual video has a particle effect with no positive empty boundary,
        // followed by four high-score red classifications and black again.
        for(t in 1300L..2300L step 100) show(observer,t,ObservedPlay(emptyList(),1f,regionObserved=false))
        for(t in 2400L..2700L step 100) show(observer,t,play(Suit.RED))
        for(t in 2800L..3200L step 100) show(observer,t,play(Suit.BLACK))
        val snapshot=ledger.snapshot()
        assertEquals(RoundPhase.ACTIVE,snapshot.phase)
        assertEquals(2,snapshot.played(Seat.OPPOSITE))
        assertEquals(1,snapshot.plays.getValue(Seat.OPPOSITE).size)
        assertEquals(List(2) { Card(Rank.SIX,Suit.BLACK) },snapshot.plays.getValue(Seat.OPPOSITE).single().cards)
    }
    @Test fun aPositiveEmptyBoundaryStillAllowsANewPairOfTheOtherColor() {
        val (ledger,observer)=setup()
        for(t in 1000L..1240L step 60) show(observer,t,play(Suit.BLACK))
        for(t in 1300L..1500L step 100) show(observer,t,ObservedPlay(emptyList(),1f))
        for(t in 1600L..1900L step 100) show(observer,t,play(Suit.RED))
        assertEquals(4,ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(2,ledger.snapshot().plays.getValue(Seat.OPPOSITE).size)
    }
    @Test fun differentRanksWithoutAnEmptyBoundaryAreStillObservedAsANewPlay() {
        val (ledger,observer)=setup()
        for(t in 1000L..1240L step 60) show(observer,t,play(Suit.BLACK))
        val seven=play(Suit.RED).let { p -> p.copy(cards=p.cards.map { Card(Rank.SEVEN,it.suit) },
            glyphs=p.glyphs.map { it.copy(card=Card(Rank.SEVEN,it.card.suit)) }) }
        for(t in 1300L..1600L step 100) show(observer,t,seven)
        assertEquals(4,ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(2,ledger.snapshot().plays.getValue(Seat.OPPOSITE).size)
    }
    @Test fun singleCardsOfDifferentColorsRetainTheReferenceException() {
        val (ledger,observer)=setup()
        fun single(suit: Suit)=play(suit).let { it.copy(cards=it.cards.take(1),glyphs=it.glyphs.take(1)) }
        for(t in 1000L..1240L step 60) show(observer,t,single(Suit.BLACK))
        for(t in 1300L..1600L step 100) show(observer,t,single(Suit.RED))
        assertEquals(2,ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(2,ledger.snapshot().plays.getValue(Seat.OPPOSITE).size)
    }
    @Test fun theOtherColorAtAnotherPositionCannotReuseTheReturnAnchor() {
        val (ledger,observer)=setup()
        for(t in 1000L..1240L step 60) show(observer,t,play(Suit.BLACK))
        val shifted=play(Suit.RED).let { p -> p.copy(glyphs=p.glyphs.map { g ->
            g.copy(box=g.box.copy(left=g.box.left+.075f,right=g.box.right+.075f))
        }) }
        for(t in 1300L..1600L step 100) show(observer,t,shifted)
        assertEquals(4,ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(2,ledger.snapshot().plays.getValue(Seat.OPPOSITE).size)
    }
    @Test fun theReturnAnchorExpiresAcrossALongBlindGap() {
        val (ledger,observer)=setup()
        for(t in 1000L..1240L step 60) show(observer,t,play(Suit.BLACK))
        for(t in 1300L..2800L step 100) show(observer,t,ObservedPlay(emptyList(),1f,regionObserved=false))
        for(t in 2900L..3200L step 100) show(observer,t,play(Suit.RED))
        assertEquals(4,ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(2,ledger.snapshot().plays.getValue(Seat.OPPOSITE).size)
    }
}
