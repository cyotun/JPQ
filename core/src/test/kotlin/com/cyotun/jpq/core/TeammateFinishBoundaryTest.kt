package com.cyotun.jpq.core

import kotlin.test.*

class TeammateFinishBoundaryTest {
    private val deck=Rank.entries.flatMap { rank -> if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
        else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } } }
    private fun prepared(oppositeCount: Int): RoundLedger=RoundLedger().also { ledger ->
        ledger.begin("team-finish",deck.take(27))
        ledger.commit(PlayEvent("team-finish","opposite",1,Seat.OPPOSITE,deck.drop(27).take(oppositeCount)))
    }
    @Test fun bothTeammatesFinishedEndsBeforeOpponentRevealsAreCounted() {
        val ledger=prepared(27)
        val states=mutableListOf<RoundSnapshot>()
        val observer=RoundObserver(ledger) { states.add(it) }
        observer.observe(TableFrame(1000,TableScene.TABLE,null,selfFinished=true))
        assertTrue(states.any { it.phase==RoundPhase.FINISHED && it.played(Seat.OPPOSITE)==27 })
        assertEquals(RoundPhase.WAITING,ledger.snapshot().phase)
        // A displayed remaining enemy hand must not become another play/new baseline.
        observer.observe(TableFrame(1200,TableScene.TABLE,deck.take(10)))
        assertEquals(RoundPhase.WAITING,ledger.snapshot().phase)
    }
    @Test fun ownFinishAndAnUnfinishedTeammateStillTracksTheRound() {
        val ledger=prepared(26)
        val observer=RoundObserver(ledger)
        observer.observe(TableFrame(1000,TableScene.TABLE,null,selfFinished=true))
        assertEquals(RoundPhase.SELF_FINISHED,ledger.snapshot().phase)
        assertEquals(26,ledger.snapshot().played(Seat.OPPOSITE))
    }
}
