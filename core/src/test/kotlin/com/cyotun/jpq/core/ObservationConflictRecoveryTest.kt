package com.cyotun.jpq.core

import kotlin.test.*

class ObservationConflictRecoveryTest {
    @Test fun rejectedCapacityCandidateKeepsLedgerLiveAndOtherSeatsContinueWithoutRecoveryBadges() {
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
        assertEquals(before,ledger.snapshot())
        assertTrue(observer.diagnosticStatus().rejectedPlayCandidates>0)
        assertFalse(observer.diagnosticStatus().locked)
        assertEquals(sequence,observer.diagnosticStatus().sequence)
        assertFalse(observer.diagnosticStatus().recovering)
        // The impossible NEXT candidate may persist while PREVIOUS plays a
        // real card. It must not starve this later seat in the same frame.
        for(t in 2000L..2600L step 200) observer.observe(TableFrame(t,TableScene.TABLE,baseline,
            clear+(Seat.NEXT to play(Card(Rank.BIG,Suit.NONE)))+(Seat.PREVIOUS to play(outside[offset]))))
        assertEquals(23,ledger.snapshot().played(Seat.PREVIOUS))
        for(t in 2800L..3400L step 200) observer.observe(TableFrame(t,TableScene.TABLE,baseline,
            clear+(Seat.NEXT to play(outside[offset+1]))))
        assertEquals(22,ledger.snapshot().played(Seat.NEXT))
        assertEquals(17,ledger.snapshot().remaining!!.values.sum())
        assertEquals(sequence+2,observer.diagnosticStatus().sequence)
        assertFalse(RoundPhase.RECOVERING in phases)
        assertFalse(RoundPhase.UNAVAILABLE in phases)
    }

    @Test fun rejectedExpansionCannotRewriteTheOldPlayOrBlockAnotherSeat() {
        val deck=Rank.entries.flatMap { rank -> if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
            else List(4) { Card(rank,Suit.RED) }+List(4) { Card(rank,Suit.BLACK) } }
        val own=deck.take(27); val outside=deck.drop(27)
        val ledger=RoundLedger(); ledger.begin("bad-expansion",own)
        val observer=RoundObserver(ledger)
        fun play(cards: List<Card>,x: Float)=ObservedPlay(cards,.99f,cards.mapIndexed { i,c ->
            val left=x+i*.04f
            PlayGlyph(c,.99f,GlyphBox(left,.3f,left+.03f,.36f)) })
        fun frame(t: Long,previous: ObservedPlay,next: ObservedPlay?=null)=
            TableFrame(t,TableScene.TABLE,own,mapOf(Seat.PREVIOUS to previous,Seat.NEXT to next))
        val old=play(listOf(outside.first()),.2f)
        for(t in 1000L..1360L step 120) observer.observe(frame(t,old))
        val oldEvent=ledger.snapshot().plays.getValue(Seat.PREVIOUS).single()
        val impossible=play(listOf(outside.first(),Card(Rank.BIG,Suit.NONE)),.2f)
        val valid=play(listOf(outside.last()),.7f)
        for(t in 1480L..2680L step 120) observer.observe(frame(t,impossible,valid))
        assertEquals(listOf(oldEvent),ledger.snapshot().plays.getValue(Seat.PREVIOUS))
        assertEquals(1,ledger.snapshot().played(Seat.NEXT))
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
        assertTrue(observer.diagnosticStatus().rejectedPlayCandidates>0)
        assertFalse(observer.diagnosticStatus().recovering)
    }
}
