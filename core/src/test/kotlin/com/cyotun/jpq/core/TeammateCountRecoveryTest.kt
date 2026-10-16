package com.cyotun.jpq.core

import kotlin.test.*

class TeammateCountRecoveryTest {
    private val smaller=listOf(Card(Rank.J,Suit.BLACK),Card(Rank.J,Suit.RED),
        Card(Rank.TEN,Suit.BLACK),Card(Rank.TEN,Suit.RED))
    private val removed=listOf(Card(Rank.TWO,Suit.RED),Card(Rank.TWO,Suit.RED),Card(Rank.TWO,Suit.BLACK),
        Card(Rank.THREE,Suit.RED),Card(Rank.THREE,Suit.BLACK))
    private val known=smaller+removed
    private val expected=mapOf(Seat.PREVIOUS to 5,Seat.OPPOSITE to 9,Seat.NEXT to 6)
    private val clear=ObservedPlay(emptyList(),1f)
    private fun play(cards: List<Card> = removed,x: Float=.43f)=ObservedPlay(cards,.90f,
        cards.mapIndexed { i,card -> PlayGlyph(card,.90f,GlyphBox(x+i*.025f,.16f,x+i*.025f+.02f,.21f)) })
    private fun checkpoint(seed: List<Card>?=known,selfFinished: Boolean=true)=RoundRecovery(1000,emptyList(),selfFinished,
        expected.mapValues { RoundRecovery.Anchor(emptyList(),emptyList(),null,900) },false,expected,seed)
    private fun frame(time: Long,source: Long=time)=TableFrame(time,TableScene.TEAMMATE,null,
        mapOf(Seat.SELF to clear,Seat.PREVIOUS to clear,Seat.OPPOSITE to play(),Seat.NEXT to clear),
        teammateHand=smaller,teammateHandConfidence=.90f,
        remainingHandCounts=mapOf(Seat.PREVIOUS to RemainingHandCount(5,source),Seat.NEXT to RemainingHandCount(6,source)))

    @Test fun exactNineToFourDifferenceAndOtherSeatsCountsProveTheFiveVisibleCards() {
        val recovery=checkpoint()
        assertNull(recovery.reconcile(frame(6000)))
        assertNull(recovery.reconcile(frame(6200)))
        assertEquals(emptyMap(),recovery.reconcile(frame(6400)))
        assertEquals(removed,recovery.recoveredTeammatePlay?.cards)
        assertEquals(play().glyphs,recovery.recoveredTeammatePlay?.glyphs)
    }

    @Test fun optionalOppositeBadgeMustAgreeWithTheDirectlyObservedCurrentHand() {
        for(count in listOf(4,5)) {
            val recovery=checkpoint()
            var result: Map<String,List<Card>>?=null
            for(time in listOf(6000L,6200L,6400L)) {
                val f=frame(time)
                result=recovery.reconcile(f.copy(remainingHandCounts=f.remainingHandCounts+
                    (Seat.OPPOSITE to RemainingHandCount(count,time))))
            }
            if(count==4) assertEquals(emptyMap(),result) else {
                assertNull(result); assertNull(recovery.recoveredTeammatePlay)
            }
        }
    }

    @Test fun missingChangedOldFutureOrMixedOtherCountsNeverCertifyTheNewPlay() {
        val changes=listOf<(TableFrame)->TableFrame>(
            { f -> f.copy(remainingHandCounts=f.remainingHandCounts-Seat.NEXT) },
            { f -> f.copy(remainingHandCounts=f.remainingHandCounts+(Seat.NEXT to RemainingHandCount(5,f.timeMs))) },
            { f -> f.copy(remainingHandCounts=f.remainingHandCounts.mapValues { RemainingHandCount(it.value.count,999) }) },
            { f -> f.copy(remainingHandCounts=f.remainingHandCounts.mapValues { RemainingHandCount(it.value.count,f.timeMs-1001) }) },
            { f -> f.copy(remainingHandCounts=f.remainingHandCounts.mapValues { RemainingHandCount(it.value.count,f.timeMs+1) }) },
            { f -> f.copy(remainingHandCounts=f.remainingHandCounts+(Seat.NEXT to RemainingHandCount(6,f.timeMs-1))) },
            { f -> f.copy(remainingHandCounts=f.remainingHandCounts+(Seat.OPPOSITE to RemainingHandCount(4,f.timeMs-1))) }
        )
        for(change in changes) {
            val recovery=checkpoint()
            for(time in listOf(6000L,6200L,6400L,6600L)) assertNull(recovery.reconcile(change(frame(time))))
            assertNull(recovery.recoveredTeammatePlay)
        }
    }

    @Test fun lowConfidencePartialOrContradictoryCardsCannotProveAPlay() {
        val changes=listOf<(TableFrame)->TableFrame>(
            { f -> f.copy(teammateHandConfidence=.79f) },
            { f -> f.copy(teammateHandConfidence=Float.NaN) },
            { f -> f.copy(teammateHand=known) },
            { f -> f.copy(teammateHand=smaller+Card(Rank.K,Suit.RED)) },
            { f -> f.copy(plays=f.plays+(Seat.OPPOSITE to play().copy(confidence=.79f))) },
            { f -> f.copy(plays=f.plays+(Seat.OPPOSITE to play().copy(glyphs=play().glyphs.dropLast(1)))) },
            { f -> f.copy(plays=f.plays+(Seat.OPPOSITE to play(removed.dropLast(1)))) },
            { f -> f.copy(plays=f.plays+(Seat.OPPOSITE to play().copy(regionObserved=false))) },
            { f -> f.copy(plays=f.plays+(Seat.NEXT to clear.copy(regionObserved=false))) },
            { f -> f.copy(plays=f.plays+(Seat.NEXT to play(listOf(Card(Rank.K,Suit.RED))))) },
            { f -> f.copy(scene=TableScene.TABLE) },
            { f -> f.copy(captureGap=true) },
            { f -> f.copy(ambiguousPresentation=true) },
            { f -> f.copy(incompleteReason="occluded") }
        )
        for(change in changes) {
            val recovery=checkpoint()
            for(time in listOf(6000L,6200L,6400L,6600L)) assertNull(recovery.reconcile(change(frame(time))))
            assertNull(recovery.recoveredTeammatePlay)
        }
    }

    @Test fun absentOrIncompleteSeedAndUnfinishedOwnStateAreRejected() {
        val impossibleSeed=List(5) { Card(Rank.TWO,Suit.RED) }+smaller
        for(recovery in listOf(checkpoint(null),checkpoint(known.dropLast(1)),checkpoint(impossibleSeed),checkpoint(selfFinished=false))) {
            for(time in listOf(6000L,6200L,6400L)) assertNull(recovery.reconcile(frame(time)))
            assertNull(recovery.recoveredTeammatePlay)
        }
    }

    @Test fun movingPhysicalGroupCannotAccumulateIndependentCountVotes() {
        val recovery=checkpoint()
        for((i,time) in listOf(6000L,6200L,6400L,6600L).withIndex()) {
            val f=frame(time)
            assertNull(recovery.reconcile(f.copy(plays=f.plays+(Seat.OPPOSITE to play(x=.43f+i*.03f)))))
        }
        assertNull(recovery.recoveredTeammatePlay)
    }

    @Test fun cachedSnapshotAndShortSourceSpanCannotSupplyThreeIndependentVotes() {
        val recovery=checkpoint()
        for(time in 6000L..6600L step 100) assertNull(recovery.reconcile(frame(time,6000)))
        assertNull(recovery.reconcile(frame(6800)))
        assertEquals(emptyMap(),recovery.reconcile(frame(7000)))
        val quick=checkpoint()
        for(time in listOf(6000L,6100L,6200L)) assertNull(quick.reconcile(frame(time)))
        assertEquals(emptyMap(),quick.reconcile(frame(6300)))
    }

    @Test fun outOfOrderSourceCannotVoteAndAChangedHandRestartsTheCandidate() {
        val recovery=checkpoint()
        assertNull(recovery.reconcile(frame(6000)))
        assertNull(recovery.reconcile(frame(6200)))
        assertNull(recovery.reconcile(frame(6300,6100)))
        assertNull(recovery.reconcile(frame(6400)))
        assertNull(recovery.reconcile(frame(6600)))
        assertEquals(emptyMap(),recovery.reconcile(frame(6800)))
        val changed=checkpoint()
        assertNull(changed.reconcile(frame(6000)))
        assertNull(changed.reconcile(frame(6200)))
        assertNull(changed.reconcile(frame(6400).copy(teammateHand=smaller.dropLast(1))))
        assertNull(changed.reconcile(frame(6600)))
        assertNull(changed.reconcile(frame(6800)))
        assertEquals(emptyMap(),changed.reconcile(frame(7000)))
    }

    @Test fun laterInterruptionAndUnknownRequireThreeSourcesAfterTheLatestGap() {
        for(explicit in listOf(true,false)) {
            val recovery=checkpoint()
            assertNull(recovery.reconcile(frame(6000)))
            assertNull(recovery.reconcile(frame(6200)))
            if(explicit) recovery.observationInterrupted(6250)
            else assertNull(recovery.reconcile(frame(6250).copy(scene=TableScene.UNKNOWN,captureGap=true)))
            assertNull(recovery.reconcile(frame(6500,6200)))
            assertNull(recovery.reconcile(frame(6700)))
            assertNull(recovery.reconcile(frame(6900)))
            assertEquals(emptyMap(),recovery.reconcile(frame(7100)))
        }
    }

    private fun snapshot(): RoundSnapshot {
        val played=listOf(Rank.A,Rank.K,Rank.Q).flatMap { rank ->
            List(4) { Card(rank,Suit.RED) }+List(4) { Card(rank,Suit.BLACK) }
        }.take(18)
        val events=played.mapIndexed { i,card -> PlayEvent("round","event-$i",i.toLong(),Seat.OPPOSITE,listOf(card)) }
        val used=played.groupingBy { it.rank }.eachCount()
        return RoundSnapshot("round",RoundPhase.SELF_FINISHED,Rank.entries.associateWith { it.capacity-(used[it] ?: 0) },
            Seat.entries.associateWith { if(it==Seat.OPPOSITE) events else emptyList() },null)
    }
    private fun seedFrame(time: Long,hand: List<Card> = known)=TableFrame(time,TableScene.TEAMMATE,null,
        mapOf(Seat.OPPOSITE to clear),teammateHand=hand,teammateHandConfidence=.90f)

    @Test fun checkpointSeedSurvivesNonmatchingSubsetReadingsWithoutClaimingAPlay() {
        val tracker=TeammateHandPlayProofTracker(); val snap=snapshot()
        assertNull(tracker.observe(seedFrame(1000),snap))
        assertNull(tracker.checkpointHand(snap))
        assertNull(tracker.observe(seedFrame(1120),snap))
        assertEquals(known,tracker.checkpointHand(snap))
        val seven=known.filter { it.rank!=Rank.THREE }
        for(time in listOf(1300L,1500L,1700L,1900L)) assertNull(tracker.observe(seedFrame(time,seven),snap))
        assertEquals(known,tracker.checkpointHand(snap))
        tracker.reset()
        assertNull(tracker.checkpointHand(snap))
    }

    @Test fun checkpointRejectsChangedVersionPhaseCapacityAndInsufficientSeedVotes() {
        val snap=snapshot(); val tracker=TeammateHandPlayProofTracker()
        tracker.observe(seedFrame(1000),snap)
        tracker.observe(seedFrame(1060),snap)
        assertNull(tracker.checkpointHand(snap))
        tracker.observe(seedFrame(1120),snap)
        assertNotNull(tracker.checkpointHand(snap))
        assertNull(tracker.checkpointHand(snap.copy(roundId="different")))
        assertNull(tracker.checkpointHand(snap.copy(phase=RoundPhase.ACTIVE)))
        assertNull(tracker.checkpointHand(snap.copy(remaining=snap.remaining!!+(Rank.THREE to 1))))
        val changed=snap.copy(plays=snap.plays+(Seat.OPPOSITE to snap.plays.getValue(Seat.OPPOSITE)+
            PlayEvent("round","new",20,Seat.OPPOSITE,listOf(Card(Rank.K,Suit.RED)))))
        assertNull(tracker.checkpointHand(changed))
        val incomplete=TeammateHandPlayProofTracker()
        incomplete.observe(seedFrame(1000,known.dropLast(1)),snap)
        incomplete.observe(seedFrame(1120,known.dropLast(1)),snap)
        assertNull(incomplete.checkpointHand(snap))
    }
}
