package com.cyotun.jpq.core

import kotlin.test.*

class SecondRoundFinishedSeatRegressionTest {
    private fun cards(vararg values: String)=values.map { value ->
        val fields=value.split(':')
        Card(Rank.valueOf(fields[0]),if(fields.size==1) Suit.NONE else Suit.valueOf(fields[1]))
    }
    // Anonymized initial multiset and NEXT sequence from the second real-round reference
    // raster replay. Other seats' earlier events are outside this focused regression.
    private val baseline=cards("BIG","SIX:BLACK","A:BLACK","A:RED","K:RED","K:RED","Q:BLACK",
        "J:RED","J:RED","J:RED","TEN:BLACK","NINE:BLACK","NINE:BLACK","EIGHT:RED","EIGHT:RED",
        "EIGHT:BLACK","SEVEN:RED","SEVEN:RED","FIVE:RED","FIVE:RED","FIVE:RED","FIVE:BLACK",
        "FOUR:RED","THREE:BLACK","THREE:RED","TWO:RED","TWO:BLACK")
    private val nextPlays=listOf(
        cards("FIVE:BLACK"),cards("SIX:BLACK"),cards("SMALL"),
        cards("SEVEN:BLACK","SEVEN:BLACK","SEVEN:BLACK","SEVEN:RED"),cards("TWO:RED"),
        cards("NINE:BLACK","SIX:RED","NINE:RED","NINE:RED"),cards("THREE:BLACK","THREE:RED"),
        cards("J:BLACK","J:BLACK","J:BLACK","J:BLACK"),
        cards("TEN:RED","TEN:RED","K:BLACK","K:RED","K:RED"),
        cards("Q:RED","Q:BLACK","Q:RED","Q:RED"),
    )
    private inner class Fixture(includeLastPlay: Boolean) {
        val ledger=RoundLedger()
        val observer=RoundObserver(ledger)
        var time=0L
        init {
            observer.observe(TableFrame(0,TableScene.LOBBY,null))
            repeat(3) {
                time+=100
                observer.observe(TableFrame(time,TableScene.TABLE,baseline,Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }))
            }
            val history=if(includeLastPlay) nextPlays else nextPlays.dropLast(1)
            history.forEach { show(Seat.NEXT,ObservedPlay(it,.99f)) }
        }
        fun tick(plays: Map<Seat,ObservedPlay?>) {
            time+=100; observer.observe(TableFrame(time,TableScene.TABLE,null,plays))
        }
        fun clear(seat: Seat) { repeat(3) { tick(mapOf(seat to ObservedPlay(emptyList(),1f))) } }
        fun show(seat: Seat,play: ObservedPlay) {
            clear(seat); repeat(4) { tick(mapOf(seat to play)) }
        }
    }

    @Test fun theFinishedRightPlayersQueensReturnAfterVisibleTableWithoutBeingDeductedAgain() {
        // 609208ms: NEXT legitimately reaches 27 with QQQQ. During the following bomb
        // the old QQQQ move away and expose actual table, then return at about 611067ms.
        // The visible table is real; completed-seat state is the decisive boundary proof.
        val fixture=Fixture(true)
        val before=fixture.ledger.snapshot()
        assertEquals(27,before.played(Seat.NEXT)); assertEquals(54,before.remaining!!.values.sum())
        fixture.show(Seat.NEXT,ObservedPlay(nextPlays.last(),.99f))
        assertEquals(before,fixture.ledger.snapshot())
        assertEquals(0,fixture.observer.diagnosticStatus().playVotes[Seat.NEXT])
        assertEquals(RoundPhase.ACTIVE,fixture.ledger.snapshot().phase)
    }

    @Test fun aFinishedSeatHasNoCandidatesButAnotherSeatStillRecordsItsPlay() {
        val fixture=Fixture(true)
        val before=fixture.ledger.snapshot()
        fixture.clear(Seat.NEXT)
        val newPlay=cards("BIG")
        repeat(4) {
            fixture.tick(mapOf(Seat.NEXT to ObservedPlay(nextPlays.last(),.99f),Seat.PREVIOUS to ObservedPlay(newPlay,.99f)))
        }
        val after=fixture.ledger.snapshot()
        assertEquals(27,after.played(Seat.NEXT)); assertEquals(before.plays[Seat.NEXT],after.plays[Seat.NEXT])
        assertEquals(1,after.played(Seat.PREVIOUS)); assertEquals(53,after.remaining!!.values.sum())
        assertEquals(RoundPhase.ACTIVE,after.phase)
        assertEquals(0,fixture.observer.diagnosticStatus().playVotes[Seat.NEXT])
    }

    @Test fun aStillPlayingSeatCanReallyPlayTheSamePairAgainAndReachTwentySeven() {
        val fixture=Fixture(false)
        assertEquals(23,fixture.ledger.snapshot().played(Seat.NEXT))
        val pair=cards("Q:RED","Q:BLACK")
        fixture.show(Seat.NEXT,ObservedPlay(pair,.99f))
        assertEquals(25,fixture.ledger.snapshot().played(Seat.NEXT))
        fixture.show(Seat.NEXT,ObservedPlay(pair,.99f))
        assertEquals(27,fixture.ledger.snapshot().played(Seat.NEXT))
        val lastTwo=fixture.ledger.snapshot().plays[Seat.NEXT]!!.takeLast(2)
        assertEquals(pair,lastTwo[0].cards); assertEquals(pair,lastTwo[1].cards)
        assertNotEquals(lastTwo[0].eventId,lastTwo[1].eventId)
        assertEquals(RoundPhase.ACTIVE,fixture.ledger.snapshot().phase)
    }

    @Test fun theFinalPresentationMayStillExpandWhileThePlayerHasOnlyTwentyFourConfirmedCards() {
        val fixture=Fixture(false)
        val full=nextPlays.last().mapIndexed { index,card ->
            PlayGlyph(card,.99f,GlyphBox(.73f+index*.028f,.28f,.755f+index*.028f,.34f))
        }
        fixture.show(Seat.NEXT,ObservedPlay(listOf(full.first().card),.99f,listOf(full.first())))
        assertEquals(24,fixture.ledger.snapshot().played(Seat.NEXT))
        val eventId=fixture.ledger.snapshot().plays[Seat.NEXT]!!.last().eventId
        repeat(4) { fixture.tick(mapOf(Seat.NEXT to ObservedPlay(full.map { it.card },.99f,full))) }
        val after=fixture.ledger.snapshot()
        assertEquals(27,after.played(Seat.NEXT)); assertEquals(eventId,after.plays[Seat.NEXT]!!.last().eventId)
        assertEquals(nextPlays.last(),after.plays[Seat.NEXT]!!.last().cards)
        assertEquals(RoundPhase.ACTIVE,after.phase)
        fixture.show(Seat.NEXT,ObservedPlay(full.map { it.card },.99f,full))
        assertEquals(after,fixture.ledger.snapshot())
    }
}
