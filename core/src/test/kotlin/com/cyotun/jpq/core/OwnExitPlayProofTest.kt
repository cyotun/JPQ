package com.cyotun.jpq.core

import kotlin.test.*

class OwnExitPlayProofTest {
    private val big=listOf(Card(Rank.BIG,Suit.NONE))
    private val foreign=listOf(Card(Rank.K,Suit.BLACK),Card(Rank.A,Suit.RED))
    private val deck=Rank.entries.flatMap { rank -> if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
        else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } } }
    private fun ledger(last: List<Card> = big,id: String="own-exit"): RoundLedger {
        val prefix=deck.filter { c -> last.none { it.rank==c.rank } }.take(27-last.size)
        return RoundLedger().apply {
            begin(id,prefix+last)
            commit(PlayEvent(id,"before",1,Seat.SELF,prefix))
        }
    }
    private fun play(cards: List<Card> = big,confidence: Float=.86f,shift: Float=0f)=ObservedPlay(cards,confidence,
        cards.mapIndexed { i,c -> PlayGlyph(c,confidence,GlyphBox(.45f+i*.025f+shift,.32f,.47f+i*.025f+shift,.37f)) })
    private fun frame(time: Long,scene: TableScene=TableScene.TABLE,hand: List<Card>?=foreign,
                      p: ObservedPlay?=play(),finished: Boolean=false)=TableFrame(time,scene,
        if(scene==TableScene.TEAMMATE) null else hand,
        Seat.entries.associateWith { if(it==Seat.SELF) p else ObservedPlay(emptyList(),1f) },finished,
        teammateHand=hand.takeIf { scene==TableScene.TEAMMATE },teammateHandConfidence=.9f)
    private fun seed(proof: OwnExitPlayProof,l: RoundLedger,time: Long=1000)=
        proof.observe(frame(time,hand=l.currentHand(),p=null),l.snapshot(),l.currentHand())
    private fun collect(proof: OwnExitPlayProof,l: RoundLedger,times: List<Long> = listOf(1120,1240,1360,1480)) {
        times.forEach { assertNull(proof.observe(frame(it,p=play(l.currentHand())),l.snapshot(),l.currentHand())) }
    }
    private fun finishOpposite(l: RoundLedger) {
        val outside=deck.toMutableList().apply { l.initialHand().forEach { remove(it) } }.take(27)
        l.commit(PlayEvent(l.snapshot().roundId!!,"opposite-out",2,Seat.OPPOSITE,outside))
    }

    @Test fun realLastJokerHandoffIsRecordedBeforeTheOwnHandIsClearedWithoutChangingOutsidePool() {
        val l=ledger(); val states=mutableListOf<RoundSnapshot>(); val observer=RoundObserver(l) { states+=it }
        val before=l.snapshot().remaining
        observer.observe(frame(1000,hand=big,p=null))
        listOf(1120L,1240L,1360L,1480L).forEach { observer.observe(frame(it)) }
        assertEquals(26,l.snapshot().played(Seat.SELF)); assertEquals(RoundPhase.ACTIVE,l.snapshot().phase)
        observer.observe(frame(1600,TableScene.TEAMMATE))
        assertEquals(27,l.snapshot().played(Seat.SELF)); assertEquals(RoundPhase.SELF_FINISHED,l.snapshot().phase)
        assertEquals(big,l.snapshot().plays.getValue(Seat.SELF).last().cards)
        assertEquals(before,l.snapshot().remaining); assertEquals(emptyList(),l.currentHand())
        repeat(5) { observer.observe(frame(1720+it*120L,TableScene.TEAMMATE)) }
        assertEquals(2,l.snapshot().plays.getValue(Seat.SELF).size)
        assertTrue(states.none { it.phase==RoundPhase.RECOVERING })
        assertTrue(listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).all { l.snapshot().played(it)==0 })
    }

    @Test fun aCompletionLabelWithoutThePriorWholeOwnDisplayCannotInventHistory() {
        for(count in 0..2) {
            val l=ledger(); val observer=RoundObserver(l)
            observer.observe(frame(1000,hand=big,p=null))
            repeat(count) { observer.observe(frame(1120+it*120L)) }
            observer.observe(frame(1600,TableScene.TEAMMATE))
            assertEquals(26,l.snapshot().played(Seat.SELF)); assertEquals(RoundPhase.SELF_FINISHED,l.snapshot().phase)
        }
    }

    @Test fun aDirectCompleteTeammateTransitionCanSupplyTheFourthSameSlotRead() {
        val l=ledger(); val observer=RoundObserver(l)
        observer.observe(frame(1000,hand=big,p=null))
        listOf(1120L,1240L,1360L).forEach { observer.observe(frame(it)) }
        assertEquals(26,l.snapshot().played(Seat.SELF))
        observer.observe(frame(1600,TableScene.TEAMMATE))
        assertEquals(27,l.snapshot().played(Seat.SELF))
        assertEquals(big,l.snapshot().plays.getValue(Seat.SELF).last().cards)
    }

    @Test fun aFreshAmbiguousForeignTeammateHandoffPreservesOnlyTheActiveAnchor() {
        val own=listOf(Card(Rank.J,Suit.RED),Card(Rank.J,Suit.RED),
            Card(Rank.J,Suit.RED),Card(Rank.J,Suit.BLACK))
        val l=ledger(own); val proof=OwnExitPlayProof()
        assertNull(proof.observe(frame(1000,hand=own,p=null),l.snapshot(),own))
        assertNull(proof.observe(frame(1100,hand=foreign,p=play(own)),l.snapshot(),own))
        assertNull(proof.observe(frame(1300,hand=foreign,p=play(own)),l.snapshot(),own))

        val ambiguousHandoff=frame(1400,TableScene.TEAMMATE,foreign,p=null).copy(
            ambiguousPresentation=true,
            incompleteReason="出牌归属待核对，正在重新识别")
        assertNull(proof.observe(ambiguousHandoff,l.snapshot(),own))
        l.selfFinished()
        // Further ownership ambiguity and an intervening empty lower hand may
        // clear votes, but they preserve the original fixed deadline only.
        assertNull(proof.observe(frame(1500,TableScene.TEAMMATE,foreign,p=null).copy(
            ambiguousPresentation=true,incompleteReason="出牌归属待核对，正在重新识别"),l.snapshot(),l.currentHand()))
        assertNull(proof.observe(frame(1600,TableScene.TEAMMATE,emptyList(),p=null),l.snapshot(),l.currentHand()))
        assertNull(proof.observe(frame(1680,TableScene.TEAMMATE,foreign,p=null),l.snapshot(),l.currentHand()))
        listOf(1760L,1880L,2000L).forEach {
            assertNull(proof.observe(frame(it,TableScene.TEAMMATE,foreign,p=play(own)),l.snapshot(),l.currentHand()))
        }
        val certificate=proof.observeWithCertificate(
            frame(2120,TableScene.TEAMMATE,foreign,p=play(own)),l.snapshot(),l.currentHand())
        assertNotNull(certificate)
        assertEquals(own,certificate.cards)
    }

    @Test fun aTrackedStrictSubsetBridgesForeignHandoffButRequiresFourFreshFullReads() {
        val own=listOf(Card(Rank.Q,Suit.RED),
            Card(Rank.A,Suit.RED),Card(Rank.A,Suit.RED),Card(Rank.A,Suit.RED),
            Card(Rank.A,Suit.BLACK),Card(Rank.A,Suit.BLACK),Card(Rank.A,Suit.BLACK))
        val partialCards=listOf(own[0],own[4],own[5],own[6])
        val full=ObservedPlay(own,.88f,own.mapIndexed { i,card ->
            val left=.45f+i*.025f
            PlayGlyph(card,.88f,GlyphBox(left,.32f,left+.02f,.37f))
        })
        val partial=ObservedPlay(partialCards,.88f,listOf(0,4,5,6).map { full.glyphs[it] })
        val l=ledger(own); val proof=OwnExitPlayProof()
        assertNull(seed(proof,l))
        assertNull(proof.observe(frame(1100,hand=emptyList(),p=full),l.snapshot(),own))
        assertNull(proof.observe(frame(1300,hand=foreign,p=full),l.snapshot(),own))

        // This observed strict subset retains the previously exact direct
        // anchor, but contributes no vote toward a completion.
        assertNull(proof.observe(frame(1400,hand=foreign,p=partial),l.snapshot(),own))
        assertNull(proof.observe(frame(1500,TableScene.TEAMMATE,foreign,partial).copy(
            ambiguousPresentation=true,incompleteReason="出牌归属待核对，正在重新识别"),l.snapshot(),own))
        for(t in listOf(1600L,1720L,1840L))
            assertNull(proof.observe(frame(t,hand=foreign,p=full),l.snapshot(),own))

        l.selfFinished()
        val certificate=proof.observeWithCertificate(
            frame(1960,TableScene.TEAMMATE,foreign,full),l.snapshot(),l.currentHand())
        assertNotNull(certificate)
        assertEquals(own,certificate.cards)
    }

    @Test fun aForeignHandAndTrackedPartialCannotStartOwnExitAuthorityWithoutAFullAnchor() {
        val own=listOf(Card(Rank.Q,Suit.RED),
            Card(Rank.A,Suit.RED),Card(Rank.A,Suit.RED),Card(Rank.A,Suit.RED),
            Card(Rank.A,Suit.BLACK),Card(Rank.A,Suit.BLACK),Card(Rank.A,Suit.BLACK))
        val partialCards=listOf(own[0],own[4],own[5],own[6])
        val full=ObservedPlay(own,.88f,own.mapIndexed { i,card ->
            val left=.45f+i*.025f
            PlayGlyph(card,.88f,GlyphBox(left,.32f,left+.02f,.37f))
        })
        val partial=ObservedPlay(partialCards,.88f,listOf(0,4,5,6).map { full.glyphs[it] })
        val l=ledger(own); val proof=OwnExitPlayProof()
        assertNull(seed(proof,l))
        assertNull(proof.observe(frame(1100,hand=foreign,p=partial),l.snapshot(),own))
        assertNull(proof.observe(frame(1700,hand=foreign,p=full),l.snapshot(),own))
        assertNull(proof.observe(frame(1820,hand=foreign,p=full),l.snapshot(),own))
        assertNull(proof.observe(frame(1940,hand=foreign,p=full),l.snapshot(),own))
        assertNull(proof.observe(frame(2060,hand=foreign,p=full),l.snapshot(),own))
    }

    @Test fun aQualifiedEmptyOwnHandDuringTableOwnershipAmbiguityKeepsOnlyTheFixedAnchorDeadline() {
        val own=listOf(Card(Rank.K,Suit.BLACK),Card(Rank.K,Suit.BLACK),
            Card(Rank.K,Suit.BLACK),Card(Rank.K,Suit.RED))
        val l=ledger(own); val proof=OwnExitPlayProof()
        assertNull(proof.observe(frame(1000,hand=own,p=null),l.snapshot(),own))
        assertNull(proof.observe(frame(1100,hand=emptyList(),p=play(own)),l.snapshot(),own))
        assertNull(proof.observe(frame(1240,hand=emptyList(),p=play(own)),l.snapshot(),own))
        fun ambiguousAt(t: Long)=frame(t,TableScene.TABLE,emptyList(),p=play(emptyList())).copy(
            ambiguousPresentation=true,incompleteReason="出牌归属待核对，正在重新识别")

        assertNull(proof.observe(ambiguousAt(1300),l.snapshot(),own))
        assertEquals(3300L,proof.pendingOwnCompletionDeadline(1300,l.snapshot()))
        assertNull(proof.observe(ambiguousAt(1500),l.snapshot(),own))
        assertEquals(3300L,proof.pendingOwnCompletionDeadline(1500,l.snapshot()))
        assertEquals(3300L,proof.pendingOwnCompletionDeadline(3300,l.snapshot()))
        assertNull(proof.pendingOwnCompletionDeadline(3301,l.snapshot()))

        l.selfFinished()
        assertNull(proof.observe(frame(1600,hand=emptyList(),p=null),l.snapshot(),l.currentHand()))
        assertEquals(3300L,proof.pendingOwnCompletionDeadline(1600,l.snapshot().copy(phase=RoundPhase.ACTIVE)))
        listOf(1700L,1820L,1940L).forEach {
            assertNull(proof.observeWithCertificate(frame(it,hand=emptyList(),p=play(own)),l.snapshot(),l.currentHand()))
        }
        val certificate=proof.observeWithCertificate(
            frame(2060,hand=emptyList(),p=play(own),finished=true),l.snapshot(),l.currentHand())
        assertNotNull(certificate)
        assertEquals(own,certificate.cards)

        val expiredLedger=ledger(own); val expiredProof=OwnExitPlayProof()
        assertNull(expiredProof.observe(frame(1000,hand=own,p=null),expiredLedger.snapshot(),own))
        assertNull(expiredProof.observe(frame(1100,hand=emptyList(),p=play(own)),expiredLedger.snapshot(),own))
        assertNull(expiredProof.observe(frame(1240,hand=emptyList(),p=play(own)),expiredLedger.snapshot(),own))
        assertNull(expiredProof.observe(ambiguousAt(1300),expiredLedger.snapshot(),own))
        expiredLedger.selfFinished()
        assertNull(expiredProof.observe(frame(3301,hand=emptyList(),p=play(own),finished=true),
            expiredLedger.snapshot(),expiredLedger.currentHand()))
        repeat(5) { index -> assertNull(expiredProof.observe(frame(3421L+index*120,
            hand=emptyList(),p=play(own),finished=true),expiredLedger.snapshot(),expiredLedger.currentHand())) }
    }

    @Test fun sameRankSlotColorOrConfidenceFlickerAbstainsWithoutSpendingTheAmbiguityWindow() {
        val own=listOf(Card(Rank.K,Suit.BLACK),Card(Rank.K,Suit.BLACK),
            Card(Rank.K,Suit.BLACK),Card(Rank.K,Suit.RED))
        val allRed=List(4) { Card(Rank.K,Suit.RED) }
        val l=ledger(own); val proof=OwnExitPlayProof()
        assertNull(proof.observe(frame(1000,hand=own,p=null),l.snapshot(),own))
        assertNull(proof.observe(frame(1100,hand=emptyList(),p=play(own)),l.snapshot(),own))
        assertNull(proof.observe(frame(1240,hand=emptyList(),p=play(own)),l.snapshot(),own))
        val ambiguous=frame(1300,hand=emptyList(),p=play(emptyList())).copy(
            ambiguousPresentation=true,incompleteReason="出牌归属待核对，正在重新识别")
        assertNull(proof.observe(ambiguous,l.snapshot(),own))
        l.selfFinished()

        assertNull(proof.observe(frame(1400,hand=emptyList(),p=play(allRed)),l.snapshot(),l.currentHand()))
        assertNull(proof.observe(frame(1520,hand=emptyList(),p=play(own,confidence=.79f)),l.snapshot(),l.currentHand()))
        assertEquals(3300L,proof.pendingOwnCompletionDeadline(1520,l.snapshot().copy(phase=RoundPhase.ACTIVE)))
        listOf(1640L,1760L,1880L).forEach {
            assertNull(proof.observe(frame(it,hand=emptyList(),p=play(own)),l.snapshot(),l.currentHand()))
        }
        val certificate=proof.observeWithCertificate(
            frame(2000,hand=emptyList(),p=play(own),finished=true),l.snapshot(),l.currentHand())
        assertNotNull(certificate)
        assertEquals(own,certificate.cards)
    }

    @Test fun ambiguousOwnHandContinuationRejectsMissingAnchorBadHandFaultsRankAndMovedSlots() {
        val own=listOf(Card(Rank.K,Suit.BLACK),Card(Rank.K,Suit.BLACK),
            Card(Rank.K,Suit.BLACK),Card(Rank.K,Suit.RED))
        fun proofWithAnchor(): Pair<RoundLedger,OwnExitPlayProof> {
            val l=ledger(own); val proof=OwnExitPlayProof()
            assertNull(proof.observe(frame(1000,hand=own,p=null),l.snapshot(),own))
            assertNull(proof.observe(frame(1100,hand=emptyList(),p=play(own)),l.snapshot(),own))
            assertNull(proof.observe(frame(1240,hand=emptyList(),p=play(own)),l.snapshot(),own))
            return l to proof
        }
        fun ambiguous(t: Long,hand: List<Card>?=emptyList(),captureGap: Boolean=false,
            localized: Set<Seat> = emptySet(),dealing: Boolean=false,reason: String="出牌归属待核对，正在重新识别",
            scene: TableScene=TableScene.TABLE)=frame(t,scene,hand,p=play(emptyList())).copy(
                ambiguousPresentation=true,incompleteReason=reason,captureGap=captureGap,
                localizedOverlaySeats=localized,dealingChoicesObserved=dealing)

        run {
            val l=ledger(own); val proof=OwnExitPlayProof()
            assertNull(proof.observe(ambiguous(1000),l.snapshot(),own))
            assertNull(proof.pendingOwnCompletionDeadline(1000,l.snapshot()))
        }
        val invalidFrames=listOf(
            ambiguous(1300,hand=null),
            ambiguous(1300,hand=own.take(2)),
            ambiguous(1300,captureGap=true),
            ambiguous(1300,localized=setOf(Seat.SELF)),
            ambiguous(1300,dealing=true),
            ambiguous(1300,reason="其他不完整原因"),
            ambiguous(1300,scene=TableScene.TEAMMATE),
        )
        for(bad in invalidFrames) {
            val (l,proof)=proofWithAnchor()
            assertNull(proof.observe(bad,l.snapshot(),own))
            assertNull(proof.pendingOwnCompletionDeadline(bad.timeMs,l.snapshot()))
            l.selfFinished()
            repeat(5) { index -> assertNull(proof.observe(frame(1500L+index*120L,
                TableScene.TEAMMATE,p=play(own)),l.snapshot(),l.currentHand())) }
        }

        for(changed in listOf(
            play(listOf(Card(Rank.Q,Suit.BLACK),Card(Rank.Q,Suit.BLACK),Card(Rank.Q,Suit.BLACK),Card(Rank.Q,Suit.RED))),
            play(own,shift=.08f))) {
            val (l,proof)=proofWithAnchor()
            assertNull(proof.observe(ambiguous(1300),l.snapshot(),own))
            l.selfFinished()
            assertNull(proof.observe(frame(1420,hand=emptyList(),p=changed),l.snapshot(),l.currentHand()))
            repeat(5) { index -> assertNull(proof.observe(frame(1540L+index*120L,
                hand=emptyList(),p=play(own)),l.snapshot(),l.currentHand())) }
        }
    }

    @Test fun pendingOwnCompletionDeadlineRequiresTheOriginalActiveLedgerVersion() {
        val own=listOf(Card(Rank.K,Suit.BLACK),Card(Rank.K,Suit.BLACK),
            Card(Rank.K,Suit.BLACK),Card(Rank.K,Suit.RED))
        val (l,proof)=run {
            val ledger=ledger(own); val ownProof=OwnExitPlayProof()
            assertNull(ownProof.observe(frame(1000,hand=own,p=null),ledger.snapshot(),own))
            assertNull(ownProof.observe(frame(1100,hand=emptyList(),p=play(own)),ledger.snapshot(),own))
            assertNull(ownProof.observe(frame(1240,hand=emptyList(),p=play(own)),ledger.snapshot(),own))
            assertNull(ownProof.observe(frame(1300,hand=emptyList(),p=play(emptyList())).copy(
                ambiguousPresentation=true,incompleteReason="出牌归属待核对，正在重新识别"),ledger.snapshot(),own))
            ledger to ownProof
        }
        val snap=l.snapshot()
        assertEquals(3300L,proof.pendingOwnCompletionDeadline(1400,snap))
        assertNull(proof.pendingOwnCompletionDeadline(1400,snap.copy(roundId="other")))
        assertNull(proof.pendingOwnCompletionDeadline(1400,snap.copy(phase=RoundPhase.SELF_FINISHED)))
        assertNull(proof.pendingOwnCompletionDeadline(1400,snap.copy(remaining=null)))
        val changedEvents=snap.plays.getValue(Seat.SELF).map { it.copy(eventId="changed") }
        assertNull(proof.pendingOwnCompletionDeadline(1400,snap.copy(
            plays=snap.plays+(Seat.SELF to changedEvents))))
    }

    @Test fun ambiguousHandoffCannotExtendItsDeadlineOrSeedFromTheFinishBadge() {
        val own=listOf(Card(Rank.J,Suit.RED),Card(Rank.J,Suit.RED),
            Card(Rank.J,Suit.RED),Card(Rank.J,Suit.BLACK))
        val l=ledger(own); val proof=OwnExitPlayProof()
        assertNull(proof.observe(frame(1000,hand=own,p=null),l.snapshot(),own))
        assertNull(proof.observe(frame(1100,hand=foreign,p=play(own)),l.snapshot(),own))
        assertNull(proof.observe(frame(1300,hand=foreign,p=play(own)),l.snapshot(),own))
        fun ambiguousAt(t: Long)=frame(t,TableScene.TEAMMATE,foreign,p=null).copy(
            ambiguousPresentation=true,incompleteReason="出牌归属待核对，正在重新识别")
        assertNull(proof.observe(ambiguousAt(1400),l.snapshot(),own))
        l.selfFinished()
        assertNull(proof.observe(ambiguousAt(2900),l.snapshot(),l.currentHand()))
        // This frame is after the original 2 s boundary; another ambiguity
        // cannot move that boundary forward.
        assertNull(proof.observe(ambiguousAt(3401),l.snapshot(),l.currentHand()))
        repeat(5) { index ->
            assertNull(proof.observe(frame(3500L+index*120L,TableScene.TEAMMATE,foreign,p=play(own)),
                l.snapshot(),l.currentHand()))
        }

        val noAnchor=OwnExitPlayProof(); val second=ledger(own)
        assertNull(noAnchor.observe(ambiguousAt(1000),second.snapshot(),own))
        second.selfFinished()
        repeat(5) { index ->
            assertNull(noAnchor.observe(frame(1120L+index*120L,TableScene.TEAMMATE,foreign,p=play(own)),
                second.snapshot(),second.currentHand()))
        }
    }

    @Test fun gapsMasksUnqualifiedForeignHandsAndWrongSceneStillDestroyHandoffAuthority() {
        val own=listOf(Card(Rank.J,Suit.RED),Card(Rank.J,Suit.RED),
            Card(Rank.J,Suit.RED),Card(Rank.J,Suit.BLACK))
        val faultFrames=listOf(
            frame(1400,TableScene.TEAMMATE,foreign,p=null).copy(ambiguousPresentation=true,
                incompleteReason="出牌归属待核对，正在重新识别",captureGap=true),
            frame(1400,TableScene.TEAMMATE,foreign,p=null).copy(ambiguousPresentation=true,
                incompleteReason="悬浮条遮挡",localizedOverlaySeats=setOf(Seat.SELF)),
            frame(1400,TableScene.TEAMMATE,foreign,p=null).copy(ambiguousPresentation=true,
                incompleteReason="出牌归属待核对，正在重新识别",teammateHandConfidence=.64f),
            frame(1400,TableScene.TEAMMATE,own.take(2),p=null).copy(ambiguousPresentation=true,
                incompleteReason="出牌归属待核对，正在重新识别"),
            frame(1400,TableScene.TABLE,foreign,p=null).copy(ambiguousPresentation=true,
                incompleteReason="出牌归属待核对，正在重新识别"),
            frame(1400,TableScene.TEAMMATE,foreign,p=null).copy(ambiguousPresentation=true,
                incompleteReason="其他不完整原因")
        )
        for(fault in faultFrames) {
            val l=ledger(own); val proof=OwnExitPlayProof()
            assertNull(proof.observe(frame(1000,hand=own,p=null),l.snapshot(),own))
            assertNull(proof.observe(frame(1100,hand=foreign,p=play(own)),l.snapshot(),own))
            assertNull(proof.observe(frame(1300,hand=foreign,p=play(own)),l.snapshot(),own))
            assertNull(proof.observe(fault,l.snapshot(),own))
            l.selfFinished()
            repeat(5) { index ->
                assertNull(proof.observe(frame(1500L+index*120L,TableScene.TEAMMATE,foreign,p=play(own)),
                    l.snapshot(),l.currentHand()))
            }
        }
    }

    @Test fun emptyPartialDepartureAndForeignHandoffRequireFourFreshWholeReadsWithoutOutsidePoolChange() {
        val own=listOf(Card(Rank.FIVE,Suit.RED),Card(Rank.TWO,Suit.BLACK),Card(Rank.SIX,Suit.BLACK),
            Card(Rank.FOUR,Suit.BLACK),Card(Rank.THREE,Suit.BLACK))
        val foreignEight=listOf(Card(Rank.K,Suit.BLACK),Card(Rank.A,Suit.RED),Card(Rank.Q,Suit.BLACK),
            Card(Rank.J,Suit.RED),Card(Rank.TEN,Suit.BLACK),Card(Rank.NINE,Suit.RED),
            Card(Rank.EIGHT,Suit.BLACK),Card(Rank.SEVEN,Suit.RED))
        val l=ledger(own); val proof=OwnExitPlayProof()
        val outsideBefore=l.snapshot().remaining
        assertNull(proof.observe(frame(1050,hand=own,p=null),l.snapshot(),l.currentHand()))
        // This is only an empty-hand departure hint. It must not be used as a
        // two-card event or as one of the four whole-hand confirmation votes.
        val partialBlack=listOf(own[1],own[2])
        assertNull(proof.observe(frame(1300,hand=emptyList(),p=play(partialBlack)),l.snapshot(),l.currentHand()))
        assertEquals(22,l.snapshot().played(Seat.SELF))
        assertNull(proof.observe(frame(1600,hand=foreignEight,p=play(own)),l.snapshot(),l.currentHand()))
        assertNull(proof.observe(frame(1860,hand=foreignEight,p=play(own)),l.snapshot(),l.currentHand()))
        assertEquals(22,l.snapshot().played(Seat.SELF))

        // The handoff begins only after a complete foreign lower hand has
        // been observed, and the transient empty/TEAMMATE frames clear votes.
        assertNull(proof.observe(frame(2100,hand=foreignEight,p=null),l.snapshot(),l.currentHand()))
        l.selfFinished()
        assertNull(proof.observe(frame(2360,TableScene.TEAMMATE,p=null),l.snapshot(),l.currentHand()))
        assertNull(proof.observe(frame(2600,TableScene.TEAMMATE,p=null),l.snapshot(),l.currentHand()))
        listOf(2850L,3100L,3350L).forEach {
            assertNull(proof.observe(frame(it,TableScene.TEAMMATE,hand=foreignEight,p=play(own)),l.snapshot(),l.currentHand()))
        }
        val certificate=proof.observeWithCertificate(frame(3600,TableScene.TEAMMATE,hand=foreignEight,p=play(own)),l.snapshot(),l.currentHand())
        assertNotNull(certificate)
        assertEquals(own,certificate.cards)
        l.commitLateOwnExit(certificate,PlayEvent("own-exit","own-exit:2",2,Seat.SELF,certificate.cards))
        assertEquals(27,l.snapshot().played(Seat.SELF))
        assertEquals(outsideBefore,l.snapshot().remaining)
    }

    @Test fun handoffContinuationRejectsUnprovedForeignHandTimeoutAndChangedSlots() {
        val own=listOf(Card(Rank.FIVE,Suit.RED),Card(Rank.TWO,Suit.BLACK),Card(Rank.SIX,Suit.BLACK),
            Card(Rank.FOUR,Suit.BLACK),Card(Rank.THREE,Suit.BLACK))
        val foreignEight=listOf(Card(Rank.K,Suit.BLACK),Card(Rank.A,Suit.RED),Card(Rank.Q,Suit.BLACK),
            Card(Rank.J,Suit.RED),Card(Rank.TEN,Suit.BLACK),Card(Rank.NINE,Suit.RED),
            Card(Rank.EIGHT,Suit.BLACK),Card(Rank.SEVEN,Suit.RED))

        // A partial return of the original hand is not foreign replacement.
        run {
            val l=ledger(own); val proof=OwnExitPlayProof()
            assertNull(proof.observe(frame(1000,hand=own,p=null),l.snapshot(),own))
            assertNull(proof.observe(frame(1120,hand=foreignEight,p=play(own)),l.snapshot(),own))
            assertNull(proof.observe(frame(1240,hand=own.take(2),p=null),l.snapshot(),own))
            l.selfFinished()
            repeat(5) { i -> assertNull(proof.observe(frame(1360L+i*120,TableScene.TEAMMATE,p=play(own)),l.snapshot(),l.currentHand())) }
        }

        // A whole display after 501ms cannot use the departure bridge, even
        // though the original own-hand witness is still less than one second old.
        run {
            val l=ledger(own); val proof=OwnExitPlayProof()
            assertNull(proof.observe(frame(1050,hand=own,p=null),l.snapshot(),own))
            assertNull(proof.observe(frame(1300,hand=emptyList(),p=play(listOf(own[1],own[2]))),l.snapshot(),own))
            assertNull(proof.observe(frame(1801,hand=foreignEight,p=play(own)),l.snapshot(),own))
            l.selfFinished()
            repeat(5) { i -> assertNull(proof.observe(frame(1920L+i*120,TableScene.TEAMMATE,p=play(own)),l.snapshot(),l.currentHand())) }
        }

        // The bounded 2000ms permission is measured from the empty/foreign transition;
        // after expiry it cannot be restarted by terminal reads.
        run {
            val l=ledger(own); val proof=OwnExitPlayProof()
            assertNull(proof.observe(frame(1000,hand=own,p=null),l.snapshot(),own))
            assertNull(proof.observe(frame(1120,hand=foreignEight,p=play(own)),l.snapshot(),own))
            assertNull(proof.observe(frame(1240,hand=foreignEight,p=play(own)),l.snapshot(),own))
            assertNull(proof.observe(frame(1300,hand=foreignEight,p=null),l.snapshot(),own))
            l.selfFinished()
            assertNull(proof.observe(frame(3301,TableScene.TEAMMATE,p=null),l.snapshot(),l.currentHand()))
            repeat(5) { i -> assertNull(proof.observe(frame(3420L+i*120,TableScene.TEAMMATE,p=play(own)),l.snapshot(),l.currentHand())) }
        }

        // Exactly 2000ms is still inside the bounded permission; four new
        // direct reads are required after the handoff cleared the prior votes.
        run {
            val l=ledger(own); val proof=OwnExitPlayProof()
            assertNull(proof.observe(frame(1000,hand=own,p=null),l.snapshot(),own))
            assertNull(proof.observe(frame(1120,hand=foreignEight,p=play(own)),l.snapshot(),own))
            assertNull(proof.observe(frame(1240,hand=foreignEight,p=play(own)),l.snapshot(),own))
            assertNull(proof.observe(frame(1300,hand=foreignEight,p=null),l.snapshot(),own))
            l.selfFinished()
            listOf(2800L,2960L,3130L).forEach {
                assertNull(proof.observe(frame(it,TableScene.TEAMMATE,p=play(own)),l.snapshot(),l.currentHand()))
            }
            val certificate=proof.observe(frame(3300,TableScene.TEAMMATE,p=play(own)),l.snapshot(),l.currentHand())
            assertNotNull(certificate)
        }

        // Even within the authority window, a moved glyph slot invalidates it.
        run {
            val l=ledger(own); val proof=OwnExitPlayProof()
            assertNull(proof.observe(frame(1000,hand=own,p=null),l.snapshot(),own))
            assertNull(proof.observe(frame(1120,hand=foreignEight,p=play(own)),l.snapshot(),own))
            assertNull(proof.observe(frame(1240,hand=foreignEight,p=play(own)),l.snapshot(),own))
            assertNull(proof.observe(frame(1300,hand=foreignEight,p=null),l.snapshot(),own))
            l.selfFinished()
            assertNull(proof.observe(frame(1360,TableScene.TEAMMATE,p=null),l.snapshot(),l.currentHand()))
            repeat(4) { i ->
                val shifted=play(own,shift=.08f)
                assertNull(proof.observe(frame(1480L+i*120,TableScene.TEAMMATE,p=shifted),l.snapshot(),l.currentHand()))
            }
        }
    }

    @Test fun fourDisplaysAloneNeverFinishWithoutAPositiveCompletionSignal() {
        val l=ledger(); val proof=OwnExitPlayProof(); seed(proof,l); collect(proof,l)
        assertNull(proof.observe(frame(1600),l.snapshot(),l.currentHand()))
        assertEquals(big,proof.observe(frame(1720,finished=true),l.snapshot(),l.currentHand()))
    }

    @Test fun anchoredProofContinuesAcrossSelfFinishedAndLateCommitUsesOnlyItsCapturedHand() {
        val l=ledger(); val proof=OwnExitPlayProof(); seed(proof,l)
        assertNull(proof.observeWithCertificate(frame(1120),l.snapshot(),l.currentHand()))
        assertNull(proof.observeWithCertificate(frame(1240),l.snapshot(),l.currentHand()))
        val outsideBefore=l.snapshot().remaining
        l.selfFinished()
        assertTrue(l.currentHand().isEmpty())
        assertNull(proof.observeWithCertificate(frame(1360,TableScene.TEAMMATE),l.snapshot(),l.currentHand()))
        val certificate=proof.observeWithCertificate(frame(1480,TableScene.TEAMMATE),l.snapshot(),l.currentHand())
        assertNotNull(certificate)
        assertEquals("own-exit",certificate.roundId)
        assertEquals(big,certificate.originalHand)
        assertEquals(l.snapshot().plays.getValue(Seat.SELF),certificate.ownEvents)
        assertEquals(big,certificate.cards)

        val event=PlayEvent("own-exit","own-exit:2",2,Seat.SELF,certificate.cards)
        l.commitLateOwnExit(certificate,event)
        assertEquals(27,l.snapshot().played(Seat.SELF))
        assertEquals(RoundPhase.SELF_FINISHED,l.snapshot().phase)
        assertTrue(l.currentHand().isEmpty())
        assertEquals(outsideBefore,l.snapshot().remaining)
        assertEquals(event.cards,l.snapshot().plays.getValue(Seat.SELF).last().cards)
    }

    @Test fun aConfirmedTeammateFinishAndThreeStrongSingleReadsPreserveTheLastOwnCardAtRoundEnd() {
        val l=ledger()
        val outside=deck.toMutableList().apply { l.initialHand().forEach { remove(it) } }.take(27)
        l.commit(PlayEvent("own-exit","opposite-out",2,Seat.OPPOSITE,outside))
        val before=l.snapshot().remaining
        val proof=OwnExitPlayProof(); seed(proof,l)
        assertNull(proof.observe(frame(1120),l.snapshot(),l.currentHand()))
        assertNull(proof.observe(frame(1240),l.snapshot(),l.currentHand()))
        val result=proof.observe(frame(1360,hand=emptyList(),finished=true),l.snapshot(),l.currentHand())
        assertEquals(big,result)
        l.commit(PlayEvent("own-exit","last-own",3,Seat.SELF,result!!))
        assertEquals(27,l.snapshot().played(Seat.SELF))
        assertEquals(before,l.snapshot().remaining)
        assertTrue(confirmedRoundFinished(l.snapshot()))
    }

    @Test fun aTerminalLabelCannotReduceEvidenceForAnUnfinishedTeammateOrAMultiCardHand() {
        for(last in listOf(big,List(2) { Card(Rank.TEN,Suit.BLACK) })) {
            val l=ledger(last)
            if(last.size>1) {
                val outside=deck.toMutableList().apply { l.initialHand().forEach { remove(it) } }.take(27)
                l.commit(PlayEvent("own-exit","opposite-out",2,Seat.OPPOSITE,outside))
            }
            val proof=OwnExitPlayProof(); seed(proof,l)
            for(t in listOf(1120L,1240L,1360L))
                assertNull(proof.observe(frame(t,p=play(last),finished=t==1360L),l.snapshot(),last))
        }
    }

    @Test fun aTerminalEmptyHandClosesThreeFreshWholeFiveJokerReadsAfterTeammateFinishes() {
        val last=listOf(Card(Rank.THREE,Suit.RED),Card(Rank.FOUR,Suit.RED),Card(Rank.FIVE,Suit.RED),
            Card(Rank.SIX,Suit.BLACK),Card(Rank.SEVEN,Suit.BLACK))
        val l=ledger(last); finishOpposite(l)
        val proof=OwnExitPlayProof(); seed(proof,l)
        listOf(1120L,1240L,1360L).forEach {
            assertNull(proof.observe(frame(it,p=play(last)),l.snapshot(),last))
        }
        l.selfFinished()
        val certificate=proof.observeWithCertificate(
            frame(1480,TableScene.TEAMMATE,emptyList(),p=null,finished=true),l.snapshot(),l.currentHand())
        assertNotNull(certificate)
        assertEquals(last,certificate.cards)
        assertEquals(22,l.snapshot().played(Seat.SELF))
        assertEquals(27,l.snapshot().played(Seat.OPPOSITE))
    }

    @Test fun aThirdDirectWholeHandCanBeTerminalOnlyWithTheSameEmptyHandAndFinishProof() {
        val last=listOf(Card(Rank.THREE,Suit.RED),Card(Rank.FOUR,Suit.RED),Card(Rank.FIVE,Suit.RED),
            Card(Rank.SIX,Suit.BLACK),Card(Rank.SEVEN,Suit.BLACK))
        val l=ledger(last); finishOpposite(l)
        val proof=OwnExitPlayProof(); seed(proof,l)
        assertNull(proof.observe(frame(1120,p=play(last)),l.snapshot(),last))
        assertNull(proof.observe(frame(1240,p=play(last)),l.snapshot(),last))
        l.selfFinished()
        val certificate=proof.observeWithCertificate(
            frame(1360,TableScene.TEAMMATE,emptyList(),p=play(last),finished=true),l.snapshot(),l.currentHand())
        assertNotNull(certificate)
        assertEquals(last,certificate.cards)
    }

    @Test fun terminalWholeHandNeedsThreeFreshReadsCurrentEmptyHandSelfFinishAndOppositeOut() {
        val last=listOf(Card(Rank.THREE,Suit.RED),Card(Rank.FOUR,Suit.RED),Card(Rank.FIVE,Suit.RED),
            Card(Rank.SIX,Suit.BLACK),Card(Rank.SEVEN,Suit.BLACK))
        fun runCase(votes: Int=3,finishOpp: Boolean=true,terminalTime: Long=1480,
                    finishCue: Boolean=true,terminalHand: List<Card>?=emptyList(),
                    weakAt: Long?=null,shiftAt: Long?=null,fault: String?=null): OwnExitPlayProof.Certificate? {
            val l=ledger(last)
            if(finishOpp) finishOpposite(l)
            val proof=OwnExitPlayProof(); seed(proof,l)
            val directTimes=listOf(1120L,1240L,1360L).take(votes)
            directTimes.forEach { time ->
                val confidence=if(time==weakAt) .79f else .86f
                val shift=if(time==shiftAt) .08f else 0f
                assertNull(proof.observe(frame(time,p=play(last,confidence,shift)),l.snapshot(),last))
            }
            l.selfFinished()
            val terminal=frame(terminalTime,TableScene.TEAMMATE,terminalHand,p=null,finished=finishCue).let {
                if(fault=="gap") it.copy(captureGap=true)
                else if(fault!=null) it.copy(incompleteReason=fault)
                else it
            }
            return proof.observeWithCertificate(terminal,l.snapshot(),l.currentHand())
        }
        assertNull(runCase(votes=2))
        assertNull(runCase(terminalTime=1881)) // Last strong read is older than 500 ms.
        assertNull(runCase(terminalHand=foreign))
        assertNull(runCase(finishOpp=false))
        assertNull(runCase(finishCue=false))
        assertNull(runCase(weakAt=1240))
        assertNull(runCase(shiftAt=1360))
        assertNull(runCase(fault="gap"))
        assertNull(runCase(fault="masked"))
        assertNull(runCase(terminalHand=null))
    }

    @Test fun observerUsesLateLedgerPathOnlyAfterProofCompletesInSelfFinishedPhase() {
        val l=ledger(); val observer=RoundObserver(l)
        observer.observe(frame(1000,hand=big,p=null))
        observer.observe(frame(1120))
        observer.observe(frame(1240))
        observer.observe(frame(1360,finished=true))
        assertEquals(RoundPhase.SELF_FINISHED,l.snapshot().phase)
        assertEquals(26,l.snapshot().played(Seat.SELF))
        assertTrue(l.currentHand().isEmpty())

        observer.observe(frame(1480,finished=true))
        assertEquals(27,l.snapshot().played(Seat.SELF))
        assertEquals(RoundPhase.SELF_FINISHED,l.snapshot().phase)
        assertTrue(l.currentHand().isEmpty())
    }

    @Test fun selfFinishedCannotSeedOrRestartAnOwnExitCandidate() {
        val l=ledger(); val proof=OwnExitPlayProof(); seed(proof,l)
        l.selfFinished()
        repeat(6) { index ->
            assertNull(proof.observeWithCertificate(frame(1120L+index*120L,finished=true),l.snapshot(),l.currentHand()))
        }

        val second=ledger(); val interrupted=OwnExitPlayProof(); seed(interrupted,second)
        assertNull(interrupted.observeWithCertificate(frame(1120),second.snapshot(),second.currentHand()))
        assertNull(interrupted.observeWithCertificate(frame(1240),second.snapshot(),second.currentHand()))
        second.selfFinished()
        assertNull(interrupted.observeWithCertificate(frame(1360,finished=true).copy(ambiguousPresentation=true),second.snapshot(),second.currentHand()))
        repeat(6) { index ->
            assertNull(interrupted.observeWithCertificate(frame(1480L+index*120L,finished=true),second.snapshot(),second.currentHand()))
        }
    }

    @Test fun lateCommitRejectsWrongProofHandWithoutChangingFinishedLedger() {
        val l=ledger(); l.selfFinished()
        val before=l.snapshot()
        val wrong=listOf(Card(Rank.SMALL,Suit.NONE))
        val certificate=OwnExitPlayProof.Certificate(
            "own-exit",wrong,before.plays.getValue(Seat.SELF),wrong)
        val event=PlayEvent("own-exit","own-exit:2",2,Seat.SELF,wrong)
        assertFailsWith<IllegalArgumentException> { l.commitLateOwnExit(certificate,event) }
        assertEquals(before,l.snapshot())
        assertTrue(l.currentHand().isEmpty())
    }

    @Test fun transitionRequiresCurrentStrongCompleteSameSlotEvidence() {
        val bad=listOf<ObservedPlay?>(null,play(confidence=.79f),play(shift=.08f),
            play().copy(regionObserved=false),play().copy(glyphs=emptyList()),play(listOf(Card(Rank.SMALL,Suit.NONE))))
        for(p in bad) {
            val l=ledger(); val proof=OwnExitPlayProof(); seed(proof,l); collect(proof,l)
            assertNull(proof.observe(frame(1600,TableScene.TEAMMATE,p=p),l.snapshot(),l.currentHand()))
        }
    }

    @Test fun aPartialOrUnqualifiedLowerHandDoesNotProveDeparture() {
        val last=List(2) { Card(Rank.TEN,Suit.BLACK) }
        for(hand in listOf<List<Card>?>(null,last,last.take(1))) {
            val l=ledger(last); val proof=OwnExitPlayProof(); seed(proof,l)
            listOf(1120L,1240L,1360L,1480L,1600L).forEach {
                assertNull(proof.observe(frame(it,hand=hand,p=play(last),finished=it==1600L),l.snapshot(),last))
            }
        }
        val l=ledger(); val proof=OwnExitPlayProof(); seed(proof,l); collect(proof,l)
        assertNull(proof.observe(frame(1600,TableScene.TEAMMATE).copy(teammateHandConfidence=.64f),l.snapshot(),big))
    }

    @Test fun noFreshOwnHandSeedOrReturnedOwnHandCannotReuseOldVotes() {
        for(seedTime in listOf<Long?>(null,500L)) {
            val l=ledger(); val proof=OwnExitPlayProof()
            seedTime?.let { seed(proof,l,it) }; collect(proof,l)
            assertNull(proof.observe(frame(1600,TableScene.TEAMMATE),l.snapshot(),big))
        }
        val l=ledger(); val proof=OwnExitPlayProof(); seed(proof,l); collect(proof,l)
        seed(proof,l,1500)
        assertNull(proof.observe(frame(1600,TableScene.TEAMMATE),l.snapshot(),big))
    }

    @Test fun gapsUnknownAndMaskedFramesClearTheCandidateEvenIfTheTableReturns() {
        val faults=listOf(frame(1500).copy(captureGap=true),frame(1500,TableScene.UNKNOWN),
            frame(1500).copy(ambiguousPresentation=true),frame(1500).copy(incompleteReason="masked"))
        for(fault in faults) {
            val l=ledger(); val proof=OwnExitPlayProof(); seed(proof,l); collect(proof,l)
            assertNull(proof.observe(fault,l.snapshot(),big))
            assertNull(proof.observe(frame(1600,TableScene.TEAMMATE),l.snapshot(),big))
        }
        val l=ledger(); val proof=OwnExitPlayProof(); seed(proof,l); collect(proof,l)
        assertNull(proof.observe(frame(1981,TableScene.TEAMMATE),l.snapshot(),big))
    }

    @Test fun duplicateTimesAndMovingSlotsDoNotAccumulateConfirmation() {
        val l=ledger(); val proof=OwnExitPlayProof(); seed(proof,l)
        repeat(4) { assertNull(proof.observe(frame(1120),l.snapshot(),big)) }
        assertNull(proof.observe(frame(1240,TableScene.TEAMMATE),l.snapshot(),big))
        proof.reset(); seed(proof,l)
        repeat(4) { assertNull(proof.observe(frame(1120+it*120L,p=play(shift=it*.006f)),l.snapshot(),big)) }
        assertNull(proof.observe(frame(1600,TableScene.TEAMMATE,p=play(shift=.018f)),l.snapshot(),big))
    }

    @Test fun aRoundChangeOrDifferentSeatCannotConsumeTheCandidateButAnchoredSelfFinishCan() {
        val l=ledger(); val proof=OwnExitPlayProof(); seed(proof,l); collect(proof,l)
        val new=ledger(id="next")
        assertNull(proof.observe(frame(1600,TableScene.TEAMMATE),new.snapshot(),big))
        proof.reset(); seed(proof,l); collect(proof,l); l.selfFinished()
        assertEquals(big,proof.observe(frame(1600,TableScene.TEAMMATE),l.snapshot(),l.currentHand()))
        val finished=ledger(); val finishedProof=OwnExitPlayProof(); seed(finishedProof,finished); collect(finishedProof,finished); finished.finish()
        assertNull(finishedProof.observe(frame(1600,TableScene.TEAMMATE),finished.snapshot(),finished.currentHand()))
        val other=ledger(); proof.reset(); seed(proof,other)
        repeat(4) {
            val f=frame(1120+it*120L,p=null).copy(plays=mapOf(Seat.OPPOSITE to play()))
            assertNull(proof.observe(f,other.snapshot(),big))
        }
        assertNull(proof.observe(frame(1600,TableScene.TEAMMATE),other.snapshot(),big))
    }

    @Test fun repeatedSameColorCardsRequireEveryOldPhysicalSlotAndAWholeCurrentDisplay() {
        val last=List(2) { Card(Rank.TEN,Suit.BLACK) }; val l=ledger(last)
        for(p in listOf(play(last.take(1)),play(last).let { it.copy(glyphs=it.glyphs.map { g -> g.copy(box=it.glyphs.first().box) }) })) {
            val proof=OwnExitPlayProof(); seed(proof,l); collect(proof,l)
            assertNull(proof.observe(frame(1600,TableScene.TEAMMATE,p=p),l.snapshot(),last))
        }
        val proof=OwnExitPlayProof(); seed(proof,l); collect(proof,l)
        assertEquals(last,proof.observe(frame(1600,TableScene.TEAMMATE,p=play(last)),l.snapshot(),last))
    }

    @Test fun duplicatedFirstAnchorCannotTurnOnePrintIntoTwoCards() {
        val last=List(2) { Card(Rank.TEN,Suit.BLACK) }; val l=ledger(last); val proof=OwnExitPlayProof()
        val duplicate=play(last).let { p -> p.copy(glyphs=p.glyphs.map { it.copy(box=p.glyphs.first().box) }) }
        seed(proof,l)
        listOf(1120L,1240L,1360L,1480L).forEach {
            assertNull(proof.observe(frame(it,p=duplicate),l.snapshot(),last))
        }
        assertNull(proof.observe(frame(1600,TableScene.TEAMMATE,p=duplicate),l.snapshot(),last))
    }
}
