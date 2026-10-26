package com.cyotun.jpq.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class C48FinalSupersetFinishObserverTest {
    private val oldQ=Card(Rank.Q,Suit.RED)
    private val finalHand=listOf(oldQ,Card(Rank.J,Suit.BLACK),Card(Rank.Q,Suit.BLACK),
        Card(Rank.K,Suit.BLACK),Card(Rank.A,Suit.BLACK))

    private fun group(rank: Rank)=listOf(Card(rank,Suit.RED),Card(rank,Suit.RED),
        Card(rank,Suit.BLACK),Card(rank,Suit.BLACK))

    private fun play(cards: List<Card>,confidence: Float=.92f,boxes: List<GlyphBox> =
        cards.indices.map { GlyphBox(.25f+it*.045f,.22f,.29f+it*.045f,.28f) }) =
        ObservedPlay(cards,confidence,cards.mapIndexed { i,card -> PlayGlyph(card,confidence,boxes[i]) })

    @Test fun freshSecondPlaceCueConfirmsOnlyTheRecentFinalSuperset() {
        val deck=Rank.entries.flatMap { rank -> if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
            else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } } }.toMutableList()
        val outside=(group(Rank.TWO)+group(Rank.THREE)+group(Rank.FOUR)+group(Rank.FIVE)+
            List(4) { Card(Rank.SIX,Suit.RED) }+Card(Rank.SIX,Suit.BLACK))
        val reserved=outside+oldQ+finalHand
        reserved.forEach { check(deck.remove(it)) }
        val own=deck.take(27)
        val ledger=RoundLedger().apply {
            begin("c48-final-superset",own)
            listOf(group(Rank.TWO),group(Rank.THREE),group(Rank.FOUR),group(Rank.FIVE),
                List(4) { Card(Rank.SIX,Suit.RED) }+Card(Rank.SIX,Suit.BLACK)).forEachIndexed { index,cards ->
                commit(PlayEvent("c48-final-superset","outside-$index",index.toLong()+1,Seat.OPPOSITE,cards))
            }
        }
        val states=mutableListOf<RoundSnapshot>()
        val observer=RoundObserver(ledger) { states+=it }
        fun frame(t: Long,shown: ObservedPlay?=null,cue: Long?=null)=TableFrame(t,TableScene.TABLE,own,
            Seat.entries.associateWith { if(it==Seat.OPPOSITE) shown else null },
            observedEarlyFinishes=cue?.let { mapOf(Seat.OPPOSITE to it) }.orEmpty())

        // Directly establish the old single-card presentation and ledger event.
        for(t in listOf(1000L,1120L,1240L,1360L)) observer.observe(frame(t,play(listOf(oldQ))))
        assertEquals(22,ledger.snapshot().played(Seat.OPPOSITE))
        val oldEvent=ledger.snapshot().plays.getValue(Seat.OPPOSITE).last()
        // Its glyph row is blind long enough to lose the presentation anchors.
        for(t in listOf(1480L,1600L,1720L,1840L,1960L,2080L)) observer.observe(frame(t))
        for(t in 2320L..9760L step 240L) observer.observe(frame(t))

        // Four full direct reads span the usual recovery threshold. The finish
        // source is deliberately delayed; these reads alone cannot commit.
        for(t in listOf(10000L,10250L,10500L,10750L,11000L)) {
            observer.observe(frame(t,play(finalHand)))
            assertNotEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
            assertEquals(22,ledger.snapshot().played(Seat.OPPOSITE))
        }
        val boxes=finalHand.indices.map { GlyphBox(.25f+it*.045f,.22f,.29f+it*.045f,.28f) }
        val partial=play(listOf(oldQ),.65f,listOf(boxes[0]))
        observer.observe(frame(11100L,partial,11100L))

        val events=ledger.snapshot().plays.getValue(Seat.OPPOSITE)
        assertEquals(27,ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(oldQ,events[events.lastIndex-1].cards.single())
        assertEquals(finalHand,events.last().cards)
        assertEquals(oldEvent.eventId,events[events.lastIndex-1].eventId)
        assertTrue(states.any { it.plays.getValue(Seat.OPPOSITE).lastOrNull()?.cards==finalHand })
    }

    @Test fun finalSupersetWithoutQualifiedCueEntersBoundedRecoveryWithoutCommitting() {
        val deck=Rank.entries.flatMap { rank -> if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
            else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } } }.toMutableList()
        val outside=(group(Rank.TWO)+group(Rank.THREE)+group(Rank.FOUR)+group(Rank.FIVE)+
            List(4) { Card(Rank.SIX,Suit.RED) }+Card(Rank.SIX,Suit.BLACK))
        (outside+oldQ+finalHand).forEach { check(deck.remove(it)) }
        val own=deck.take(27)
        val ledger=RoundLedger().apply {
            begin("c48-final-superset-no-cue",own)
            listOf(group(Rank.TWO),group(Rank.THREE),group(Rank.FOUR),group(Rank.FIVE),
                List(4) { Card(Rank.SIX,Suit.RED) }+Card(Rank.SIX,Suit.BLACK)).forEachIndexed { index,cards ->
                commit(PlayEvent("c48-final-superset-no-cue","outside-$index",index.toLong()+1,Seat.OPPOSITE,cards))
            }
        }
        val observer=RoundObserver(ledger)
        fun frame(t: Long,shown: ObservedPlay?=null)=TableFrame(t,TableScene.TABLE,own,
            Seat.entries.associateWith { if(it==Seat.OPPOSITE) shown else null })
        for(t in listOf(1000L,1120L,1240L,1360L)) observer.observe(frame(t,play(listOf(oldQ))))
        for(t in listOf(1480L,1600L,1720L,1840L,1960L,2080L)) observer.observe(frame(t))
        for(t in 2320L..9760L step 240L) observer.observe(frame(t))
        for(t in listOf(10000L,10250L,10500L,10750L,11000L,11250L,11500L,11750L,12000L)) {
            observer.observe(frame(t,play(finalHand)))
            assertEquals(22,ledger.snapshot().played(Seat.OPPOSITE))
        }
        assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
    }
}
