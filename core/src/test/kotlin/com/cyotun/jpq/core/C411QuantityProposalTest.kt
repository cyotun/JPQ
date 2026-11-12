package com.cyotun.jpq.core

import kotlin.test.*

class C411QuantityProposalTest {
    private val deck=Rank.entries.flatMap { r -> if(r.isJoker) List(2) { Card(r,Suit.NONE) }
        else listOf(Suit.RED,Suit.BLACK).flatMap { s -> List(4) { Card(r,s) } } }
    private val snapshot=RoundLedger().apply { begin("proposal",deck.filter { it.rank!=Rank.Q }.take(27)) }.snapshot()
    private val cards=List(2) { Card(Rank.Q,Suit.RED) }
    private val play=ObservedPlay(cards,.9f,cards.mapIndexed { i,c ->
        PlayGlyph(c,.9f,GlyphBox(.7f+i*.03f,.28f,.72f+i*.03f,.32f)) })
    private fun frame(t: Long,p: ObservedPlay=play)=TableFrame(t,TableScene.TABLE,null,mapOf(Seat.NEXT to p),
        remainingHandCounts=mapOf(Seat.NEXT to RemainingHandCount(if(p.cards.isEmpty()) 27 else 25,t)))
    private fun seed(proof: QuantityAnchoredPlayProof): ObservedPlay {
        val empty=ObservedPlay(emptyList(),1f)
        assertNull(proof.observe(frame(500,empty),snapshot,Seat.NEXT,null))
        assertNull(proof.observe(frame(700,empty),snapshot,Seat.NEXT,null))
        assertNull(proof.observe(frame(900),snapshot,Seat.NEXT,700))
        return assertNotNull(proof.observe(frame(1100),snapshot,Seat.NEXT,700))
    }

    @Test fun aRejectedProposalIsReturnedAgainUntilTheExactLatestValueCommits() {
        val proof=QuantityAnchoredPlayProof();val first=seed(proof)
        proof.commitAccepted(first.copy()) // equal value is not the issued proposal.
        val second=assertNotNull(proof.observe(frame(1200),snapshot,Seat.NEXT,700))
        proof.commitAccepted(first) // a later observation revoked this token.
        val third=assertNotNull(proof.observe(frame(1300),snapshot,Seat.NEXT,700))
        assertEquals(second.cards,third.cards)
        proof.commitAccepted(third)
        assertNull(proof.observe(frame(1400),snapshot,Seat.NEXT,700))
    }

    @Test fun clearRevokesTheTokenWithoutBlockingAFreshCorrectlyProvedGroup() {
        val proof=QuantityAnchoredPlayProof();val old=seed(proof)
        proof.clear();proof.commitAccepted(old)
        assertNull(proof.observe(frame(1200),snapshot,Seat.NEXT,1100))
        val fresh=assertNotNull(proof.observe(frame(1400),snapshot,Seat.NEXT,1100))
        proof.commitAccepted(fresh)
        assertNull(proof.observe(frame(1500),snapshot,Seat.NEXT,1100))
    }
}
