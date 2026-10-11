package com.cyotun.jpq.core

import kotlin.test.*
class ObservationTest {
    private fun baseline()=Rank.entries.filter { !it.isJoker }.flatMap { listOf(Card(it,Suit.RED),Card(it,Suit.BLACK)) }+Card(Rank.SMALL,Suit.NONE)
    private fun start(o: RoundObserver) { o.observe(TableFrame(0,TableScene.LOBBY,null)); repeat(4) { o.observe(TableFrame(1+it*120L,TableScene.TABLE,baseline(),Seat.entries.associateWith { ObservedPlay(emptyList(),1f) })) } }
    @Test fun stableDetectionDeduplicatesAndEmptyOpensBoundary() {
        val l=RoundLedger(); val o=RoundObserver(l); start(o)
        val play=ObservedPlay(listOf(Card(Rank.A,Suit.RED)),.99f)
        for(t in 480L..1320L step 120) o.observe(TableFrame(t,TableScene.TABLE,baseline(),mapOf(Seat.NEXT to play)))
        assertEquals(1,l.snapshot().played(Seat.NEXT))
        for(t in 1440L..1560L step 120) o.observe(TableFrame(t,TableScene.TABLE,null,mapOf(Seat.NEXT to ObservedPlay(emptyList(),1f))))
        for(t in 1680L..2040L step 120) o.observe(TableFrame(t,TableScene.TABLE,null,mapOf(Seat.NEXT to play)))
        assertEquals(2,l.snapshot().played(Seat.NEXT))
    }
    @Test fun missingRegionDoesNotOpenDuplicateBoundary() {
        val l=RoundLedger(); val o=RoundObserver(l); start(o)
        val p=ObservedPlay(listOf(Card(Rank.K,Suit.RED)),1f)
        for(t in 480L..840L step 120) o.observe(TableFrame(t,TableScene.TABLE,null,mapOf(Seat.NEXT to p)))
        for(t in 960L..1200L step 120) o.observe(TableFrame(t,TableScene.TABLE,null,mapOf(Seat.NEXT to null)))
        for(t in 1320L..1800L step 120) o.observe(TableFrame(t,TableScene.TABLE,null,mapOf(Seat.NEXT to p)))
        assertEquals(1,l.snapshot().played(Seat.NEXT))
    }
    @Test fun selfHandDifferenceDoesNotChangeOutsideCounts() {
        val l=RoundLedger(); val o=RoundObserver(l); start(o); val before=l.snapshot().remaining
        for(t in 480L..1320L step 120) o.observe(TableFrame(t,TableScene.TABLE,baseline().drop(2),mapOf(Seat.SELF to ObservedPlay(baseline().take(2),1f))))
        assertEquals(2,l.snapshot().played(Seat.SELF)); assertEquals(before,l.snapshot().remaining)
    }
    @Test fun tributeSuppressesPlayAndNeedsObservedEmpty() {
        val l=RoundLedger(); val o=RoundObserver(l)
        o.observe(TableFrame(0,TableScene.LOBBY,null)); o.observe(TableFrame(1,TableScene.TRIBUTE,baseline()))
        for(t in 120L..600L step 120) o.observe(TableFrame(t,TableScene.TABLE,baseline()))
        assertEquals(RoundPhase.WAITING,l.snapshot().phase)
        for(t in 720L..1680L step 120) o.observe(TableFrame(t,TableScene.TABLE,baseline(),Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }))
        assertEquals(RoundPhase.ACTIVE,l.snapshot().phase)
    }
    @Test fun interruptedActiveRoundDoesNotGuessRecovery() {
        val l=RoundLedger(); val o=RoundObserver(l); start(o)
        o.observe(TableFrame(4000,TableScene.TABLE,baseline()))
        assertNull(l.snapshot().remaining)
        o.observe(TableFrame(4120,TableScene.RESULT,null)); assertNull(l.snapshot().remaining); startAfter(o,5000)
        assertNotNull(l.snapshot().remaining)
    }
    private fun startAfter(o: RoundObserver,time: Long) { repeat(4) { o.observe(TableFrame(time+it*120,TableScene.TABLE,baseline())) } }
    @Test fun coloredCapacityAllowsFourButRejectsFifth() {
        val l=RoundLedger(); l.begin("r",baseline())
        l.commit(PlayEvent("r","p",1,Seat.NEXT,List(3) { Card(Rank.A,Suit.RED) }))
        assertFails { l.commit(PlayEvent("r","q",2,Seat.NEXT,listOf(Card(Rank.A,Suit.RED)))) }
        assertEquals(3,l.snapshot().played(Seat.NEXT))
    }
    @Test fun startingMidRoundWaitsForObservedNewRound() {
        val l=RoundLedger(); val o=RoundObserver(l)
        repeat(10) { o.observe(TableFrame(it*120L,TableScene.TABLE,baseline())) }
        assertNull(l.snapshot().remaining)
    }
    @Test fun missingHandGlyphsWithoutVisiblePlayNeverBecomeSelfEvent() {
        val l=RoundLedger(); val o=RoundObserver(l); start(o)
        for(t in 480L..1440L step 120) o.observe(TableFrame(t,TableScene.TABLE,baseline().drop(2)))
        assertEquals(0,l.snapshot().played(Seat.SELF)); assertEquals(27,l.currentHand().size)
    }
}
