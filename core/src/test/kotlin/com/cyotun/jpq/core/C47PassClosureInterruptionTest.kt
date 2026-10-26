package com.cyotun.jpq.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class C47PassClosureInterruptionTest {
    private val old = Card(Rank.J, Suit.RED)
    private val oldBox = GlyphBox(.4775f, .1579f, .4916f, .2041f)
    private val play = listOf(
        Card(Rank.J, Suit.RED), Card(Rank.TEN, Suit.RED), Card(Rank.NINE, Suit.BLACK),
        Card(Rank.EIGHT, Suit.RED), Card(Rank.SEVEN, Suit.RED),
    )
    private val hand: List<Card>
    private val ledger: RoundLedger
    private val snapshot: RoundSnapshot
    private val prior: QuantityAnchoredPlayProof.PriorPresentation

    init {
        val deck = Rank.entries.flatMap { rank ->
            if(rank.isJoker) List(2) { Card(rank, Suit.NONE) }
            else listOf(Suit.RED, Suit.BLACK).flatMap { suit -> List(4) { Card(rank, suit) } }
        }.toMutableList()
        val external = listOf(old, Card(Rank.BIG, Suit.NONE)) + play
        external.forEach { check(deck.remove(it)) }
        hand = deck.take(27)
        ledger = RoundLedger()
        ledger.begin("c47-r2", hand)
        ledger.commit(PlayEvent("c47-r2", "opp-j", 1, Seat.OPPOSITE, listOf(old)))
        ledger.commit(PlayEvent("c47-r2", "next-big", 2, Seat.NEXT, listOf(Card(Rank.BIG, Suit.NONE))))
        snapshot = ledger.snapshot()
        prior = QuantityAnchoredPlayProof.PriorPresentation(
            "opp-j", listOf(old), listOf(PlayGlyph(old, .87f, oldBox)),
        )
    }

    private fun emptyFrame(time: Long, passSource: Long? = null, handCards: List<Card> = hand,
        scene: TableScene = TableScene.TABLE, captureGap: Boolean = false,
        ambiguous: Boolean = false, reason: String? = null,
        localized: Set<Seat> = emptySet(), dealing: Boolean = false): TableFrame {
        val plays = Seat.entries.associateWith { seat ->
            ObservedPlay(emptyList(), 1f, regionObserved = seat != Seat.OPPOSITE)
        }
        return TableFrame(time, scene, handCards, plays,
            incompleteReason = reason, ambiguousPresentation = ambiguous, captureGap = captureGap,
            dealingChoicesObserved = dealing, localizedOverlaySeats = localized,
            observedPasses = passSource?.let { mapOf(Seat.OPPOSITE to it) }.orEmpty())
    }

    private fun candidateFrame(time: Long, confidence: Float = .87f, weakGlyphConfidence: Float = confidence): TableFrame {
        val glyphs = play.map { card ->
            val left = when(card.rank) {
                Rank.SEVEN -> .4285f
                Rank.EIGHT -> .4531f
                Rank.NINE -> .4776f
                Rank.TEN -> .4997f
                Rank.J -> .5265f
                else -> error("Unexpected fixture card")
            }
            val glyphConfidence=if(card.rank==Rank.NINE) weakGlyphConfidence else .87f
            PlayGlyph(card, glyphConfidence, GlyphBox(left, .1582f, left + .014f, .2046f))
        }
        val shown = ObservedPlay(play, confidence, glyphs)
        val plays = Seat.entries.associateWith { if(it == Seat.OPPOSITE) shown else ObservedPlay(emptyList(), 1f) }
        return TableFrame(time, TableScene.TABLE, hand, plays)
    }

    private fun openPass(proof: PassClosedSupersetProof) {
        assertNull(proof.observe(emptyFrame(1000), snapshot, Seat.OPPOSITE, prior, hand, 900))
        assertNull(proof.observe(emptyFrame(1100, 1100), snapshot, Seat.OPPOSITE, prior, hand, 900))
        assertNull(proof.observe(emptyFrame(1250, 1250), snapshot, Seat.OPPOSITE, prior, hand, 900))
    }

    private fun ambiguity(time: Long, handCards: List<Card> = hand, scene: TableScene = TableScene.TABLE,
        captureGap: Boolean = false, reason: String? = "出牌归属待核对，正在重新识别",
        localized: Set<Seat> = emptySet(), dealing: Boolean = false) =
        emptyFrame(time, handCards = handCards, scene = scene, captureGap = captureGap,
            ambiguous = true, reason = reason, localized = localized, dealing = dealing)

    @Test fun aShortOwnershipOnlyAmbiguityPreservesPassFenceWithoutVoting() {
        val proof = PassClosedSupersetProof()
        openPass(proof)

        assertTrue(proof.retainAcrossOwnershipAmbiguity(
            ambiguity(1515), snapshot, Seat.OPPOSITE, prior, hand, 900,
        ))
        assertNull(proof.observe(candidateFrame(1780), snapshot, Seat.OPPOSITE, prior, hand, 900))
        assertNull(proof.observe(candidateFrame(1840), snapshot, Seat.OPPOSITE, prior, hand, 900))
        assertNull(proof.observe(candidateFrame(1900), snapshot, Seat.OPPOSITE, prior, hand, 900))
        val result = assertNotNull(proof.observe(candidateFrame(1960), snapshot, Seat.OPPOSITE, prior, hand, 900))
        assertEquals(play.map { it.colorKey() }.sorted(), result.cards.map { it.colorKey() }.sorted())
    }

    @Test fun repeatedAmbiguityCannotRefreshTheFixedRetentionDeadline() {
        val proof = PassClosedSupersetProof()
        openPass(proof)
        assertTrue(proof.retainAcrossOwnershipAmbiguity(ambiguity(1515), snapshot, Seat.OPPOSITE, prior, hand, 900))
        assertTrue(proof.retainAcrossOwnershipAmbiguity(ambiguity(2010), snapshot, Seat.OPPOSITE, prior, hand, 900))
        assertFalse(proof.retainAcrossOwnershipAmbiguity(ambiguity(2016), snapshot, Seat.OPPOSITE, prior, hand, 900))
        for(time in listOf(2070L, 2130L, 2190L, 2250L))
            assertNull(proof.observe(candidateFrame(time), snapshot, Seat.OPPOSITE, prior, hand, 900))
    }

    @Test fun handChangesCaptureGapsMasksDealingAndOtherScenesInvalidateTheFence() {
        val absent = Rank.entries.flatMap { rank ->
            if(rank.isJoker) listOf(Card(rank, Suit.NONE))
            else listOf(Card(rank, Suit.RED), Card(rank, Suit.BLACK))
        }.first { it !in hand }
        val changedHand = hand.toMutableList().also { it[0] = absent }
        val invalid = listOf<(Long) -> TableFrame>(
            { t -> ambiguity(t, handCards = changedHand) },
            { t -> ambiguity(t, captureGap = true) },
            { t -> ambiguity(t, localized = setOf(Seat.NEXT)) },
            { t -> ambiguity(t, dealing = true) },
            { t -> ambiguity(t, scene = TableScene.TEAMMATE) },
            { t -> ambiguity(t, reason = "屏幕遮挡") },
        )
        for(make in invalid) {
            val proof = PassClosedSupersetProof()
            openPass(proof)
            assertFalse(proof.retainAcrossOwnershipAmbiguity(
                make(1515), snapshot, Seat.OPPOSITE, prior, hand, 900,
            ))
            for(time in listOf(1575L, 1635L, 1695L, 1755L))
                assertNull(proof.observe(candidateFrame(time), snapshot, Seat.OPPOSITE, prior, hand, 900))
        }
    }

    @Test fun verifiedAppendAdvancesTheClosingFenceDuringOwnershipAmbiguity() {
        val proof = PassClosedSupersetProof()
        openPass(proof)
        val appended = ledger.apply {
            commit(PlayEvent("c47-r2", "previous-four", 3, Seat.PREVIOUS,
                listOf(Card(Rank.FOUR, Suit.BLACK))))
        }.snapshot()
        assertTrue(proof.retainAcrossOwnershipAmbiguity(
            ambiguity(1515), appended, Seat.OPPOSITE, prior, hand, 900,
        ))
        assertNull(proof.observe(candidateFrame(1780), appended, Seat.OPPOSITE, prior, hand, 900))
        assertNull(proof.observe(candidateFrame(1840), appended, Seat.OPPOSITE, prior, hand, 900))
        assertNull(proof.observe(candidateFrame(1900), appended, Seat.OPPOSITE, prior, hand, 900))
        assertNotNull(proof.observe(candidateFrame(1960), appended, Seat.OPPOSITE, prior, hand, 900))
    }

    @Test fun rewrittenClosingHistoryCannotRetainTheWitness() {
        val proof = PassClosedSupersetProof()
        openPass(proof)
        val rewritten = ledger.replaceLatestObservedPresentation("next-big", listOf(
            Card(Rank.BIG, Suit.NONE), play.first(),
        ))
        assertFalse(proof.retainAcrossOwnershipAmbiguity(
            ambiguity(1515), rewritten, Seat.OPPOSITE, prior, hand, 900,
        ))
    }

    @Test fun weakButStructurallyValidFramesAbstainAndPreserveThePassFenceUntilStrongVotesArrive() {
        val proof = PassClosedSupersetProof()
        openPass(proof)
        assertTrue(proof.retainAcrossOwnershipAmbiguity(
            ambiguity(1515), snapshot, Seat.OPPOSITE, prior, hand, 900,
        ))

        // Match the phone trace's first two complete runs: the weakest glyph
        // fluctuates below .80, but remains a valid, distinct-slot detection.
        assertNull(proof.observe(candidateFrame(1780, .7863f, .7863f), snapshot, Seat.OPPOSITE, prior, hand, 900))
        assertFalse(proof.pendingStrongCandidate(1780))
        assertNull(proof.observe(candidateFrame(1840, .7409f, .7409f), snapshot, Seat.OPPOSITE, prior, hand, 900))
        assertFalse(proof.pendingStrongCandidate(1840))

        for(time in listOf(1900L, 1960L, 2020L)) {
            assertNull(proof.observe(candidateFrame(time), snapshot, Seat.OPPOSITE, prior, hand, 900))
            assertTrue(proof.pendingStrongCandidate(time))
            assertFalse(proof.pendingStrongCandidate(time - 1))
        }
        val result = assertNotNull(proof.observe(candidateFrame(2080), snapshot, Seat.OPPOSITE, prior, hand, 900))
        assertEquals(play.map { it.colorKey() }.sorted(), result.cards.map { it.colorKey() }.sorted())
        assertFalse(proof.pendingStrongCandidate(2080))
    }
}
