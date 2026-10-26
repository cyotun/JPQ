package com.cyotun.jpq.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Observer-level coverage for a seat-local ambiguity that must not suspend the whole round. */
class SeatLocalReviewObserverTest {
    private val deck = Rank.entries.flatMap { rank ->
        if (rank.isJoker) List(2) { Card(rank, Suit.NONE) }
        else listOf(Suit.RED, Suit.BLACK).flatMap { suit -> List(4) { Card(rank, suit) } }
    }

    private data class Fixture(
        val ledger: RoundLedger,
        val observer: RoundObserver,
        val own: List<Card>,
        val old: Card,
        val candidate: List<Card>,
        val nextCards: List<Card>,
        val snapshots: MutableList<RoundSnapshot>,
    )

    private fun fixture(id: String = "seat-review") : Fixture {
        val own = deck.take(27)
        val outside = deck.drop(27)
        // Both the old singleton and a new triple need four outside copies;
        // deck.drop(27).first() has only one outside colour copy remaining.
        val old = outside.first { card -> outside.count { it == card } == 4 }
        val candidate = List(3) { old }
        val nextCards = outside.drop(3).filter { it.rank != old.rank }.distinctBy { it.colorKey() }.take(2)
        val ledger = RoundLedger()
        ledger.begin(id, own)
        val snapshots = mutableListOf<RoundSnapshot>()
        return Fixture(ledger, RoundObserver(ledger) { snapshots += it }, own, old, candidate, nextCards, snapshots)
    }

    private fun observed(cards: List<Card>, startX: Float, y: Float = .24f): ObservedPlay =
        ObservedPlay(cards, .96f, cards.mapIndexed { i, card ->
            val x = startX + i * .055f
            PlayGlyph(card, .96f, GlyphBox(x, y, x + .035f, y + .06f))
        })

    private fun frame(
        time: Long,
        hand: List<Card>?,
        previous: ObservedPlay? = null,
        next: ObservedPlay? = null,
        self: ObservedPlay? = null,
        scene: TableScene = TableScene.TABLE,
        counts: Map<Seat, RemainingHandCount> = emptyMap(),
    ) = TableFrame(time, scene, hand,
        mapOf(Seat.PREVIOUS to previous, Seat.OPPOSITE to null, Seat.NEXT to next, Seat.SELF to self),
        remainingHandCounts = counts)

    private fun warmOldRow(f: Fixture, start: Long = 1000L) {
        val oldRow = observed(listOf(f.old), .70f)
        for (time in start..start + 360L step 120L)
            f.observer.observe(frame(time, f.own, previous = oldRow))
        assertEquals(1, f.ledger.snapshot().played(Seat.PREVIOUS))
    }

    @Test fun heldPreviousSupersetDoesNotBlockIndependentNextAndOwnPlaysThenCountSourcesResolveExactCards() {
        val f = fixture()
        warmOldRow(f)
        val previousBefore = f.ledger.snapshot().plays.getValue(Seat.PREVIOUS).toList()
        // The old card is no longer in its old physical row. The complete
        // strict superset is directly legible at distinct new slots.
        val superset = observed(f.candidate, .14f)
        for (time in 1480L..2680L step 240L) {
            f.observer.observe(frame(time, f.own, previous = superset))
            assertEquals(RoundPhase.ACTIVE, f.ledger.snapshot().phase)
            assertEquals(previousBefore, f.ledger.snapshot().plays.getValue(Seat.PREVIOUS))
        }

        // Other seats remain live while PREVIOUS is locally held.
        val nextOne = observed(f.nextCards.take(1), .45f, .48f)
        for (time in 2800L..3160L step 120L)
            f.observer.observe(frame(time, f.own, previous = superset, next = nextOne))
        assertEquals(1, f.ledger.snapshot().played(Seat.NEXT))
        val nextTwo = observed(f.nextCards.drop(1), .45f, .48f)
        for (time in 3280L..3640L step 120L)
            f.observer.observe(frame(time, f.own, previous = superset, next = nextTwo))
        assertEquals(2, f.ledger.snapshot().played(Seat.NEXT))

        val ownCard = f.own.first()
        val reducedOwn = f.own.drop(1)
        val ownPlay = observed(listOf(ownCard), .50f, .58f)
        for (time in 3760L..4120L step 120L)
            f.observer.observe(frame(time, reducedOwn, previous = superset, next = nextTwo, self = ownPlay))
        assertEquals(1, f.ledger.snapshot().played(Seat.SELF))
        assertEquals(RoundPhase.ACTIVE, f.ledger.snapshot().phase)

        // Three fresh, post-certificate count sources match a three-card new
        // play. The journal may append exactly those already observed cards.
        for ((time, source) in listOf(4240L to 4240L, 4390L to 4390L, 4540L to 4540L)) {
            val count = RemainingHandCount(23, source)
            f.observer.observe(frame(time, reducedOwn, previous = superset, next = nextTwo,
                counts = mapOf(Seat.PREVIOUS to count)))
        }
        val previousEvents = f.ledger.snapshot().plays.getValue(Seat.PREVIOUS)
        assertEquals(previousBefore.size + 1, previousEvents.size)
        assertEquals(previousBefore.single().eventId, previousEvents.first().eventId)
        assertEquals(f.candidate, previousEvents.last().cards)
        assertEquals(4, f.ledger.snapshot().played(Seat.PREVIOUS))
        assertEquals(RoundPhase.ACTIVE, f.ledger.snapshot().phase)
        for (time in 4660L..4900L step 120L)
            f.observer.observe(frame(time, reducedOwn, previous = superset, next = nextTwo))
        assertEquals(previousEvents, f.ledger.snapshot().plays.getValue(Seat.PREVIOUS))
    }

    @Test fun weakCandidateCannotBeAuthorizedByCountsOrGuessMissingCards() {
        val f = fixture("seat-review-weak")
        warmOldRow(f)
        val weak = observed(f.candidate, .14f).copy(confidence = .79f)
        for (time in 1480L..2680L step 240L) {
            f.observer.observe(frame(time, f.own, previous = weak,
                counts = mapOf(Seat.PREVIOUS to RemainingHandCount(23, time))))
        }
        assertEquals(1, f.ledger.snapshot().played(Seat.PREVIOUS))
        assertTrue(f.ledger.snapshot().phase in setOf(RoundPhase.ACTIVE, RoundPhase.RECOVERING))
        assertFalse(f.observer.diagnosticStatus().locked)
    }

    @Test fun shortOwnershipAmbiguityCannotReuseEarlierLocalQuantityVotes() {
        val f=fixture("local-short-ambiguity")
        warmOldRow(f)
        val shown=observed(f.candidate,.14f)
        for(t in 1480L..3280L step 120) f.observer.observe(frame(t,f.own,previous=shown))
        for(t in listOf(3400L,3550L)) f.observer.observe(frame(t,f.own,previous=shown,
            counts=mapOf(Seat.PREVIOUS to RemainingHandCount(23,t))))
        f.observer.observe(frame(3620,f.own,previous=shown).copy(ambiguousPresentation=true,
            incompleteReason="出牌归属待核对，正在重新识别"))
        for(t in listOf(3700L,3820L,3940L,4060L)) {
            f.observer.observe(frame(t,f.own,previous=shown,counts=mapOf(Seat.PREVIOUS to RemainingHandCount(23,t))))
            assertEquals(1,f.ledger.snapshot().played(Seat.PREVIOUS))
        }
        for(t in listOf(4180L,4330L,4480L)) f.observer.observe(frame(t,f.own,previous=shown,
            counts=mapOf(Seat.PREVIOUS to RemainingHandCount(23,t))))
        assertEquals(4,f.ledger.snapshot().played(Seat.PREVIOUS))
        assertFalse(f.observer.diagnosticStatus().recovering)
    }

    @Test fun unrelatedLocalizedMaskRecoveryCannotDemandTheHeldSeatsOldPixelsOrPermanentlyFenceIt() {
        val f=fixture("local-mask-with-held-seat")
        warmOldRow(f)
        val shown=observed(f.candidate,.14f)
        val empty=ObservedPlay(emptyList(),1f)
        fun clear(t: Long)=frame(t,f.own,previous=shown,next=empty).copy(
            plays=mapOf(Seat.PREVIOUS to shown,Seat.OPPOSITE to empty,Seat.NEXT to empty),
            remainingHandCounts=mapOf(Seat.OPPOSITE to RemainingHandCount(27,t),Seat.NEXT to RemainingHandCount(27,t)))
        for(t in 1480L..3280L step 120) f.observer.observe(clear(t))
        for(t in listOf(3400L,3550L,3700L)) f.observer.observe(clear(t).copy(
            incompleteReason="local overlay",localizedOverlaySeats=setOf(Seat.NEXT),
            plays=clear(t).plays+(Seat.NEXT to empty.copy(regionObserved=false))))
        f.observer.observe(clear(3850))
        assertEquals(RoundPhase.ACTIVE,f.ledger.snapshot().phase)
        assertFalse(f.observer.diagnosticStatus().recovering)
        for(t in listOf(3970L,4090L,4210L,4330L)) f.observer.observe(clear(t))
        for(t in listOf(4450L,4600L,4750L)) f.observer.observe(clear(t).copy(
            remainingHandCounts=clear(t).remainingHandCounts+(Seat.PREVIOUS to RemainingHandCount(23,t))))
        assertEquals(4,f.ledger.snapshot().played(Seat.PREVIOUS))
        assertTrue(f.observer.diagnosticStatus().pendingReviewSeats.isEmpty())
    }

    @Test fun localReviewCannotLeakAcrossRoundOrClaimRemainingAtResult() {
        val f = fixture("seat-review-end")
        warmOldRow(f)
        val superset = observed(f.candidate, .14f)
        for (time in 1480L..2680L step 240L)
            f.observer.observe(frame(time, f.own, previous = superset))
        val beforeResult = f.ledger.snapshot()
        assertEquals(1, beforeResult.played(Seat.PREVIOUS))
        f.observer.observe(frame(2800L, null, scene = TableScene.RESULT))
        assertTrue(f.snapshots.any { it.phase == RoundPhase.FINISHED && it.remaining == null })
        assertNull(f.ledger.snapshot().remaining)

        f.ledger.waitForNext()
        f.ledger.begin("seat-review-new-round", f.own)
        f.observer.observe(frame(3000L, f.own, previous = superset))
        assertEquals(0, f.ledger.snapshot().played(Seat.PREVIOUS))
        assertEquals("seat-review-new-round", f.ledger.snapshot().roundId)
        assertNotNull(f.ledger.snapshot().remaining)
    }
}
