package com.cyotun.jpq.core

import kotlin.test.*

/** Nine-to-four reveal from the failing tail; only the visible five are added. */
class TeammateRecoveryIntegrationTest {
    private val nine=listOf(Card(Rank.J,Suit.BLACK),Card(Rank.J,Suit.RED),
        Card(Rank.TEN,Suit.BLACK),Card(Rank.TEN,Suit.RED),
        Card(Rank.THREE,Suit.BLACK),Card(Rank.THREE,Suit.RED),
        Card(Rank.TWO,Suit.BLACK),Card(Rank.TWO,Suit.RED),Card(Rank.TWO,Suit.RED))
    private val clear=Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }
    private fun play(cards: List<Card>)=ObservedPlay(cards,.95f,cards.mapIndexed { i,c ->
        PlayGlyph(c,.95f,GlyphBox(.42f+i*.025f,.18f,.44f+i*.025f,.23f))
    })
    private fun fixture(): Pair<RoundLedger,RoundObserver> {
        val available=Rank.entries.flatMap { rank ->
            if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
            else List(4) { Card(rank,Suit.RED) }+List(4) { Card(rank,Suit.BLACK) }
        }.toMutableList().apply { nine.forEach { check(remove(it)) } }
        val own=available.take(27); val other=available.drop(27)
        val ledger=RoundLedger(); ledger.begin("nine-to-four",own)
        ledger.commit(PlayEvent("nine-to-four","self",1,Seat.SELF,own))
        ledger.commit(PlayEvent("nine-to-four","old-opp",2,Seat.OPPOSITE,other.take(18)))
        ledger.commit(PlayEvent("nine-to-four","old-prev",3,Seat.PREVIOUS,other.drop(18).take(22)))
        ledger.commit(PlayEvent("nine-to-four","old-next",4,Seat.NEXT,other.drop(40).take(21)))
        return ledger to RoundObserver(ledger)
    }
    private fun frame(time: Long,hand: List<Card>,desk: ObservedPlay=ObservedPlay(emptyList(),1f),
        counts: Map<Seat,Int> = emptyMap(),source: Long=time)=TableFrame(time,TableScene.TEAMMATE,null,
        clear+(Seat.OPPOSITE to desk),teammateHand=hand,teammateHandConfidence=.95f,
        remainingHandCounts=counts.mapValues { RemainingHandCount(it.value,source) })

    @Test fun completeCheckpointHandAndFreshUnchangedOtherCountsResumeAndContinueWithoutDuplicate() {
        val (ledger,observer)=fixture()
        observer.observe(frame(1000,nine)); observer.observe(frame(1200,nine))
        // An incomplete subset before the capture loss must not replace the seed.
        observer.observe(frame(1400,nine.filterNot { it.rank==Rank.THREE }))
        observer.observe(TableFrame(1600,TableScene.UNKNOWN,null,captureGap=true))
        val five=nine.drop(4); val shown=play(five)
        for(t in 6000L..6400L step 200) observer.observe(frame(t,nine.take(4),shown,
            mapOf(Seat.PREVIOUS to 5,Seat.NEXT to 6)))
        assertFalse(observer.diagnosticStatus().recovering)
        assertEquals(RoundPhase.SELF_FINISHED,ledger.snapshot().phase)
        assertEquals(23,ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(five,ledger.snapshot().plays.getValue(Seat.OPPOSITE).last().cards)
        assertEquals(15,ledger.snapshot().remaining!!.values.sum())
        observer.observe(frame(6600,nine.take(4),shown)); observer.observe(frame(6800,nine.take(4),shown))
        assertEquals(23,ledger.snapshot().played(Seat.OPPOSITE))
        val pair=play(nine.take(2))
        observer.observe(frame(7000,nine.subList(2,4),pair)); observer.observe(frame(7200,nine.subList(2,4),pair))
        assertEquals(25,ledger.snapshot().played(Seat.OPPOSITE))
        assertEquals(3,ledger.snapshot().plays.getValue(Seat.OPPOSITE).size)
        assertEquals(6,observer.diagnosticStatus().sequence)
    }

    @Test fun aLostOtherSeatBadgeOrUnqualifiedSeedCannotResumeTheSameTail() {
        for(seedVotes in listOf(1,2)) {
            val (ledger,observer)=fixture()
            observer.observe(frame(1000,nine))
            if(seedVotes==2) observer.observe(frame(1200,nine))
            observer.observe(TableFrame(1600,TableScene.UNKNOWN,null,captureGap=true))
            val counts=if(seedVotes==1) mapOf(Seat.PREVIOUS to 5,Seat.NEXT to 6) else mapOf(Seat.PREVIOUS to 5)
            for(t in 6000L..6800L step 200) observer.observe(frame(t,nine.take(4),play(nine.drop(4)),counts))
            assertTrue(observer.diagnosticStatus().recovering)
            assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
            assertNull(ledger.snapshot().remaining)
            assertEquals(18,ledger.snapshot().played(Seat.OPPOSITE))
        }
    }
}
