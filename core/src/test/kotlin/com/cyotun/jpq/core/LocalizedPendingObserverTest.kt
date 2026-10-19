package com.cyotun.jpq.core

import kotlin.test.*

class LocalizedPendingObserverTest {
    private val deck=Rank.entries.flatMap { rank -> if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
        else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } } }
    private val old=deck.drop(27).take(5)
    private val fresh=deck.drop(32).take(5)
    private val empty=ObservedPlay(emptyList(),1f)
    private class Fixture(val ledger: RoundLedger,val observer: RoundObserver,val states: MutableList<RoundSnapshot>)
    private fun play(cards: List<Card>,x: Float)=ObservedPlay(cards,.9f,cards.mapIndexed { i,c ->
        PlayGlyph(c,.9f,GlyphBox(x+i*.025f,.30f,x+.020f+i*.025f,.35f)) })
    private fun fixture(): Fixture {
        val ledger=RoundLedger().apply {
            begin("localized-observer",deck.take(27))
            commit(PlayEvent("localized-observer","self-before",1,Seat.SELF,deck.take(8)))
        }
        val states=mutableListOf<RoundSnapshot>()
        val f=Fixture(ledger,RoundObserver(ledger) { states+=it },states)
        for(t in listOf(100L,250L,400L,550L)) tick(f,t,previous=empty)
        assertEquals(5,ledger.snapshot().played(Seat.OPPOSITE))
        tick(f,1000,previous=empty); tick(f,1200); tick(f,1432)
        assertEquals(0,ledger.snapshot().played(Seat.PREVIOUS))
        return f
    }
    private fun tick(f: Fixture,time: Long,previous: ObservedPlay=play(fresh,.15f),masked: Boolean=false,
                     next: ObservedPlay=empty) {
        val partial=play(old.take(2),.47f).copy(regionObserved=false)
        f.observer.observe(TableFrame(time,TableScene.TABLE,f.ledger.currentHand(),mapOf(
            Seat.PREVIOUS to previous,Seat.OPPOSITE to (if(masked) partial else play(old,.47f)),
            Seat.NEXT to next,Seat.SELF to empty),
            incompleteReason=if(masked) "localized overlay" else null,
            localizedOverlaySeats=if(masked) setOf(Seat.OPPOSITE) else emptySet()))
    }

    @Test fun boundedReconnectionAppendsOnlyTheNewSeatThenOrdinaryCountingContinues() {
        val f=fixture()
        for(t in listOf(1631L,1833L,2034L,2235L,2430L)) tick(f,t,masked=true)
        assertTrue(f.observer.diagnosticStatus().recovering)
        assertNull(f.ledger.snapshot().remaining)
        tick(f,2628); tick(f,2853)
        assertFalse(f.observer.diagnosticStatus().recovering)
        assertEquals(RoundPhase.ACTIVE,f.ledger.snapshot().phase)
        assertEquals(5,f.ledger.snapshot().played(Seat.PREVIOUS))
        assertEquals(5,f.ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(8,f.ledger.snapshot().played(Seat.SELF))
        assertEquals(1,f.ledger.snapshot().plays.getValue(Seat.PREVIOUS).size)
        assertEquals(1,f.ledger.snapshot().plays.getValue(Seat.OPPOSITE).size)
        assertEquals(fresh,f.ledger.snapshot().plays.getValue(Seat.PREVIOUS).single().cards)
        for(t in listOf(3000L,3200L,3400L,3600L)) tick(f,t)
        assertEquals(5,f.ledger.snapshot().played(Seat.PREVIOUS))
        assertTrue(f.states.any { it.phase==RoundPhase.RECOVERING })
        assertNotNull(f.ledger.snapshot().remaining)
    }

    @Test fun anotherSeatsUntrackedPlayCannotBeHiddenByTheShortLocalTransaction() {
        val f=fixture()
        tick(f,1631,masked=true); tick(f,1833,masked=true)
        tick(f,2034,masked=true,next=play(deck.drop(37).take(1),.76f))
        tick(f,2235,masked=true); tick(f,2430,masked=true)
        tick(f,2628); tick(f,2853)
        assertTrue(f.observer.diagnosticStatus().recovering)
        assertEquals(0,f.ledger.snapshot().played(Seat.PREVIOUS))
        assertEquals(5,f.ledger.snapshot().played(Seat.OPPOSITE))
        assertNull(f.ledger.snapshot().remaining)
    }
}
