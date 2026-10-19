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
        val result=proof.observe(frame(1360,finished=true),l.snapshot(),l.currentHand())
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
