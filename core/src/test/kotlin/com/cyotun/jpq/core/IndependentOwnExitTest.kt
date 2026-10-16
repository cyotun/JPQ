package com.cyotun.jpq.core

import kotlin.test.*

/** Regression: final four kings were visible while other seats revealed cards. */
class IndependentOwnExitTest {
    private val final=listOf(Card(Rank.K,Suit.BLACK))+List(3) { Card(Rank.K,Suit.RED) }
    private val deck=Rank.entries.flatMap { rank -> if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
        else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } } }
    private fun setup(finishOthers: Boolean=false): Pair<RoundLedger,MutableList<RoundSnapshot>> {
        val other=deck.filter { it.rank!=Rank.K }.take(23)
        val ledger=RoundLedger(); ledger.begin("exit",other+final)
        ledger.commit(PlayEvent("exit","own-before",1,Seat.SELF,other))
        if(finishOthers) {
            val outside=deck.toMutableList().apply { (other+final).forEach { remove(it) } }
            ledger.commit(PlayEvent("exit","opposite-finished",2,Seat.OPPOSITE,outside.take(27)))
            ledger.commit(PlayEvent("exit","previous-finished",3,Seat.PREVIOUS,outside.drop(27).take(27)))
        }
        return ledger to mutableListOf()
    }
    private fun play(cards: List<Card> = final,confidence: Float=.89f,observed: Boolean=true)=ObservedPlay(cards,confidence,
        cards.mapIndexed { i,card -> PlayGlyph(card,confidence,GlyphBox(.44f+i*.026f,.324f,.46f+i*.026f,.37f)) },observed)
    private fun frame(time: Long,ownPlay: ObservedPlay,hand: List<Card> = emptyList())=TableFrame(time,TableScene.TABLE,hand,
        mapOf(Seat.SELF to ownPlay,Seat.NEXT to play(listOf(Card(Rank.A,Suit.BLACK)))),
        incompleteReason="other seat ambiguous",ambiguousPresentation=true)

    @Test fun otherSeatAmbiguityDoesNotEraseCompleteOwnDifferenceVotes() {
        val (ledger,changes)=setup(); val observer=RoundObserver(ledger) { changes.add(it) }
        observer.observe(TableFrame(100,TableScene.TABLE,final))
        for(time in 200L..380L step 60) observer.observe(frame(time,play()))
        assertEquals(27,ledger.snapshot().played(Seat.SELF))
        assertEquals(RoundPhase.SELF_FINISHED,ledger.snapshot().phase)
        assertEquals(0,ledger.snapshot().played(Seat.NEXT))
        assertEquals(final,ledger.snapshot().plays.getValue(Seat.SELF).last().cards)
        assertTrue(changes.none { it.phase==RoundPhase.UNAVAILABLE })
    }
    @Test fun positivelyFinishedOwnTeamEndsBeforeUnownedLosingHandReveals() {
        val (ledger,changes)=setup(true); val observer=RoundObserver(ledger) { changes.add(it) }
        observer.observe(TableFrame(100,TableScene.TABLE,final))
        for(time in 200L..380L step 60) observer.observe(frame(time,play()))
        val finished=changes.single { it.phase==RoundPhase.FINISHED }
        assertNotNull(finished.remaining)
        assertEquals(27,finished.played(Seat.SELF))
        assertEquals(27,finished.played(Seat.OPPOSITE))
        assertEquals(RoundPhase.WAITING,ledger.snapshot().phase)
    }
    @Test fun partialUnobservedLowConfidenceOrUnchangedHandCannotProveExit() {
        for((p,h) in listOf(play(final.take(3)) to emptyList(),play(observed=false) to emptyList(),
            play(confidence=.604f) to emptyList(),play() to final)) {
            val (ledger,_)=setup(); val observer=RoundObserver(ledger)
            observer.observe(TableFrame(100,TableScene.TABLE,final))
            for(time in 200L..380L step 60) observer.observe(frame(time,p,h))
            assertEquals(23,ledger.snapshot().played(Seat.SELF))
        }
    }
    @Test fun genuineCaptureGapCannotBeBypassedByAnOwnDisplay() {
        val (ledger,_)=setup(); val observer=RoundObserver(ledger)
        observer.observe(TableFrame(100,TableScene.TABLE,final))
        for(time in 200L..380L step 60) observer.observe(frame(time,play()).copy(captureGap=true))
        assertEquals(23,ledger.snapshot().played(Seat.SELF))
    }
}
