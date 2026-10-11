package com.cyotun.jpq.core

import kotlin.test.*
import org.junit.Test

class RoundLedgerTest {
    private val baseline = Rank.entries.filterNot { it.isJoker }.flatMap { r ->
        listOf(Suit.SPADE,Suit.HEART,Suit.CLUB,Suit.DIAMOND).flatMap { s -> List(2) { Card(r,s) } }
    }.takeLast(27)
    private fun available(ledger: RoundLedger) = Rank.entries.flatMap { r ->
        if (r.isJoker) List(2) { Card(r,Suit.NONE) }
        else listOf(Suit.SPADE,Suit.HEART,Suit.CLUB,Suit.DIAMOND).flatMap { s -> List(2) { Card(r,s) } }
    }.toMutableList().also { all -> baseline.forEach { all.remove(it) } }

    @Test fun ownPlayDoesNotDeductTwice() {
        val ledger=RoundLedger(); val before=ledger.begin("a",baseline)
        val after=ledger.commit(PlayEvent("a","self1",1,Seat.SELF,listOf(baseline[0])))
        assertEquals(81,before.remaining!!.values.sum()); assertEquals(before.remaining,after.remaining)
    }
    @Test fun stableFramesDeduplicateButLaterIdenticalHandCounts() {
        val ledger=RoundLedger(); ledger.begin("a",baseline)
        val card=available(ledger).groupingBy { it }.eachCount().entries.first { it.value == 2 }.key
        val event=PlayEvent("a","1",1,Seat.PREVIOUS,listOf(card))
        ledger.commit(event); ledger.commit(event)
        assertEquals(1,ledger.snapshot().played(Seat.PREVIOUS))
        ledger.commit(event.copy(eventId="2",sequence=2))
        assertEquals(2,ledger.snapshot().played(Seat.PREVIOUS))
        assertEquals(79,ledger.snapshot().remaining!!.values.sum())
    }
    @Test fun wrongPhysicalSuitOrOverdrawRejectsAtomically() {
        val ledger=RoundLedger(); val before=ledger.begin("a",baseline)
        val exhausted=baseline.groupingBy { it }.eachCount().entries.first { it.value==2 }.key
        assertFailsWith<IllegalArgumentException> { ledger.commit(PlayEvent("a","bad",1,Seat.NEXT,listOf(exhausted))) }
        assertEquals(before,ledger.snapshot())
    }
    @Test fun incompleteBaselineAndCrossRoundRejected() {
        val ledger=RoundLedger()
        assertFailsWith<IllegalArgumentException> { ledger.begin("a",baseline.take(26)) }
        ledger.begin("a",baseline)
        assertFailsWith<IllegalArgumentException> { ledger.commit(PlayEvent("b","1",1,Seat.SELF,listOf(baseline[0]))) }
    }
    @Test fun teammateDisplayMustNotReplaceSelfBaseline() {
        val ledger=RoundLedger(); ledger.begin("a",baseline)
        val expected=ledger.snapshot().remaining
        val after=ledger.commit(PlayEvent("a","finished",1,Seat.SELF,baseline))
        assertEquals(RoundPhase.SELF_FINISHED,after.phase); assertEquals(expected,after.remaining)
    }
    @Test fun repeatedInitializationDoesNotClearHistoryOrChangeBaseline() {
        val ledger=RoundLedger(); ledger.begin("a",baseline)
        val card=available(ledger).first()
        ledger.commit(PlayEvent("a","1",1,Seat.NEXT,listOf(card)))
        val state=ledger.snapshot()
        assertEquals(state,ledger.begin("a",baseline))
        assertEquals(state,ledger.begin("a",baseline.reversed()))
        assertFailsWith<IllegalArgumentException> { ledger.begin("a",baseline.drop(1)+available(ledger).first { it!=baseline[0] }) }
        assertFailsWith<IllegalStateException> { ledger.begin("b",baseline) }
        assertEquals(state,ledger.snapshot())
    }
    @Test fun unavailableDoesNotPresentStalePreciseNumbers() {
        val ledger=RoundLedger(); ledger.begin("a",baseline)
        assertNull(ledger.invalidate("gap").remaining)
        assertFailsWith<IllegalStateException> { ledger.commit(PlayEvent("a","1",1,Seat.SELF,listOf(baseline[0]))) }
        assertEquals(RoundPhase.WAITING,ledger.waitForNext().phase)
    }
    @Test fun originalColorThresholdsAndBothOrders() {
        assertEquals(CountTone.RED,countTone(Rank.BIG,2)); assertEquals(CountTone.WHITE,countTone(Rank.SMALL,1))
        assertEquals(CountTone.RED,countTone(Rank.A,4)); assertEquals(CountTone.WHITE,countTone(Rank.A,3))
        assertEquals(CountTone.UNKNOWN,countTone(Rank.A,null)); assertEquals(CountTone.ZERO,countTone(Rank.A,0))
        assertEquals(CountTone.UNKNOWN,countTone(Rank.A,-1))
        assertEquals(Rank.TWO,guandanOrder.last()); assertEquals(Rank.TWO,landlordOrder[2])
        assertTrue(validOrder(guandanOrder)); assertFalse(validOrder(guandanOrder.dropLast(1)+Rank.A))
    }
    @Test fun widthExpandsRightOnlyAndResetsForNextRound() {
        val config=DisplayConfig(); val w=HistoryWidth()
        val base=w.measure(config,158.25f,1000f); val wide=w.measure(config,466.806f,1000f)
        assertEquals(base.grid,wide.grid); assertEquals(511f,wide.history)
        assertEquals(wide,w.measure(config,10f,1000f))
        w.newRound(); assertEquals(base,w.measure(config,158.25f,1000f))
        assertEquals(base.grid,w.measure(config.copy(showHistory=false),100f,1000f).grid,.001f)
        assertEquals(700f,w.measure(config,466.806f,700f).total)
    }
    @Test fun explicitSizeChangeRecomputesBaseWithoutLosingRecordSetting() {
        val config=DisplayConfig(); val width=HistoryWidth()
        width.measure(config,466.806f,1000f)
        val smaller=width.measure(config.copy(widthScale=.8f),20f,1000f)
        assertTrue(smaller.history<320f)
        assertEquals(smaller.history,width.measure(config.copy(widthScale=.8f),1f,1000f).history)
    }
}
