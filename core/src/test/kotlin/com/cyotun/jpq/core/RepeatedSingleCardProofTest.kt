package com.cyotun.jpq.core

import kotlin.test.*

class RepeatedSingleCardProofTest {
    private val card=Card(Rank.A,Suit.BLACK)
    private val glyph=PlayGlyph(card,.95f,GlyphBox(.47f,.16f,.49f,.20f))
    private fun play(time: Long,suit: Suit=Suit.CLUB)=ObservedPlay(listOf(card),.95f,listOf(glyph),
        singleCardIdentity=SingleCardIdentity(Card(Rank.A,suit),.86f,.15f,time))
    private fun feed(p: RepeatedSingleCardProof,time: Long,suit: Suit=Suit.CLUB,
                     remaining: RemainingHandCount?=null,event: String="a-1",round: String="r")=
        p.observe(time,round,event,2,play(time,suit),remaining)
    private fun anchor(p: RepeatedSingleCardProof) {
        assertFalse(feed(p,1000)); assertFalse(feed(p,1266)); assertFalse(feed(p,1532))
    }
    @Test fun differentBlackSuitsProveOneAdditionalSingleWithoutATableClear() {
        val p=RepeatedSingleCardProof(); anchor(p)
        assertFalse(feed(p,1798,Suit.SPADE)); assertFalse(feed(p,2064,Suit.SPADE))
        assertTrue(feed(p,2330,Suit.SPADE))
        repeat(5) { assertFalse(feed(p,2600+it*266L,Suit.SPADE)) }
    }
    @Test fun theSamePhysicalFaceNeverProvesADuplicate() {
        val p=RepeatedSingleCardProof(); anchor(p)
        repeat(8) { assertFalse(feed(p,1798+it*266L)) }
    }
    @Test fun aSuitFlickerOrTwoReadingsCannotProveADuplicate() {
        val p=RepeatedSingleCardProof(); anchor(p)
        assertFalse(feed(p,1798,Suit.SPADE)); assertFalse(feed(p,2064,Suit.SPADE))
        assertFalse(feed(p,2330)); assertFalse(feed(p,2596,Suit.SPADE))
    }
    @Test fun cachedSourceOrRepeatedFrameDoesNotVote() {
        val p=RepeatedSingleCardProof(); anchor(p)
        assertFalse(feed(p,1798,Suit.SPADE)); assertFalse(feed(p,1798,Suit.SPADE))
        assertFalse(p.observe(2064,"r","a-1",2,play(1798,Suit.SPADE),null))
        assertFalse(feed(p,2330,Suit.SPADE)); assertFalse(feed(p,2596,Suit.SPADE))
    }
    @Test fun weakUnobservedOrMismatchedIdentityBreaksConfirmation() {
        for(bad in listOf(
            play(2064,Suit.SPADE).copy(regionObserved=false),
            play(2064,Suit.SPADE).copy(singleCardIdentity=SingleCardIdentity(Card(Rank.A,Suit.SPADE),.79f,.15f,2064)),
            play(2064,Suit.SPADE).copy(singleCardIdentity=SingleCardIdentity(Card(Rank.A,Suit.SPADE),.86f,.11f,2064)),
            play(2064,Suit.SPADE).copy(singleCardIdentity=SingleCardIdentity(Card(Rank.K,Suit.SPADE),.86f,.15f,2064)),
            play(2064,Suit.SPADE).copy(singleCardIdentity=SingleCardIdentity(Card(Rank.A,Suit.HEART),.86f,.15f,2064)),
        )) {
            val p=RepeatedSingleCardProof(); anchor(p)
            assertFalse(feed(p,1798,Suit.SPADE)); assertFalse(p.observe(2064,"r","a-1",2,bad,null))
            assertFalse(feed(p,2330,Suit.SPADE)); assertFalse(feed(p,2596,Suit.SPADE))
        }
    }
    @Test fun sourceFromTheFutureOrTooOldIsRejected() {
        for(source in listOf(2065L,1000L)) {
            val p=RepeatedSingleCardProof(); anchor(p)
            assertFalse(feed(p,1798,Suit.SPADE))
            assertFalse(p.observe(2064,"r","a-1",2,play(source,Suit.SPADE),null))
            assertFalse(feed(p,2330,Suit.SPADE)); assertFalse(feed(p,2596,Suit.SPADE))
        }
    }
    @Test fun differentGeometryMustStartANewThreeVoteTrack() {
        val p=RepeatedSingleCardProof(); anchor(p)
        assertFalse(feed(p,1798,Suit.SPADE)); assertFalse(feed(p,2064,Suit.SPADE))
        val moved=play(2330,Suit.SPADE).copy(glyphs=listOf(glyph.copy(box=GlyphBox(.57f,.16f,.59f,.20f))))
        assertFalse(p.observe(2330,"r","a-1",2,moved,null))
    }
    @Test fun unchangedBadgeVetoesAPhysicalBoundaryUntilFreshAgreement() {
        val p=RepeatedSingleCardProof(); anchor(p)
        assertFalse(feed(p,1798,Suit.SPADE)); assertFalse(feed(p,2064,Suit.SPADE))
        assertFalse(feed(p,2330,Suit.SPADE,RemainingHandCount(2,2330)))
        assertTrue(feed(p,2596,Suit.SPADE,RemainingHandCount(1,2596)))
    }
    @Test fun aNewRoundOrEventCannotBorrowTheOldFace() {
        for(newRound in listOf(true,false)) {
            val p=RepeatedSingleCardProof(); anchor(p)
            repeat(4) { i -> assertFalse(feed(p,1798+i*266L,Suit.SPADE,
                event=if(newRound) "a-1" else "a-2",round=if(newRound) "r-2" else "r")) }
        }
    }
    @Test fun aGlobalClearDiscardsOldPhysicalEvidence() {
        val p=RepeatedSingleCardProof(); anchor(p); p.clear()
        repeat(4) { assertFalse(feed(p,1798+it*266L,Suit.SPADE)) }
    }
    @Test fun shortEffectsMayRetainTheAcceptedFaceButNotVotes() {
        val p=RepeatedSingleCardProof(); anchor(p); p.unobserved(1798)
        assertFalse(feed(p,2064,Suit.SPADE)); assertFalse(feed(p,2330,Suit.SPADE))
        assertTrue(feed(p,2596,Suit.SPADE))
    }
    @Test fun anOldFaceExpiresAfterAnUnobservedGap() {
        val p=RepeatedSingleCardProof(); anchor(p); p.unobserved(3600)
        repeat(4) { assertFalse(feed(p,3866+it*266L,Suit.SPADE)) }
    }
}
