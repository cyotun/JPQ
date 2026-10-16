package com.cyotun.jpq.core

import kotlin.test.*

class RecoveryTransactionTest {
    private val deck=Rank.entries.flatMap { rank ->
        if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
        else List(4) { Card(rank,Suit.RED) }+List(4) { Card(rank,Suit.BLACK) }
    }
    private fun fixture(): RoundLedger = RoundLedger().also {
        it.begin("round",deck.take(27))
        it.commit(PlayEvent("round","old",1,Seat.NEXT,listOf(deck[27])))
        it.suspendForRecovery("missing capture")
    }

    @Test fun recoveryAndProvedNewCardsBecomeCurrentAtomicallyAndAdvanceSequence() {
        val ledger=fixture()
        val after=ledger.resumeAfterRecovery(mapOf("old" to deck.subList(27,29)),
            listOf(PlayEvent("round","recovered",2,Seat.OPPOSITE,deck.subList(29,34))))
        assertEquals(RoundPhase.ACTIVE,after.phase)
        assertEquals(2,after.played(Seat.NEXT)); assertEquals(5,after.played(Seat.OPPOSITE))
        assertEquals(74,after.remaining!!.values.sum())
        assertFails { ledger.commit(PlayEvent("round","reused-sequence",2,Seat.PREVIOUS,listOf(deck[34]))) }
        assertEquals(after,ledger.snapshot())
        ledger.commit(PlayEvent("round","following",3,Seat.PREVIOUS,listOf(deck[34])))
        assertEquals(73,ledger.snapshot().remaining!!.values.sum())
    }

    @Test fun anyInvalidRecoveredEventLeavesBothLedgerAndRecoveryStateUntouched() {
        val valid=PlayEvent("round","first",2,Seat.OPPOSITE,listOf(deck[29]))
        val next=valid.copy(eventId="second",sequence=3,cards=listOf(deck[30]))
        for(invalid in listOf(
            next.copy(roundId="other"), next.copy(seat=Seat.SELF),
            next.copy(eventId=""), next.copy(eventId="old"), next.copy(eventId="first"), next.copy(cards=emptyList()),
            next.copy(sequence=2), next.copy(cards=List(9) { deck[30] }),
        )) {
            val ledger=fixture(); val before=ledger.snapshot()
            assertFails { ledger.resumeAfterRecovery(mapOf("old" to deck.subList(27,29)),listOf(valid,invalid)) }
            assertEquals(before,ledger.snapshot())
            assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
            assertNull(ledger.snapshot().remaining)
            // A failed batch cannot consume even its first valid event/sequence.
            ledger.resumeAfterRecovery(emptyMap(),listOf(valid))
            assertEquals(1,ledger.snapshot().played(Seat.OPPOSITE))
        }
    }
}
