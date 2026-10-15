package com.cyotun.jpq.core

import kotlin.test.*

/** After own finish, a completed opponent has no pass label to read again. */
class TeammateFinishedSeatOwnershipTest {
    private val central=listOf(Suit.RED,Suit.BLACK).mapIndexed { i,suit ->
        PlayGlyph(Card(Rank.TWO,suit),.94f,GlyphBox(.475f+i*.026f,.323f,.490f+i*.026f,.372f))
    }
    private val deck=Rank.entries.flatMap { rank ->
        if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
        else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } }
    }
    private fun emptyPlays()=Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }

    @Test fun onePassAndOneConfirmedFinisherIdentifyTheTeammateCentralPair() {
        val owned=assignPlayGroups(central,setOf(Seat.PREVIOUS),emptyList(),true,
            finishedSeats=setOf(Seat.SELF,Seat.NEXT))
        assertEquals(central,owned.seats.getValue(Seat.OPPOSITE))
        assertTrue(owned.ambiguous.isEmpty())
    }

    @Test fun aPassByAnAlreadyFinishedSeatIsNotTwoIndependentExclusions() {
        val owned=assignPlayGroups(central,setOf(Seat.NEXT),emptyList(),true,
            finishedSeats=setOf(Seat.SELF,Seat.NEXT))
        assertEquals(central,owned.ambiguous)
        assertTrue(owned.seats.values.all { it.isEmpty() })
    }

    @Test fun twoUnfinishedEligiblePlayersRemainAmbiguous() {
        val owned=assignPlayGroups(central,emptySet(),emptyList(),true,
            finishedSeats=setOf(Seat.SELF,Seat.NEXT))
        assertEquals(central,owned.ambiguous)
    }

    @Test fun theKnownOwnExitStillWinsOverTheOnlyEligibleOpponent() {
        val owned=assignPlayGroups(central,setOf(Seat.PREVIOUS),emptyList(),true,central,
            setOf(Seat.SELF,Seat.NEXT))
        assertEquals(central,owned.seats.getValue(Seat.SELF))
        assertTrue(owned.seats.getValue(Seat.OPPOSITE).isEmpty())
    }

    @Test fun conflictingOwnExitColoursAreNeverReassignedToTheOpponent() {
        val recolored=central.map { it.copy(card=Card(Rank.TWO,Suit.RED)) }
        val owned=assignPlayGroups(recolored,setOf(Seat.PREVIOUS),emptyList(),true,central,
            setOf(Seat.SELF,Seat.NEXT))
        assertEquals(recolored,owned.ambiguous)
        assertTrue(owned.seats.getValue(Seat.OPPOSITE).isEmpty())
    }

    @Test fun onlyPositiveLedgerCompletionExcludesAPlayer() {
        val plays=Seat.entries.associateWith { emptyList<PlayEvent>() }.toMutableMap()
        plays[Seat.NEXT]=listOf(PlayEvent("round","next",1,Seat.NEXT,deck.drop(27).take(26)))
        val partial=RoundSnapshot("round",RoundPhase.SELF_FINISHED,emptyMap(),plays,null)
        assertEquals(setOf(Seat.SELF),confirmedFinishedSeats(partial))
        plays[Seat.NEXT]=listOf(PlayEvent("round","next",1,Seat.NEXT,deck.drop(27).take(27)))
        val completed=partial.copy(plays=plays.toMap())
        assertEquals(setOf(Seat.SELF,Seat.NEXT),confirmedFinishedSeats(completed))
        assertFalse(confirmedRoundFinished(completed)) // Two different teams have finished.
        assertTrue(confirmedFinishedSeats(completed.copy(phase=RoundPhase.UNAVAILABLE)).isEmpty())
    }

    @Test fun lateTwoPlayerRoundKeepsCountingAndDoesNotInventAnEnd() {
        val ledger=RoundLedger(); ledger.begin("late-two-active",deck.take(27))
        val phases=mutableListOf<RoundPhase>()
        val observer=RoundObserver(ledger) { phases.add(it.phase) }
        var time=1000L
        fun see(scene: TableScene,plays: Map<Seat,ObservedPlay> = emptyPlays()) {
            observer.observe(TableFrame(time,scene,null,plays)); time+=100
        }
        // Establish known pre-tail totals through the same observer. Each fixture
        // group is a compact checkpoint of earlier plays; card capacities are real.
        for((seat,cards) in listOf(Seat.NEXT to deck.drop(27).take(27),
            Seat.PREVIOUS to deck.drop(54).take(20),Seat.OPPOSITE to deck.drop(81).take(20))) {
            repeat(4) { see(TableScene.TABLE,emptyPlays()+(seat to ObservedPlay(cards,.95f))) }
        }
        repeat(2) { see(TableScene.TEAMMATE) }
        assertEquals(RoundPhase.SELF_FINISHED,ledger.snapshot().phase)
        assertEquals(27,ledger.snapshot().played(Seat.NEXT))
        assertEquals(14,ledger.snapshot().remaining!!.values.sum())
        val pair=deck.drop(101).take(2).mapIndexed { i,card ->
            PlayGlyph(card,.95f,GlyphBox(.475f+i*.026f,.323f,.490f+i*.026f,.372f))
        }
        // The real new presentation persists well beyond the former 1.5-second
        // ambiguity timeout. It must be counted once, with both other seats excluded.
        repeat(20) {
            val snapshot=ledger.snapshot()
            val owned=assignPlayGroups(pair,setOf(Seat.PREVIOUS),emptyList(),true,
                finishedSeats=confirmedFinishedSeats(snapshot))
            assertTrue(owned.ambiguous.isEmpty())
            see(TableScene.TEAMMATE,owned.seats.mapValues { (_,glyphs) ->
                ObservedPlay(glyphs.map { it.card },glyphs.minOfOrNull { it.confidence } ?: 1f,glyphs)
            })
        }
        assertEquals(RoundPhase.SELF_FINISHED,ledger.snapshot().phase)
        assertEquals(22,ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(20,ledger.snapshot().played(Seat.PREVIOUS))
        assertEquals(12,ledger.snapshot().remaining!!.values.sum())
        assertFalse(phases.any { it in setOf(RoundPhase.UNAVAILABLE,RoundPhase.FINISHED) })
        see(TableScene.RESULT)
        assertEquals(RoundPhase.WAITING,ledger.snapshot().phase)
        assertTrue(RoundPhase.FINISHED in phases)
    }
}
