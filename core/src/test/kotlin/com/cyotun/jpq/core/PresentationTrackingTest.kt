package com.cyotun.jpq.core

import kotlin.test.*

class PresentationTrackingTest {
    private val baseline=Rank.entries.filterNot { it.isJoker }.flatMap { listOf(Card(it,Suit.RED),Card(it,Suit.BLACK)) }+Card(Rank.SMALL,Suit.NONE)
    private val ace=Card(Rank.A,Suit.RED)
    private fun empty()=Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }
    private fun start(observer: RoundObserver) {
        observer.observe(TableFrame(0,TableScene.LOBBY,null))
        for(time in 60L..240L step 60) observer.observe(TableFrame(time,TableScene.TABLE,baseline,empty()))
    }
    private fun glyph(card: Card,x: Float)=PlayGlyph(card,.99f,GlyphBox(x,.30f,x+.025f,.36f))
    private fun play(cards: List<Card>,x: Float=.75f)=ObservedPlay(cards,.99f,cards.mapIndexed { i,card -> glyph(card,x+i*.028f) })
    private fun show(observer: RoundObserver,play: ObservedPlay,start: Long,end: Long) {
        for(time in start..end step 60) observer.observe(TableFrame(time,TableScene.TABLE,null,mapOf(Seat.NEXT to play)))
    }
    @Test fun sameBoxJokerRankFlickerCannotCreateAnotherPlay() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(observer)
        val small=play(listOf(Card(Rank.SMALL,Suit.NONE)))
        val big=play(listOf(Card(Rank.BIG,Suit.NONE)))
        show(observer,small,480,660)
        val before=ledger.snapshot()
        show(observer,ObservedPlay(emptyList(),1f,regionObserved=false),720,840)
        show(observer,big,900,1380) // False class persists longer than the ordinary four votes.
        show(observer,small,1440,1800)
        assertEquals(before,ledger.snapshot())
    }
    @Test fun lastingJokerChangeCanStillBecomeANewPlay() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(observer)
        show(observer,play(listOf(Card(Rank.SMALL,Suit.NONE))),480,660)
        show(observer,play(listOf(Card(Rank.BIG,Suit.NONE))),720,1740)
        assertEquals(2,ledger.snapshot().played(Seat.NEXT))
        assertEquals(listOf(Rank.SMALL,Rank.BIG),ledger.snapshot().plays.getValue(Seat.NEXT).flatMap { it.cards }.map { it.rank })
    }
    @Test fun provedEmptyBoundaryDoesNotDelayADifferentJoker() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(observer)
        show(observer,play(listOf(Card(Rank.SMALL,Suit.NONE))),480,660)
        show(observer,ObservedPlay(emptyList(),1f),720,840)
        show(observer,play(listOf(Card(Rank.BIG,Suit.NONE))),900,1080)
        assertEquals(2,ledger.snapshot().played(Seat.NEXT))
    }
    @Test fun aGrowingBombIsOnePresentationAndOneHistoryEntry() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(observer)
        show(observer,play(listOf(ace)),480,660)
        val initialEvent=ledger.snapshot().plays[Seat.NEXT]!!.single()
        val bomb=listOf(ace,ace,Card(Rank.A,Suit.BLACK),Card(Rank.A,Suit.BLACK))
        show(observer,play(bomb),720,960)
        val event=ledger.snapshot().plays[Seat.NEXT]!!.single()
        assertEquals(initialEvent.eventId,event.eventId); assertEquals(initialEvent.sequence,event.sequence)
        assertEquals(4,ledger.snapshot().played(Seat.NEXT)); assertEquals(77,ledger.snapshot().remaining!!.values.sum())
        assertEquals(bomb,event.cards)
    }
    @Test fun missingBombGlyphsDoNotAppendAnotherSmallerPlay() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(observer)
        val cards=listOf(ace,ace,Card(Rank.A,Suit.BLACK),Card(Rank.A,Suit.BLACK))
        val full=play(cards)
        show(observer,full,480,660)
        val partial=ObservedPlay(cards.take(2),.99f,full.glyphs.take(2))
        show(observer,partial,720,960); show(observer,full,1020,1200)
        assertEquals(4,ledger.snapshot().played(Seat.NEXT)); assertEquals(1,ledger.snapshot().plays[Seat.NEXT]!!.size)
    }
    @Test fun aClearBoundaryMakesTheFollowingBombANewPlay() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(observer)
        show(observer,play(listOf(ace)),480,660)
        show(observer,ObservedPlay(emptyList(),1f),720,840)
        val bomb=listOf(ace,ace,Card(Rank.A,Suit.BLACK),Card(Rank.A,Suit.BLACK))
        show(observer,play(bomb),900,1080)
        assertEquals(5,ledger.snapshot().played(Seat.NEXT)); assertEquals(2,ledger.snapshot().plays[Seat.NEXT]!!.size)
    }
    @Test fun rankSupersetWithoutTheOldTracksNeverGetsAddedToItsPrefix() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(observer)
        show(observer,play(listOf(ace)),480,660)
        val bomb=listOf(ace,ace,Card(Rank.A,Suit.BLACK),Card(Rank.A,Suit.BLACK))
        show(observer,play(bomb,.68f),720,1740)
        assertEquals(1,ledger.snapshot().played(Seat.NEXT)); assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
        assertNull(ledger.snapshot().remaining)
    }
    @Test fun cardsMovingIntoPlaceMustSettleBeforeConfirmation() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(observer)
        for(i in 0..3) observer.observe(TableFrame(480+i*60L,TableScene.TABLE,null,mapOf(Seat.NEXT to play(listOf(ace),.65f+i*.028f))))
        assertEquals(0,ledger.snapshot().played(Seat.NEXT))
        show(observer,play(listOf(ace),.734f),720,900)
        assertEquals(1,ledger.snapshot().played(Seat.NEXT))
    }
    @Test fun aMissingObservationBreaksPlayConfirmation() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(observer)
        val p=play(listOf(ace)); show(observer,p,480,600)
        observer.observe(TableFrame(660,TableScene.TABLE,null,mapOf(Seat.NEXT to null)))
        show(observer,p,720,720); assertEquals(0,ledger.snapshot().played(Seat.NEXT))
        show(observer,p,780,900); assertEquals(1,ledger.snapshot().played(Seat.NEXT))
    }
    @Test fun sparseSamplesDoNotJoinIntoAStableConfirmation() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(observer)
        for(time in listOf(480L,1080L,1680L,2280L)) observer.observe(TableFrame(time,TableScene.TABLE,null,mapOf(Seat.NEXT to play(listOf(ace)))))
        assertEquals(0,ledger.snapshot().played(Seat.NEXT))
    }
    @Test fun anUnobservedEmptyAreaCannotOpenASecondIdenticalPlay() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(observer)
        val p=play(listOf(ace)); show(observer,p,480,660)
        show(observer,ObservedPlay(emptyList(),1f,regionObserved=false),720,900)
        show(observer,p,960,1200)
        assertEquals(1,ledger.snapshot().played(Seat.NEXT))
    }
    @Test fun aLowConfidenceEmptyAreaCannotOpenASecondIdenticalPlay() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(observer)
        val p=play(listOf(ace)); show(observer,p,480,660)
        show(observer,ObservedPlay(emptyList(),.3f),720,900); show(observer,p,960,1200)
        assertEquals(1,ledger.snapshot().played(Seat.NEXT))
    }
    @Test fun oneEmptyFrameAtBaselineCommitIsNotTwoBoundaryFrames() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger)
        observer.observe(TableFrame(0,TableScene.DEALING,null,empty()))
        val p=play(listOf(ace))
        show(observer,p,60,240)
        for(time in listOf(300L,360L)) observer.observe(TableFrame(time,TableScene.TABLE,baseline,empty()+(Seat.NEXT to p)))
        observer.observe(TableFrame(420,TableScene.TABLE,baseline,empty()))
        show(observer,p,480,660)
        assertEquals(1,ledger.snapshot().played(Seat.NEXT))
    }
    @Test fun aPresentationCanFinishExpandingBeforeTheBaselineIsReady() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger)
        observer.observe(TableFrame(0,TableScene.DEALING,null,empty()))
        show(observer,play(listOf(ace)),60,240)
        val bomb=listOf(ace,ace,Card(Rank.A,Suit.BLACK),Card(Rank.A,Suit.BLACK))
        val p=play(bomb); show(observer,p,300,480)
        for(time in 540L..720L step 60) observer.observe(TableFrame(time,TableScene.TABLE,baseline,empty()+(Seat.NEXT to p)))
        assertEquals(4,ledger.snapshot().played(Seat.NEXT)); assertEquals(1,ledger.snapshot().plays[Seat.NEXT]!!.size)
    }
    @Test fun missingHandFramesBreakBaselineConfirmation() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger)
        observer.observe(TableFrame(0,TableScene.LOBBY,null))
        for(time in listOf(60L,120L)) observer.observe(TableFrame(time,TableScene.TABLE,baseline,empty()))
        observer.observe(TableFrame(180,TableScene.TABLE,null,empty()))
        observer.observe(TableFrame(240,TableScene.TABLE,baseline,empty())); assertNull(ledger.snapshot().remaining)
        for(time in listOf(300L,360L)) observer.observe(TableFrame(time,TableScene.TABLE,baseline,empty()))
        assertNotNull(ledger.snapshot().remaining)
    }
    @Test fun missingSelfProofCannotReuseOldVotesAgainstANewHand() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger); start(observer)
        val removed=baseline.take(2); val hand=baseline.drop(2); val proof=ObservedPlay(removed,.99f)
        for(time in 480L..600L step 60) observer.observe(TableFrame(time,TableScene.TABLE,hand,mapOf(Seat.SELF to proof)))
        observer.observe(TableFrame(660,TableScene.TABLE,hand))
        observer.observe(TableFrame(720,TableScene.TABLE,hand,mapOf(Seat.SELF to proof)))
        assertEquals(0,ledger.snapshot().played(Seat.SELF))
        for(time in 780L..900L step 60) observer.observe(TableFrame(time,TableScene.TABLE,hand,mapOf(Seat.SELF to proof)))
        assertEquals(2,ledger.snapshot().played(Seat.SELF)); assertEquals(81,ledger.snapshot().remaining!!.values.sum())
    }
    @Test fun persistenceCallbackFailureDoesNotTurnAValidPlayIntoAConflict() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger) { error("disk unavailable") }; start(observer)
        show(observer,play(listOf(ace)),480,660)
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase); assertEquals(1,ledger.snapshot().played(Seat.NEXT))
    }
}
