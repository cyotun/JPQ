package com.cyotun.jpq.core

import kotlin.test.*

class OwnExitPresentationTrackerTest {
    private val own=listOf(Suit.BLACK,Suit.BLACK,Suit.BLACK,Suit.RED).mapIndexed { i,s ->
        PlayGlyph(Card(Rank.EIGHT,s),.91f,GlyphBox(.44f+i*.025f,.324f,.455f+i*.025f,.371f))
    }
    @Test fun observedWholeOwnExitSurvivesShortAnimationAndNeedsNoGuessedCommit() {
        val tracker=OwnExitPresentationTracker()
        tracker.prepare(1000,TableScene.TABLE,"round")
        tracker.observe(1000,RoundPhase.ACTIVE,own.map { it.card },emptyList(),own)
        tracker.emptySurface(false,false)
        assertEquals(own,tracker.prepare(2200,TableScene.TEAMMATE,"round"))
        tracker.observe(2200,RoundPhase.SELF_FINISHED,emptyList(),emptyList(),own)
        assertEquals(own,tracker.prepare(3400,TableScene.TEAMMATE,"round"))
    }
    @Test fun cleanEmptyBoundaryAndNewRoundRemoveTheOldExitProof() {
        val tracker=OwnExitPresentationTracker()
        tracker.prepare(1000,TableScene.TABLE,"round")
        tracker.observe(1000,RoundPhase.ACTIVE,own.map { it.card },emptyList(),own)
        tracker.emptySurface(true,false); tracker.emptySurface(true,false)
        assertTrue(tracker.prepare(1100,TableScene.TEAMMATE,"round").isEmpty())
        tracker.observe(1200,RoundPhase.ACTIVE,own.map { it.card },emptyList(),own)
        assertTrue(tracker.prepare(1300,TableScene.DEALING,"new-round").isEmpty())
    }
    @Test fun teammateHandCanAppearBeforeItsLabelWithoutLosingTheOwnExit() {
        val tracker=OwnExitPresentationTracker()
        tracker.prepare(1000,TableScene.TABLE,"round")
        val revealed=List(24) { Card(Rank.THREE,Suit.RED) }
        tracker.observe(1000,RoundPhase.ACTIVE,own.map { it.card },revealed,own)
        assertEquals(own,tracker.prepare(1100,TableScene.TEAMMATE,"round"))
    }
    @Test fun sameCardsAtAnotherPositionCannotMoveTheOwnExitProof() {
        val tracker=OwnExitPresentationTracker()
        tracker.prepare(1000,TableScene.TABLE,"round")
        tracker.observe(1000,RoundPhase.ACTIVE,own.map { it.card },emptyList(),own)
        val shifted=own.map { p -> p.copy(box=p.box.copy(left=p.box.left+.07f,right=p.box.right+.07f)) }
        tracker.prepare(1100,TableScene.TABLE,"round")
        tracker.observe(1100,RoundPhase.SELF_FINISHED,emptyList(),emptyList(),shifted)
        assertEquals(own,tracker.prepare(1200,TableScene.TEAMMATE,"round"))
    }
    @Test fun shortColorEffectDoesNotReplaceTheOriginalCards() {
        val tracker=OwnExitPresentationTracker()
        tracker.prepare(1000,TableScene.TABLE,"round")
        tracker.observe(1000,RoundPhase.ACTIVE,own.map { it.card },emptyList(),own)
        val recolored=own.map { it.copy(card=Card(Rank.EIGHT,Suit.RED)) }
        for(t in 1200L..1400L step 200) {
            val proof=tracker.prepare(t,TableScene.TEAMMATE,"round")
            assertEquals(own.map { it.card },proof.map { it.card })
            tracker.observe(t,RoundPhase.SELF_FINISHED,emptyList(),emptyList(),recolored)
        }
        tracker.observe(1600,RoundPhase.SELF_FINISHED,emptyList(),emptyList(),own)
        assertEquals(own,tracker.prepare(2300,TableScene.TEAMMATE,"round"))
    }

    @Test fun teammateLabelOnFirstCompletionFrameCanEstablishTheOwnExitAnchor() {
        val tracker=OwnExitPresentationTracker()
        tracker.prepare(1000,TableScene.TABLE,"round")
        // The immediately preceding frame still showed the complete, confirmed own hand.
        tracker.observe(1000,RoundPhase.ACTIVE,own.map { it.card },own.map { it.card },emptyList())
        assertTrue(tracker.prepare(1200,TableScene.TEAMMATE,"round").isEmpty())
        tracker.teammateTransition(1200,RoundPhase.ACTIVE,own.map { it.card },own)
        val proof=tracker.prepare(1200,TableScene.TEAMMATE,"round")
        val owned=assignPlayGroups(own,emptySet(),emptyList(),true,proof)
        assertEquals(own,owned.seats.getValue(Seat.SELF))
        assertTrue(owned.ambiguous.isEmpty())
    }

    @Test fun staleOwnHandCannotClaimAnUnrelatedLaterCentralGroup() {
        val tracker=OwnExitPresentationTracker()
        tracker.prepare(1000,TableScene.TABLE,"round")
        tracker.observe(1000,RoundPhase.ACTIVE,own.map { it.card },own.map { it.card },emptyList())
        tracker.prepare(1600,TableScene.TEAMMATE,"round")
        tracker.teammateTransition(1600,RoundPhase.ACTIVE,own.map { it.card },own)
        assertTrue(tracker.prepare(1600,TableScene.TEAMMATE,"round").isEmpty())
    }

    @Test fun wrongCardsWrongRowOrLowConfidenceCannotSeedTheOwnExit() {
        for(table in listOf(own.map { it.copy(card=Card(Rank.THREE,it.card.suit)) },own.map { it.copy(box=it.box.copy(top=.16f,bottom=.21f)) },
            own.map { it.copy(confidence=.60f) })) {
            val tracker=OwnExitPresentationTracker()
            tracker.prepare(1000,TableScene.TABLE,"round")
            tracker.observe(1000,RoundPhase.ACTIVE,own.map { it.card },own.map { it.card },emptyList())
            tracker.prepare(1200,TableScene.TEAMMATE,"round")
            tracker.teammateTransition(1200,RoundPhase.ACTIVE,own.map { it.card },table)
            assertTrue(tracker.prepare(1200,TableScene.TEAMMATE,"round").isEmpty())
        }
    }

    @Test fun finishedStateAndNewRoundNeverLearnANewOwnExit() {
        for(phase in listOf(RoundPhase.SELF_FINISHED,RoundPhase.FINISHED,RoundPhase.UNAVAILABLE)) {
            val tracker=OwnExitPresentationTracker()
            tracker.prepare(1000,TableScene.TABLE,"round")
            tracker.observe(1000,RoundPhase.ACTIVE,own.map { it.card },own.map { it.card },emptyList())
            tracker.prepare(1200,TableScene.TEAMMATE,"round")
            tracker.teammateTransition(1200,phase,own.map { it.card },own)
            assertTrue(tracker.prepare(1200,TableScene.TEAMMATE,"round").isEmpty())
        }
        val tracker=OwnExitPresentationTracker()
        tracker.prepare(1000,TableScene.TABLE,"round")
        tracker.observe(1000,RoundPhase.ACTIVE,own.map { it.card },own.map { it.card },emptyList())
        tracker.prepare(1200,TableScene.TEAMMATE,"new-round")
        tracker.teammateTransition(1200,RoundPhase.ACTIVE,own.map { it.card },own)
        assertTrue(tracker.prepare(1200,TableScene.TEAMMATE,"new-round").isEmpty())
    }
}
