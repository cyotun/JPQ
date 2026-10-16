package com.cyotun.jpq.core

import kotlin.test.*

class TencentPhysicalCardTest {
    private val rule=WildcardRuleEvidence(setOf(Rank.TWO),true)
    private val paper=SuitSymbolPixels(12,12,IntArray(144) { 0xffeeeeee.toInt() },Suit.RED)
    private fun matched(suit: Suit,gold: Boolean=false)=SuitSymbolObservation(suit,.9f,.2f,gold,"shape_matched")

    @Test fun acceptedOrdinaryCornersDistinguishNaturalCardsFromTheHeartGrade() {
        for(suit in listOf(Suit.HEART,Suit.DIAMOND,Suit.SPADE,Suit.CLUB)) {
            assertFalse(tencentPhysicalWildcardIdentity(Card(Rank.FIVE,suit),paper,matched(suit),rule)!!)
            assertEquals(suit==Suit.HEART,tencentPhysicalWildcardIdentity(Card(Rank.TWO,suit),paper,matched(suit),rule))
        }
    }
    @Test fun unconfirmedClippedMaskedOrWeakReadingsRemainUnknown() {
        val card=Card(Rank.FIVE,Suit.RED)
        assertNull(tencentPhysicalWildcardIdentity(card,paper,matched(Suit.HEART),WildcardRuleEvidence(null,false)))
        assertNull(tencentPhysicalWildcardIdentity(card,paper.copy(complete=false),matched(Suit.HEART),rule))
        assertNull(tencentPhysicalWildcardIdentity(card,paper,matched(Suit.HEART).copy(reason="shape_uncertain"),rule))
        assertNull(tencentPhysicalWildcardIdentity(card,paper,matched(Suit.HEART).copy(suit=null),rule))
    }
    @Test fun aGoldHeartIsPositiveButAnUnclassifiedGoldPixelCannotProveANaturalCard() {
        val card=Card(Rank.FIVE,Suit.BLACK)
        val pixels=paper.argb.copyOf().also { it[40]=0xffffdc70.toInt() }
        val gold=paper.copy(argb=pixels,expectedColor=Suit.BLACK)
        assertTrue(tencentPhysicalWildcardIdentity(card,gold,matched(Suit.HEART,true),rule)!!)
        assertNull(tencentPhysicalWildcardIdentity(card,gold,matched(Suit.SPADE),rule))
    }
    @Test fun theActualFirstNaturalPairKeepsBothHeartWildcardsAvailableAfterVoting() {
        val initial=Rank.entries.filter { !it.isJoker && it !in setOf(Rank.TWO,Rank.FIVE) }
            .flatMap { rank -> listOf(Suit.SPADE,Suit.HEART,Suit.CLUB,Suit.DIAMOND).flatMap { suit -> List(2) { Card(rank,suit) } } }.take(27)
        val ledger=RoundLedger(); ledger.begin("live-first-pair",initial)
        val pair=List(2) { Card(Rank.FIVE,Suit.RED) }
        ledger.commit(PlayEvent("live-first-pair","pair",1,Seat.OPPOSITE,pair))
        val evidence=StraightFlushEvidenceTracker()
        val group=SuitedGroup(List(2) { Card(Rank.FIVE,Suit.HEART) },listOf(
            GlyphBox(.44f,.18f,.46f,.23f),GlyphBox(.49f,.18f,.51f,.23f)),
            pair.map { tencentPhysicalWildcardIdentity(it,paper,matched(Suit.HEART),rule) })
        for(time in listOf(1000L,1300L,1600L)) evidence.update(time,ledger.snapshot(),initial,null,mapOf(Seat.OPPOSITE to group),rule)
        val state=GradeCardCounter.evaluate(ledger.snapshot(),initial,evidence.physicalEventCards,rule)
        assertEquals(2,state.heartCount?.exact)
        assertEquals(6,state.ordinaryCounts[Rank.TWO]?.exact)
        assertEquals(2,ledger.snapshot().played(Seat.OPPOSITE))
    }

    @Test fun originalFacesDoNotFlashUnknownWhenANaturalOrBlackGradePairIsCommitted() {
        val initial=Rank.entries.filter { !it.isJoker && it !in setOf(Rank.TEN,Rank.FIVE) }
            .flatMap { rank -> listOf(Suit.SPADE,Suit.HEART,Suit.CLUB,Suit.DIAMOND).flatMap { suit -> List(2) { Card(rank,suit) } } }.take(27)
        val ledger=RoundLedger(); ledger.begin("original",initial)
        val ten=WildcardRuleEvidence(setOf(Rank.TEN),true)
        val projection=GradeCounterProjection.create(ledger.snapshot(),initial,emptyMap(),ten,faces=TableCardFaces.ORIGINAL)
        ledger.commit(PlayEvent("original","five",1,Seat.OPPOSITE,List(2) { Card(Rank.FIVE,Suit.RED) }))
        val pair=assertNotNull(projection.rebase(ledger.snapshot(),ten))
        assertEquals(2,pair.state.heartCount?.exact)
        ledger.commit(PlayEvent("original","black-ten",2,Seat.PREVIOUS,List(2) { Card(Rank.TEN,Suit.BLACK) }))
        val gradePair=assertNotNull(pair.rebase(ledger.snapshot(),ten))
        assertEquals(2,gradePair.state.heartCount?.exact)
        assertEquals(4,gradePair.state.ordinaryCounts[Rank.TEN]?.exact)
        assertEquals(GradeCountBounds(0,2),GradeCardCounter.evaluate(ledger.snapshot(),initial,emptyMap(),ten).heartCount)
    }

    @Test fun originalRedGradeFacesRemainBoundedAndMismatchedGoldIsRejected() {
        val initial=Rank.entries.filter { !it.isJoker && it !in setOf(Rank.TEN,Rank.FIVE) }
            .flatMap { rank -> listOf(Suit.SPADE,Suit.HEART,Suit.CLUB,Suit.DIAMOND).flatMap { suit -> List(2) { Card(rank,suit) } } }.take(27)
        val ledger=RoundLedger(); ledger.begin("original",initial)
        val ten=WildcardRuleEvidence(setOf(Rank.TEN),true)
        val red=List(2) { Card(Rank.TEN,Suit.RED) }
        ledger.commit(PlayEvent("original","red-ten",1,Seat.OPPOSITE,red))
        val pending=GradeCardCounter.evaluate(ledger.snapshot(),initial,emptyMap(),ten,faces=TableCardFaces.ORIGINAL)
        assertEquals(GradeCountBounds(0,2),pending.heartCount)
        val known=mapOf("red-ten" to listOf(PhysicalPlayedCard(red[0],true),PhysicalPlayedCard(red[1],false)))
        val resolved=GradeCardCounter.evaluate(ledger.snapshot(),initial,known,ten,faces=TableCardFaces.ORIGINAL)
        assertEquals(1,resolved.heartCount?.exact); assertEquals(5,resolved.ordinaryCounts[Rank.TEN]?.exact)
        ledger.commit(PlayEvent("original","five",2,Seat.NEXT,List(2) { Card(Rank.FIVE,Suit.RED) }))
        val impossible=known+mapOf("five" to List(2) { PhysicalPlayedCard(Card(Rank.FIVE,Suit.RED),true) })
        assertEquals("physical_evidence_conflict",GradeCardCounter.evaluate(ledger.snapshot(),initial,impossible,ten,faces=TableCardFaces.ORIGINAL).reason)
    }
}
