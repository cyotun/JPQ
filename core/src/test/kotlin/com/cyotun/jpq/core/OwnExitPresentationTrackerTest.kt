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
        // A matching group and a scene label alone do not prove the hand departed.
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

    @Test fun completeFiveAcesAfterTheTeammateTransitionCannotBecomeTheOnlyOpponentsPlay() {
        val full=listOf(Suit.BLACK,Suit.BLACK,Suit.BLACK,Suit.RED,Suit.RED).mapIndexed { i,suit ->
            PlayGlyph(Card(Rank.A,suit),.89f,GlyphBox(.427f+i*.0245f,.3245f,.444f+i*.0245f,.3707f))
        }
        val cards=full.map { it.card }
        val tracker=OwnExitPresentationTracker()
        tracker.prepare(1000,TableScene.TABLE,"round")
        tracker.observeOwnHand(1000,RoundPhase.ACTIVE,cards,cards)
        // Real 8131..8135 order: blank, a late partial A, wrong colours, then
        // every final A directly visible only after the teammate label arrives.
        tracker.prepare(1283,TableScene.TABLE,"round")
        tracker.observe(1283,RoundPhase.ACTIVE,cards,emptyList(),emptyList())
        tracker.prepare(1532,TableScene.TABLE,"round")
        tracker.observe(1532,RoundPhase.ACTIVE,cards,emptyList(),listOf(full.first().copy(card=Card(Rank.A,Suit.RED))))
        assertFalse(tracker.blocksCentralFallback()) // The 500ms partial gate stays closed.
        val allRed=full.map { it.copy(card=Card(Rank.A,Suit.RED)) }
        tracker.prepare(1782,TableScene.TABLE,"round")
        tracker.observe(1782,RoundPhase.ACTIVE,cards,emptyList(),allRed)
        assertTrue(tracker.prepare(2015,TableScene.TEAMMATE,"round").isEmpty())
        tracker.teammateTransition(2015,RoundPhase.ACTIVE,cards,full,listOf(Card(Rank.K,Suit.BLACK)))
        for(time in listOf(2015L,2282L,2548L,2816L)) {
            val proof=tracker.prepare(time,TableScene.TEAMMATE,"round")
            assertEquals(full,proof)
            val owned=assignPlayGroups(full,setOf(Seat.OPPOSITE,Seat.NEXT),emptyList(),true,
                proof,ownExitUnresolved=tracker.blocksCentralFallback())
            assertEquals(full,owned.seats.getValue(Seat.SELF))
            assertTrue(owned.seats.getValue(Seat.PREVIOUS).isEmpty())
            assertTrue(owned.ambiguous.isEmpty())
            tracker.teammateTransition(time,RoundPhase.SELF_FINISHED,emptyList(),full)
            tracker.observe(time,RoundPhase.SELF_FINISHED,emptyList(),emptyList(),owned.seats.getValue(Seat.SELF))
        }
    }

    @Test fun lateTransitionStillRequiresEveryCorrectCardInTheSelfRow() {
        for(candidate in listOf(own.dropLast(1),own.map { it.copy(card=Card(Rank.EIGHT,Suit.RED)) },
            own.map { it.copy(card=Card(Rank.A,it.card.suit)) },own.map { it.copy(confidence=.64f) },
            own.map { it.copy(box=it.box.copy(top=.16f,bottom=.21f)) })) {
            val tracker=OwnExitPresentationTracker()
            tracker.prepare(1000,TableScene.TABLE,"round")
            tracker.observeOwnHand(1000,RoundPhase.ACTIVE,own.map { it.card },own.map { it.card })
            tracker.prepare(2015,TableScene.TEAMMATE,"round")
            tracker.teammateTransition(2015,RoundPhase.ACTIVE,own.map { it.card },candidate,listOf(Card(Rank.K,Suit.BLACK)))
            assertTrue(tracker.prepare(2015,TableScene.TEAMMATE,"round").isEmpty())
            assertFalse(tracker.blocksCentralFallback())
        }
    }

    @Test fun completeTransitionCannotBorrowAnOlderOwnHandVersionOrAnUnseenHand() {
        for(observeOldHand in listOf(false,true)) {
            val tracker=OwnExitPresentationTracker()
            tracker.prepare(1000,TableScene.TABLE,"round")
            if(observeOldHand) tracker.observeOwnHand(1000,RoundPhase.ACTIVE,own.map { it.card },own.map { it.card })
            val current=if(observeOldHand) own.dropLast(1) else own
            tracker.prepare(2015,TableScene.TEAMMATE,"round")
            tracker.teammateTransition(2015,RoundPhase.ACTIVE,current.map { it.card },current,listOf(Card(Rank.K,Suit.BLACK)))
            assertTrue(tracker.prepare(2015,TableScene.TEAMMATE,"round").isEmpty())
        }
        val outOfOrder=OwnExitPresentationTracker()
        outOfOrder.prepare(1000,TableScene.TABLE,"round")
        outOfOrder.observeOwnHand(1000,RoundPhase.ACTIVE,own.map { it.card },own.map { it.card })
        outOfOrder.prepare(900,TableScene.TEAMMATE,"round")
        outOfOrder.teammateTransition(900,RoundPhase.ACTIVE,own.map { it.card },own,emptyList())
        assertTrue(outOfOrder.prepare(900,TableScene.TEAMMATE,"round").isEmpty())
        val nextRound=OwnExitPresentationTracker()
        nextRound.prepare(1000,TableScene.TABLE,"old-round")
        nextRound.observeOwnHand(1000,RoundPhase.ACTIVE,own.map { it.card },own.map { it.card })
        nextRound.prepare(1200,TableScene.TABLE,"new-round")
        nextRound.prepare(1300,TableScene.TEAMMATE,"new-round")
        nextRound.teammateTransition(1300,RoundPhase.ACTIVE,own.map { it.card },own,emptyList())
        assertTrue(nextRound.prepare(1300,TableScene.TEAMMATE,"new-round").isEmpty())
    }

    @Test fun finishedEmptyHandWithoutAnExitAnchorCannotClaimAnotherCentralGroup() {
        val tracker=OwnExitPresentationTracker()
        tracker.prepare(1000,TableScene.TABLE,"round")
        tracker.observeOwnHand(1000,RoundPhase.ACTIVE,own.map { it.card },own.map { it.card })
        tracker.prepare(2015,TableScene.TEAMMATE,"round")
        tracker.teammateTransition(2015,RoundPhase.SELF_FINISHED,emptyList(),own,listOf(Card(Rank.K,Suit.BLACK)))
        assertTrue(tracker.prepare(2015,TableScene.TEAMMATE,"round").isEmpty())
    }

    @Test fun completeTransitionCannotBypassPendingGeometryOrItsExpiry() {
        val tracker=OwnExitPresentationTracker()
        val cards=own.map { it.card }
        tracker.prepare(1000,TableScene.TABLE,"round")
        tracker.observeOwnHand(1000,RoundPhase.ACTIVE,cards,cards)
        tracker.observe(1100,RoundPhase.ACTIVE,cards,emptyList(),own.dropLast(1))
        val moved=own.map { it.copy(box=it.box.copy(left=it.box.left+.07f,right=it.box.right+.07f)) }
        tracker.prepare(1300,TableScene.TEAMMATE,"round")
        tracker.teammateTransition(1300,RoundPhase.ACTIVE,cards,moved,emptyList())
        assertEquals(own.dropLast(1),tracker.prepare(1300,TableScene.TEAMMATE,"round"))
        assertTrue(tracker.prepare(5200,TableScene.TEAMMATE,"round").isEmpty())
        tracker.teammateTransition(5200,RoundPhase.ACTIVE,cards,own,emptyList())
        assertTrue(tracker.prepare(5200,TableScene.TEAMMATE,"round").isEmpty())
        assertTrue(tracker.blocksCentralFallback())
    }

    @Test fun completeTransitionNeedsAnExplicitDepartureAndCannotReuseALaterTeammateFrame() {
        val cards=own.map { it.card }
        for(observed in listOf(null,cards,cards.dropLast(1))) {
            val tracker=OwnExitPresentationTracker()
            tracker.prepare(1000,TableScene.TABLE,"round")
            tracker.observeOwnHand(1000,RoundPhase.ACTIVE,cards,cards)
            tracker.prepare(2015,TableScene.TEAMMATE,"round")
            tracker.teammateTransition(2015,RoundPhase.ACTIVE,cards,own,observed)
            assertTrue(tracker.prepare(2015,TableScene.TEAMMATE,"round").isEmpty())
        }
        val later=OwnExitPresentationTracker()
        later.prepare(1000,TableScene.TABLE,"round")
        later.observeOwnHand(1000,RoundPhase.ACTIVE,cards,cards)
        later.prepare(1800,TableScene.TEAMMATE,"round")
        later.teammateTransition(1800,RoundPhase.ACTIVE,cards,emptyList(),emptyList())
        later.prepare(2015,TableScene.TEAMMATE,"round")
        later.teammateTransition(2015,RoundPhase.ACTIVE,cards,own,emptyList())
        assertTrue(later.prepare(2015,TableScene.TEAMMATE,"round").isEmpty())
    }

    @Test fun completeDepartureStillCannotClaimAnUnboundedlyStaleOwnHand() {
        val tracker=OwnExitPresentationTracker()
        val cards=own.map { it.card }
        tracker.prepare(1000,TableScene.TABLE,"round")
        tracker.observeOwnHand(1000,RoundPhase.ACTIVE,cards,cards)
        tracker.prepare(2501,TableScene.TEAMMATE,"round")
        tracker.teammateTransition(2501,RoundPhase.ACTIVE,cards,own,emptyList())
        assertTrue(tracker.prepare(2501,TableScene.TEAMMATE,"round").isEmpty())
    }
}
