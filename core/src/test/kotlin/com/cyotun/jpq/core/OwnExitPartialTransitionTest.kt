package com.cyotun.jpq.core

import kotlin.test.*

/** The 0.2.16 failure's real 5411..5416 boundary, without player/account metadata. */
class OwnExitPartialTransitionTest {
    private val finalCards=listOf(Card(Rank.NINE,Suit.BLACK),Card(Rank.TEN,Suit.RED),
        Card(Rank.J,Suit.RED),Card(Rank.Q,Suit.RED),Card(Rank.K,Suit.BLACK))
    private val partial=listOf(
        PlayGlyph(finalCards[0],.8877f,GlyphBox(.4287f,.3247f,.4419f,.3695f)),
        PlayGlyph(finalCards[1],.8297f,GlyphBox(.4507f,.3242f,.4691f,.3704f)),
        PlayGlyph(finalCards[2],.8261f,GlyphBox(.4779f,.3243f,.4913f,.3697f)),
        PlayGlyph(finalCards[3],.7106f,GlyphBox(.5016f,.3227f,.5163f,.3709f)))
    private val full=listOf(
        PlayGlyph(finalCards[0],.8855f,GlyphBox(.4279f,.3242f,.4417f,.3683f)),
        PlayGlyph(finalCards[1],.8919f,GlyphBox(.4502f,.3242f,.4686f,.3700f)),
        PlayGlyph(finalCards[2],.8893f,GlyphBox(.4770f,.3242f,.4908f,.3683f)),
        PlayGlyph(finalCards[3],.8939f,GlyphBox(.5012f,.3225f,.5157f,.3700f)),
        PlayGlyph(finalCards[4],.8832f,GlyphBox(.5249f,.3225f,.5410f,.3700f)))
    private fun pending(): OwnExitPresentationTracker=OwnExitPresentationTracker().also { tracker ->
        tracker.prepare(1000,TableScene.TABLE,"round")
        // The own-hand region was complete even though the central region still
        // contained an effect, so it must not depend on empty-SELF evidence.
        tracker.observeOwnHand(1000,RoundPhase.ACTIVE,finalCards,finalCards)
        tracker.prepare(1115,TableScene.TABLE,"round")
        tracker.observe(1115,RoundPhase.ACTIVE,finalCards,emptyList(),partial)
    }

    @Test fun missingKingRecoversAfterTheFirstTeammateFrameWithoutGuessingIt() {
        val tracker=pending()
        val before=tracker.prepare(1914,TableScene.TEAMMATE,"round")
        assertEquals(4,before.size) // No invented K in the partial presentation.
        assertEquals(partial,assignPlayGroups(partial,emptySet(),emptyList(),true,before).seats.getValue(Seat.SELF))
        tracker.teammateTransition(2331,RoundPhase.SELF_FINISHED,emptyList(),full)
        val proof=tracker.prepare(2331,TableScene.TEAMMATE,"round")
        assertEquals(finalCards,proof.map { it.card })
        val ownership=assignPlayGroups(full,emptySet(),emptyList(),true,proof)
        assertEquals(full,ownership.seats.getValue(Seat.SELF))
        assertTrue(ownership.ambiguous.isEmpty())
    }

    @Test fun identicalRanksAtAnotherPositionCannotCompleteThePartialAnchor() {
        val tracker=pending()
        val moved=full.map { it.copy(box=it.box.copy(left=it.box.left+.075f,right=it.box.right+.075f)) }
        tracker.prepare(1914,TableScene.TEAMMATE,"round")
        tracker.teammateTransition(2331,RoundPhase.SELF_FINISHED,emptyList(),moved)
        val proof=tracker.prepare(2331,TableScene.TEAMMATE,"round")
        assertEquals(4,proof.size)
        assertEquals(5,assignPlayGroups(moved,emptySet(),emptyList(),true,proof).ambiguous.size)
    }

    @Test fun wrongFinalCardAndExpiredGeometryCannotCompleteTheAnchor() {
        val tracker=pending()
        val wrong=full.dropLast(1)+full.last().copy(card=Card(Rank.A,Suit.BLACK))
        tracker.prepare(1914,TableScene.TEAMMATE,"round")
        tracker.teammateTransition(2331,RoundPhase.SELF_FINISHED,emptyList(),wrong)
        assertEquals(4,tracker.prepare(2331,TableScene.TEAMMATE,"round").size)
        assertTrue(tracker.prepare(5200,TableScene.TEAMMATE,"round").isEmpty())
        tracker.teammateTransition(5200,RoundPhase.SELF_FINISHED,emptyList(),full)
        assertTrue(tracker.prepare(5200,TableScene.TEAMMATE,"round").isEmpty())
    }

    @Test fun seeingTheOwnHandOrACleanEmptySurfaceDisprovesThePendingExit() {
        val stillOwn=pending()
        stillOwn.observeOwnHand(1300,RoundPhase.ACTIVE,finalCards,finalCards)
        assertTrue(stillOwn.prepare(1400,TableScene.TEAMMATE,"round").isEmpty())
        val cleared=pending()
        cleared.emptySurface(true,false); cleared.emptySurface(true,false)
        assertTrue(cleared.prepare(1400,TableScene.TEAMMATE,"round").isEmpty())
    }

    @Test fun pendingOwnExitCannotBecomeTheOnlyEligibleOpponentsPlay() {
        val tracker=pending()
        val proof=tracker.prepare(1914,TableScene.TEAMMATE,"round")
        // The K has appeared, but has not yet been checked by the tracker.
        val owned=assignPlayGroups(full,setOf(Seat.PREVIOUS),emptyList(),true,proof,setOf(Seat.OPPOSITE))
        assertTrue(owned.seats.getValue(Seat.NEXT).isEmpty())
        assertTrue(owned.seats.getValue(Seat.SELF).isEmpty())
        assertEquals(full,owned.ambiguous)
        tracker.teammateTransition(2331,RoundPhase.SELF_FINISHED,emptyList(),full)
        val recovered=assignPlayGroups(full,setOf(Seat.PREVIOUS),emptyList(),true,
            tracker.prepare(2331,TableScene.TEAMMATE,"round"),setOf(Seat.OPPOSITE))
        assertEquals(full,recovered.seats.getValue(Seat.SELF))
        assertTrue(recovered.seats.getValue(Seat.NEXT).isEmpty())
    }

    @Test fun aDirectlySeenMissingCardCanRecoverAfterTheOldOnePointFiveSecondCutoff() {
        val tracker=pending()
        tracker.prepare(3100,TableScene.TEAMMATE,"round")
        tracker.teammateTransition(3100,RoundPhase.SELF_FINISHED,emptyList(),full)
        assertEquals(finalCards,tracker.prepare(3100,TableScene.TEAMMATE,"round").map { it.card })
    }
    @Test fun expiryDoesNotHandAnUnresolvedOwnExitToTheOnlyEligibleOpponent() {
        val tracker=pending()
        assertTrue(tracker.prepare(5200,TableScene.TEAMMATE,"round").isEmpty())
        val blocked=assignPlayGroups(full,setOf(Seat.PREVIOUS),emptyList(),true,emptyList(),
            setOf(Seat.OPPOSITE),tracker.blocksCentralFallback())
        assertEquals(full,blocked.ambiguous)
        assertTrue(blocked.seats.getValue(Seat.NEXT).isEmpty())
        repeat(3) { tracker.emptySurface(true,false,unownedCentralDetected=true) }
        assertTrue(tracker.blocksCentralFallback())
        tracker.emptySurface(true,false); tracker.emptySurface(true,false)
        assertFalse(tracker.blocksCentralFallback())
        val afterBoundary=assignPlayGroups(full,setOf(Seat.PREVIOUS),emptyList(),true,emptyList(),
            setOf(Seat.OPPOSITE),tracker.blocksCentralFallback())
        assertEquals(full,afterBoundary.seats.getValue(Seat.NEXT))
    }
}
