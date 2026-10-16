package com.cyotun.jpq.core

import kotlin.test.*

class ObservationConflictRecoveryTest {
    @Test fun rejectedCapacityCandidateDoesNotPoisonTheCheckpointOrStopLaterIndependentRecovery() {
        val deck=Rank.entries.flatMap { rank ->
            if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
            else List(4) { Card(rank,Suit.RED) }+List(4) { Card(rank,Suit.BLACK) }
        }
        val baseline=deck.take(27); val outside=deck.drop(27)
        val ledger=RoundLedger(); ledger.begin("capacity",baseline)
        var sequence=0L; var offset=0
        for((seat,count) in listOf(Seat.PREVIOUS to 22,Seat.OPPOSITE to 19,Seat.NEXT to 21)) repeat(count) {
            sequence++
            ledger.commit(PlayEvent("capacity","old-$sequence",sequence,seat,listOf(outside[offset++])))
        }
        val before=ledger.snapshot(); val phases=mutableListOf<RoundPhase>()
        val observer=RoundObserver(ledger) { phases.add(it.phase) }
        val clear=Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }
        observer.observe(TableFrame(1000,TableScene.TABLE,baseline,clear))
        fun play(card: Card)=ObservedPlay(listOf(card),.99f,
            listOf(PlayGlyph(card,.99f,GlyphBox(.75f,.30f,.77f,.35f))))
        // Both BIG cards are already in our immutable baseline: this candidate
        // cannot be written, even after it survives ordinary display voting.
        for(t in 1200L..1800L step 200) observer.observe(TableFrame(t,TableScene.TABLE,baseline,
            clear+(Seat.NEXT to play(Card(Rank.BIG,Suit.NONE)))))
        assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
        assertEquals(before.plays,ledger.snapshot().plays)
        assertNull(ledger.snapshot().remaining)
        assertFalse(observer.diagnosticStatus().locked)
        assertEquals(sequence,observer.diagnosticStatus().sequence)
        val counts=mapOf(Seat.PREVIOUS to 5,Seat.OPPOSITE to 8,Seat.NEXT to 6)
        for(t in 6000L..6400L step 200) observer.observe(TableFrame(t,TableScene.TABLE,baseline,clear,
            remainingHandCounts=counts.mapValues { RemainingHandCount(it.value,t) }))
        assertEquals(before,ledger.snapshot())
        assertFalse(observer.diagnosticStatus().recovering)
        for(t in 6600L..7200L step 200) observer.observe(TableFrame(t,TableScene.TABLE,baseline,
            clear+(Seat.NEXT to play(outside[offset]))))
        assertEquals(22,ledger.snapshot().played(Seat.NEXT))
        assertEquals(18,ledger.snapshot().remaining!!.values.sum())
        assertEquals(sequence+1,observer.diagnosticStatus().sequence)
        assertFalse(RoundPhase.UNAVAILABLE in phases)
    }
}
