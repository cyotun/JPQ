package com.cyotun.jpq.core

import kotlin.test.*

class StraightFlushPresentationTest {
    private fun snapshot(id: String="live",phase: RoundPhase=RoundPhase.SELF_FINISHED)=
        RoundLedger().apply { begin(id,Rank.entries.filterNot { it.isJoker }.flatMap { rank ->
            listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } }
        }.take(27)) }.snapshot().copy(phase=phase)
    private val absent=StraightFlushResult(StraightFlushState.IMPOSSIBLE)
    private val pending=StraightFlushResult(StraightFlushState.UNKNOWN,reason="suit_uncertain")

    @Test fun teammateHandReannotationCannotFlashAnExcludedRunOrItsRankHeaders() {
        val display=StraightFlushPresentation(); val snap=snapshot()
        val before=display.accept(snap,absent,true)
        // Live 88345480..88345995: teammate pool/physical annotation is pending.
        assertEquals(before,display.accept(snap,pending,true))
        assertEquals(before,display.accept(snap,pending,true))
        assertTrue(before.result.candidateRanks.isEmpty())
        assertEquals(StraightFlushState.UNKNOWN,pending.state)
        assertEquals(before,display.accept(snap,absent,true))
    }

    @Test fun pendingReadsPreserveTheConfirmedSubsetRatherThanColoringEveryRank() {
        val display=StraightFlushPresentation(); val snap=snapshot()
        val ranks=setOf(Rank.TEN,Rank.J,Rank.Q,Rank.K,Rank.A)
        val before=display.accept(snap,StraightFlushResult(StraightFlushState.POSSIBLE,candidateRanks=ranks),true)
        assertEquals(before,display.accept(snap,pending,false))
        assertEquals(ranks,display.accept(snap,pending,true).result.candidateRanks)
    }

    @Test fun newConclusiveWildcardEvidenceCanReplaceEarlierNaturalAbsence() {
        val display=StraightFlushPresentation(); val snap=snapshot()
        display.accept(snap,absent,false)
        val possible=StraightFlushResult(StraightFlushState.POSSIBLE,candidateRanks=setOf(Rank.TWO,Rank.THREE,Rank.FOUR,Rank.FIVE,Rank.SIX))
        assertEquals(StraightFlushDisplay(possible,true),display.accept(snap,possible,true))
    }

    @Test fun aTemporarilyMissingTeammatePoolCannotPublishPossibilityFromTheWrongPool() {
        val display=StraightFlushPresentation(); val snap=snapshot()
        val before=display.accept(snap,absent,true,teammateExcluded=true)
        val fromThreeOutsideHands=StraightFlushResult(StraightFlushState.POSSIBLE)
        assertEquals(before,display.accept(snap,fromThreeOutsideHands,true,teammateExcluded=false))
        assertEquals(before,display.accept(snap,pending,true,teammateExcluded=false))
        // A newly confirmed complete hand may supply a genuine new result.
        assertEquals(StraightFlushState.POSSIBLE,
            display.accept(snap,fromThreeOutsideHands,true,teammateExcluded=true).result.state)
    }

    @Test fun changedOrWithdrawnWildcardProofCannotKeepAnOldAbsenceWhileRecalculationIsUnknown() {
        val snap=snapshot()
        for(next in listOf(WildcardRuleEvidence(setOf(Rank.Q),true),WildcardRuleEvidence(null,false))) {
            val display=StraightFlushPresentation()
            display.accept(snap,absent,true,true,WildcardRuleEvidence(setOf(Rank.TEN),true))
            val changed=display.accept(snap,pending,next.confirmed,true,next)
            assertEquals(StraightFlushState.UNKNOWN,changed.result.state)
            assertTrue(changed.result.candidateRanks.isNotEmpty())
            val possible=StraightFlushResult(StraightFlushState.POSSIBLE,candidateRanks=setOf(Rank.TEN,Rank.J,Rank.Q,Rank.K,Rank.A))
            assertEquals(possible,display.accept(snap,possible,next.confirmed,true,next).result)
        }
    }

    @Test fun recoveryAndFinalCommitRetainTheSameDealsPresentationUntilWaiting() {
        val display=StraightFlushPresentation(); val snap=snapshot()
        val before=display.accept(snap,absent,true)
        for(phase in listOf(RoundPhase.RECOVERING,RoundPhase.UNAVAILABLE,RoundPhase.FINISHED))
            assertEquals(before,display.accept(snap.copy(phase=phase),pending,false))
        val waiting=display.accept(RoundLedger().snapshot(),pending,false)
        assertEquals(StraightFlushState.UNKNOWN,waiting.result.state)
        assertEquals(Rank.entries.filterNot { it.isJoker }.toSet(),waiting.result.candidateRanks)
    }

    @Test fun aNewDealCannotInheritThePreviousDealsAbsence() {
        val display=StraightFlushPresentation()
        display.accept(snapshot(),absent,true)
        assertEquals(pending,display.accept(snapshot("next"),pending,false).result)
    }

    @Test fun initialUncertaintyStillStartsLitAndAcceptedCandidatesAreOwned() {
        val display=StraightFlushPresentation(); val snap=snapshot(phase=RoundPhase.ACTIVE)
        assertEquals(StraightFlushState.UNKNOWN,display.accept(snap,pending,false).result.state)
        val ranks=mutableSetOf(Rank.TEN,Rank.J,Rank.Q,Rank.K,Rank.A)
        display.accept(snap,StraightFlushResult(StraightFlushState.POSSIBLE,candidateRanks=ranks),true)
        ranks.clear()
        assertEquals(5,display.accept(snap,pending,true).result.candidateRanks.size)
    }

    private fun uncertainLowRuns(): StraightFlushResult {
        val low=setOf(Rank.A,Rank.TWO,Rank.THREE,Rank.FOUR,Rank.FIVE,Rank.SIX)
        val consumed=Rank.entries.flatMap { rank ->
            if(rank.isJoker) List(rank.capacity) { Card(rank,Suit.NONE) }
            else {
                // Both heart fours are consumed: no wildcard remains. Six black
                // A..6 cards remain, each still uncertain between spades/clubs.
                val red=if(rank==Rank.FOUR) List(2) { Card(rank,Suit.HEART) }+
                    List(2) { Card(rank,Suit.DIAMOND) } else List(4) { Card(rank,Suit.RED) }
                red+List(if(rank in low) 3 else 4) { Card(rank,Suit.BLACK) }
            }
        }
        return StraightFlushEvaluator.evaluate(consumed,setOf(Rank.FOUR),true,
            remainingHandSizes=listOf(6,0,0)).also {
            assertEquals(StraightFlushState.UNKNOWN,it.state)
            assertEquals("suit_uncertain",it.reason)
            assertEquals(low,it.candidateRanks)
        }
    }

    @Test fun uncertainSuitBoundsRemoveHighRanksBeforeExactSuitsAreKnown() {
        val display=StraightFlushPresentation(); val snap=snapshot()
        val rule=WildcardRuleEvidence(setOf(Rank.FOUR),true)
        display.accept(snap,StraightFlushResult(StraightFlushState.POSSIBLE),true,true,rule)
        val bounded=uncertainLowRuns()
        val narrowed=display.accept(snap,bounded,true,true,rule)
        assertEquals(bounded,narrowed.result)
        assertTrue(setOf(Rank.TEN,Rank.J,Rank.Q,Rank.K).none { it in narrowed.result.candidateRanks })
        assertEquals(narrowed,display.accept(snap,pending,true,true,rule))
    }

    @Test fun invalidOrUnboundedPendingCannotReplaceAnAcceptedCandidateSubset() {
        val snap=snapshot(); val bounded=uncertainLowRuns()
        for(reason in listOf("invalid_card_capacity","invalid_hand_capacity","awaiting_physical_evidence")) {
            val display=StraightFlushPresentation()
            val before=display.accept(snap,StraightFlushResult(StraightFlushState.POSSIBLE),true,true)
            assertEquals(before,display.accept(snap,bounded.copy(reason=reason),true,true))
        }
        val display=StraightFlushPresentation()
        val before=display.accept(snap,bounded.copy(state=StraightFlushState.POSSIBLE),true,true)
        assertEquals(before,display.accept(snap,pending,true,true))
        assertEquals(before,display.accept(snap,bounded.copy(candidateRanks=emptySet()),true,true))
    }

    @Test fun uncertainBoundsFromAnUnconfirmedTeammatePoolCannotNarrowTheOtherPool() {
        val display=StraightFlushPresentation(); val snap=snapshot()
        val before=display.accept(snap,StraightFlushResult(StraightFlushState.POSSIBLE),true,true)
        assertEquals(before,display.accept(snap,uncertainLowRuns(),true,false))
    }

    @Test fun aChangedWildcardScopeDoesNotIntersectItsBoundWithThePreviousScope() {
        val display=StraightFlushPresentation(); val snap=snapshot()
        val low=uncertainLowRuns()
        display.accept(snap,low.copy(state=StraightFlushState.POSSIBLE),true,true,
            WildcardRuleEvidence(setOf(Rank.FOUR),true))
        val high=pending.copy(candidateRanks=setOf(Rank.TEN,Rank.J,Rank.Q,Rank.K,Rank.A))
        assertEquals(high,display.accept(snap,high,true,true,
            WildcardRuleEvidence(setOf(Rank.TEN),true)).result)
    }
}
