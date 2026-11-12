package com.cyotun.jpq.core

import kotlin.test.*

class C411QuantityCountSourceTest {
    private val cards=listOf(Card(Rank.Q,Suit.RED),Card(Rank.Q,Suit.BLACK))+
        List(3) { Card(Rank.J,Suit.BLACK) }
    private val deck=Rank.entries.flatMap { rank ->
        if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
        else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } }
    }
    private val initial=deck.filter { it.rank !in setOf(Rank.A,Rank.Q,Rank.J) }.take(27)
    private val outside=deck.toMutableList().apply { initial.forEach { assertTrue(remove(it)) } }
    private val prefix=outside.filter { it.rank !in setOf(Rank.A,Rank.Q,Rank.J) }.take(16)
    private val snapshot=RoundLedger().apply {
        begin("quantity-source",initial)
        commit(PlayEvent("quantity-source","old",1,Seat.NEXT,prefix))
        selfFinished()
    }.snapshot()
    private fun play(cards: List<Card> = this.cards,shift: Float=0f)=ObservedPlay(cards,.9f,
        cards.mapIndexed { i,c -> PlayGlyph(c,.9f,
            GlyphBox(.30f+i*.025f+shift,.28f,.32f+i*.025f+shift,.32f)) })
    private fun frame(time: Long,count: Int=6,source: Long=time,p: ObservedPlay=play(),
        scene: TableScene=TableScene.TEAMMATE)=TableFrame(time,scene,null,
        mapOf(Seat.NEXT to p),remainingHandCounts=mapOf(Seat.NEXT to RemainingHandCount(count,source)))
    private fun empty(time: Long,count: Int=11,source: Long=time,
        scene: TableScene=TableScene.TEAMMATE)=frame(time,count,source,ObservedPlay(emptyList(),1f),scene)
    private fun offer(proof: QuantityAnchoredPlayProof,frame: TableFrame,
        s: RoundSnapshot=snapshot,boundary: Long?=900)=proof.observe(frame,s,Seat.NEXT,boundary)

    @Test fun contradictoryPreSourceCannotBecomeFreshAfterItsLocalVotesReset() {
        val proof=QuantityAnchoredPlayProof()
        assertNull(offer(proof,empty(700),boundary=null))
        assertNull(offer(proof,empty(800,count=10,source=700),boundary=null))
        assertNull(offer(proof,empty(900,source=700),boundary=null))
        assertNull(offer(proof,empty(1000,source=900),boundary=null))
        for(t in listOf(1100L,1200L,1300L))
            assertNull(offer(proof,frame(t,source=1100),boundary=1000))
        assertEquals(cards,assertNotNull(offer(proof,frame(1400),boundary=1000)).cards)
    }

    @Test fun clearDoesNotTurnCachedPreSourcesIntoTwoNewIndependentVotes() {
        val proof=QuantityAnchoredPlayProof()
        offer(proof,empty(500),boundary=null)
        offer(proof,empty(700),boundary=null)
        proof.clear()
        assertNull(offer(proof,empty(800,source=500),boundary=null))
        assertNull(offer(proof,empty(900,source=700),boundary=null))
        for(t in listOf(1000L,1100L,1200L)) assertNull(offer(proof,frame(t,source=1000)))
        assertEquals(cards,assertNotNull(offer(proof,frame(1300))).cards)
    }

    @Test fun sceneChangeDoesNotMakeOldPreSourcesFreshAgain() {
        val proof=QuantityAnchoredPlayProof()
        offer(proof,empty(500),boundary=null)
        offer(proof,empty(700),boundary=null)
        offer(proof,empty(800,source=500,scene=TableScene.TABLE),boundary=null)
        offer(proof,empty(900,source=700,scene=TableScene.TABLE),boundary=null)
        for(t in listOf(1000L,1100L,1200L))
            assertNull(offer(proof,frame(t,source=1000,scene=TableScene.TABLE)))
        assertNotNull(offer(proof,frame(1300,scene=TableScene.TABLE)))
    }

    @Test fun phaseChangeAndAnotherSeatsAppendCannotMakeCachedPreSourcesFreshAgain() {
        val proof=QuantityAnchoredPlayProof()
        offer(proof,empty(500),boundary=null)
        offer(proof,empty(700),boundary=null)
        // A phase change changes the ordinary proof version without changing
        // this seat's confirmed cards/count, as an own completion does in play.
        val changed=RoundLedger().apply {
            begin("quantity-source",initial)
            commit(PlayEvent("quantity-source","old",1,Seat.NEXT,prefix))
            commit(PlayEvent("quantity-source","other",2,Seat.PREVIOUS,listOf(Card(Rank.A,Suit.BLACK))))
        }.snapshot()
        offer(proof,empty(800,source=500),changed,boundary=null)
        offer(proof,empty(900,source=700),changed,boundary=null)
        for(t in listOf(1000L,1100L,1200L))
            assertNull(offer(proof,frame(t,source=1000),changed))
        assertNotNull(offer(proof,frame(1300),changed))
    }

    @Test fun samePostSourceCorrectWrongCorrectCannotReappearAsAQuantityVote() {
        val proof=QuantityAnchoredPlayProof()
        assertNull(offer(proof,frame(1000)))
        assertNull(offer(proof,frame(1050,count=7,source=1000)))
        assertNull(offer(proof,frame(1100,source=1000)))
        assertNull(offer(proof,frame(1200)))
        assertEquals(cards,assertNotNull(offer(proof,frame(1300))).cards)
    }

    @Test fun contradictoryPreSourceAlsoRevokesTheCandidatesCopiedShortBaseline() {
        val proof=QuantityAnchoredPlayProof()
        offer(proof,empty(500),boundary=null)
        offer(proof,empty(700),boundary=null)
        assertNull(offer(proof,frame(1000,count=11,source=900)))
        assertNull(offer(proof,frame(1050,count=10,source=700)))
        assertNull(offer(proof,frame(1100)))
        assertNull(offer(proof,frame(1200,source=1100)))
        assertNull(offer(proof,frame(1300,source=1100)))
        assertNotNull(offer(proof,frame(1400)))
    }

    @Test fun aRepeatedValidSourceStillTransmitsTheShortProofWithoutAddingAnotherSource() {
        val proof=QuantityAnchoredPlayProof()
        offer(proof,empty(500),boundary=null)
        offer(proof,empty(700),boundary=null)
        assertNull(offer(proof,frame(1000)))
        assertNull(offer(proof,frame(1100,source=1000)))
        assertEquals(cards,assertNotNull(offer(proof,frame(1200,source=1000))).cards)
    }

    @Test fun ambiguousBadgeValuesNeitherVoteNorPoisonTheNextCleanSource() {
        val proof=QuantityAnchoredPlayProof()
        offer(proof,empty(500),boundary=null)
        offer(proof,empty(700),boundary=null)
        for(t in listOf(1000L,1100L,1200L))
            assertNull(offer(proof,frame(t,count=11,source=900)))
        assertTrue(proof.retainAcrossOwnershipAmbiguity(frame(1300,count=99).copy(
            ambiguousPresentation=true,incompleteReason="出牌归属待核对，正在重新识别"),snapshot,Seat.NEXT))
        assertEquals(cards,assertNotNull(offer(proof,frame(1400,source=1300))).cards)
    }

    @Test fun negativeFutureAndReverseSourcesRevokeCountsButPermitFreshRecovery() {
        for(bad in listOf(-1L,1101L,990L)) {
            val proof=QuantityAnchoredPlayProof()
            assertNull(offer(proof,frame(1000)))
            assertNull(offer(proof,frame(1100,source=bad)))
            assertNull(offer(proof,frame(1200)))
            assertNotNull(offer(proof,frame(1300)))
        }
    }

    @Test fun strictSupersetUsesOnlyUncontradictedNewSourcesAfterACountFault() {
        val proof=QuantityAnchoredPlayProof()
        val priorCard=Card(Rank.Q,Suit.RED)
        val priorPlay=play(listOf(priorCard),shift=.3f)
        val prior=QuantityAnchoredPlayProof.PriorPresentation("prior",priorPlay.cards,priorPlay.glyphs)
        val s=RoundLedger().apply {
            begin("quantity-source",initial)
            commit(PlayEvent("quantity-source","prior",1,Seat.NEXT,prior.cards))
            selfFinished()
        }.snapshot()
        val group=play(listOf(priorCard,Card(Rank.J,Suit.RED)))
        fun strict(time: Long,count: Int=24,source: Long=time)=proof.observeStrictSuperset(
            frame(time,count,source,group),s,Seat.NEXT,prior)
        assertNull(strict(1000))
        assertNull(strict(1060,count=26,source=1000))
        assertNull(strict(1120,source=1000))
        assertNull(strict(1180,source=1000))
        assertNull(strict(1240))
        assertNotNull(strict(1360))
    }
}
