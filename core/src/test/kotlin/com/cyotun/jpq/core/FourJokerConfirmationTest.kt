package com.cyotun.jpq.core

import kotlin.test.*

class FourJokerConfirmationTest {
    private val own=Rank.entries.filterNot { it.isJoker }.flatMap { listOf(Card(it,Suit.RED),Card(it,Suit.BLACK)) }+
        Card(Rank.THREE,Suit.RED)
    private val cards=List(2) { Card(Rank.BIG,Suit.NONE) }+List(2) { Card(Rank.SMALL,Suit.NONE) }
    private fun start(): Pair<RoundLedger,RoundObserver> {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger)
        observer.observe(TableFrame(0,TableScene.LOBBY,null))
        for(t in 60L..240L step 60) observer.observe(TableFrame(t,TableScene.TABLE,own,
            Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }))
        return ledger to observer
    }
    private fun show(observer: RoundObserver,time: Long,faces: List<Card> = cards,confidence: Float=.84f) {
        val glyphs=faces.mapIndexed { index,card -> PlayGlyph(card,confidence,
            GlyphBox(.72f+index*.025f,.28f,.733f+index*.025f,.355f)) }
        observer.observe(TableFrame(time,TableScene.TABLE,own,
            Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }+
                (Seat.NEXT to ObservedPlay(faces,confidence,glyphs))))
    }
    @Test fun threeIndependentStrongCompleteReadingsConfirmBeforeTheEffectClears() {
        val (ledger,observer)=start()
        show(observer,300); show(observer,400)
        assertEquals(0,ledger.snapshot().played(Seat.NEXT))
        show(observer,500)
        assertEquals(4,ledger.snapshot().played(Seat.NEXT))
    }
    @Test fun repeatedFramesAndWeakFirstReadingCannotUseTheThreeFrameProof() {
        val (ledger,observer)=start()
        show(observer,300,confidence=.79f)
        show(observer,400); repeat(5) { show(observer,400) }; show(observer,500)
        assertEquals(0,ledger.snapshot().played(Seat.NEXT))
        show(observer,600)
        assertEquals(4,ledger.snapshot().played(Seat.NEXT))
    }
    @Test fun anImpossibleFourBigReadingCannotBreakTheLedgerOrCountAsFourKings() {
        val (ledger,observer)=start()
        for(t in 300L..700L step 100) show(observer,t,List(4) { Card(Rank.BIG,Suit.NONE) })
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
        assertEquals(0,ledger.snapshot().played(Seat.NEXT))
        for(t in 800L..1000L step 100) show(observer,t)
        val confirmed=ledger.snapshot()
        assertEquals(4,confirmed.played(Seat.NEXT))
        for(t in 1100L..1500L step 100) show(observer,t,List(4) { Card(Rank.BIG,Suit.NONE) })
        for(t in 1600L..1800L step 100) show(observer,t)
        assertEquals(confirmed,ledger.snapshot())
    }
}
