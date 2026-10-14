package com.cyotun.jpq.core

import kotlin.test.*

class RoundEndRegressionTest {
    private val baseline=Rank.entries.filterNot { it.isJoker }.flatMap { listOf(Card(it,Suit.RED),Card(it,Suit.BLACK)) }+Card(Rank.SMALL,Suit.NONE)
    private val shownRemainder=listOf(Card(Rank.Q,Suit.RED),Card(Rank.Q,Suit.BLACK),Card(Rank.Q,Suit.BLACK),Card(Rank.THREE,Suit.RED),Card(Rank.THREE,Suit.BLACK))
    private val remainingDeck=Rank.entries.flatMap { rank ->
        if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
        else List(4) { Card(rank,Suit.RED) }+List(4) { Card(rank,Suit.BLACK) }
    }.toMutableList().also { all -> baseline.forEach { check(all.remove(it)) } }
    private val playedCards=remainingDeck.toMutableList().also { all -> shownRemainder.forEach { check(all.remove(it)) } }
    private val previous=playedCards.take(27)
    private val opposite=playedCards.drop(27).take(27)
    private val next=playedCards.drop(54)

    private inner class Fixture(previousCount: Int,oppositeCount: Int,nextCount: Int) {
        val ledger=RoundLedger()
        val snapshots=mutableListOf<RoundSnapshot>()
        val observer=RoundObserver(ledger) { snapshots.add(it) }
        var time=0L
        init {
            observer.observe(TableFrame(time,TableScene.LOBBY,null))
            repeat(3) { time+=120; observer.observe(TableFrame(time,TableScene.TABLE,baseline,Seat.entries.associateWith { ObservedPlay(emptyList(),1f) })) }
            addPlays(Seat.PREVIOUS,previous.take(previousCount))
            addPlays(Seat.OPPOSITE,opposite.take(oppositeCount))
            addPlays(Seat.NEXT,next.take(nextCount))
        }
        private fun addPlays(seat: Seat,cards: List<Card>) {
            // Each ordinary rank group is a legal single/pair/triple/bomb, not a fictitious
            // whole-hand 27-card play. Clear observations separate repeated presentations.
            for(group in cards.groupBy { it.rank }.values) show(seat,group)
        }
        fun show(seat: Seat,cards: List<Card>,finished: Boolean=false,scene: TableScene=TableScene.TABLE) {
            repeat(2) { tick(mapOf(seat to ObservedPlay(emptyList(),1f)),finished,scene) }
            repeat(4) { tick(mapOf(seat to ObservedPlay(cards,.99f)),finished,scene) }
        }
        fun tick(plays: Map<Seat,ObservedPlay?> = emptyMap(),finished: Boolean=false,scene: TableScene=TableScene.TABLE) {
            time+=120; observer.observe(TableFrame(time,scene,null,plays,selfFinished=finished))
        }
    }

    @Test fun visibleSelfFinishAndTwoCompletedOpponentsArchiveFiveUnplayedCards() {
        val fixture=Fixture(27,27,22)
        assertEquals(5,fixture.ledger.snapshot().remaining!!.values.sum())
        fixture.tick(mapOf(Seat.NEXT to ObservedPlay(shownRemainder,.99f)),finished=true)
        val finished=fixture.snapshots.single { it.phase==RoundPhase.FINISHED }
        assertEquals(5,finished.remaining!!.values.sum())
        assertEquals(22,finished.played(Seat.NEXT)); assertEquals(0,finished.played(Seat.SELF))
        assertTrue(finished.plays[Seat.SELF]!!.isEmpty())
        assertEquals(RoundPhase.WAITING,fixture.ledger.snapshot().phase)
        repeat(6) { fixture.tick(mapOf(Seat.NEXT to ObservedPlay(shownRemainder,.99f)),finished=true) }
        assertEquals(RoundPhase.WAITING,fixture.ledger.snapshot().phase)
        assertEquals(0,fixture.observer.diagnosticStatus().pendingPlays)
        assertFalse(fixture.observer.diagnosticStatus().armed)
        // A new observed deal re-arms from its own baseline, without buffering the last shown hand.
        repeat(3) {
            fixture.time+=120
            fixture.observer.observe(TableFrame(fixture.time,TableScene.DEALING,baseline,Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }))
        }
        assertEquals(RoundPhase.ACTIVE,fixture.ledger.snapshot().phase)
        assertEquals(81,fixture.ledger.snapshot().remaining!!.values.sum())
        assertTrue(fixture.ledger.snapshot().plays.values.all { it.isEmpty() })
    }

    @Test fun twoCompletedPlayersOnDifferentTeamsContinueUntilTheThirdFinishes() {
        val fixture=Fixture(27,27,22)
        assertEquals(RoundPhase.ACTIVE,fixture.ledger.snapshot().phase)
        fixture.show(Seat.NEXT,shownRemainder)
        val finished=fixture.snapshots.single { it.phase==RoundPhase.FINISHED }
        assertEquals(27,finished.played(Seat.NEXT))
        assertEquals(0,finished.remaining!!.values.sum())
        assertEquals(RoundPhase.WAITING,fixture.ledger.snapshot().phase)
    }

    @Test fun selfFinishWithTwoPlayersStillPlayingContinuesWithoutInventingSelfEvents() {
        val fixture=Fixture(27,0,22)
        fixture.show(Seat.NEXT,shownRemainder.take(1),finished=true)
        assertEquals(RoundPhase.SELF_FINISHED,fixture.ledger.snapshot().phase)
        assertEquals(23,fixture.ledger.snapshot().played(Seat.NEXT))
        assertEquals(31,fixture.ledger.snapshot().remaining!!.values.sum())
        assertEquals(0,fixture.ledger.snapshot().played(Seat.SELF))
        assertTrue(fixture.snapshots.none { it.phase==RoundPhase.FINISHED })
    }

    @Test fun teammateViewKeepsTheSameFinishAndContinuationRules() {
        val fixture=Fixture(27,0,22)
        fixture.show(Seat.NEXT,shownRemainder.take(1),scene=TableScene.TEAMMATE)
        assertEquals(RoundPhase.SELF_FINISHED,fixture.ledger.snapshot().phase)
        assertEquals(23,fixture.ledger.snapshot().played(Seat.NEXT))
        assertEquals(0,fixture.ledger.snapshot().played(Seat.SELF))
        assertTrue(fixture.snapshots.none { it.phase==RoundPhase.FINISHED })
    }

    @Test fun theSecondTeammateFinalPlayFinishesWithinItsConfirmedFrame() {
        val fixture=Fixture(27,26,22)
        val lastOppositeCard=opposite.last()
        fixture.show(Seat.OPPOSITE,listOf(lastOppositeCard),finished=true)
        val finished=fixture.snapshots.single { it.phase==RoundPhase.FINISHED }
        assertEquals(27,finished.played(Seat.OPPOSITE))
        assertEquals(5,finished.remaining!!.values.sum()); assertEquals(22,finished.played(Seat.NEXT))
        assertEquals(RoundPhase.WAITING,fixture.ledger.snapshot().phase)
        fixture.tick(mapOf(Seat.NEXT to ObservedPlay(shownRemainder,.99f)),finished=true)
        assertEquals(RoundPhase.WAITING,fixture.ledger.snapshot().phase)
    }

    @Test fun anEmptyHandWithoutAVisibleFinishMarkerIsNotACompletionSignal() {
        val fixture=Fixture(27,27,22)
        repeat(4) {
            fixture.time+=120
            fixture.observer.observe(TableFrame(fixture.time,TableScene.TABLE,emptyList(),emptyMap()))
        }
        assertEquals(RoundPhase.ACTIVE,fixture.ledger.snapshot().phase)
        assertTrue(fixture.snapshots.none { it.phase==RoundPhase.FINISHED })
    }
}
