package com.cyotun.jpq.core

import kotlin.test.*

class FinalHandPresentationTest {
    private val deck=Rank.entries.flatMap { rank -> if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
        else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } } }
    private class Fixture(val ledger: RoundLedger,val states: MutableList<RoundSnapshot>,val observer: RoundObserver)
    private fun fixture(): Fixture {
        val ledger=RoundLedger().apply {
            begin("last-five",deck.take(27))
            commit(PlayEvent("last-five","previous-22",1,Seat.PREVIOUS,deck.drop(27).take(22)))
            commit(PlayEvent("last-five","next-27",2,Seat.NEXT,deck.drop(54).take(27)))
        }
        val states=mutableListOf<RoundSnapshot>()
        return Fixture(ledger,states,RoundObserver(ledger) { states+=it })
    }
    private fun play(cards: List<Card> = deck.drop(49).take(5),confidence: Float=.872f,shift: Float=0f)=
        ObservedPlay(cards,confidence,cards.mapIndexed { i,card ->
            PlayGlyph(card,confidence,GlyphBox(.15f+i*.025f+shift,.30f,.17f+i*.025f+shift,.35f))
        })
    private fun tick(f: Fixture,time: Long,p: ObservedPlay,hand: List<Card>?=deck.take(27),scene: TableScene=TableScene.TABLE) =
        f.observer.observe(TableFrame(time,scene,hand,mapOf(Seat.PREVIOUS to p)))

    @Test fun threeCompleteStableFinalReadingsAcross532msFinishBeforeTheReveal() {
        val f=fixture()
        tick(f,1000,play()); tick(f,1266,play())
        assertEquals(22,f.ledger.snapshot().played(Seat.PREVIOUS))
        tick(f,1532,play())
        val finished=f.states.single { it.phase==RoundPhase.FINISHED }
        assertEquals(27,finished.played(Seat.PREVIOUS)); assertEquals(27,finished.played(Seat.NEXT))
        assertEquals(27,finished.remaining!!.values.sum())
        assertEquals(RoundPhase.WAITING,f.ledger.snapshot().phase)
        repeat(4) { tick(f,1700+it*266L,play(deck.drop(81).take(22)),hand=emptyList()) }
        assertEquals(1,f.states.count { it.phase==RoundPhase.FINISHED })
        assertEquals(RoundPhase.WAITING,f.ledger.snapshot().phase)
    }

    @Test fun threeFastOrLowConfidenceReadingsRetainTheNormalFourFrameGate() {
        val fast=fixture()
        listOf(1000L,1100L,1200L).forEach { tick(fast,it,play()) }
        assertEquals(22,fast.ledger.snapshot().played(Seat.PREVIOUS))
        val low=fixture()
        listOf(1000L,1266L,1532L).forEach { tick(low,it,play(confidence=.84f)) }
        assertEquals(22,low.ledger.snapshot().played(Seat.PREVIOUS))
        tick(low,1798,play(confidence=.84f))
        assertEquals(RoundPhase.WAITING,low.ledger.snapshot().phase)
    }

    @Test fun aFragmentOrMovingGroupCannotUseTheCompleteFinalHandProof() {
        val partial=fixture()
        listOf(1000L,1266L,1532L).forEach { tick(partial,it,play(deck.drop(49).take(4))) }
        assertEquals(22,partial.ledger.snapshot().played(Seat.PREVIOUS))
        val moving=fixture()
        repeat(3) { tick(moving,1000+it*266L,play(shift=it*.05f)) }
        assertEquals(22,moving.ledger.snapshot().played(Seat.PREVIOUS))
    }

    @Test fun resultHandRevealsAndMissingOwnHandDoNotQualifyForTheShortcut() {
        for(hand in listOf<List<Card>?>(null,emptyList(),deck.take(26))) {
            val f=fixture()
            listOf(1000L,1266L,1532L).forEach { tick(f,it,play(),hand=hand) }
            assertEquals(22,f.ledger.snapshot().played(Seat.PREVIOUS))
        }
        val teammate=fixture()
        teammate.ledger.selfFinished()
        listOf(1000L,1266L,1532L).forEach { tick(teammate,it,play(),hand=null,scene=TableScene.TEAMMATE) }
        assertEquals(22,teammate.ledger.snapshot().played(Seat.PREVIOUS))
    }
}
