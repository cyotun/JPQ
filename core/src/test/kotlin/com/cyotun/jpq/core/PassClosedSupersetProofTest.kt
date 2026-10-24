package com.cyotun.jpq.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PassClosedSupersetProofTest {
    private val old = Card(Rank.TWO, Suit.BLACK)
    private val added = listOf(
        Card(Rank.A, Suit.BLACK), Card(Rank.Q, Suit.RED),
        Card(Rank.FOUR, Suit.BLACK), Card(Rank.FIVE, Suit.BLACK),
    )
    private val other = Card(Rank.THREE, Suit.RED)
    private val oldBox = GlyphBox(.70f, .28f, .74f, .34f)
    private val hand: List<Card>
    private val ledger: RoundLedger
    private val snapshot: RoundSnapshot
    private val prior: QuantityAnchoredPlayProof.PriorPresentation

    init {
        val deck = Rank.entries.flatMap { rank ->
            if (rank.isJoker) List(2) { Card(rank, Suit.NONE) }
            else listOf(Suit.RED, Suit.BLACK).flatMap { suit -> List(4) { Card(rank, suit) } }
        }
        val reserved = listOf(old, other) + added
        val available = deck.toMutableList()
        reserved.forEach { check(available.remove(it)) }
        hand = available.take(27)
        ledger = RoundLedger()
        ledger.begin("pass-superset", hand)
        ledger.commit(PlayEvent("pass-superset", "opp-1", 1, Seat.OPPOSITE, listOf(old)))
        ledger.commit(PlayEvent("pass-superset", "prev-2", 2, Seat.PREVIOUS, listOf(other)))
        snapshot = ledger.snapshot()
        prior = QuantityAnchoredPlayProof.PriorPresentation(
            "opp-1", listOf(old), listOf(PlayGlyph(old, .95f, oldBox)),
        )
    }

    private fun pass(time: Long, source: Long? = time, handCards: List<Card> = hand,
        captureGap: Boolean = false, ambiguous: Boolean = false, incomplete: String? = null,
        scene: TableScene = TableScene.TABLE, passRegionObserved: Boolean = false): TableFrame {
        val empty = ObservedPlay(emptyList(), 1f, regionObserved = passRegionObserved)
        return TableFrame(
            timeMs = time, scene = scene, hand = handCards,
            plays = Seat.entries.associateWith { if (it == Seat.OPPOSITE) empty else ObservedPlay(emptyList(), 1f) },
            incompleteReason = incomplete, ambiguousPresentation = ambiguous, captureGap = captureGap,
            observedPasses = source?.let { mapOf(Seat.OPPOSITE to it) }.orEmpty(),
        )
    }

    private fun group(
        cards: List<Card> = listOf(old) + added,
        time: Long,
        oldAtOldSlot: Boolean = false,
        qAtOldSlot: Boolean = false,
        confidence: Float = .92f,
        glyphConfidence: Float = confidence,
        regionObserved: Boolean = true,
    ): TableFrame {
        val glyphs = cards.mapIndexed { index, card ->
            val box = when {
                oldAtOldSlot && card.colorKey() == old.colorKey() -> oldBox
                // R2-like case: a new Q glyph can occupy the old 2's center;
                // the old physical card itself is in a new slot.
                qAtOldSlot && card.rank == Rank.Q -> oldBox
                else -> GlyphBox(.20f + index * .085f, .28f, .24f + index * .085f, .34f)
            }
            PlayGlyph(card, glyphConfidence, box)
        }
        val play = ObservedPlay(cards, confidence, glyphs, regionObserved)
        return TableFrame(time, TableScene.TABLE, hand,
            Seat.entries.associateWith { if (it == Seat.OPPOSITE) play else ObservedPlay(emptyList(), 1f) })
    }

    private fun openBoundary(proof: PassClosedSupersetProof) {
        assertNull(proof.observe(pass(1000, source = null), snapshot, Seat.OPPOSITE, prior, hand, 900))
        assertNull(proof.observe(pass(1100), snapshot, Seat.OPPOSITE, prior, hand, 900))
        assertNull(proof.observe(pass(1250), snapshot, Seat.OPPOSITE, prior, hand, 900))
    }

    private fun prove(proof: PassClosedSupersetProof): ObservedPlay? {
        openBoundary(proof)
        assertNull(proof.observe(group(time = 1300), snapshot, Seat.OPPOSITE, prior, hand, 900))
        assertNull(proof.observe(group(time = 1360), snapshot, Seat.OPPOSITE, prior, hand, 900))
        assertNull(proof.observe(group(time = 1420), snapshot, Seat.OPPOSITE, prior, hand, 900))
        return proof.observe(group(time = 1480), snapshot, Seat.OPPOSITE, prior, hand, 900)
    }

    @Test fun passClosureAllowsOnlyFreshSpatiallyNewStrictSupersetAndEmitsOnce() {
        val proof = PassClosedSupersetProof()
        val result = assertNotNull(prove(proof))
        assertEquals((listOf(old) + added).map { it.colorKey() }.sorted(), result.cards.map { it.colorKey() }.sorted())
        assertNull(proof.observe(group(time = 1540), snapshot, Seat.OPPOSITE, prior, hand, 900))
    }

    @Test fun newRankAtOldCenterIsAllowedButOldCardAtItsOldCenterIsNot() {
        val allowed = PassClosedSupersetProof()
        openBoundary(allowed)
        for (t in listOf(1300L, 1360L, 1420L))
            assertNull(allowed.observe(group(time = t,qAtOldSlot = true), snapshot, Seat.OPPOSITE, prior, hand, 900))
        assertNotNull(allowed.observe(group(time = 1480, qAtOldSlot = true), snapshot,
            Seat.OPPOSITE, prior, hand, 900))

        val blocked = PassClosedSupersetProof()
        openBoundary(blocked)
        for (t in listOf(1300L, 1360L, 1420L, 1480L))
            assertNull(blocked.observe(group(time = t, oldAtOldSlot = true), snapshot,
                Seat.OPPOSITE, prior, hand, 900))
    }

    @Test fun cachedBackdatedStaleOrTooCloselySpacedPassesCannotOpenBoundary() {
        val cases = listOf(
            listOf(1100L to 1100L, 1200L to 1100L), // repeated OCR source
            listOf(1100L to 999L, 1250L to 1250L), // predates other-event fence
            listOf(2200L to 1100L, 2250L to 1250L), // stale source timestamps
            listOf(1100L to 1100L, 1150L to 1150L), // not 100 ms apart
        )
        for (pair in cases) {
            val proof = PassClosedSupersetProof()
            assertNull(proof.observe(pass(1000, source = null), snapshot, Seat.OPPOSITE, prior, hand, 900))
            pair.forEach { (time, source) ->
                assertNull(proof.observe(pass(time, source), snapshot, Seat.OPPOSITE, prior, hand, 900))
            }
            for(t in (pair.last().first+50)..(pair.last().first+230) step 60)
                assertNull(proof.observe(group(time = t),snapshot,Seat.OPPOSITE,prior,hand,900))
        }
    }

    @Test fun oldGlyphSourceHandSceneAndFaultsInvalidatePendingPassEvidence() {
        val faultFrames = listOf<(Long) -> TableFrame>(
            { t -> pass(t, captureGap = true) },
            { t -> pass(t, ambiguous = true) },
            { t -> pass(t, incomplete = "masked") },
            { t -> pass(t, scene = TableScene.TEAMMATE) },
            { t -> pass(t, handCards = hand.drop(1) + Card(Rank.SEVEN, Suit.BLACK)) },
        )
        for (fault in faultFrames) {
            val proof = PassClosedSupersetProof()
            assertNull(proof.observe(pass(1000, source = null), snapshot, Seat.OPPOSITE, prior, hand, 900))
            assertNull(proof.observe(pass(1100), snapshot, Seat.OPPOSITE, prior, hand, 900))
            assertNull(proof.observe(fault(1250), snapshot, Seat.OPPOSITE, prior, hand, 900))
            for(t in 1300L..1480L step 60)
                assertNull(proof.observe(group(time = t),snapshot,Seat.OPPOSITE,prior,hand,900))
        }

        val proof = PassClosedSupersetProof()
        assertNull(proof.observe(pass(1000, source = null), snapshot, Seat.OPPOSITE, prior, hand, 1000))
        assertNull(proof.observe(pass(1100), snapshot, Seat.OPPOSITE, prior, hand, 1000))
        assertNull(proof.observe(pass(1250), snapshot, Seat.OPPOSITE, prior, hand, 1000))
        assertNull(proof.observe(group(time = 1300), snapshot, Seat.OPPOSITE, prior, hand, 1000))
    }

    @Test fun unrelatedLatestEventAndMissingOwnHandProofCannotBorrowTheWitness() {
        val proof = PassClosedSupersetProof()
        openBoundary(proof)
        val newerTarget = ledger.apply {
            commit(PlayEvent("pass-superset", "opp-3", 3, Seat.OPPOSITE, listOf(added.first())))
        }.snapshot()
        assertNull(proof.observe(group(time = 1300), newerTarget, Seat.OPPOSITE, prior, hand, 900))
        assertNull(proof.observe(group(time = 1360), snapshot, Seat.OPPOSITE, prior, hand.drop(1), 900))
    }

    @Test fun sameBoxExpansionWithoutAQualifiedPassIsNotAProof() {
        val proof = PassClosedSupersetProof()
        assertNull(proof.observe(pass(1000, source = null), snapshot, Seat.OPPOSITE, prior, hand, 900))
        for (t in listOf(1100L, 1250L, 1300L, 1360L, 1420L, 1480L))
            assertNull(proof.observe(group(time = t, oldAtOldSlot = true), snapshot,
                Seat.OPPOSITE, prior, hand, 900))
    }

    @Test fun recoloringTheOldRankAtItsSlotDoesNotProveItsPhysicalDeparture() {
        val proof=PassClosedSupersetProof();openBoundary(proof)
        for(t in 1300L..1480L step 60) {
            val original=group(time=t)
            val p=original.plays.getValue(Seat.OPPOSITE)!!
            val changed=p.glyphs.map { if(it.card.rank==Rank.Q) it.copy(card=Card(Rank.TWO,Suit.RED),box=oldBox) else it }
            val frame=original.copy(plays=original.plays+(Seat.OPPOSITE to p.copy(cards=changed.map { it.card },glyphs=changed)))
            assertNull(proof.observe(frame,snapshot,Seat.OPPOSITE,prior,hand,900))
        }
    }

    @Test fun noLaterOtherSeatEventOrAnObservationGapCannotBorrowPassEvidence() {
        val noOther=snapshot.copy(plays=snapshot.plays.mapValues { (seat,events) -> if(seat==Seat.OPPOSITE) events else emptyList() })
        val proof=PassClosedSupersetProof()
        for(t in 1000L..1200L step 100) assertNull(proof.observe(pass(t),noOther,Seat.OPPOSITE,prior,hand,900))
        for(t in 1300L..1480L step 60) assertNull(proof.observe(group(time=t),noOther,Seat.OPPOSITE,prior,hand,900))
        val gap=PassClosedSupersetProof(); openBoundary(gap)
        for(t in 2000L..2180L step 60) assertNull(gap.observe(group(time=t),snapshot,Seat.OPPOSITE,prior,hand,900))
    }
}
