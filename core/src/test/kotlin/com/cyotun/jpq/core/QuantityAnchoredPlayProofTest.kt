package com.cyotun.jpq.core

import kotlin.test.*

class QuantityAnchoredPlayProofTest {
    private val cards=listOf(Card(Rank.Q,Suit.RED),Card(Rank.Q,Suit.BLACK))+List(3) { Card(Rank.J,Suit.BLACK) }
    private val deck=Rank.entries.flatMap { r -> if(r.isJoker) List(2) { Card(r,Suit.NONE) }
        else listOf(Suit.RED,Suit.BLACK).flatMap { s -> List(4) { Card(r,s) } } }
    private fun ledger(id: String="quantity"): RoundLedger {
        val initial=deck.filter { it.rank !in setOf(Rank.A,Rank.Q,Rank.J) }.take(27)
        val outside=deck.toMutableList().apply { initial.forEach { remove(it) } }
        return RoundLedger().apply {
            begin(id,initial)
            commit(PlayEvent(id,"next-before",1,Seat.NEXT,outside.filter { it.rank !in setOf(Rank.A,Rank.Q,Rank.J) }.take(16)))
            selfFinished()
        }
    }
    private fun play(confidence: Float=.86f,shift: Float=0f,c: List<Card> = cards)=ObservedPlay(c,confidence,
        c.mapIndexed { i,card -> PlayGlyph(card,confidence,GlyphBox(.70f+i*.022f+shift,.28f,.718f+i*.022f+shift,.32f)) })
    private fun frame(t: Long,p: ObservedPlay?=play(),count: Int?=6,source: Long=t)=TableFrame(t,TableScene.TEAMMATE,null,
        Seat.entries.associateWith { if(it==Seat.NEXT) p else ObservedPlay(emptyList(),1f) },
        remainingHandCounts=if(count==null) emptyMap() else mapOf(Seat.NEXT to RemainingHandCount(count,source)))
    private fun empty(t: Long,source: Long=t,count: Int?=6)=frame(t,ObservedPlay(emptyList(),1f),count,source)
    private fun two(proof: QuantityAnchoredPlayProof,s: RoundSnapshot) {
        assertNull(proof.observe(frame(1000,play(.75f),source=620),s,Seat.NEXT,900))
        assertNull(proof.observe(frame(1168,source=1000),s,Seat.NEXT,900))
    }

    @Test fun originalTwoFullReadingsAndPostArrivalCountsCanFinishWhileTheDeskIsStillPositivelyEmpty() {
        val s=ledger().snapshot(); val proof=QuantityAnchoredPlayProof(); two(proof,s)
        assertNull(proof.observe(empty(1383,1000),s,Seat.NEXT,900))
        assertNull(proof.observe(empty(1551,1000),s,Seat.NEXT,900))
        val result=assertNotNull(proof.observe(empty(1733,1383),s,Seat.NEXT,900))
        assertEquals(cards,result.cards)
        assertNull(proof.observe(empty(1800,1600),s,Seat.NEXT,900))
    }

    @Test fun shortQJPlayIsCommittedOnceBeforeTheFollowingAcesWithoutRelaxingOrdinaryVoting() {
        val l=ledger(); val observer=RoundObserver(l); val before=l.snapshot().remaining!!
        observer.observe(empty(500,count=null)); observer.observe(empty(700,count=null))
        observer.observe(frame(1000,play(.75f),source=620)); observer.observe(frame(1168,source=1000))
        assertEquals(16,l.snapshot().played(Seat.NEXT))
        observer.observe(empty(1383,1000)); observer.observe(empty(1551,1000)); observer.observe(empty(1733,1383))
        assertEquals(21,l.snapshot().played(Seat.NEXT))
        assertEquals(cards,l.snapshot().plays.getValue(Seat.NEXT).last().cards)
        assertEquals(before.getValue(Rank.Q)-2,l.snapshot().remaining!![Rank.Q])
        assertEquals(before.getValue(Rank.J)-3,l.snapshot().remaining!![Rank.J])
        val aces=List(2) { Card(Rank.A,Suit.RED) }
        for(t in listOf(1850L,1970L,2090L,2210L)) observer.observe(frame(t,play(c=aces),count=null))
        assertEquals(23,l.snapshot().played(Seat.NEXT))
        assertEquals(3,l.snapshot().plays.getValue(Seat.NEXT).size)
    }

    @Test fun oneFullFrameOrTwoWeakFramesCannotUseTheCountShortcut() {
        val s=ledger().snapshot()
        for(single in listOf(true,false)) {
            val proof=QuantityAnchoredPlayProof()
            proof.observe(frame(1000,play(.75f)),s,Seat.NEXT,900)
            if(!single) proof.observe(frame(1168,play(.75f)),s,Seat.NEXT,900)
            assertNull(proof.observe(empty(1383,1168),s,Seat.NEXT,900))
            assertNull(proof.observe(empty(1551,1383),s,Seat.NEXT,900))
        }
    }

    @Test fun preArrivalOrOneRepeatedCountSourceCannotSupplyTwoVotes() {
        val s=ledger().snapshot()
        for(source in listOf(900L,1000L)) {
            val proof=QuantityAnchoredPlayProof(); two(proof,s)
            for(t in listOf(1300L,1400L)) assertNull(proof.observe(empty(t,source),s,Seat.NEXT,900))
        }
    }

    @Test fun freshWrongQuantityBackwardSourceAndFutureSourceInvalidateTheCandidate() {
        val s=ledger().snapshot()
        val bad=listOf(empty(1383,1168,count=5),empty(1383,1168,count=7),empty(1383,990),empty(1383,1400))
        for(f in bad) {
            val proof=QuantityAnchoredPlayProof(); two(proof,s)
            assertNull(proof.observe(f,s,Seat.NEXT,900))
            assertNull(proof.observe(empty(1551,1383),s,Seat.NEXT,900))
        }
    }

    @Test fun newCardsBeforeCountCompletionCannotReceiveTheOldDecrement() {
        val s=ledger().snapshot(); val proof=QuantityAnchoredPlayProof(); two(proof,s)
        proof.observe(empty(1383,1000),s,Seat.NEXT,900)
        assertNull(proof.observe(frame(1500,play(c=List(2) { Card(Rank.A,Suit.RED) }),source=1400),s,Seat.NEXT,900))
        assertNull(proof.observe(empty(1650,1500),s,Seat.NEXT,900))
    }

    @Test fun blindDeskCaptureGapAmbiguityAndMaskCannotBeAnEmptyBoundary() {
        val s=ledger().snapshot()
        val bad=listOf(empty(1383,1168).copy(captureGap=true),empty(1383,1168).copy(ambiguousPresentation=true),
            empty(1383,1168).copy(incompleteReason="masked"),frame(1383,ObservedPlay(emptyList(),1f,regionObserved=false),source=1168))
        for(f in bad) {
            val proof=QuantityAnchoredPlayProof(); two(proof,s)
            assertNull(proof.observe(f,s,Seat.NEXT,900))
            assertNull(proof.observe(empty(1551,1383),s,Seat.NEXT,900))
        }
    }

    @Test fun movedPartialDuplicateSlotsAndLowGlyphConfidenceCannotBeTheSecondCompleteReading() {
        val s=ledger().snapshot()
        val duplicate=play().let { it.copy(glyphs=it.glyphs.map { g -> g.copy(box=it.glyphs.first().box) }) }
        val bad=listOf(play(shift=.05f),play(c=cards.take(4)),duplicate,
            play().let { it.copy(glyphs=it.glyphs.mapIndexed { i,g -> if(i==0) g.copy(confidence=.69f) else g }) })
        for(p in bad) {
            val proof=QuantityAnchoredPlayProof()
            proof.observe(frame(1000,play(.75f)),s,Seat.NEXT,900)
            assertNull(proof.observe(frame(1168,p),s,Seat.NEXT,900))
            assertNull(proof.observe(empty(1383,1168),s,Seat.NEXT,900))
        }
    }

    @Test fun aMissingOldBoundaryWrongSeatOrExpiredCandidateDoesNotGuess() {
        val s=ledger().snapshot()
        for(boundary in listOf<Long?>(null,-600,1000)) {
            val proof=QuantityAnchoredPlayProof()
            proof.observe(frame(1000),s,Seat.NEXT,boundary)
            assertNull(proof.observe(frame(1168),s,Seat.NEXT,boundary))
        }
        val proof=QuantityAnchoredPlayProof(); two(proof,s)
        assertNull(proof.observe(empty(1733,1600),s,Seat.PREVIOUS,900))
        val late=QuantityAnchoredPlayProof()
        late.observe(frame(1000,play(.75f),count=null),s,Seat.NEXT,900)
        late.observe(frame(1168,count=null),s,Seat.NEXT,900)
        late.observe(empty(1500,1490),s,Seat.NEXT,900)
        late.observe(empty(1800,1490),s,Seat.NEXT,900)
        assertNull(late.observe(empty(2001,1800),s,Seat.NEXT,900))
    }

    @Test fun aRoundEventPhaseOrSceneChangeCannotBorrowTheCandidate() {
        val s=ledger().snapshot()
        for(changed in listOf(s.copy(roundId="next"),s.copy(phase=RoundPhase.ACTIVE),
            s.copy(plays=s.plays+mapOf(Seat.NEXT to (s.plays.getValue(Seat.NEXT)+PlayEvent("quantity","new",2,Seat.NEXT,listOf(Card(Rank.SIX,Suit.RED)))))))) {
            val proof=QuantityAnchoredPlayProof(); two(proof,s)
            assertNull(proof.observe(empty(1383,1168),changed,Seat.NEXT,900))
            assertNull(proof.observe(empty(1551,1383),changed,Seat.NEXT,900))
        }
        val proof=QuantityAnchoredPlayProof(); two(proof,s)
        assertNull(proof.observe(empty(1383,1168).copy(scene=TableScene.TABLE),s,Seat.NEXT,900))
    }

    @Test fun aDifferentSeatsCountOrOnePositiveEmptyCannotCompleteTheProof() {
        val s=ledger().snapshot(); val proof=QuantityAnchoredPlayProof(); two(proof,s)
        val other=empty(1383,1168).copy(remainingHandCounts=mapOf(Seat.OPPOSITE to RemainingHandCount(6,1168)))
        assertNull(proof.observe(other,s,Seat.NEXT,900))
        val onlyOne=QuantityAnchoredPlayProof(); two(onlyOne,s)
        assertNull(onlyOne.observe(empty(1383,1168),s,Seat.NEXT,900))
    }

    @Test fun aStrictSupersetCountCandidateCannotCrossAnUnrelatedLedgerEvent() {
        val l=RoundLedger()
        val initial=deck.take(27)
        val outside=deck.drop(27)
        val oldCard=outside.first()
        val added=outside[1]
        l.begin("strict-superset",initial)
        l.commit(PlayEvent("strict-superset","strict-superset:1",1,Seat.NEXT,listOf(oldCard)))
        val before=l.snapshot()
        val oldGlyph=PlayGlyph(oldCard,.95f,GlyphBox(.70f,.28f,.74f,.34f))
        val prior=QuantityAnchoredPlayProof.PriorPresentation("strict-superset:1",listOf(oldCard),listOf(oldGlyph))
        val expanded=listOf(oldCard,added)
        val group=ObservedPlay(expanded,.95f,expanded.mapIndexed { i,c ->
            PlayGlyph(c,.95f,GlyphBox(.30f+i*.06f,.28f,.34f+i*.06f,.34f))
        })
        fun observe(proof: QuantityAnchoredPlayProof,s: RoundSnapshot,time: Long,count: Int)=proof.observeStrictSuperset(
            TableFrame(time,TableScene.TABLE,null,Seat.entries.associateWith { if(it==Seat.NEXT) group else ObservedPlay(emptyList(),1f) },
                remainingHandCounts=mapOf(Seat.NEXT to RemainingHandCount(count,time))),
            s,Seat.NEXT,prior)
        val proof=QuantityAnchoredPlayProof()
        assertNull(observe(proof,before,1000,26)); assertNull(observe(proof,before,1100,26))
        val otherCard=outside[2]
        l.commit(PlayEvent("strict-superset","strict-superset:2",2,Seat.PREVIOUS,listOf(otherCard)))
        val after=l.snapshot()
        assertNull(observe(proof,after,1200,24)); assertNull(observe(proof,after,1300,24))
        assertEquals(1,after.played(Seat.NEXT))
    }

    @Test fun anUnresolvedStrictSupersetCandidateExpiresOnceForItsLedgerVersion() {
        val l=RoundLedger(); val initial=deck.take(27); val outside=deck.drop(27)
        val oldCard=outside.first(); val added=outside[1]
        l.begin("expiring-superset",initial)
        l.commit(PlayEvent("expiring-superset","expiring-superset:1",1,Seat.NEXT,listOf(oldCard)))
        val snapshot=l.snapshot()
        val prior=QuantityAnchoredPlayProof.PriorPresentation("expiring-superset:1",listOf(oldCard),
            listOf(PlayGlyph(oldCard,.95f,GlyphBox(.70f,.28f,.74f,.34f))))
        val cards=listOf(oldCard,added)
        val group=ObservedPlay(cards,.95f,cards.mapIndexed { i,c ->
            PlayGlyph(c,.95f,GlyphBox(.30f+i*.06f,.28f,.34f+i*.06f,.34f))
        })
        val proof=QuantityAnchoredPlayProof()
        fun offer(t: Long,count: Int) = proof.observeStrictSuperset(
            TableFrame(t,TableScene.TABLE,null,mapOf(Seat.NEXT to group),
                remainingHandCounts=mapOf(Seat.NEXT to RemainingHandCount(count,t))),
            snapshot,Seat.NEXT,prior)
        for(t in 1000L..3300L step 100) assertNull(offer(t,26))
        for(t in 3400L..3800L step 100) assertNull(offer(t,24))
    }
}
