package com.cyotun.jpq.core

import kotlin.test.*

class ShortTrackedExpansionTest {
    private val baseline=Rank.entries.filterNot { it.isJoker }.flatMap { listOf(Card(it,Suit.RED),Card(it,Suit.BLACK)) }+Card(Rank.SMALL,Suit.NONE)
    private val prefix=listOf(Card(Rank.SIX,Suit.BLACK),Card(Rank.SEVEN,Suit.BLACK),Card(Rank.EIGHT,Suit.RED),Card(Rank.NINE,Suit.BLACK))
    private val full=prefix+Card(Rank.TEN,Suit.BLACK)
    private fun play(cards: List<Card>,confidence: Float=.99f,tailOffset: Float=0f)=ObservedPlay(cards,confidence,
        cards.mapIndexed { i,card -> val offset=if(i==4) tailOffset else 0f
            PlayGlyph(card,confidence,GlyphBox(.15f+i*.027f+offset,.28f,.17f+i*.027f+offset,.33f)) })
    private fun setup(): Pair<RoundLedger,RoundObserver> {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger)
        observer.observe(TableFrame(0,TableScene.LOBBY,null))
        val empty=Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }
        for(time in 60L..240L step 60) observer.observe(TableFrame(time,TableScene.TABLE,baseline,empty))
        for(time in 480L..660L step 60) observer.observe(TableFrame(time,TableScene.TABLE,baseline,mapOf(Seat.PREVIOUS to play(prefix))))
        return ledger to observer
    }
    @Test fun threeDirectFullViewsCompleteTheSameTrackedStraightBeforeItClears() {
        val (ledger,observer)=setup(); val id=ledger.snapshot().plays.getValue(Seat.PREVIOUS).single().eventId
        for((time,confidence) in listOf(720L to .731f,819L to .800f,869L to .841f))
            observer.observe(TableFrame(time,TableScene.TABLE,baseline,mapOf(Seat.PREVIOUS to play(full,confidence))))
        val event=ledger.snapshot().plays.getValue(Seat.PREVIOUS).single()
        assertEquals(id,event.eventId); assertEquals(full,event.cards)
    }
    @Test fun movingNewGlyphCannotUseTheThreeViewExpansionProof() {
        val (ledger,observer)=setup()
        for((i,time) in listOf(720L,819L,869L).withIndex())
            observer.observe(TableFrame(time,TableScene.TABLE,baseline,mapOf(Seat.PREVIOUS to play(full,.85f,i*.025f))))
        assertEquals(prefix,ledger.snapshot().plays.getValue(Seat.PREVIOUS).single().cards)
    }
    @Test fun lowConfidenceExpansionStillNeedsTheNormalFourReadings() {
        val (ledger,observer)=setup()
        for(time in listOf(720L,819L,869L))
            observer.observe(TableFrame(time,TableScene.TABLE,baseline,mapOf(Seat.PREVIOUS to play(full,.69f))))
        assertEquals(prefix,ledger.snapshot().plays.getValue(Seat.PREVIOUS).single().cards)
        observer.observe(TableFrame(930L,TableScene.TABLE,baseline,mapOf(Seat.PREVIOUS to play(full,.69f))))
        assertEquals(full,ledger.snapshot().plays.getValue(Seat.PREVIOUS).single().cards)
    }
}
