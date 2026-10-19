package com.cyotun.jpq.core

import kotlin.test.*

class RecoveryCountEvidenceTest {
    private val own=listOf(Card(Rank.A,Suit.BLACK))
    private val expected=mapOf(Seat.PREVIOUS to 5,Seat.OPPOSITE to 8,Seat.NEXT to 6)
    private fun recovery()=RoundRecovery(1000,own,false,
        expected.mapValues { RoundRecovery.Anchor(emptyList(),emptyList(),null,900) },false,expected)
    private fun frame(t: Long,source: Long=t,counts: Map<Seat,Int> = expected)=TableFrame(t,TableScene.TABLE,own,
        Seat.entries.associateWith { ObservedPlay(emptyList(),1f) },remainingHandCounts=counts.mapValues { RemainingHandCount(it.value,source) })

    @Test fun threeNewQuantitativeSnapshotsCanRecoverBeyondThePhysicalTrackWindow() {
        val checkpoint=recovery()
        assertNull(checkpoint.reconcile(frame(6000)))
        assertNull(checkpoint.reconcile(frame(6200)))
        assertEquals(emptyMap(),checkpoint.reconcile(frame(6400)))
    }
    @Test fun cachedOcrCannotAccumulateVotesFromManyFrames() {
        val checkpoint=recovery()
        for(t in 6000L..6600L step 60) assertNull(checkpoint.reconcile(frame(t,6000)))
        assertNull(checkpoint.reconcile(frame(6800,6800)))
        assertEquals(emptyMap(),checkpoint.reconcile(frame(7000,7000)))
    }
    @Test fun missingChangedStaleMixedOrPreGapCountsCannotCertifyNoMissedPlay() {
        for(change in listOf<(TableFrame)->TableFrame>(
            { f -> f.copy(remainingHandCounts=f.remainingHandCounts-Seat.NEXT) },
            { f -> f.copy(remainingHandCounts=f.remainingHandCounts+(Seat.NEXT to RemainingHandCount(5,f.timeMs))) },
            { f -> f.copy(remainingHandCounts=f.remainingHandCounts.mapValues { RemainingHandCount(it.value.count,999) }) },
            { f -> f.copy(remainingHandCounts=f.remainingHandCounts.mapValues { RemainingHandCount(it.value.count,f.timeMs-1001) }) },
            { f -> f.copy(remainingHandCounts=f.remainingHandCounts+(Seat.NEXT to RemainingHandCount(6,f.timeMs-10))) }
        )) {
            val checkpoint=recovery()
            for(t in 6000L..6800L step 200) assertNull(checkpoint.reconcile(change(frame(t))))
        }
    }
    @Test fun newVisiblePlayChangedOwnHandOrCaptureOcclusionStillBlocksRecovery() {
        val newCard=Card(Rank.K,Suit.RED)
        for(change in listOf<(TableFrame)->TableFrame>(
            { f -> f.copy(plays=f.plays+(Seat.NEXT to ObservedPlay(listOf(newCard),.99f,listOf(PlayGlyph(newCard,.99f,GlyphBox(.75f,.3f,.77f,.35f)))))) },
            { f -> f.copy(hand=emptyList()) },
            { f -> f.copy(captureGap=true) },
            { f -> f.copy(ambiguousPresentation=true) },
            { f -> f.copy(incompleteReason="masked") },
            { f -> f.copy(plays=f.plays+(Seat.NEXT to ObservedPlay(emptyList(),1f,regionObserved=false))) }
        )) {
            val checkpoint=recovery()
            for(t in 6000L..6800L step 200) assertNull(checkpoint.reconcile(change(frame(t))))
        }
    }
    @Test fun oneBadSnapshotResetsTheIndependentCountProof() {
        val checkpoint=recovery()
        assertNull(checkpoint.reconcile(frame(6000)))
        assertNull(checkpoint.reconcile(frame(6200)))
        assertNull(checkpoint.reconcile(frame(6400,counts=expected-Seat.OPPOSITE)))
        assertNull(checkpoint.reconcile(frame(6600)))
        assertNull(checkpoint.reconcile(frame(6800)))
        assertEquals(emptyMap(),checkpoint.reconcile(frame(7000)))
    }
    @Test fun aLaterVisibilityGapRequiresThreeNewSourcesAfterThatGap() {
        val checkpoint=recovery()
        assertNull(checkpoint.reconcile(frame(6000)))
        assertNull(checkpoint.reconcile(frame(6200)))
        checkpoint.observationInterrupted(6250)
        assertNull(checkpoint.reconcile(frame(6500,6200)))
        assertNull(checkpoint.reconcile(frame(6750)))
        assertNull(checkpoint.reconcile(frame(7000)))
        assertEquals(emptyMap(),checkpoint.reconcile(frame(7250)))
    }
    @Test fun unknownFramesAdvanceTheCountCutoffEvenIfTheirCachedTextWasFresh() {
        val checkpoint=recovery()
        assertNull(checkpoint.reconcile(frame(6000)))
        assertNull(checkpoint.reconcile(frame(6200)))
        assertNull(checkpoint.reconcile(frame(6250).copy(scene=TableScene.UNKNOWN,captureGap=true)))
        assertNull(checkpoint.reconcile(frame(6500,6200)))
        assertNull(checkpoint.reconcile(frame(6750)))
        assertNull(checkpoint.reconcile(frame(7000)))
        assertEquals(emptyMap(),checkpoint.reconcile(frame(7250)))
    }
    private fun localMask(t: Long,source: Long=t)=frame(t,source).let { f -> f.copy(
        plays=f.plays+(Seat.NEXT to ObservedPlay(emptyList(),1f,regionObserved=false)),
        incompleteReason="local overlay",localizedOverlaySeats=setOf(Seat.NEXT)) }

    @Test fun localMaskCanCollectIndependentProofButCannotResumeUntilClear() {
        val checkpoint=recovery()
        for(t in 6000L..6600L step 200) assertNull(checkpoint.reconcile(localMask(t)))
        // Four OCR snapshots were independent. This repeated source contributes
        // no vote; the newly visible table supplies the final missing boundary.
        assertEquals(emptyMap(),checkpoint.reconcile(frame(6700,6600)))
    }
    @Test fun cachedSourcesUnderLocalMaskNeverBecomeIndependentProof() {
        val checkpoint=recovery()
        for(t in 6000L..6600L step 100) assertNull(checkpoint.reconcile(localMask(t,6000)))
        assertNull(checkpoint.reconcile(frame(6700,6000)))
    }
    @Test fun localMaskProofCannotIgnoreChangedQuantitiesOrAVisibleNewPlay() {
        val newCard=Card(Rank.K,Suit.RED)
        for(change in listOf<(TableFrame)->TableFrame>(
            { f -> f.copy(remainingHandCounts=f.remainingHandCounts+(Seat.NEXT to RemainingHandCount(5,f.timeMs))) },
            { f -> f.copy(remainingHandCounts=f.remainingHandCounts-Seat.NEXT) },
            { f -> f.copy(hand=emptyList()) },
            { f -> f.copy(plays=f.plays+(Seat.PREVIOUS to ObservedPlay(listOf(newCard),.99f,
                listOf(PlayGlyph(newCard,.99f,GlyphBox(.2f,.3f,.22f,.35f)))))) }
        )) {
            val checkpoint=recovery()
            for(t in 6000L..6400L step 200) assertNull(checkpoint.reconcile(change(localMask(t))))
            assertNull(checkpoint.reconcile(frame(6500,6400)))
        }
    }
    @Test fun untypedFaultOrNewGapDiscardsAccumulatedLocalMaskProof() {
        for(change in listOf<(TableFrame)->TableFrame>(
            { it.copy(localizedOverlaySeats=emptySet()) },
            { it.copy(captureGap=true) }, { it.copy(ambiguousPresentation=true) },
            { it.copy(scene=TableScene.UNKNOWN) }
        )) {
            val checkpoint=recovery()
            for(t in 6000L..6400L step 200) assertNull(checkpoint.reconcile(localMask(t)))
            assertNull(checkpoint.reconcile(change(localMask(6450))))
            assertNull(checkpoint.reconcile(frame(6500,6400)))
        }
    }
    @Test fun localMaskProofExpiresOrRejectsAContradictionOnClearReturn() {
        for(clear in listOf(frame(7500,6400),frame(6500,6400,expected+(Seat.NEXT to 5)),
            frame(6500,6400).copy(plays=frame(6500).plays+(Seat.NEXT to ObservedPlay(emptyList(),1f,regionObserved=false))))) {
            val checkpoint=recovery()
            for(t in 6000L..6400L step 200) assertNull(checkpoint.reconcile(localMask(t)))
            assertNull(checkpoint.reconcile(clear))
        }
    }
    @Test fun observerPreservesSourcesAcrossLocalMaskAndResumesOnceWithoutChangingLedger() {
        val baseline=Rank.entries.filterNot { it.isJoker }.flatMap { listOf(Card(it,Suit.RED),Card(it,Suit.BLACK)) }+Card(Rank.SMALL,Suit.NONE)
        val ledger=RoundLedger(); val observer=RoundObserver(ledger)
        val clear=Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }
        observer.observe(TableFrame(0,TableScene.LOBBY,null))
        for(t in 60L..240L step 60) observer.observe(TableFrame(t,TableScene.TABLE,baseline,clear))
        val before=ledger.snapshot()
        fun masked(t: Long)=TableFrame(t,TableScene.TABLE,baseline,
            clear+(Seat.NEXT to ObservedPlay(emptyList(),1f,regionObserved=false)),incompleteReason="local overlay",
            remainingHandCounts=listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).associateWith { RemainingHandCount(27,t) },
            localizedOverlaySeats=setOf(Seat.NEXT))
        for(t in 300L..1100L step 200) observer.observe(masked(t))
        assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
        val unmasked=masked(1200).copy(plays=clear,incompleteReason=null,localizedOverlaySeats=emptySet(),
            remainingHandCounts=masked(1100).remainingHandCounts)
        observer.observe(unmasked)
        assertEquals(before,ledger.snapshot()); assertFalse(observer.diagnosticStatus().recovering)
        observer.observe(unmasked.copy(timeMs=1250))
        assertEquals(before,ledger.snapshot()); assertFalse(observer.diagnosticStatus().recovering)
    }
    @Test fun observerResumesItsExistingLedgerAndSubsequentRealCardsAreCountedOnce() {
        val deck=Rank.entries.flatMap { rank ->
            if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
            else List(4) { Card(rank,Suit.RED) }+List(4) { Card(rank,Suit.BLACK) }
        }
        val baseline=deck.take(27); val outside=deck.drop(27)
        val ledger=RoundLedger(); ledger.begin("existing-round",baseline)
        var sequence=0L; var offset=0
        for((seat,size) in listOf(Seat.PREVIOUS to 22,Seat.OPPOSITE to 19,Seat.NEXT to 21)) {
            repeat(size) {
                sequence++
                ledger.commit(PlayEvent("existing-round","event-$sequence",sequence,seat,listOf(outside[offset++])))
            }
        }
        val before=ledger.snapshot(); assertEquals(19,before.remaining!!.values.sum())
        val observer=RoundObserver(ledger)
        val clear=Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }
        observer.observe(TableFrame(1000,TableScene.TABLE,baseline,clear))
        observer.observe(TableFrame(1100,TableScene.UNKNOWN,null))
        for(t in 6000L..6400L step 200) observer.observe(TableFrame(t,TableScene.TABLE,baseline,clear,
            remainingHandCounts=expected.mapValues { RemainingHandCount(it.value,t) }))
        assertEquals(before,ledger.snapshot())
        assertFalse(observer.diagnosticStatus().recovering)
        val card=outside[offset]
        val play=ObservedPlay(listOf(card),.99f,listOf(PlayGlyph(card,.99f,GlyphBox(.48f,.18f,.50f,.23f))))
        for(t in 6600L..7200L step 200) observer.observe(TableFrame(t,TableScene.TABLE,baseline,clear+(Seat.OPPOSITE to play)))
        assertEquals(20,ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(18,ledger.snapshot().remaining!!.values.sum())
        assertEquals(63,ledger.snapshot().plays.values.sumOf { it.size })
    }
    @Test fun observerReceivingAnotherGapDoesNotReuseItsEarlierQuantityVotes() {
        val baseline=Rank.entries.filterNot { it.isJoker }.flatMap { listOf(Card(it,Suit.RED),Card(it,Suit.BLACK)) }+Card(Rank.SMALL,Suit.NONE)
        val ledger=RoundLedger(); val observer=RoundObserver(ledger)
        val clear=Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }
        observer.observe(TableFrame(0,TableScene.LOBBY,null))
        for(t in 60L..240L step 60) observer.observe(TableFrame(t,TableScene.TABLE,baseline,clear))
        val before=ledger.snapshot()
        observer.observe(TableFrame(300,TableScene.UNKNOWN,null))
        fun countFrame(t: Long,source: Long=t)=TableFrame(t,TableScene.TABLE,baseline,clear,
            remainingHandCounts=listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).associateWith { RemainingHandCount(27,source) })
        observer.observe(countFrame(6000)); observer.observe(countFrame(6200))
        observer.incomplete("second actual visibility loss",6250,immediate=true)
        observer.observe(countFrame(6500,6200))
        assertNull(ledger.snapshot().remaining)
        observer.observe(countFrame(6700)); observer.observe(countFrame(6900))
        assertNull(ledger.snapshot().remaining)
        observer.observe(countFrame(7100))
        assertEquals(before,ledger.snapshot())
    }
}
