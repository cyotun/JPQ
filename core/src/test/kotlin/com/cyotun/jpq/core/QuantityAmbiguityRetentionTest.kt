package com.cyotun.jpq.core

import kotlin.test.*

class QuantityAmbiguityRetentionTest {
    private val deck=Rank.entries.flatMap { rank ->
        if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
        else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } }
    }
    private val target=listOf(
        Card(Rank.THREE,Suit.RED),Card(Rank.THREE,Suit.BLACK),Card(Rank.THREE,Suit.RED),
        Card(Rank.TWO,Suit.RED),Card(Rank.TWO,Suit.BLACK))
    private val snapshot: RoundSnapshot by lazy {
        val initial=deck.filterNot { it in target }.take(27)
        val outside=deck.filterNot { it in initial }
        val previous=outside.filterNot { it in target }.take(15)
        RoundLedger().apply {
            begin("ambiguity-retention",initial)
            commit(PlayEvent("ambiguity-retention","previous-1",1,Seat.PREVIOUS,previous))
        }.snapshot()
    }

    private fun play(cards: List<Card> = target,confidence: Float=.86f,shift: Float=0f)=ObservedPlay(cards,confidence,
        cards.mapIndexed { index,card ->
            val left=.30f+index*.055f+shift
            PlayGlyph(card,confidence,GlyphBox(left,.28f,left+.035f,.34f))
        })
    private fun frame(time: Long,p: ObservedPlay=play(),count: Int?=null,source: Long=time,
        scene: TableScene=TableScene.TABLE)=TableFrame(time,scene,null,
        Seat.entries.associateWith { seat -> if(seat==Seat.PREVIOUS) p else ObservedPlay(emptyList(),1f) },
        remainingHandCounts=if(count==null) emptyMap() else mapOf(Seat.PREVIOUS to RemainingHandCount(count,source)))
    private fun empty(time: Long,count: Int?=null,source: Long=time)=
        frame(time,ObservedPlay(emptyList(),1f),count,source)
    private fun ambiguous(frame: TableFrame)=frame.copy(ambiguousPresentation=true,
        incompleteReason="出牌归属待核对，正在重新识别")
    private fun seededProof(): QuantityAnchoredPlayProof {
        val proof=QuantityAnchoredPlayProof()
        val s=snapshot
        assertNull(proof.observe(frame(1000),s,Seat.PREVIOUS,900))
        assertNull(proof.observe(frame(1100,play(confidence=.88f)),s,Seat.PREVIOUS,900))
        // A single count source keeps this already-certified card proposal
        // pending until clean, post-ambiguity observations arrive.
        assertNull(proof.observe(frame(1200,play(confidence=.88f),count=7,source=1200),s,Seat.PREVIOUS,900))
        return proof
    }

    @Test fun qualifiedAmbiguityPreservesTheStrongGroupButNeverCountsAmbiguousFrames() {
        val proof=seededProof(); val s=snapshot
        assertTrue(proof.retainAcrossOwnershipAmbiguity(
            ambiguous(frame(1300,play(confidence=.88f),count=4,source=1300)),s,Seat.PREVIOUS))
        assertTrue(proof.retainAcrossOwnershipAmbiguity(
            ambiguous(empty(1400,count=99,source=1400)),s,Seat.PREVIOUS))

        // This reuses the last clean OCR source. Ambiguous-frame badges above
        // cannot poison monotonicity or manufacture the missing count vote.
        assertNull(proof.observe(frame(1450,ObservedPlay(emptyList(),1f),count=7,source=1200),s,Seat.PREVIOUS,900))
        val result=proof.observe(empty(1600,count=7,source=1600),s,Seat.PREVIOUS,900)
        assertEquals(target,assertNotNull(result).cards)
    }

    @Test fun delayedOcrSourcesAfterTheObservedAmbiguityResolveWithinTheirOriginalAgeWindow() {
        val proof=QuantityAnchoredPlayProof(); val s=snapshot
        fun offer(time: Long,p: ObservedPlay=play(),count: Int?=null,source: Long=time)=
            proof.observe(frame(time,p,count,source),s,Seat.PREVIOUS,-100)
        assertNull(offer(0))
        assertNull(offer(200,play(confidence=.88f)))
        assertNull(offer(432,play(confidence=.88f)))
        assertTrue(proof.retainAcrossOwnershipAmbiguity(ambiguous(frame(698)),s,Seat.PREVIOUS))
        assertTrue(proof.retainAcrossOwnershipAmbiguity(ambiguous(empty(899)),s,Seat.PREVIOUS))

        // OCR source times lag the current frame. These readings are still
        // distinct and fresh against their original timestamps (716ms, 514ms).
        assertNull(offer(1148,ObservedPlay(emptyList(),1f),count=7,source=432))
        assertEquals(target,assertNotNull(offer(1413,ObservedPlay(emptyList(),1f),count=7,source=899)).cards)
    }

    @Test fun onlyTheExactObservedTargetOrAConfirmedEmptyRegionCanBridgeAmbiguity() {
        val s=snapshot
        val changed=target.toMutableList().also { it[0]=Card(Rank.FOUR,Suit.RED) }
        val bad=listOf(
            ambiguous(frame(1300,play(target.take(4),.88f))),
            ambiguous(frame(1300,play(changed,.88f))),
            ambiguous(frame(1300,play(confidence=.72f))),
            ambiguous(frame(1300,play().copy(regionObserved=false))),
            ambiguous(frame(1300).copy(captureGap=true)),
            ambiguous(frame(1300).copy(localizedOverlaySeats=setOf(Seat.NEXT))),
            frame(1300).copy(ambiguousPresentation=true,incompleteReason="其他归属问题"),
            ambiguous(frame(1300,scene=TableScene.RESULT)),
        )
        for(frame in bad) {
            val proof=seededProof()
            assertFalse(proof.retainAcrossOwnershipAmbiguity(frame,s,Seat.PREVIOUS))
        }
    }

    @Test fun ambiguityAndLedgerVersionsHaveFixedBounds() {
        val s=snapshot
        val tooLong=seededProof()
        assertTrue(tooLong.retainAcrossOwnershipAmbiguity(ambiguous(frame(1300)),s,Seat.PREVIOUS))
        assertFalse(tooLong.retainAcrossOwnershipAmbiguity(ambiguous(empty(1801)),s,Seat.PREVIOUS))

        val changedRound=seededProof()
        assertFalse(changedRound.retainAcrossOwnershipAmbiguity(
            ambiguous(frame(1300)),s.copy(roundId="replacement"),Seat.PREVIOUS))
        val changedEvent=seededProof()
        val changedEvents=s.copy(plays=s.plays+mapOf(Seat.PREVIOUS to
            (s.plays.getValue(Seat.PREVIOUS)+PlayEvent(s.roundId!!,"previous-2",2,Seat.PREVIOUS,listOf(Card(Rank.SEVEN,Suit.RED))))))
        assertFalse(changedEvent.retainAcrossOwnershipAmbiguity(ambiguous(frame(1300)),changedEvents,Seat.PREVIOUS))
    }

    @Test fun retainedCandidateKeepsItsOriginalTwoSecondLifetime() {
        val s=snapshot; val proof=seededProof()
        assertTrue(proof.retainAcrossOwnershipAmbiguity(ambiguous(frame(1300)),s,Seat.PREVIOUS))
        assertNull(proof.observe(frame(1500),s,Seat.PREVIOUS,900))
        assertNull(proof.observe(frame(2000),s,Seat.PREVIOUS,900))
        assertNull(proof.observe(frame(2200),s,Seat.PREVIOUS,900))
        assertNull(proof.observe(frame(2600),s,Seat.PREVIOUS,900))
        assertNull(proof.observe(frame(2999),s,Seat.PREVIOUS,900))
        assertNull(proof.observe(frame(3001),s,Seat.PREVIOUS,900))
        // The expired retained proposal cannot be revived by another direct
        // frame or by the same previous-event version.
        assertNull(proof.observe(frame(3100),s,Seat.PREVIOUS,900))
    }
}
