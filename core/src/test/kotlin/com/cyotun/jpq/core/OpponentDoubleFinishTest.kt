package com.cyotun.jpq.core

import kotlin.test.*

class OpponentDoubleFinishTest {
    private val deck=Rank.entries.flatMap { rank -> if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
        else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } } }
    private fun ledger(previous: Int,next: Int,opposite: Int=0)=RoundLedger().also { l ->
        l.begin("enemy-double",deck.take(27))
        var sequence=0L
        for((seat,cards) in listOf(Seat.PREVIOUS to deck.drop(27).take(previous),
            Seat.NEXT to deck.drop(54).take(next),Seat.OPPOSITE to deck.drop(81).take(opposite))) {
            if(cards.isNotEmpty()) l.commit(PlayEvent("enemy-double","enemy-double:${++sequence}",sequence,seat,cards))
        }
    }
    private inner class ObservedFixture {
        val ledger=RoundLedger()
        val states=mutableListOf<RoundSnapshot>()
        val observer=RoundObserver(ledger) { states.add(it) }
        var time=0L
        init {
            observer.observe(TableFrame(time,TableScene.LOBBY,null))
            repeat(3) { time+=120; observer.observe(TableFrame(time,TableScene.TABLE,deck.take(27),
                Seat.entries.associateWith { ObservedPlay(emptyList(),1f) })) }
            for((seat,cards) in listOf(Seat.PREVIOUS to deck.drop(27).take(27),Seat.NEXT to deck.drop(54).take(26))) {
                for(group in cards.groupBy { it.rank }.values) {
                    repeat(2) { tick(seat,ObservedPlay(emptyList(),1f)) }
                    repeat(4) { tick(seat,ObservedPlay(group,.99f)) }
                }
            }
        }
        fun tick(seat: Seat,play: ObservedPlay) {
            time+=120; observer.observe(TableFrame(time,TableScene.TABLE,null,mapOf(seat to play)))
        }
    }
    @Test fun opposingTeammatesBothFinishedEndsBeforeUnplayedHandsArePresented() {
        // User round 1638044480: left/right 27, own 11, teammate 13. The losing
        // hands subsequently appear across the centre; they are not further plays.
        val l=ledger(27,27,13); val states=mutableListOf<RoundSnapshot>()
        val observer=RoundObserver(l) { states.add(it) }
        val reveal=ObservedPlay(deck.take(16),.99f)
        observer.observe(TableFrame(1000,TableScene.TABLE,null,mapOf(Seat.OPPOSITE to reveal)))
        assertEquals(RoundPhase.WAITING,l.snapshot().phase)
        val finished=states.single { it.phase==RoundPhase.FINISHED }
        assertEquals(13,finished.played(Seat.OPPOSITE)); assertEquals(14,finished.remaining!!.values.sum())
        assertFalse(observer.diagnosticStatus().armed)
        repeat(5) { observer.observe(TableFrame(1120+it*120L,TableScene.TABLE,deck.take(27),mapOf(Seat.OPPOSITE to reveal))) }
        assertEquals(RoundPhase.WAITING,l.snapshot().phase)
        assertEquals(0,observer.diagnosticStatus().pendingPlays)
    }
    @Test fun missingResultOcrDoesNotTurnAConfirmedDoubleFinishIntoUnknownFailure() {
        val l=ledger(27,27); val observer=RoundObserver(l)
        observer.observe(TableFrame(1000,TableScene.UNKNOWN,null))
        assertEquals(RoundPhase.WAITING,l.snapshot().phase)
    }
    @Test fun oneCardRemainingForEitherOpponentMustContinue() {
        for((previous,next) in listOf(27 to 26,26 to 27)) {
            val l=ledger(previous,next); val observer=RoundObserver(l)
            observer.observe(TableFrame(1000,TableScene.TABLE,null))
            assertEquals(RoundPhase.ACTIVE,l.snapshot().phase)
            assertFalse(confirmedRoundFinished(l.snapshot()))
        }
    }
    @Test fun twoFinishedPlayersOnDifferentTeamsMustContinue() {
        val l=ledger(27,22,27); val observer=RoundObserver(l)
        observer.observe(TableFrame(1000,TableScene.TABLE,null))
        assertEquals(RoundPhase.ACTIVE,l.snapshot().phase)
    }
    @Test fun genuineCapacityConflictRetainsAnUnfinishedCheckpointWithoutInventingTheOverflow() {
        val fixture=ObservedFixture()
        val overflow=ObservedPlay(listOf(Card(Rank.TWO,Suit.RED),Card(Rank.TWO,Suit.BLACK)),.99f)
        repeat(2) { fixture.tick(Seat.NEXT,ObservedPlay(emptyList(),1f)) }
        repeat(4) { fixture.tick(Seat.NEXT,overflow) }
        assertEquals(RoundPhase.ACTIVE,fixture.ledger.snapshot().phase)
        assertNotNull(fixture.ledger.snapshot().remaining)
        assertTrue(fixture.observer.diagnosticStatus().rejectedPlayCandidates>0)
        assertEquals(26,fixture.ledger.snapshot().played(Seat.NEXT))
    }
    @Test fun finalOpponentPlayArchivesBothFinishersInTheFourthVoteFrame() {
        val fixture=ObservedFixture()
        val final=ObservedPlay(listOf(deck[80]),.99f)
        repeat(2) { fixture.tick(Seat.NEXT,ObservedPlay(emptyList(),1f)) }
        repeat(3) { fixture.tick(Seat.NEXT,final) }
        assertEquals(RoundPhase.ACTIVE,fixture.ledger.snapshot().phase)
        fixture.tick(Seat.NEXT,final)
        val finished=fixture.states.single { it.phase==RoundPhase.FINISHED }
        assertEquals(27,finished.played(Seat.PREVIOUS)); assertEquals(27,finished.played(Seat.NEXT))
        assertEquals(RoundPhase.WAITING,fixture.ledger.snapshot().phase)
    }
}
