package com.cyotun.jpq.core

import kotlin.test.*

/** Cross-component checks for teammate-pool and display checkpoints at round boundaries. */
class C411RoundBoundaryPresentationTest {
    private val deck=Rank.entries.flatMap { rank ->
        (if(rank.isJoker) listOf(Suit.NONE) else listOf(Suit.SPADE,Suit.HEART,Suit.CLUB,Suit.DIAMOND))
            .flatMap { suit -> List(2) { Card(rank,suit) } }
    }
    private val teammateHand get()=deck.drop(50).take(4)

    private fun finishedLedger(id: String="round-1") = RoundLedger().apply {
        begin(id,deck.take(27))
        commit(PlayEvent(id,"opposite-before-finish",0,Seat.OPPOSITE,deck.drop(27).take(23)))
        selfFinished()
    }

    private fun confirmPool(tracker: RemainingPoolTracker,snapshot: RoundSnapshot,epoch: Long=1): RemainingPoolExclusion {
        assertNull(tracker.observe(0,epoch,snapshot,teammateHand))
        assertNull(tracker.observe(250,epoch,snapshot,teammateHand))
        return assertNotNull(tracker.observe(500,epoch,snapshot,teammateHand))
    }

    private fun exactCounter(round: String="round-1",rank: Rank=Rank.TEN)=GradeCounterState(
        round,setOf(rank),GradeCountBounds(1,1),mapOf(rank to GradeCountBounds(3,3)))

    private val absent=StraightFlushResult(StraightFlushState.IMPOSSIBLE)
    private val pending=StraightFlushResult(StraightFlushState.UNKNOWN,reason="suit_uncertain")

    @Test fun teammateExclusionSurvivesOnlyTheSameRoundAndVisibilityEpoch() {
        val ledger=finishedLedger()
        val tracker=RemainingPoolTracker()
        val initial=ledger.snapshot()
        val exclusion=confirmPool(tracker,initial)
        assertEquals(initial.remaining!!.values.sum()-teammateHand.size,
            remainingOutsidePool(initial,tracker.projection(initial,1))!!.values.sum())

        val recovering=initial.copy(phase=RoundPhase.RECOVERING,remaining=null)
        assertNull(tracker.projection(recovering,1))
        // The immutable checkpoint remains valid if the same visibility stream resumes.
        assertEquals(exclusion,tracker.projection(initial,1))
        // A visibility break retires the old reveal; recovery must collect fresh votes.
        assertNull(tracker.projection(recovering,2))
        assertNull(tracker.projection(initial,2))
        assertNull(tracker.observe(750,2,initial,teammateHand))
        assertNull(tracker.observe(1000,2,initial,teammateHand))
        val fresh=assertNotNull(tracker.observe(1250,2,initial,teammateHand))
        assertEquals(2,fresh.epoch)

        val next=finishedLedger("round-2").snapshot()
        assertNull(tracker.projection(next,2))
        assertEquals(remainingOutsidePool(next,null),remainingOutsidePool(next,exclusion))
    }

    @Test fun recoveringKeepsConfirmedDisplayButNewRoundAndStopClearIt() {
        val counter=GradeCounterPresentation()
        val straight=StraightFlushPresentation()
        val round=finishedLedger().snapshot()
        val confirmedCounter=exactCounter()
        counter.accept(confirmedCounter)
        val confirmedStraight=straight.accept(round,absent,true,teammateExcluded=true)

        assertEquals(confirmedCounter,counter.accept(GradeCounterState("round-1",setOf(Rank.TEN),
            reason="round_recovering")))
        assertEquals(confirmedStraight,straight.accept(round.copy(phase=RoundPhase.RECOVERING),pending,false))

        // Once the tracker withdraws an old teammate exclusion after an epoch break,
        // the same round cannot publish a result computed from the larger pool.
        assertEquals(confirmedStraight,straight.accept(round,pending,true,teammateExcluded=false))
        val newRoundCounter=GradeCounterState("round-2",setOf(Rank.TEN),reason="awaiting_physical_evidence")
        assertEquals(newRoundCounter,counter.accept(newRoundCounter))
        val newRoundStraight=straight.accept(finishedLedger("round-2").snapshot(),pending,false)
        assertEquals(pending,newRoundStraight.result)

        // Stop publishes an empty transaction; neither a later pending read nor a
        // WAITING presentation can resurrect the previous deal's confirmed values.
        assertEquals(GradeCounterState(),counter.accept(GradeCounterState()))
        assertEquals(newRoundCounter,counter.accept(newRoundCounter))
        val waiting=straight.accept(RoundLedger().snapshot(),pending,false)
        assertEquals(StraightFlushState.UNKNOWN,waiting.result.state)
        assertEquals(Rank.entries.filterNot { it.isJoker }.toSet(),waiting.result.candidateRanks)
    }
}
