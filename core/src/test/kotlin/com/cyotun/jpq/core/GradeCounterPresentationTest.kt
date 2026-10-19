package com.cyotun.jpq.core

import kotlin.test.*

class GradeCounterPresentationTest {
    private fun exact(heart: Int=1,ordinary: Int=3,round: String="r1",rank: Rank=Rank.TEN)=
        GradeCounterState(round,setOf(rank),GradeCountBounds(heart,heart),mapOf(rank to GradeCountBounds(ordinary,ordinary)))

    @Test fun pendingNewRedGradePlayKeepsTheConfirmedPairUntilBothCountsAreProved() {
        val display=GradeCounterPresentation()
        val before=exact()
        assertEquals(before,display.accept(before))
        // Live event12 at13117743: the new red10 is not yet H/D proof.
        val pending=GradeCounterState("r1",setOf(Rank.TEN),GradeCountBounds(0,1),
            mapOf(Rank.TEN to GradeCountBounds(2,3)),"physical_identity_uncertain")
        assertEquals(before,display.accept(pending))
        assertNull(pending.heartCount?.exact) // Analysis remains uncertain.
        // A stable diamond commits both cells together; neither uses guessed minima.
        val after=exact(ordinary=2)
        assertEquals(after,display.accept(after))
    }

    @Test fun anExactHeartCannotCommitSeparatelyFromAnUnconfirmedOrdinaryCount() {
        val display=GradeCounterPresentation(); val before=exact()
        display.accept(before)
        val partial=before.copy(heartCount=GradeCountBounds(0,0),
            ordinaryCounts=mapOf(Rank.TEN to GradeCountBounds(1,2)),reason="physical_identity_uncertain")
        assertEquals(before,display.accept(partial))
        assertEquals(exact(0,1),display.accept(exact(0,1)))
    }

    @Test fun startupHasNoInventedDefaultAndCannotReuseAnotherRoundOrGrade() {
        val display=GradeCounterPresentation()
        val pending=GradeCounterState("r1",setOf(Rank.TEN),reason="awaiting_physical_evidence")
        assertEquals(pending,display.accept(pending)); assertNull(display.accept(pending).heartCount)
        display.accept(exact())
        val next=pending.copy(roundId="r2")
        assertEquals(next,display.accept(next))
        display.accept(exact(round="r2"))
        val gradeChanged=next.copy(gradeRanks=setOf(Rank.J))
        assertEquals(gradeChanged,display.accept(gradeChanged))
    }

    @Test fun stopAndInvalidEvidenceDiscardTheHeldTransaction() {
        val display=GradeCounterPresentation(); display.accept(exact())
        val conflict=GradeCounterState("r1",setOf(Rank.TEN),reason="physical_evidence_conflict")
        assertEquals(conflict,display.accept(conflict))
        val pending=conflict.copy(reason="awaiting_physical_evidence")
        assertEquals(pending,display.accept(pending))
        display.accept(exact()); assertEquals(GradeCounterState(),display.accept(GradeCounterState()))
        assertEquals(pending,display.accept(pending))
    }

    @Test fun captureRecoveryPreservesTheSameRoundTransactionUntilResumption() {
        val display=GradeCounterPresentation(); val before=exact()
        display.accept(before)
        assertEquals(before,display.accept(GradeCounterState("r1",setOf(Rank.TEN),reason="round_recovering")))
        assertEquals(exact(0,2),display.accept(exact(0,2)))
    }

    @Test fun dualGradesCommitAllThreeCellsTogether() {
        val display=GradeCounterPresentation()
        val ranks=setOf(Rank.TEN,Rank.J)
        val before=GradeCounterState("r1",ranks,GradeCountBounds(2,2),
            mapOf(Rank.TEN to GradeCountBounds(3,3),Rank.J to GradeCountBounds(4,4)))
        display.accept(before)
        val pending=before.copy(heartCount=GradeCountBounds(1,1),ordinaryCounts=mapOf(
            Rank.TEN to GradeCountBounds(2,2),Rank.J to GradeCountBounds(3,4)),reason="physical_identity_uncertain")
        assertEquals(before,display.accept(pending))
        val complete=pending.copy(ordinaryCounts=mapOf(Rank.TEN to GradeCountBounds(2,2),Rank.J to GradeCountBounds(3,3)),reason=null)
        assertEquals(complete,display.accept(complete))
    }

    @Test fun heldTransactionOwnsTheInputCollections() {
        val display=GradeCounterPresentation(); val ranks=mutableSetOf(Rank.TEN)
        val counts=mutableMapOf(Rank.TEN to GradeCountBounds(3,3))
        val before=exact().copy(gradeRanks=ranks,ordinaryCounts=counts)
        display.accept(before); ranks.clear(); counts.clear()
        assertEquals(exact(),display.accept(GradeCounterState("r1",setOf(Rank.TEN),reason="awaiting_physical_evidence")))
    }
}
