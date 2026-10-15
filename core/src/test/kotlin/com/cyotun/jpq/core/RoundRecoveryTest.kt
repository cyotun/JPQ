package com.cyotun.jpq.core

import kotlin.test.*

class RoundRecoveryTest {
    private val baseline=Rank.entries.filterNot { it.isJoker }.flatMap { listOf(Card(it,Suit.RED),Card(it,Suit.BLACK)) }+Card(Rank.SMALL,Suit.NONE)
    private fun empty()=Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }
    private fun play(rank: Rank,x: Float)=listOf(Card(rank,Suit.RED)).let { cards ->
        ObservedPlay(cards,.99f,listOf(PlayGlyph(cards.single(),.99f,GlyphBox(x,.30f,x+.02f,.35f))))
    }
    private val next=play(Rank.A,.75f)
    private val groups=empty()+mapOf(Seat.NEXT to next,Seat.OPPOSITE to play(Rank.Q,.48f),Seat.PREVIOUS to play(Rank.K,.20f))
    private fun start(ledger: RoundLedger,observer: RoundObserver,plays: Map<Seat,ObservedPlay> = groups) {
        observer.observe(TableFrame(0,TableScene.LOBBY,null))
        for(t in 60L..240L step 60) observer.observe(TableFrame(t,TableScene.TABLE,baseline,empty()))
        for(t in 300L..480L step 60) observer.observe(TableFrame(t,TableScene.TABLE,baseline,plays))
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
    }
    private fun afterFourJokers(): Triple<RoundLedger,RoundObserver,List<Card>> {
        val own=baseline.filterNot { it.rank.isJoker }+Card(Rank.THREE,Suit.RED)
        val ledger=RoundLedger(); val observer=RoundObserver(ledger)
        observer.observe(TableFrame(0,TableScene.LOBBY,null))
        for(t in 60L..240L step 60) observer.observe(TableFrame(t,TableScene.TABLE,own,empty()))
        val cards=List(2) { Card(Rank.BIG,Suit.NONE) }+List(2) { Card(Rank.SMALL,Suit.NONE) }
        val glyphs=cards.mapIndexed { i,c -> PlayGlyph(c,.99f,GlyphBox(.75f+i*.025f,.30f,.77f+i*.025f,.35f)) }
        val p=ObservedPlay(cards,.99f,glyphs)
        for(t in 300L..480L step 60) observer.observe(TableFrame(t,TableScene.TABLE,own,empty()+(Seat.NEXT to p)))
        assertEquals(4,ledger.snapshot().played(Seat.NEXT))
        return Triple(ledger,observer,own)
    }
    @Test fun unchangedPhysicalPresentationsRecoverAfterLongAnimationWithoutDuplicateEvents() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(ledger,observer)
        val checkpoint=ledger.snapshot()
        for(t in 540L..2340L step 180) observer.observe(TableFrame(t,TableScene.UNKNOWN,null))
        assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
        assertNull(ledger.snapshot().remaining)
        assertEquals(checkpoint.plays,ledger.snapshot().plays)
        for(t in 2400L..2520L step 60) observer.observe(TableFrame(t,TableScene.TABLE,baseline,groups))
        assertNull(ledger.snapshot().remaining) // Three frames are not sufficient.
        observer.observe(TableFrame(2580,TableScene.TABLE,baseline,groups))
        assertEquals(checkpoint,ledger.snapshot())
        assertFalse(observer.diagnosticStatus().locked)
    }
    @Test fun aPartialRegionRecoversWhileOtherEmptySeatsWereContinuouslyObserved() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger)
        val plays=empty()+(Seat.NEXT to next); start(ledger,observer,plays)
        for(t in 540L..2340L step 180) observer.observe(TableFrame(t,TableScene.TABLE,baseline,
            plays+(Seat.NEXT to next.copy(regionObserved=false)),incompleteReason="动画待核对"))
        assertNull(ledger.snapshot().remaining)
        for(t in 2400L..2580L step 60) observer.observe(TableFrame(t,TableScene.TABLE,baseline,plays))
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
        assertEquals(1,ledger.snapshot().played(Seat.NEXT))
    }
    @Test fun changedHandOrDisplacedPresentationCannotCertifyTheOldCount() {
        for(displaced in listOf(false,true)) {
            val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(ledger,observer)
            observer.observe(TableFrame(540,TableScene.UNKNOWN,null))
            val restored=if(displaced) groups+(Seat.NEXT to next.copy(glyphs=next.glyphs.map {
                it.copy(box=it.box.copy(left=it.box.left-.07f,right=it.box.right-.07f)) })) else groups
            for(t in 600L..1200L step 60) observer.observe(TableFrame(t,TableScene.TABLE,
                if(displaced) baseline else baseline.drop(1),restored))
            assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
            assertNull(ledger.snapshot().remaining)
            assertEquals(1,ledger.snapshot().played(Seat.NEXT))
        }
    }
    @Test fun blankReturnAndLongBlindEmptySeatCannotProveThatNoPlayWasMissed() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger)
        start(ledger,observer,empty()+(Seat.NEXT to next))
        for(t in 540L..1800L step 180) observer.observe(TableFrame(t,TableScene.UNKNOWN,null))
        for(t in 1860L..2340L step 60) observer.observe(TableFrame(t,TableScene.TABLE,baseline,empty()))
        assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
        assertNull(ledger.snapshot().remaining)
    }
    @Test fun boundedCheckpointExpiresWithoutBecomingLockedAndAResultStartsANewRound() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(ledger,observer)
        observer.observe(TableFrame(540,TableScene.UNKNOWN,null))
        for(t in 4600L..4900L step 60) observer.observe(TableFrame(t,TableScene.TABLE,baseline,groups))
        assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
        assertFalse(observer.diagnosticStatus().locked)
        observer.observe(TableFrame(5000,TableScene.RESULT,null))
        for(t in 5060L..5240L step 60) observer.observe(TableFrame(t,TableScene.TABLE,baseline,empty()))
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
        assertEquals(81,ledger.snapshot().remaining!!.values.sum())
    }
    @Test fun selfFirstStillTracksTheTeammateAfterRecovery() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(ledger,observer)
        observer.observe(TableFrame(540,TableScene.TEAMMATE,null,groups,true))
        observer.observe(TableFrame(600,TableScene.UNKNOWN,null))
        assertEquals(RoundPhase.SELF_FINISHED,ledger.phaseBeforeRecovery())
        for(t in 660L..840L step 60) observer.observe(TableFrame(t,TableScene.TEAMMATE,null,groups))
        assertEquals(RoundPhase.SELF_FINISHED,ledger.snapshot().phase)
        for(t in 900L..1080L step 60) observer.observe(TableFrame(t,TableScene.TEAMMATE,null,
            groups+(Seat.OPPOSITE to play(Rank.J,.48f))))
        assertEquals(2,ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(RoundPhase.SELF_FINISHED,ledger.snapshot().phase)
    }
    @Test fun atomicRecoveryExpansionRejectsCapacityConflictWithoutChangingCheckpoint() {
        val ledger=RoundLedger(); ledger.begin("r",baseline)
        val card=Card(Rank.A,Suit.RED)
        ledger.commit(PlayEvent("r","event",1,Seat.NEXT,listOf(card)))
        val before=ledger.snapshot().plays
        ledger.suspendForRecovery("recover")
        assertFails { ledger.resumeAfterRecovery(mapOf("event" to List(4) { card })) }
        assertEquals(before,ledger.snapshot().plays)
        assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
        assertNull(ledger.snapshot().remaining)
        ledger.resumeAfterRecovery(mapOf("event" to List(3) { card }))
        assertEquals(3,ledger.snapshot().played(Seat.NEXT))
        assertEquals(78,ledger.snapshot().remaining!!.values.sum())
    }
    @Test fun restoredMissingGlyphExpandsItsOriginalEventAtomically() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(ledger,observer)
        val previous=ledger.snapshot().plays.getValue(Seat.NEXT).single()
        observer.observe(TableFrame(540,TableScene.UNKNOWN,null))
        val second=PlayGlyph(Card(Rank.A,Suit.BLACK),.99f,GlyphBox(.775f,.30f,.795f,.35f))
        val expanded=next.copy(cards=next.cards+second.card,glyphs=next.glyphs+second)
        for(t in 600L..780L step 60) observer.observe(TableFrame(t,TableScene.TABLE,baseline,groups+(Seat.NEXT to expanded)))
        val recovered=ledger.snapshot().plays.getValue(Seat.NEXT).single()
        assertEquals(previous.eventId,recovered.eventId)
        assertEquals(previous.sequence,recovered.sequence)
        assertEquals(2,recovered.cards.size)
        assertEquals(77,ledger.snapshot().remaining!!.values.sum())
    }
    @Test fun aLateGapNotificationCannotReviveAnAlreadyStalePhysicalAnchor() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(ledger,observer)
        observer.incomplete("late visibility callback",2000,immediate=true)
        for(t in 2060L..2420L step 60) observer.observe(TableFrame(t,TableScene.TABLE,baseline,groups))
        assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
        assertNull(ledger.snapshot().remaining)
    }
    @Test fun oneShortBadFrameReconcilesWithoutPublishingARecoveryBannerAndNewPlaysContinue() {
        val ledger=RoundLedger(); val published=mutableListOf<RoundPhase>()
        val observer=RoundObserver(ledger) { published.add(it.phase) }; start(ledger,observer)
        val before=ledger.snapshot(); published.clear()
        observer.observe(TableFrame(540,TableScene.UNKNOWN,null))
        assertEquals(before,ledger.snapshot())
        assertTrue(observer.diagnosticStatus().recovering)
        for(t in 600L..780L step 60) observer.observe(TableFrame(t,TableScene.TABLE,baseline,groups))
        assertEquals(before,ledger.snapshot())
        assertFalse(observer.diagnosticStatus().recovering)
        assertFalse(RoundPhase.RECOVERING in published)
        for(t in 840L..1020L step 60) observer.observe(TableFrame(t,TableScene.TABLE,baseline,
            groups+(Seat.NEXT to play(Rank.J,.75f))))
        assertEquals(2,ledger.snapshot().played(Seat.NEXT))
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
    }
    @Test fun repeatedBadFramesCannotRestartTheFiveHundredMillisecondGrace() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(ledger,observer)
        for(t in 540L..1020L step 120) observer.observe(TableFrame(t,TableScene.UNKNOWN,null))
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
        assertNotNull(ledger.snapshot().remaining)
        observer.observe(TableFrame(1060,TableScene.UNKNOWN,null))
        assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
        assertNull(ledger.snapshot().remaining)
        for(t in 1120L..1300L step 60) observer.observe(TableFrame(t,TableScene.TABLE,baseline,groups))
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
        assertEquals(1,ledger.snapshot().played(Seat.NEXT))
    }
    @Test fun anUnprovedChangedPlayDuringGraceCannotBeAppendedAsThoughTheGapDidNotHappen() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(ledger,observer)
        observer.observe(TableFrame(540,TableScene.UNKNOWN,null))
        for(t in 600L..1140L step 60) observer.observe(TableFrame(t,TableScene.TABLE,baseline,
            groups+(Seat.NEXT to play(Rank.J,.75f))))
        assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
        assertNull(ledger.snapshot().remaining)
        assertEquals(1,ledger.snapshot().played(Seat.NEXT))
    }
    @Test fun visibilityLossHidesCountsImmediatelyEvenIfNoFurtherFrameCanAdvanceTheGrace() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(ledger,observer)
        observer.incomplete("screen hidden",540,immediate=true)
        assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
        assertNull(ledger.snapshot().remaining)
    }
    @Test fun aSingleAmbiguousAnimationIsAnUnconfirmedCandidateAndCannotFreezeOrdinarySubsequentPlays() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(ledger,observer)
        observer.observe(TableFrame(540,TableScene.TABLE,baseline.drop(10),
            incompleteReason="unconfirmed animation",ambiguousPresentation=true))
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
        assertFalse(observer.diagnosticStatus().recovering)
        // Real source frames1118 and3850 are each one ambiguous animation frame;
        // subsequent hand OCR can differ while fresh opponent plays still settle.
        for(t in 780L..1320L step 180) observer.observe(TableFrame(t,TableScene.TABLE,baseline.drop(3),
            groups+(Seat.OPPOSITE to play(Rank.J,.48f))))
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
        assertEquals(2,ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(27,ledger.currentHand().size) // No unproved hand loss became a self event.
    }
    @Test fun ownershipAmbiguityPreservesTheOriginalFifteenHundredMillisecondBoundary() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(ledger,observer)
        for(t in listOf(540L,780L,1020L,1540L,2040L)) observer.observe(TableFrame(t,TableScene.TABLE,baseline,
            incompleteReason="ambiguous",ambiguousPresentation=true))
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
        assertNotNull(ledger.snapshot().remaining)
        assertFalse(observer.diagnosticStatus().recovering)
        observer.observe(TableFrame(2041,TableScene.TABLE,baseline,
            incompleteReason="ambiguous",ambiguousPresentation=true))
        assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
        assertNull(ledger.snapshot().remaining)
    }
    @Test fun disappearingAmbiguityDoesNotClearAnExistingUnknownCaptureCheckpoint() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(ledger,observer)
        observer.observe(TableFrame(540,TableScene.UNKNOWN,null))
        observer.observe(TableFrame(600,TableScene.TABLE,baseline,
            incompleteReason="animation",ambiguousPresentation=true))
        for(t in 660L..1200L step 60) observer.observe(TableFrame(t,TableScene.TABLE,baseline.drop(1),groups))
        assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
        assertNull(ledger.snapshot().remaining)
    }
    @Test fun recordedShrinkingFragmentsReattachToTheirConfirmedOwnerWithoutCreatingAnotherEvent() {
        fun glyph(rank: Rank,suit: Suit,l: Float,t: Float,r: Float,b: Float)=PlayGlyph(Card(rank,suit),.89f,GlyphBox(l,t,r,b))
        // Sanitized source frames3936/3937: an already-confirmed display loses
        // glyphs during an effect; no absent card is supplied by these fragments.
        val full=listOf(glyph(Rank.A,Suit.BLACK,.2536f,.2883f,.2694f,.3287f),glyph(Rank.A,Suit.BLACK,.2786f,.2881f,.294f,.3287f),
            glyph(Rank.A,Suit.RED,.2292f,.2883f,.2444f,.3287f),glyph(Rank.K,Suit.RED,.1563f,.2877f,.1703f,.3282f),
            glyph(Rank.K,Suit.RED,.2051f,.2872f,.2192f,.3285f),glyph(Rank.K,Suit.RED,.1805f,.2873f,.1948f,.3284f))
        val fragments=listOf(glyph(Rank.A,Suit.BLACK,.2506f,.2798f,.2685f,.3251f),
            glyph(Rank.A,Suit.RED,.2264f,.2797f,.2434f,.3249f),glyph(Rank.K,Suit.RED,.153f,.2784f,.1674f,.3245f))
        val fullPlay=ObservedPlay(full.map { it.card },.89f,full)
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(ledger,observer,groups+(Seat.PREVIOUS to fullPlay))
        val before=ledger.snapshot()
        val raw=assignPlayGroups(fragments,emptySet(),emptyList())
        assertEquals(3,raw.ambiguous.size)
        for(t in 540L..1260L step 180) {
            val associated=observer.reassociatePresentationFragments(raw,t)
            assertTrue(associated.ambiguous.isEmpty())
            assertEquals(fragments,associated.seats.getValue(Seat.PREVIOUS))
            val p=ObservedPlay(fragments.map { it.card },.89f,fragments)
            observer.observe(TableFrame(t,TableScene.TABLE,baseline,groups+(Seat.PREVIOUS to p)))
        }
        for(t in 1320L..1500L step 60) observer.observe(TableFrame(t,TableScene.TABLE,baseline,groups+(Seat.PREVIOUS to fullPlay)))
        assertEquals(before,ledger.snapshot())
        val unknown=glyph(Rank.J,Suit.BLACK,.31f,.278f,.327f,.324f)
        val mixed=observer.reassociatePresentationFragments(raw.copy(ambiguous=fragments+unknown),1560)
        assertEquals(listOf(unknown),mixed.ambiguous) // Unmatched evidence is never swallowed.
        assertEquals(fragments,mixed.seats.getValue(Seat.PREVIOUS))
    }
    @Test fun clearedOrStaleGeometryCannotReassociateAnAmbiguousCard() {
        for(clearBoundary in listOf(false,true)) {
            val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(ledger,observer)
            val fragment=groups.getValue(Seat.PREVIOUS).glyphs.single()
            if(clearBoundary) for(t in listOf(540L,660L)) observer.observe(TableFrame(t,TableScene.TABLE,baseline,
                groups+(Seat.PREVIOUS to ObservedPlay(emptyList(),1f))))
            val time=if(clearBoundary) 720L else 2100L
            val raw=OwnedPlays(Seat.entries.associateWith { emptyList() },listOf(fragment))
            assertEquals(raw,observer.reassociatePresentationFragments(raw,time))
        }
    }
    @Test fun anUnconfirmedSixCardBombCanLoseGlyphsForSixHundredMillisecondsAndStillSettleNormally() {
        fun glyph(rank: Rank,suit: Suit,l: Float,t: Float,r: Float,b: Float)=PlayGlyph(Card(rank,suit),.89f,GlyphBox(l,t,r,b))
        val full=listOf(glyph(Rank.A,Suit.BLACK,.2536f,.2883f,.2694f,.3287f),glyph(Rank.A,Suit.BLACK,.2786f,.2881f,.294f,.3287f),
            glyph(Rank.A,Suit.RED,.2292f,.2883f,.2444f,.3287f),glyph(Rank.K,Suit.RED,.1563f,.2877f,.1703f,.3282f),
            glyph(Rank.K,Suit.RED,.2051f,.2872f,.2192f,.3285f),glyph(Rank.K,Suit.RED,.1805f,.2873f,.1948f,.3284f))
        val fragments=listOf(glyph(Rank.A,Suit.BLACK,.2506f,.2798f,.2685f,.3251f),
            glyph(Rank.A,Suit.RED,.2264f,.2797f,.2434f,.3249f),glyph(Rank.K,Suit.RED,.153f,.2784f,.1674f,.3245f))
        val fullPlay=ObservedPlay(full.map { it.card },.89f,full)
        val ledger=RoundLedger(); val phases=mutableListOf<RoundPhase>()
        val observer=RoundObserver(ledger) { phases.add(it.phase) }
        start(ledger,observer,groups+(Seat.PREVIOUS to ObservedPlay(emptyList(),1f)))
        for(t in listOf(540L,720L)) observer.observe(TableFrame(t,TableScene.TABLE,baseline,groups+(Seat.PREVIOUS to fullPlay)))
        assertEquals(0,ledger.snapshot().played(Seat.PREVIOUS)) // No confirmed event/anchor yet.
        val raw=assignPlayGroups(fragments,emptySet(),emptyList())
        for(t in 900L..1500L step 200) {
            assertEquals(3,observer.reassociatePresentationFragments(raw,t).ambiguous.size)
            observer.observe(TableFrame(t,TableScene.TABLE,baseline,incompleteReason="bomb animation",ambiguousPresentation=true))
        }
        for(t in 1700L..2300L step 200) observer.observe(TableFrame(t,TableScene.TABLE,baseline,groups+(Seat.PREVIOUS to fullPlay)))
        assertEquals(6,ledger.snapshot().played(Seat.PREVIOUS))
        assertEquals(1,ledger.snapshot().plays.getValue(Seat.PREVIOUS).size)
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
        assertFalse(RoundPhase.RECOVERING in phases)
    }
    @Test fun shortFourJokerScreenClearRechecksTheFullOwnHandWithoutCountingConfirmationWaitAsUnknown() {
        val (ledger,observer,own)=afterFourJokers()
        observer.observe(TableFrame(540,TableScene.UNKNOWN,null))
        observer.observe(TableFrame(720,TableScene.UNKNOWN,null))
        // Source2562..2565: 483ms UNKNOWN, then a partial hand on TABLE,
        // then the directly visible original whole hand during clear frames.
        observer.observe(TableFrame(1020,TableScene.TABLE,own.drop(2),empty().mapValues { it.value.copy(regionObserved=false) }))
        assertTrue(observer.diagnosticStatus().recovering)
        observer.observe(TableFrame(1140,TableScene.TABLE,own,empty()))
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
        assertFalse(observer.diagnosticStatus().recovering)
        assertEquals(4,ledger.snapshot().played(Seat.NEXT))
        observer.observe(TableFrame(1260,TableScene.TABLE,own,empty()))
        val pair=listOf(Card(Rank.Q,Suit.RED),Card(Rank.Q,Suit.BLACK))
        val glyphs=pair.mapIndexed { i,c -> PlayGlyph(c,.99f,GlyphBox(.75f+i*.025f,.30f,.77f+i*.025f,.35f)) }
        for(t in 1320L..1500L step 60) observer.observe(TableFrame(t,TableScene.TABLE,own,
            empty()+(Seat.NEXT to ObservedPlay(pair,.99f,glyphs))))
        assertEquals(6,ledger.snapshot().played(Seat.NEXT))
        assertEquals(2,ledger.snapshot().plays.getValue(Seat.NEXT).size)
    }
    @Test fun missingPixelsChangedHandNewTableCardsAndLongUnknownCannotUseTheFourJokerShortcut() {
        for(case in listOf("capture","hand","table","long")) {
            val (ledger,observer,own)=afterFourJokers()
            observer.observe(TableFrame(540,TableScene.UNKNOWN,null,captureGap=case=="capture"))
            if(case=="long") observer.observe(TableFrame(1080,TableScene.UNKNOWN,null))
            else observer.observe(TableFrame(1020,TableScene.TABLE,own.drop(2),empty()))
            val restored=if(case=="hand") own.drop(1)+Card(Rank.BIG,Suit.NONE) else own
            val plays=if(case=="table") empty()+(Seat.NEXT to play(Rank.J,.75f)) else empty()
            observer.observe(TableFrame(1140,TableScene.TABLE,restored,plays))
            assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase,case)
            assertNull(ledger.snapshot().remaining,case)
            assertEquals(4,ledger.snapshot().played(Seat.NEXT),case)
        }
    }
    @Test fun aPositiveResultFinishesASuspendedRoundWithoutCertifyingItsCounts() {
        val ledger=RoundLedger(); val snapshots=mutableListOf<RoundSnapshot>()
        val observer=RoundObserver(ledger) { snapshots.add(it) }; start(ledger,observer)
        val before=ledger.snapshot()
        observer.observe(TableFrame(540,TableScene.UNKNOWN,null,captureGap=true))
        observer.observe(TableFrame(1140,TableScene.UNKNOWN,null,captureGap=true))
        observer.observe(TableFrame(1260,TableScene.RESULT,null))
        val ended=snapshots.single { it.phase==RoundPhase.FINISHED }
        assertEquals(before.roundId,ended.roundId)
        assertEquals(before.plays,ended.plays)
        assertNull(ended.remaining)
        assertNotNull(ended.reason)
        assertEquals(RoundPhase.WAITING,ledger.snapshot().phase)
    }
    @Test fun shortFourJokerEffectAfterOwnExitCanReconnectThroughACompleteStableTeammateHand() {
        val (ledger,observer,own)=afterFourJokers()
        ledger.selfFinished()
        val remaining=Rank.entries.filterNot { it.isJoker }.flatMap { rank ->
            List(4) { Card(rank,Suit.RED) }+List(4) { Card(rank,Suit.BLACK) }
        }.toMutableList().apply { own.forEach { remove(it) } }.take(27)
        val before=ledger.snapshot()
        observer.observe(TableFrame(540,TableScene.UNKNOWN,null))
        observer.observe(TableFrame(720,TableScene.TEAMMATE,null,empty(),teammateHand=remaining.take(5)))
        for(t in listOf(1020L,1140L)) {
            observer.observe(TableFrame(t,TableScene.TEAMMATE,null,empty(),teammateHand=remaining))
            assertTrue(observer.diagnosticStatus().recovering)
        }
        observer.observe(TableFrame(1260,TableScene.TEAMMATE,null,empty(),teammateHand=remaining))
        assertFalse(observer.diagnosticStatus().recovering)
        assertEquals(before,ledger.snapshot())
    }
    @Test fun incompleteOrRepeatedTeammatePicturesCannotReconnectTheFourJokerEffect() {
        for(repeatTimestamp in listOf(false,true)) {
            val (ledger,observer,own)=afterFourJokers(); ledger.selfFinished()
            val cards=Rank.entries.filterNot { it.isJoker }.flatMap { rank ->
                List(4) { Card(rank,Suit.RED) }+List(4) { Card(rank,Suit.BLACK) }
            }.toMutableList().apply { own.forEach { remove(it) } }.take(if(repeatTimestamp) 27 else 26)
            observer.observe(TableFrame(540,TableScene.UNKNOWN,null))
            observer.observe(TableFrame(720,TableScene.TEAMMATE,null,empty(),teammateHand=cards))
            for(t in listOf(900L,1020L,1140L)) observer.observe(TableFrame(if(repeatTimestamp) 900 else t,
                TableScene.TEAMMATE,null,empty(),teammateHand=cards))
            observer.observe(TableFrame(1800,TableScene.TEAMMATE,null,empty(),teammateHand=null))
            assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
        }
    }
}
