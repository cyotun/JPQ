package com.cyotun.jpq.core

import kotlin.test.*

class TeammateShortPlayFinishObserverTest {
    @Test fun shortFiveCardHandoffThenLastTenClosesBeforeOpponentsRevealedPair() {
        val ten=Card(Rank.TEN,Suit.BLACK)
        val straight=listOf(Card(Rank.SIX,Suit.RED),Card(Rank.SEVEN,Suit.BLACK),
            Card(Rank.EIGHT,Suit.RED),Card(Rank.NINE,Suit.RED),ten)
        val teammate=straight+ten
        val deck=Rank.entries.flatMap { rank -> if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
            else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } } }.toMutableList()
        teammate.forEach { check(deck.remove(it)) }
        fun take(n: Int)=deck.take(n).also { repeat(n) { deck.removeAt(0) } }
        val own=take(27);val opposite=take(21);val previous=take(25);val next=take(27);val revealed=take(2)
        val ledger=RoundLedger().apply {
            begin("short-teammate-finish",own)
            listOf(Seat.SELF to own,Seat.OPPOSITE to opposite,Seat.PREVIOUS to previous,Seat.NEXT to next)
                .forEachIndexed { i,(seat,cards) -> commit(PlayEvent("short-teammate-finish","old-$i",i.toLong(),seat,cards)) }
        }
        val states=mutableListOf<RoundSnapshot>();val observer=RoundObserver(ledger) { states+=it }
        fun play(cards: List<Card>,confidence: Float=.9f)=ObservedPlay(cards,confidence,cards.mapIndexed { i,c ->
            PlayGlyph(c,confidence,GlyphBox(.42f+i*.025f,.18f,.44f+i*.025f,.23f)) })
        fun frame(t: Long,hand: List<Card>,shown: ObservedPlay=play(emptyList()),source: Long?=null,
            previousPlay: List<Card> = emptyList())=TableFrame(t,TableScene.TEAMMATE,null,
            Seat.entries.associateWith { seat -> when(seat) {
                Seat.OPPOSITE -> shown
                Seat.PREVIOUS -> play(previousPlay)
                else -> play(emptyList())
            } },selfFinished=true,teammateHand=hand,teammateHandConfidence=.9f,
            remainingHandCounts=source?.let { mapOf(Seat.OPPOSITE to RemainingHandCount(1,it)) }.orEmpty())
        observer.observe(frame(1000,teammate));observer.observe(frame(1120,teammate))
        observer.observe(frame(1300,listOf(ten),play(straight.take(4),.709f)))
        observer.observe(frame(1420,listOf(ten),play(straight,.632f)))
        observer.observe(frame(1540,listOf(ten),play(straight,.836f),source=1420))
        observer.observe(frame(1660,listOf(ten),source=1420))
        observer.observe(frame(1780,listOf(ten),source=1660))
        assertEquals(26,ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(straight,ledger.snapshot().plays.getValue(Seat.OPPOSITE).last().cards)
        assertEquals(RoundPhase.SELF_FINISHED,ledger.snapshot().phase)
        // The next frame already shows the last TEN leaving. There are no new
        // views of the one-card hand from which to rebuild a two-vote seed.
        for(t in listOf(2020L,2140L,2260L,2380L)) observer.observe(frame(t,emptyList(),play(listOf(ten))))
        val finished=states.last { it.phase==RoundPhase.FINISHED }
        assertEquals(27,finished.played(Seat.SELF));assertEquals(25,finished.played(Seat.PREVIOUS))
        assertEquals(27,finished.played(Seat.OPPOSITE));assertEquals(27,finished.played(Seat.NEXT))
        assertEquals(3,finished.plays.getValue(Seat.OPPOSITE).size)
        for(t in listOf(2620L,2740L,2860L,2980L)) observer.observe(frame(t,emptyList(),previousPlay=revealed))
        assertEquals(RoundPhase.WAITING,ledger.snapshot().phase)
        assertEquals(25,states.last { it.phase==RoundPhase.FINISHED }.played(Seat.PREVIOUS))
    }
}
