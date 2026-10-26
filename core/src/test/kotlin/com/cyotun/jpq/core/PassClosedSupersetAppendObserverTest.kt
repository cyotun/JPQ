package com.cyotun.jpq.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PassClosedSupersetAppendObserverTest {
    @Test fun appendedOtherPlayKeepsOppositePassProofAndObserverContinuesWithPreviousAndSelf() {
        val old=Card(Rank.EIGHT,Suit.RED)
        val ownPlay=Card(Rank.NINE,Suit.BLACK)
        val nextKing=Card(Rank.K,Suit.BLACK)
        val previousPlay=Card(Rank.A,Suit.RED)
        val nextStraight=listOf(Card(Rank.TWO,Suit.RED),Card(Rank.THREE,Suit.BLACK),
            Card(Rank.FOUR,Suit.BLACK),Card(Rank.FIVE,Suit.BLACK),Card(Rank.SIX,Suit.BLACK))
        val oppositeStraight=listOf(old,Card(Rank.SEVEN,Suit.BLACK),Card(Rank.SIX,Suit.RED),
            Card(Rank.FIVE,Suit.RED),Card(Rank.FOUR,Suit.RED))
        val external=listOf(old,nextKing,previousPlay)+nextStraight+oppositeStraight
        val deck=Rank.entries.flatMap { rank ->
            if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
            else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } }
        }.toMutableList()
        external.forEach { check(deck.remove(it)) }
        val hand=listOf(ownPlay)+deck.filterNot { it==ownPlay }.take(26)
        val afterOwn=hand.toMutableList().also { check(it.remove(ownPlay)) }
        val ledger=RoundLedger()
        val observer=RoundObserver(ledger)

        fun play(cards: List<Card>,seat: Seat,confidence: Float=.95f): ObservedPlay {
            val glyphs=cards.mapIndexed { i,card ->
                val left=if(seat==Seat.OPPOSITE && card.rank==Rank.EIGHT && cards.size>1) .529f
                    else .40f+i*.025f
                PlayGlyph(card,confidence,GlyphBox(left,.15f,left+.018f,.20f))
            }
            return ObservedPlay(cards,confidence,glyphs)
        }
        fun frame(time: Long,shown: Map<Seat,List<Card>> = emptyMap(),
            currentHand: List<Card>?=hand, passOpp: Long?=null): TableFrame {
            val keepOppBlind=passOpp!=null ||
                (shown[Seat.NEXT]==nextStraight && shown[Seat.OPPOSITE].isNullOrEmpty())
            val plays=Seat.entries.associateWith { seat ->
                val cards=shown[seat].orEmpty()
                when {
                    seat==Seat.OPPOSITE && passOpp!=null -> ObservedPlay(emptyList(),1f,regionObserved=false)
                    seat==Seat.OPPOSITE && keepOppBlind && cards.isEmpty() -> ObservedPlay(emptyList(),1f,regionObserved=false)
                    cards.isNotEmpty() -> play(cards,seat)
                    else -> ObservedPlay(emptyList(),1f)
                }
            }
            return TableFrame(time,TableScene.TABLE,currentHand,plays,
                observedPasses=passOpp?.let { mapOf(Seat.OPPOSITE to it) }.orEmpty())
        }

        observer.observe(TableFrame(100,TableScene.RESULT,null))
        for(t in 1000L..1400L step 200) observer.observe(frame(t))
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
        for(t in 1600L..2200L step 200) observer.observe(frame(t,mapOf(Seat.OPPOSITE to listOf(old))))
        assertEquals(1,ledger.snapshot().played(Seat.OPPOSITE))
        for(t in 2400L..3000L step 200)
            observer.observe(frame(t,mapOf(Seat.OPPOSITE to listOf(old),Seat.NEXT to listOf(nextKing))))
        assertEquals(1,ledger.snapshot().played(Seat.NEXT))
        for(t in 3200L..3600L step 200)
            observer.observe(frame(t,mapOf(Seat.NEXT to listOf(nextKing)),passOpp=t))
        for(t in 3800L..4400L step 200)
            observer.observe(frame(t,mapOf(Seat.NEXT to nextStraight)))
        assertEquals(6,ledger.snapshot().played(Seat.NEXT))
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)

        for(t in 4600L..5200L step 200)
            observer.observe(frame(t,mapOf(Seat.OPPOSITE to oppositeStraight,Seat.NEXT to nextStraight)))
        assertEquals(6,ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(2,ledger.snapshot().plays.getValue(Seat.OPPOSITE).size)
        assertEquals(oppositeStraight.toSet(),ledger.snapshot().plays.getValue(Seat.OPPOSITE).last().cards.toSet())
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)

        for(t in 5400L..6000L step 200)
            observer.observe(frame(t,mapOf(Seat.OPPOSITE to oppositeStraight,Seat.NEXT to nextStraight,
                Seat.PREVIOUS to listOf(previousPlay))))
        assertEquals(1,ledger.snapshot().played(Seat.PREVIOUS))

        for(t in 6200L..6800L step 200)
            observer.observe(frame(t,mapOf(Seat.SELF to listOf(ownPlay),Seat.OPPOSITE to oppositeStraight,
                Seat.NEXT to nextStraight,Seat.PREVIOUS to listOf(previousPlay)),currentHand=afterOwn))
        assertEquals(1,ledger.snapshot().played(Seat.SELF))
        assertEquals(6,ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(6,ledger.snapshot().played(Seat.NEXT))
        assertEquals(1,ledger.snapshot().played(Seat.PREVIOUS))
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
    }
}
