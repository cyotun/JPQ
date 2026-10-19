package com.cyotun.jpq.core

import kotlin.test.*

class ConfirmedRoundPresentationTest {
    private fun snapshot(id: String="r")=RoundLedger().apply {
        begin(id,Rank.entries.filterNot { it.isJoker }.flatMap { rank ->
            listOf(Suit.RED,Suit.BLACK).flatMap { color -> List(4) { Card(rank,color) } }
        }.take(27))
    }.snapshot()
    @Test fun recoveryKeepsConfirmedNumbersAndHistoryWithoutChangingRawEvidence() {
        val presentation=ConfirmedRoundPresentation(); val snapshot=snapshot()
        val accepted=presentation.accept(snapshot,snapshot.remaining)
        val recovering=snapshot.copy(phase=RoundPhase.RECOVERING,remaining=null,reason="gap")
        val held=presentation.accept(recovering,null)
        assertEquals(accepted.remaining,held.remaining)
        assertEquals(accepted.snapshot.remaining,held.snapshot.remaining)
        assertEquals(accepted.snapshot.plays,held.snapshot.plays)
        assertNull(recovering.remaining)
        assertEquals(RoundPhase.RECOVERING,held.snapshot.phase)
    }
    @Test fun aGapBeforeTheVisionQueueRunsFreezesTheLastConfirmedProjection() {
        val presentation=ConfirmedRoundPresentation(); val snapshot=snapshot()
        val projected=snapshot.remaining!!.mapValues { (_,count) -> maxOf(0,count-1) }
        presentation.accept(snapshot,projected)
        assertEquals(projected,presentation.accept(snapshot,null,unobserved=true).remaining)
    }
    @Test fun confirmedRecoveryCommitsTheCompleteNewTransaction() {
        val presentation=ConfirmedRoundPresentation(); val snapshot=snapshot()
        presentation.accept(snapshot,snapshot.remaining)
        presentation.accept(snapshot.copy(phase=RoundPhase.RECOVERING,remaining=null),null)
        val next=snapshot.copy(remaining=snapshot.remaining!!.toMutableMap().apply { put(Rank.TWO,7) })
        val committed=presentation.accept(next,next.remaining)
        assertEquals(7,committed.remaining!![Rank.TWO]); assertEquals(next,committed.snapshot)
    }
    @Test fun newRoundAndItsPendingReadingNeverBorrowOldValues() {
        val presentation=ConfirmedRoundPresentation(); val snapshot=snapshot()
        presentation.accept(snapshot,snapshot.remaining)
        val other=snapshot("next").copy(phase=RoundPhase.RECOVERING,remaining=null)
        assertNull(presentation.accept(other,null).snapshot.remaining)
    }
    @Test fun finishWaitStopAndReinitializationDiscardHeldValues() {
        for(boundary in listOf("finish","wait","stop","initializing")) {
            val presentation=ConfirmedRoundPresentation(); val snapshot=snapshot()
            presentation.accept(snapshot,snapshot.remaining)
            when(boundary) {
                "finish" -> presentation.accept(snapshot.copy(phase=RoundPhase.FINISHED),snapshot.remaining)
                "wait" -> presentation.accept(snapshot.copy(phase=RoundPhase.WAITING,roundId=null,remaining=null),null)
                "stop" -> presentation.accept(snapshot,snapshot.remaining,running=false)
                else -> presentation.accept(snapshot,snapshot.remaining,ready=false)
            }
            assertNull(presentation.accept(snapshot.copy(phase=RoundPhase.RECOVERING,remaining=null),null).remaining,boundary)
        }
    }
    @Test fun noConfirmedBaselineMeansNoInventedNumbers() {
        val presentation=ConfirmedRoundPresentation()
        val recovering=snapshot().copy(phase=RoundPhase.RECOVERING,remaining=null)
        assertNull(presentation.accept(recovering,null).remaining)
    }
    @Test fun awaitingANewTeammateRevealCannotAddTheirCardsBackIntoTheOpponentPool() {
        val presentation=ConfirmedRoundPresentation(); val snapshot=snapshot().copy(phase=RoundPhase.SELF_FINISHED)
        val opponents=snapshot.remaining!!.mapValues { (_,count) -> maxOf(0,count-1) }
        assertTrue(presentation.accept(snapshot,opponents,teammateExcluded=true).teammateExcluded)
        val pending=presentation.accept(snapshot,snapshot.remaining,teammateExcluded=false)
        assertEquals(opponents,pending.remaining)
        val next=opponents.toMutableMap().apply { put(Rank.TWO,6) }
        assertEquals(next,presentation.accept(snapshot,next,teammateExcluded=true).remaining)
    }
    @Test fun nextRoundDoesNotInheritTheRequirementForATeammateReveal() {
        val presentation=ConfirmedRoundPresentation(); val old=snapshot().copy(phase=RoundPhase.SELF_FINISHED)
        presentation.accept(old,old.remaining,teammateExcluded=true)
        val next=snapshot("next")
        assertEquals(next.remaining,presentation.accept(next,next.remaining).remaining)
    }
}
