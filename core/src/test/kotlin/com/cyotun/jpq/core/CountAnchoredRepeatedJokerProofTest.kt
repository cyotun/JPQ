package com.cyotun.jpq.core

import kotlin.test.*

class CountAnchoredRepeatedJokerProofTest {
    private val seat=Seat.OPPOSITE
    private val box=GlyphBox(.4768f,.1576f,.4884f,.2351f)
    private val id="joker:2"
    private fun snapshot(rank: Rank=Rank.BIG): RoundSnapshot {
        val card=Card(rank,if(rank.isJoker) Suit.NONE else Suit.BLACK)
        val deck=Rank.entries.flatMap { r -> if(r.isJoker) List(2) { Card(r,Suit.NONE) }
            else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(r,suit) } } }.toMutableList()
        assertTrue(deck.remove(card));assertTrue(deck.remove(card))
        val hand=deck.take(27);val outside=deck.drop(27)
        val ledger=RoundLedger();ledger.begin("joker",hand)
        ledger.commit(PlayEvent("joker","joker:1",1,seat,outside.take(23)))
        ledger.commit(PlayEvent("joker",id,2,seat,listOf(card)))
        return ledger.snapshot()
    }
    private fun f(t: Long,count: Int=3,source: Long=t,visible: Boolean=false,rank: Rank=Rank.BIG,
                  confidence: Float=.867f,observed: Boolean=visible,region: GlyphBox=box): TableFrame {
        val card=Card(rank,if(rank.isJoker) Suit.NONE else Suit.BLACK)
        val play=if(visible) ObservedPlay(listOf(card),confidence,listOf(PlayGlyph(card,confidence,region)),regionObserved=observed)
            else ObservedPlay(emptyList(),1f,regionObserved=observed)
        return TableFrame(t,TableScene.TABLE,null,mapOf(seat to play),
            remainingHandCounts=mapOf(seat to RemainingHandCount(count,source)))
    }
    private fun baseline(p: CountAnchoredRepeatedSingleProof,s: RoundSnapshot) {
        assertNull(p.observe(f(1000),s,seat,id));assertNull(p.observe(f(1300),s,seat,id))
    }
    private fun finalFrames(p: CountAnchoredRepeatedSingleProof,s: RoundSnapshot,start: Long=1500,rank: Rank=Rank.BIG): ObservedPlay? {
        var result: ObservedPlay?=null
        for(t in start..start+300 step 100) result=p.observe(f(t,2,visible=true,rank=rank),s,seat,id) ?: result
        return result
    }
    @Test fun bothJokersRequireDirectStrongFramesAndIndependentCountDropWithoutInventingASuit() {
        for(rank in listOf(Rank.BIG,Rank.SMALL)) {
            val p=CountAnchoredRepeatedSingleProof();val s=snapshot(rank);baseline(p,s)
            val result=assertNotNull(finalFrames(p,s,rank=rank))
            assertEquals(listOf(Card(rank,Suit.NONE)),result.cards);assertNull(result.singleCardIdentity)
            for(t in 1900L..2500L step 100) assertNull(p.observe(f(t,2,visible=true,rank=rank),s,seat,id))
            p.clear();baseline(p,s) // old/out-of-order sources cannot reopen a delivered version
            assertNull(finalFrames(p,s,start=2800,rank=rank))
        }
    }
    @Test fun reviewedThreeToTwoTimingCanArriveAfterTheFirstStrongJokerFrame() {
        val p=CountAnchoredRepeatedSingleProof();val s=snapshot();baseline(p,s)
        assertNull(p.observe(f(1500,source=1300,visible=true),s,seat,id))
        assertNull(p.observe(f(1600,source=1300,visible=true),s,seat,id))
        assertNull(p.observe(f(1700,2,visible=true),s,seat,id))
        assertNotNull(p.observe(f(1800,2,visible=true),s,seat,id))
    }
    @Test fun oldPersistentJokerWithoutADecrementCannotBecomeAnotherPlay() {
        val p=CountAnchoredRepeatedSingleProof();val s=snapshot();baseline(p,s)
        for(t in 1500L..4000L step 100) assertNull(p.observe(f(t,visible=true),s,seat,id))
    }
    @Test fun cachedBaselineOrOnePostSourceCannotCertifyARepeatedJoker() {
        val s=snapshot();val p=CountAnchoredRepeatedSingleProof()
        assertNull(p.observe(f(1000),s,seat,id));assertNull(p.observe(f(1300,source=1000),s,seat,id))
        assertNull(finalFrames(p,s))
        val q=CountAnchoredRepeatedSingleProof();baseline(q,s)
        for(t in 1500L..2000L step 100) assertNull(q.observe(f(t,2,source=1500,visible=true),s,seat,id))
    }
    @Test fun badgeDropBeforeTheFaceArrivesCannotGuessAJoker() {
        val p=CountAnchoredRepeatedSingleProof();val s=snapshot();baseline(p,s)
        assertNull(p.observe(f(1400,2),s,seat,id));assertNull(finalFrames(p,s))
    }
    @Test fun wrongFaceWeakConfidenceBadGeometryOrHiddenRegionBreakTheChain() {
        val s=snapshot()
        for(bad in listOf(f(1600,visible=true,rank=Rank.SMALL),f(1600,visible=true,confidence=.849f),
            f(1600,visible=true,observed=false),f(1600,visible=true,region=GlyphBox(Float.NaN,.1f,.5f,.2f)))) {
            val p=CountAnchoredRepeatedSingleProof();baseline(p,s)
            assertNull(p.observe(f(1500,source=1300,visible=true),s,seat,id))
            assertNull(p.observe(bad,s,seat,id));assertNull(finalFrames(p,s,start=1700))
        }
    }
    @Test fun faultsSceneChangeAndEventVersionCannotBorrowTheBaseline() {
        val s=snapshot()
        for(bad in listOf(f(1600).copy(captureGap=true),f(1600).copy(ambiguousPresentation=true),
            f(1600).copy(incompleteReason="masked"),f(1600).copy(scene=TableScene.TEAMMATE))) {
            val p=CountAnchoredRepeatedSingleProof();baseline(p,s)
            assertNull(p.observe(f(1500,source=1300,visible=true),s,seat,id));assertNull(p.observe(bad,s,seat,id))
            assertNull(finalFrames(p,s,start=1700))
        }
        val p=CountAnchoredRepeatedSingleProof();baseline(p,s)
        val changed=s.copy(plays=mapOf(seat to s.plays.getValue(seat).map { if(it.eventId==id) it.copy(eventId="another") else it }))
        assertNull(finalFrames(p,changed))
    }
    @Test fun mismatchedJokerGlyphAndOrdinaryRankWithoutCornerIdentityRemainIneligible() {
        val s=snapshot();val p=CountAnchoredRepeatedSingleProof();baseline(p,s)
        for(t in 1500L..1800L step 100) {
            val base=f(t,2,visible=true);val c=Card(Rank.BIG,Suit.NONE)
            val invalid=base.copy(plays=mapOf(seat to ObservedPlay(listOf(c),.99f,listOf(PlayGlyph(Card(Rank.SMALL,Suit.NONE),.99f,box)))))
            assertNull(p.observe(invalid,s,seat,id))
        }
        val q=CountAnchoredRepeatedSingleProof();val ordinary=snapshot(Rank.SIX);baseline(q,ordinary)
        assertNull(finalFrames(q,ordinary,rank=Rank.SIX))
    }
}
