package com.cyotun.jpq.core

import kotlin.test.*

class OpeningChoiceRecoveryTest {
    private val own=Rank.entries.filterNot { it.isJoker }.flatMap { listOf(Card(it,Suit.RED),Card(it,Suit.BLACK)) }+Card(Rank.SMALL,Suit.NONE)
    private val outside=listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT)
    private val empty=Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }
    private fun checkpoint(opening: Boolean=true)=RoundRecovery(1000,own,false,
        outside.associateWith { RoundRecovery.Anchor(emptyList(),emptyList(),null,900) },opening,outside.associateWith { 27 })
    private fun clear(t: Long,source: Long?=null)=TableFrame(t,if(source==null) TableScene.TABLE else TableScene.DEALING,
        own,empty,dealingChoicesObserved=source!=null,openingChoiceSourceTimeMs=source)
    private fun establish(checkpoint: RoundRecovery,from: Long=6000): Map<String,List<Card>>? {
        for(t in from..from+600L step 100) {
            val source=when(t-from) { 200L -> from; 600L -> from+400; else -> null }
            val result=checkpoint.reconcile(clear(t,source))
            if(result!=null) return result
        }
        return null
    }
    @Test fun twoIndependentChoiceSourcesRecoverAnOpeningBeyondTheGeometryWindow() {
        assertEquals(emptyMap(),establish(checkpoint()))
    }
    @Test fun repeatedCachedControlsCannotAccumulateIndependentVotes() {
        val checkpoint=checkpoint()
        assertNull(checkpoint.reconcile(clear(6000)))
        for(t in 6100L..6900L step 100) assertNull(checkpoint.reconcile(clear(t,6000)))
    }
    @Test fun sourceNeedsItsOwnClearCardFrameAndCannotBeStaleFutureOrBeforeInterruption() {
        for(source in listOf(999L,5000L,6050L,8000L)) {
            val checkpoint=checkpoint()
            for(t in 6000L..6800L step 100) assertNull(checkpoint.reconcile(clear(t,source)))
        }
    }
    @Test fun visibilityLossBetweenSourceAndResultClearsBothVotesAndSourceHistory() {
        val checkpoint=checkpoint()
        checkpoint.reconcile(clear(6000)); checkpoint.reconcile(clear(6200,6000))
        checkpoint.reconcile(clear(6300).copy(plays=empty+(Seat.NEXT to ObservedPlay(emptyList(),1f,regionObserved=false))))
        for(t in 6400L..6800L step 100) assertNull(checkpoint.reconcile(clear(t,6200)))
        assertEquals(emptyMap(),establish(checkpoint,7000))
    }
    @Test fun missingCardFramesCannotBeBridgedByCachedControls() {
        val checkpoint=checkpoint()
        checkpoint.reconcile(clear(6000)); checkpoint.reconcile(clear(6200,6000))
        assertNull(checkpoint.reconcile(clear(6800,6200)))
        assertEquals(emptyMap(),establish(checkpoint,7000))
    }
    @Test fun currentHandOrTableOrSceneMustStillProveTheUnplayedOpening() {
        for(change in listOf<(TableFrame)->TableFrame>(
            { it.copy(hand=own.dropLast(1)) }, { it.copy(hand=null) },
            { it.copy(selfFinished=true) }, { it.copy(captureGap=true) },
            { it.copy(incompleteReason="overlay") }, { it.copy(ambiguousPresentation=true) },
            { it.copy(plays=empty+(Seat.PREVIOUS to ObservedPlay(listOf(Card(Rank.K,Suit.RED)),.99f))) },
            { it.copy(plays=empty+(Seat.SELF to ObservedPlay(listOf(Card(Rank.K,Suit.RED)),.99f))) },
            { it.copy(scene=TableScene.TEAMMATE) }, { it.copy(dealingChoicesObserved=false) }
        )) {
            val checkpoint=checkpoint()
            for(t in 6000L..6800L step 100) assertNull(checkpoint.reconcile(change(clear(t,t))))
        }
    }
    @Test fun existingPlaysOrAConfirmedFinishedSelfCannotUseOpeningControls() {
        assertNull(establish(checkpoint(opening=false)))
        val played=RoundRecovery(1000,own,false,outside.associateWith {
            RoundRecovery.Anchor(emptyList(),emptyList(),if(it==Seat.NEXT) "event" else null,900)
        },true,outside.associateWith { if(it==Seat.NEXT) 26 else 27 })
        assertNull(establish(played))
        assertNull(establish(RoundRecovery(1000,own,true,
            outside.associateWith { RoundRecovery.Anchor(emptyList(),emptyList(),null,900) },true,outside.associateWith { 27 })))
    }
    @Test fun aLaterExplicitInterruptionCannotReuseEarlierSourceVotes() {
        val checkpoint=checkpoint()
        checkpoint.reconcile(clear(6000)); checkpoint.reconcile(clear(6200,6000))
        checkpoint.observationInterrupted(6250)
        for(t in 6300L..6800L step 100) assertNull(checkpoint.reconcile(clear(t,6200)))
        assertEquals(emptyMap(),establish(checkpoint,7000))
    }
    @Test fun unrelatedOcrMissesDoNotInterruptOtherwiseContinuousCardEvidence() {
        val checkpoint=checkpoint()
        for(t in 6000L..6500L step 100) assertNull(checkpoint.reconcile(clear(t,if(t==6200L) 6000 else null)))
        assertEquals(emptyMap(),checkpoint.reconcile(clear(6600,6400)))
    }
    @Test fun recoveryPreservesRoundAndBaselineAndTheNextRealPlayCommitsOnce() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger)
        observer.observe(TableFrame(0,TableScene.LOBBY,null))
        for(t in 60L..240L step 60) observer.observe(clear(t))
        val before=ledger.snapshot(); val initial=ledger.initialHand()
        for(t in 300L..5400L step 300) observer.observe(clear(t).copy(incompleteReason="悬浮条遮住出牌区"))
        assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
        for(t in 5500L..6100L step 100) observer.observe(clear(t,when(t) { 5700L -> 5500; 6100L -> 5900; else -> null }))
        assertFalse(observer.diagnosticStatus().recovering)
        assertEquals(before,ledger.snapshot()); assertEquals(initial,ledger.initialHand())
        val card=Card(Rank.A,Suit.BLACK)
        val play=ObservedPlay(listOf(card),.99f,listOf(PlayGlyph(card,.99f,GlyphBox(.48f,.18f,.50f,.23f))))
        for(t in 6200L..6800L step 100) observer.observe(clear(t).copy(plays=empty+(Seat.OPPOSITE to play)))
        assertEquals(before.roundId,ledger.snapshot().roundId)
        assertEquals(1,ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(1,observer.diagnosticStatus().sequence)
        assertEquals(80,ledger.snapshot().remaining!!.values.sum())
    }
}
