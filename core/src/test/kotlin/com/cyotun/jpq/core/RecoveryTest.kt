package com.cyotun.jpq.core
import kotlin.test.*
class RecoveryTest {
    private val baseline=Rank.entries.filter { !it.isJoker }.flatMap { listOf(Card(it,Suit.RED),Card(it,Suit.BLACK)) }+Card(Rank.SMALL,Suit.NONE)
    private fun empty()=Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }
    @Test fun firstPlayDuringInitialConfirmationIsKept() {
        val ledger=RoundLedger(); val o=RoundObserver(ledger)
        o.observe(TableFrame(0,TableScene.LOBBY,null)); o.observe(TableFrame(60,TableScene.TABLE,baseline,empty()))
        val p=ObservedPlay(listOf(Card(Rank.FOUR,Suit.RED)),.96f)
        for(t in 120L..420L step 60) o.observe(TableFrame(t,TableScene.TABLE,baseline,empty()+ (Seat.NEXT to p)))
        assertEquals(1,ledger.snapshot().played(Seat.NEXT)); assertEquals(80,ledger.snapshot().remaining!!.values.sum())
    }
    @Test fun nextDealingRecoversWithoutReturningToLobby() {
        val ledger=RoundLedger(); val o=RoundObserver(ledger)
        o.observe(TableFrame(0,TableScene.LOBBY,null)); for(t in 60L..240L step 60) o.observe(TableFrame(t,TableScene.TABLE,baseline,empty()))
        o.interrupted("old failure")
        for(t in 5000L..5240L step 60) o.observe(TableFrame(t,TableScene.DEALING,baseline,empty()))
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase); assertEquals(81,ledger.snapshot().remaining!!.values.sum())
    }
    @Test fun oneFrameGetsOnlyOneConfirmationVote() {
        val ledger=RoundLedger(); val o=RoundObserver(ledger)
        o.observe(TableFrame(0,TableScene.LOBBY,null)); o.observe(TableFrame(60,TableScene.DEALING,baseline,empty()))
        val p=ObservedPlay(listOf(Card(Rank.A,Suit.RED)),1f)
        o.observe(TableFrame(120,TableScene.DEALING,baseline,empty()+ (Seat.NEXT to p)))
        o.observe(TableFrame(180,TableScene.DEALING,baseline,empty()+ (Seat.NEXT to p)))
        o.observe(TableFrame(240,TableScene.TABLE,baseline,empty()+ (Seat.NEXT to p)))
        assertEquals(0,ledger.snapshot().played(Seat.NEXT))
        o.observe(TableFrame(300,TableScene.TABLE,baseline,empty()+ (Seat.NEXT to p)))
        assertEquals(1,ledger.snapshot().played(Seat.NEXT))
    }
    @Test fun displayConfigurationDoesNotChangeLedgerAndNewDealStillStarts() {
        val ledger=RoundLedger(); val o=RoundObserver(ledger)
        o.observe(TableFrame(0,TableScene.LOBBY,null)); for(t in 60L..240L step 60) o.observe(TableFrame(t,TableScene.TABLE,baseline,empty()))
        val before=ledger.snapshot()
        val widths=HistoryWidth(); var config=DisplayConfig()
        repeat(4) { config=config.copy(showHistory=!config.showHistory,showReset=!config.showReset,doubleTapHide=!config.doubleTapHide); widths.measure(config,120f,870f) }
        assertEquals(before,ledger.snapshot())
        o.observe(TableFrame(360,TableScene.UNKNOWN,null))
        assertEquals(before,ledger.snapshot())
        assertTrue(o.diagnosticStatus().recovering)
        for(t in 480L..660L step 60) o.observe(TableFrame(t,TableScene.TABLE,baseline,empty()))
        assertEquals(before,ledger.snapshot())
    }
    @Test fun tributeHidesProvisionalBaselineUntilItEnds() {
        val ledger=RoundLedger(); val o=RoundObserver(ledger)
        o.observe(TableFrame(0,TableScene.LOBBY,null)); for(t in 60L..240L step 60) o.observe(TableFrame(t,TableScene.DEALING,baseline,empty()))
        assertNotNull(ledger.snapshot().remaining)
        o.observe(TableFrame(300,TableScene.TRIBUTE,baseline,empty())); assertNull(ledger.snapshot().remaining)
        for(t in 360L..660L step 60) o.observe(TableFrame(t,TableScene.TABLE,baseline,empty()))
        assertEquals(81,ledger.snapshot().remaining!!.values.sum())
    }
    @Test fun lowConfidenceOrUnobservedEmptyRegionsDoNotOpenABaselineWindow() {
        for(play in listOf(ObservedPlay(emptyList(),.3f),ObservedPlay(emptyList(),1f,regionObserved=false))) {
            val ledger=RoundLedger(); val observer=RoundObserver(ledger)
            observer.observe(TableFrame(0,TableScene.LOBBY,null))
            for(time in 60L..360L step 60) observer.observe(TableFrame(time,TableScene.TABLE,baseline,Seat.entries.associateWith { play }))
            assertNull(ledger.snapshot().remaining)
            for(time in 420L..600L step 60) observer.observe(TableFrame(time,TableScene.TABLE,baseline,empty()))
            assertNotNull(ledger.snapshot().remaining)
        }
    }
    @Test fun unobservedEmptyRegionsCannotEndTribute() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger)
        observer.observe(TableFrame(0,TableScene.LOBBY,null)); observer.observe(TableFrame(60,TableScene.TRIBUTE,baseline,empty()))
        for(time in 120L..600L step 60) observer.observe(TableFrame(time,TableScene.TABLE,baseline,empty().mapValues { it.value.copy(regionObserved=false) }))
        assertNull(ledger.snapshot().remaining); assertTrue(observer.diagnosticStatus().tribute)
        for(time in 660L..1080L step 60) observer.observe(TableFrame(time,TableScene.TABLE,baseline,empty()))
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
    }
    @Test fun tributeNeedsConsecutiveTimedEmptyObservations() {
        val ledger=RoundLedger(); val observer=RoundObserver(ledger)
        observer.observe(TableFrame(0,TableScene.LOBBY,null)); observer.observe(TableFrame(60,TableScene.TRIBUTE,baseline,empty()))
        observer.observe(TableFrame(120,TableScene.TABLE,baseline,empty()))
        observer.observe(TableFrame(180,TableScene.TABLE,baseline))
        observer.observe(TableFrame(240,TableScene.TABLE,baseline,empty()))
        observer.observe(TableFrame(300,TableScene.TABLE,baseline,empty()))
        assertTrue(observer.diagnosticStatus().tribute); assertNull(ledger.snapshot().remaining)
        for(time in 360L..540L step 60) observer.observe(TableFrame(time,TableScene.TABLE,baseline,empty()))
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
    }
}
