package com.cyotun.jpq.core

import kotlin.test.*

class C411RecoveryCountSourceTest {
    private val hand=Rank.entries.filterNot { it.isJoker }.flatMap {
        listOf(Card(it,Suit.RED),Card(it,Suit.BLACK))
    }+Card(Rank.SMALL,Suit.NONE)
    private val old=Card(Rank.Q,Suit.RED)
    private val oldGlyph=PlayGlyph(old,.95f,GlyphBox(.7f,.28f,.72f,.32f))
    private fun recovery()=RoundRecovery(100,hand,false,
        mapOf(Seat.NEXT to RoundRecovery.Anchor(listOf(old),listOf(oldGlyph),"old",0)),
        false,mapOf(Seat.NEXT to 26))
    // Use a late, positively empty return so only independent quantities can
    // reconnect the checkpoint; the 4-second physical fallback has expired.
    private fun frame(time: Long,count: Int=26,source: Long=time,masked: Boolean=false)=
        TableFrame(time,TableScene.TABLE,hand,
            Seat.entries.associateWith { seat -> ObservedPlay(emptyList(),1f,
                regionObserved=!(masked && seat==Seat.NEXT)) },
            remainingHandCounts=mapOf(Seat.NEXT to RemainingHandCount(count,source)),
            incompleteReason=if(masked) "local overlay" else null,
            localizedOverlaySeats=if(masked) setOf(Seat.NEXT) else emptySet())

    @Test fun contradictorySourceCannotVoteAgainButThreeLaterSourcesCanRecover() {
        val proof=recovery()
        assertNull(proof.reconcile(frame(5000)))
        assertNull(proof.reconcile(frame(5010,count=25,source=5000)))
        assertNull(proof.reconcile(frame(5020,source=5000)))
        assertNull(proof.reconcile(frame(5150)))
        assertNull(proof.reconcile(frame(5300)))
        assertEquals(emptyMap(),proof.reconcile(frame(5450)))
    }

    @Test fun ordinaryWrongCountOnlyRevokesVotesAndDoesNotPoisonLaterSources() {
        val proof=recovery()
        assertNull(proof.reconcile(frame(5000,count=25)))
        assertNull(proof.reconcile(frame(5150)))
        assertNull(proof.reconcile(frame(5300)))
        assertEquals(emptyMap(),proof.reconcile(frame(5450)))
    }

    @Test fun cachedSourceCannotAccumulateVotesOrRefreshItsTimestamp() {
        val proof=recovery()
        for(t in listOf(5000L,5150L,5300L)) assertNull(proof.reconcile(frame(t,source=5000)))
        assertNull(proof.reconcile(frame(5450)))
        assertEquals(emptyMap(),proof.reconcile(frame(5600)))
    }

    @Test fun reverseDeliveryRevokesVotesWithoutAllowingEitherOldSourceToReplay() {
        val proof=recovery()
        assertNull(proof.reconcile(frame(5000)))
        assertNull(proof.reconcile(frame(5150)))
        assertNull(proof.reconcile(frame(5200,source=5000)))
        assertNull(proof.reconcile(frame(5250,source=5150)))
        assertNull(proof.reconcile(frame(5300)))
        assertNull(proof.reconcile(frame(5450)))
        assertEquals(emptyMap(),proof.reconcile(frame(5600)))
    }

    @Test fun explicitInterruptKeepsTheSourceFenceAndNeedsThreeFreshSources() {
        val proof=recovery()
        assertNull(proof.reconcile(frame(5000)))
        assertNull(proof.reconcile(frame(5150)))
        proof.observationInterrupted(5200)
        assertNull(proof.reconcile(frame(5250,source=5150)))
        assertNull(proof.reconcile(frame(5300)))
        assertNull(proof.reconcile(frame(5450)))
        assertEquals(emptyMap(),proof.reconcile(frame(5600)))
    }

    @Test fun aPresentationResetCannotMakeTheLastCountSourceFreshAgain() {
        val proof=recovery()
        assertNull(proof.reconcile(frame(5000)))
        assertNull(proof.reconcile(frame(5150).copy(hand=null)))
        assertNull(proof.reconcile(frame(5200,source=5150)))
        assertNull(proof.reconcile(frame(5300)))
        assertNull(proof.reconcile(frame(5450)))
        assertEquals(emptyMap(),proof.reconcile(frame(5600)))
    }

    @Test fun negativeFutureAndStaleSourcesCannotVoteAndDoNotBlockValidRecovery() {
        for(badSource in listOf(-1L,5001L,3900L)) {
            val proof=recovery()
            assertNull(proof.reconcile(frame(5000,source=badSource)))
            assertNull(proof.reconcile(frame(5150)))
            assertNull(proof.reconcile(frame(5300)))
            assertEquals(emptyMap(),proof.reconcile(frame(5450)))
        }
    }

    @Test fun threeSourcesStillNeedTheOriginalThreeHundredMillisecondSpan() {
        val proof=recovery()
        assertNull(proof.reconcile(frame(5000)))
        assertNull(proof.reconcile(frame(5050)))
        assertNull(proof.reconcile(frame(5100)))
        assertNull(proof.reconcile(frame(5200,source=5100)))
        assertEquals(emptyMap(),proof.reconcile(frame(5300)))
    }

    @Test fun clearingALocalMaskCanDeliverAnAlreadyProvedCachedSourceWithoutAnotherVote() {
        val proof=recovery()
        assertNull(proof.reconcile(frame(5000,masked=true)))
        assertNull(proof.reconcile(frame(5150,masked=true)))
        assertNull(proof.reconcile(frame(5300,masked=true)))
        assertEquals(emptyMap(),proof.reconcile(frame(5350,source=5300)))
    }

    @Test fun contradictorySourceCannotDeliverAnAlreadyProvedMaskedResult() {
        val proof=recovery()
        assertNull(proof.reconcile(frame(5000,masked=true)))
        assertNull(proof.reconcile(frame(5150,masked=true)))
        assertNull(proof.reconcile(frame(5300,masked=true)))
        assertNull(proof.reconcile(frame(5310,count=25,source=5300,masked=true)))
        assertNull(proof.reconcile(frame(5350,source=5300)))
        assertNull(proof.reconcile(frame(5450)))
        assertNull(proof.reconcile(frame(5600)))
        assertEquals(emptyMap(),proof.reconcile(frame(5750)))
    }
}
