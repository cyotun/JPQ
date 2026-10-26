package com.cyotun.jpq.core

import kotlin.test.*

/** C4.9: a visible 33322 reaches three strong reads before an unrelated own animation. */
class C410OwnershipAnimationObserverTest {
    private fun play(cards: List<Card>,score: Float=.9f)=ObservedPlay(cards,score,cards.mapIndexed { i,c ->
        PlayGlyph(c,score,GlyphBox(.16f+i*.025f,.28f,.18f+i*.025f,.34f)) })

    @Test fun retainedOutsideFiveCommitsAfterOwnAnimationFromTwoOriginalDelayedBadgeSources() {
        val deck=Rank.entries.flatMap { rank -> if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
            else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } } }
        val five=List(3) { Card(Rank.THREE,Suit.RED) }+Card(Rank.TWO,Suit.RED)+Card(Rank.TWO,Suit.BLACK)
        val earlier=listOf(Rank.SIX,Rank.SEVEN,Rank.EIGHT,Rank.NINE,Rank.TEN).map { rank -> List(3) { Card(rank,Suit.BLACK) } }
        val following=listOf(Rank.EIGHT,Rank.NINE,Rank.FIVE,Rank.J,Rank.Q).map { Card(it,Suit.RED) }
        val lastPair=List(2) { Card(Rank.EIGHT,Suit.RED) }
        val rest=deck.toMutableList().apply { (earlier.flatten()+five+following+lastPair).forEach { assertTrue(remove(it)) } }
        val own=rest.take(27)
        val ledger=RoundLedger().apply { begin("ownership-five",own) }
        var sequence=0L
        fun prefix(seat: Seat,cards: List<Card>) {
            sequence++
            ledger.commit(PlayEvent("ownership-five","prefix-$sequence",sequence,seat,cards))
        }
        earlier.forEach { prefix(Seat.PREVIOUS,it) }
        own.take(16).forEach { prefix(Seat.SELF,listOf(it)) }
        rest.drop(27).take(27).forEach { prefix(Seat.OPPOSITE,listOf(it)) }
        rest.drop(54).forEach { prefix(Seat.NEXT,listOf(it)) }
        val hand=ledger.currentHand()
        val ownMove=hand.filter { it.rank==Rank.Q }.take(5)
        assertEquals(5,ownMove.size)
        val handAfter=hand.toMutableList().apply { ownMove.forEach { assertTrue(remove(it)) } }
        val snapshots=mutableListOf<RoundSnapshot>()
        val observer=RoundObserver(ledger) { snapshots+=it }
        val empty=ObservedPlay(emptyList(),1f)
        fun frame(t: Long,p: ObservedPlay=empty,ambiguous: Boolean=false,source: Long?=null,ownMoves: Boolean=false)=
            TableFrame(t,TableScene.TABLE,if(ownMoves) handAfter else hand,
                Seat.entries.associateWith { if(it==Seat.PREVIOUS) p else empty },
                ambiguousPresentation=ambiguous,
                incompleteReason=if(ambiguous) "出牌归属待核对，正在重新识别" else null,
                remainingHandCounts=source?.let { mapOf(Seat.PREVIOUS to RemainingHandCount(7,it)) }.orEmpty())
        observer.observe(frame(1700)); observer.observe(frame(1850))
        observer.observe(frame(2000,play(five,.847f)))
        observer.observe(frame(2233,play(five.dropLast(1),.701f)))
        observer.observe(frame(2432,play(five.dropLast(1),.736f)))
        observer.observe(frame(2649,play(five.dropLast(1),.596f)))
        for((t,score) in listOf(2848L to .861f,3048L to .881f,3280L to .881f))
            observer.observe(frame(t,play(five,score)))
        assertEquals(15,ledger.snapshot().played(Seat.PREVIOUS))
        observer.observe(frame(3546,play(five,.879f),ambiguous=true,ownMoves=true))
        observer.observe(frame(3747,ambiguous=true,source=3280,ownMoves=true))
        observer.observe(frame(3996,source=3280,ownMoves=true)) // actual OCR age 716ms
        assertEquals(15,ledger.snapshot().played(Seat.PREVIOUS))
        observer.observe(frame(4261,source=3747,ownMoves=true)) // second distinct source, age 514ms
        assertEquals(20,ledger.snapshot().played(Seat.PREVIOUS))
        assertEquals(five,ledger.snapshot().plays.getValue(Seat.PREVIOUS).last().cards)
        assertEquals(RoundPhase.ACTIVE,ledger.snapshot().phase)
        assertFalse(observer.diagnosticStatus().recovering)
        observer.observe(frame(4494,source=3747,ownMoves=true))
        assertEquals(20,ledger.snapshot().played(Seat.PREVIOUS))
        for(t in 4610L..4970L step 120) observer.observe(frame(t,ownMoves=true).let {
            it.copy(plays=it.plays+(Seat.SELF to play(ownMove)))
        })
        assertEquals(21,ledger.snapshot().played(Seat.SELF))
        for(t in 5090L..5450L step 120) observer.observe(frame(t,play(following),ownMoves=true))
        assertEquals(25,ledger.snapshot().played(Seat.PREVIOUS))
        observer.observe(frame(5570,ownMoves=true)); observer.observe(frame(5690,ownMoves=true))
        for(t in 5810L..6170L step 120) observer.observe(frame(t,play(lastPair),ownMoves=true))
        val finished=snapshots.last { it.phase==RoundPhase.FINISHED }
        assertEquals(27,finished.played(Seat.PREVIOUS))
        assertEquals(21,finished.played(Seat.SELF)) // last-place hand was never played
        assertEquals(0,finished.remaining!!.values.sum())
        observer.observe(frame(6290,ownMoves=true).copy(scene=TableScene.RESULT,
            plays=mapOf(Seat.SELF to play(handAfter))))
        assertEquals(RoundPhase.WAITING,ledger.snapshot().phase)
        assertEquals(1,snapshots.count { it.phase==RoundPhase.FINISHED })
    }
}
