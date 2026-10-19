package com.cyotun.jpq.core

import kotlin.test.*

class TerminalOwnHandObserverTest {
    private val deck=Rank.entries.flatMap { rank -> if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
        else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } } }
    private val last=listOf(Card(Rank.TEN,Suit.RED))+List(3) { Card(Rank.TEN,Suit.BLACK) }
    private class Fixture(val ledger: RoundLedger,val observer: RoundObserver,val states: List<RoundSnapshot>)
    private fun fixture(opposite: Int=27): Fixture {
        val first=deck.filter { it.rank!=Rank.TEN }.take(23)+last
        val outside=deck.toMutableList().apply { first.forEach { remove(it) } }
        val ledger=RoundLedger().apply {
            begin("terminal-four",first)
            commit(PlayEvent("terminal-four","own-before",1,Seat.SELF,first.take(23)))
            commit(PlayEvent("terminal-four","opposite-before",2,Seat.OPPOSITE,outside.take(opposite)))
            commit(PlayEvent("terminal-four","next-before",3,Seat.NEXT,outside.drop(opposite).take(27)))
            commit(PlayEvent("terminal-four","previous-before",4,Seat.PREVIOUS,outside.drop(opposite+27).take(25)))
        }
        val states=mutableListOf<RoundSnapshot>()
        return Fixture(ledger,RoundObserver(ledger) { states+=it },states)
    }
    private fun play(cards: List<Card> = last)=ObservedPlay(cards,.89f,cards.mapIndexed { i,card ->
        PlayGlyph(card,.89f,GlyphBox(.38f+i*.025f,.32f,.40f+i*.025f,.38f))
    })
    private fun tick(f: Fixture,time: Long,hand: List<Card> = emptyList(),p: ObservedPlay?=play(),
                     scene: TableScene=TableScene.TABLE,finished: Boolean=false,visible: Boolean=true) =
        f.observer.observe(TableFrame(time,scene,hand,mapOf(
            Seat.SELF to (p ?: ObservedPlay(emptyList(),1f)),
            Seat.PREVIOUS to ObservedPlay(emptyList(),1f,regionObserved=visible)),selfFinished=finished))
    private fun toRecovery(f: Fixture,hiddenOutside: Boolean=false) {
        tick(f,1000,hand=last,p=null)
        tick(f,1266,p=play(last.take(1)))
        tick(f,1532)
        tick(f,1798,p=play(last.take(1)))
        tick(f,2064,p=null,scene=TableScene.UNKNOWN,visible=!hiddenOutside)
        tick(f,2330,p=play(List(4) { Card(Rank.TEN,Suit.RED) }))
        tick(f,2596)
    }
    @Test fun aShortOwnAnimationRecoversThenRecordsTheFinalWholeHandAndFinishes() {
        val f=fixture(); toRecovery(f)
        assertTrue(f.observer.diagnosticStatus().recovering)
        tick(f,2862,finished=true)
        val done=f.states.single { it.phase==RoundPhase.FINISHED }
        assertNotNull(done.remaining)
        assertEquals(27,done.played(Seat.SELF)); assertEquals(27,done.played(Seat.OPPOSITE))
        assertEquals(27,done.played(Seat.NEXT)); assertEquals(25,done.played(Seat.PREVIOUS))
        assertEquals(RoundPhase.WAITING,f.ledger.snapshot().phase)
    }
    @Test fun anUnconfirmedOutsideCardOrBlindRemainingPlayerCannotBeHiddenByACompletionLabel() {
        for(f in listOf(fixture(26),fixture())) {
            toRecovery(f,hiddenOutside=f.ledger.snapshot().played(Seat.OPPOSITE)==27)
            tick(f,2862,finished=true)
            assertTrue(f.states.none { it.phase==RoundPhase.FINISHED })
            assertEquals(RoundPhase.RECOVERING,f.ledger.snapshot().phase)
            assertNull(f.ledger.snapshot().remaining)
        }
    }
}
