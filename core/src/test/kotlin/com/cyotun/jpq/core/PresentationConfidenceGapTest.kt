package com.cyotun.jpq.core

import kotlin.test.*

class PresentationConfidenceGapTest {
    private val deck=Rank.entries.flatMap { rank -> if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
        else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } } }
    private val five=listOf(Card(Rank.EIGHT,Suit.RED),Card(Rank.EIGHT,Suit.RED),Card(Rank.EIGHT,Suit.BLACK),
        Card(Rank.NINE,Suit.RED),Card(Rank.NINE,Suit.BLACK))
    private fun play(cards: List<Card>,score: Float)=ObservedPlay(cards,score,cards.mapIndexed { i,c ->
        PlayGlyph(c,score,GlyphBox(.15f+i*.025f,.28f,.168f+i*.025f,.328f))
    })
    private fun setup(): Pair<RoundLedger,RoundObserver> {
        val ledger=RoundLedger(); ledger.begin("confidence-gap",deck.take(27))
        return ledger to RoundObserver(ledger)
    }
    private fun show(observer: RoundObserver,time: Long,p: ObservedPlay) =
        observer.observe(TableFrame(time,TableScene.TABLE,null,mapOf(Seat.PREVIOUS to p)))
    @Test fun sameGeometryRecoversAfterAShortConfidenceGapWithoutCountingLowScores() {
        val (ledger,observer)=setup()
        for(t in 1000L..1240L step 60) show(observer,t,play(five,.9f))
        assertEquals(5,ledger.snapshot().played(Seat.PREVIOUS))
        for(t in 1300L..2200L step 150) show(observer,t,play(five.take(2),.62f))
        assertEquals(5,ledger.snapshot().played(Seat.PREVIOUS))
        val six=five+Card(Rank.NINE,Suit.BLACK)
        for(t in 2350L..2650L step 100) show(observer,t,play(six,.9f))
        assertEquals(6,ledger.snapshot().played(Seat.PREVIOUS))
        assertEquals(1,ledger.snapshot().plays.getValue(Seat.PREVIOUS).size)
    }
    @Test fun aLongGapCannotGuessAnExpansion() {
        val (ledger,observer)=setup()
        for(t in 1000L..1240L step 60) show(observer,t,play(five,.9f))
        for(t in 1300L..2900L step 200) show(observer,t,play(five.take(2),.62f))
        val six=five+Card(Rank.NINE,Suit.BLACK)
        for(t in 3100L..4300L step 100) show(observer,t,play(six,.9f))
        assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
        assertEquals(5,ledger.snapshot().played(Seat.PREVIOUS))
    }
    @Test fun aBlindRegionCannotMergeAnUnseenExpansion() {
        val (ledger,observer)=setup()
        for(t in 1000L..1240L step 60) show(observer,t,play(five,.9f))
        for(t in 1300L..2200L step 150) show(observer,t,play(five,.9f).copy(regionObserved=false))
        for(t in 2350L..3450L step 100) show(observer,t,play(five+Card(Rank.NINE,Suit.BLACK),.9f))
        assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
        assertEquals(5,ledger.snapshot().played(Seat.PREVIOUS))
    }
    @Test fun anAbsentObservationCannotMergeAnUnseenExpansion() {
        val (ledger,observer)=setup()
        for(t in 1000L..1240L step 60) show(observer,t,play(five,.9f))
        for(t in 1500L..2100L step 150) observer.observe(TableFrame(t,TableScene.TABLE,null))
        for(t in 2300L..3400L step 100) show(observer,t,play(five+Card(Rank.NINE,Suit.BLACK),.9f))
        assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
        assertEquals(5,ledger.snapshot().played(Seat.PREVIOUS))
    }
}
