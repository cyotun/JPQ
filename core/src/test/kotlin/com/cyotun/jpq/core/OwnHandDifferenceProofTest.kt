package com.cyotun.jpq.core

import kotlin.test.*

class OwnHandDifferenceProofTest {
    private val known = deck().take(27)
    private val removed = known.takeLast(5)
    private val hand = known.dropLast(5)
    private val round = "own-difference"

    private fun snapshot(
        phase: RoundPhase = RoundPhase.ACTIVE,
        ownEvents: List<PlayEvent> = emptyList(),
        otherEvents: List<PlayEvent> = emptyList()
    ) = RoundSnapshot(round, phase, Rank.entries.associateWith { rank ->
        (if(rank.isJoker) 2 else 8)-known.count { it.rank==rank }
    }, buildMap {
        if (ownEvents.isNotEmpty()) put(Seat.SELF, ownEvents)
        if (otherEvents.isNotEmpty()) put(Seat.OPPOSITE, otherEvents)
    }, null)

    private fun play(
        cards: List<Card> = removed,
        confidence: Float = .88f,
        glyphConfidence: Float = confidence,
        regionObserved: Boolean = true,
        boxes: List<GlyphBox> = boxes(cards.size)
    ) = ObservedPlay(cards, confidence,
        cards.mapIndexed { index, card -> PlayGlyph(card, glyphConfidence, boxes[index]) },
        regionObserved)

    private fun frame(
        time: Long,
        cards: List<Card> = removed,
        observedHand: List<Card> = hand,
        observedPlay: ObservedPlay? = play(cards),
        scene: TableScene = TableScene.TABLE,
        phaseFault: String? = null,
        ambiguous: Boolean = false,
        captureGap: Boolean = false,
        localizedOverlaySeats: Set<Seat> = emptySet(),
        selfFinished: Boolean = false
    ) = TableFrame(time, scene, observedHand, mapOf(Seat.SELF to observedPlay), selfFinished,
        phaseFault, ambiguous, captureGap, localizedOverlaySeats = localizedOverlaySeats)

    private fun positive(p: OwnHandDifferenceProof, s: RoundSnapshot = snapshot()): ObservedPlay? {
        var result: ObservedPlay? = null
        for (t in listOf(1000L, 1100L, 1200L)) result = p.observe(frame(t), s, known) ?: result
        return result
    }

    @Test fun threeFreshStableFramesReturnTheExactObservedPlay() {
        val result = assertNotNull(positive(OwnHandDifferenceProof()))
        assertEquals(removed, result.cards)
        assertEquals(removed, result.glyphs.map { it.card })
    }

    @Test fun twoFramesDoNotCommitAndThirdFrameMustReachMinimumSpan() {
        val p = OwnHandDifferenceProof()
        assertNull(p.observe(frame(1000), snapshot(), known))
        assertNull(p.observe(frame(1100), snapshot(), known))
        assertNull(p.observe(frame(1150), snapshot(), known))
        assertNotNull(p.observe(frame(1200), snapshot(), known))
    }

    @Test fun oneWeakGroupOrGlyphConfidenceBreaksTheCandidate() {
        val p = OwnHandDifferenceProof()
        assertNull(p.observe(frame(1000, observedPlay = play(confidence = .79f)), snapshot(), known))
        assertNull(p.observe(frame(1100, observedPlay = play(glyphConfidence = .79f)), snapshot(), known))
        assertNull(p.observe(frame(1200), snapshot(), known))
        assertNull(p.observe(frame(1300), snapshot(), known))
        assertNotNull(p.observe(frame(1400), snapshot(), known))
    }

    @Test fun observedSelfGroupMustEqualTheMultisetDifferenceExactly() {
        val p = OwnHandDifferenceProof()
        val wrongHand = known.dropLast(4)
        for (t in 1000L..1400L step 100) {
            assertNull(p.observe(frame(t, observedHand = wrongHand), snapshot(), known))
        }
    }

    @Test fun handMustBeNonemptyAndStrictlySmallerThanKnownHand() {
        val p = OwnHandDifferenceProof()
        assertNull(p.observe(frame(1000, observedHand = emptyList()), snapshot(), known))
        assertNull(p.observe(frame(1100, observedHand = known), snapshot(), known))
        assertNull(p.observe(frame(1200), snapshot(), known))
        assertNull(p.observe(frame(1300), snapshot(), known))
    }

    @Test fun invalidGeometryAndMismatchedGlyphIdentityAreRejected() {
        val p = OwnHandDifferenceProof()
        val invalid = boxes(removed.size).toMutableList().also {
            it[0] = GlyphBox(.2f, .2f, .2f, .3f)
        }
        assertNull(p.observe(frame(1000, observedPlay = play(boxes = invalid)), snapshot(), known))
        val wrongGlyphs = play().glyphs.toMutableList().also {
            it[0] = PlayGlyph(Card(Rank.A, Suit.RED), .88f, it[0].box)
        }
        assertNull(p.observe(frame(1100, observedPlay = play().copy(glyphs = wrongGlyphs)), snapshot(), known))
        assertNull(p.observe(frame(1200), snapshot(), known))
        assertNull(p.observe(frame(1300), snapshot(), known))
    }

    @Test fun changedCardSlotsRestartRatherThanBorrowPriorVotes() {
        val p = OwnHandDifferenceProof()
        assertNull(p.observe(frame(1000), snapshot(), known))
        assertNull(p.observe(frame(1100), snapshot(), known))
        val shifted = boxes(removed.size, offset = .16f)
        assertNull(p.observe(frame(1200, observedPlay = play(boxes = shifted)), snapshot(), known))
        assertNull(p.observe(frame(1300, observedPlay = play(boxes = shifted)), snapshot(), known))
        assertNotNull(p.observe(frame(1400, observedPlay = play(boxes = shifted)), snapshot(), known))
        assertNull(p.observe(frame(1500, observedPlay = play(boxes = shifted)), snapshot(), known))
    }

    @Test fun sceneAndAnyFrameFaultResetTheProof() {
        val p = OwnHandDifferenceProof()
        assertNull(p.observe(frame(1000), snapshot(), known))
        assertNull(p.observe(frame(1100, scene = TableScene.TEAMMATE), snapshot(), known))
        assertNull(p.observe(frame(1200), snapshot(), known))
        assertNull(p.observe(frame(1300, phaseFault = "incomplete"), snapshot(), known))
        assertNull(p.observe(frame(1400), snapshot(), known))
        assertNull(p.observe(frame(1500, ambiguous = true), snapshot(), known))
        assertNull(p.observe(frame(1600), snapshot(), known))
        assertNull(p.observe(frame(1700, captureGap = true), snapshot(), known))
        assertNull(p.observe(frame(1800), snapshot(), known))
        assertNull(p.observe(frame(1900, localizedOverlaySeats = setOf(Seat.SELF)), snapshot(), known))
        assertNull(p.observe(frame(2000), snapshot(), known))
        assertNull(p.observe(frame(2100), snapshot(), known))
    }

    @Test fun selfFinishedAndRecoveryPhasesCannotSeedOrContinueProof() {
        val p = OwnHandDifferenceProof()
        assertNull(p.observe(frame(1000, selfFinished = true), snapshot(), known))
        assertNull(p.observe(frame(1100), snapshot(RoundPhase.SELF_FINISHED), known))
        assertNull(p.observe(frame(1200), snapshot(RoundPhase.RECOVERING), known))
        assertNull(p.observe(frame(1300), snapshot(), known))
        assertNull(p.observe(frame(1400), snapshot(), known))
    }

    @Test fun ownCheckpointOrOwnEventVersionChangeRestartsVotes() {
        val p = OwnHandDifferenceProof()
        assertNull(p.observe(frame(1000), snapshot(), known))
        assertNull(p.observe(frame(1100), snapshot(), known))
        val changedHand = known.dropLast(1)
        val event = PlayEvent(round, "self-1", 1, Seat.SELF, listOf(known.last()))
        fun changedFrame(t: Long) = frame(t, cards = changedHand.takeLast(5),
            observedHand = changedHand.dropLast(5))
        assertNull(p.observe(changedFrame(1200), snapshot(ownEvents = listOf(event)), changedHand))
        assertNull(p.observe(changedFrame(1300), snapshot(ownEvents = listOf(event)), changedHand))
        assertNotNull(p.observe(changedFrame(1400), snapshot(ownEvents = listOf(event)), changedHand))
        assertNull(p.observe(changedFrame(1500), snapshot(ownEvents = listOf(event)), changedHand))
    }

    @Test fun unrelatedOpponentEventsDoNotInvalidateOwnCandidate() {
        val p = OwnHandDifferenceProof()
        assertNull(p.observe(frame(1000), snapshot(), known))
        val opponentEvent = PlayEvent(round, "opp-1", 1, Seat.OPPOSITE, listOf(deck()[80]))
        assertNull(p.observe(frame(1100), snapshot(otherEvents = listOf(opponentEvent)), known))
        assertNotNull(p.observe(frame(1200), snapshot(otherEvents = listOf(opponentEvent)), known))
    }

    @Test fun successfulVersionCanCommitOnlyOnce() {
        val p = OwnHandDifferenceProof()
        assertNotNull(positive(p))
        for (t in 1300L..1800L step 100) assertNull(p.observe(frame(t), snapshot(), known))
    }

    @Test fun unrelatedRemainingCardColorFlickerDoesNotHideAnExactOwnedPlay() {
        val p=OwnHandDifferenceProof()
        val changed=hand.toMutableList()
        val index=changed.indexOfFirst { !it.rank.isJoker }
        val original=changed[index]
        changed[index]=Card(original.rank,if(original.suit==Suit.RED) Suit.BLACK else Suit.RED)
        assertNull(p.observe(frame(1000,observedHand=changed),snapshot(),known))
        assertNull(p.observe(frame(1100,observedHand=changed),snapshot(),known))
        assertEquals(removed,p.observe(frame(1200,observedHand=changed),snapshot(),known)?.cards)
    }

    @Test fun rankDifferenceNeverSuppliesAnUnownedPlayedColor() {
        val owned=List(4) { Card(Rank.TWO,Suit.RED) }+deck().filter { it.rank!=Rank.TWO }.take(23)
        val remaining=owned.drop(4)
        val wrong=List(4) { Card(Rank.TWO,Suit.BLACK) }
        val p=OwnHandDifferenceProof()
        for(t in 1000L..1400L step 100)
            assertNull(p.observe(frame(t,cards=wrong,observedHand=remaining),snapshot(),owned))
    }

    @Test fun staleOrGappedFramesCannotCompleteAProof() {
        val p = OwnHandDifferenceProof()
        assertNull(p.observe(frame(1000), snapshot(), known))
        assertNull(p.observe(frame(999), snapshot(), known))
        assertNull(p.observe(frame(1100), snapshot(), known))
        assertNull(p.observe(frame(1700), snapshot(), known))
        assertNull(p.observe(frame(1800), snapshot(), known))
    }

    private fun boxes(count: Int, offset: Float = 0f) = List(count) { index ->
        val left = .25f + offset + index * .055f
        GlyphBox(left, .34f, left + .035f, .44f)
    }

    companion object {
        private fun deck(): List<Card> = Rank.entries.flatMap { rank ->
            if (rank.isJoker) List(2) { Card(rank, Suit.NONE) }
            else listOf(Suit.RED, Suit.BLACK).flatMap { suit -> List(4) { Card(rank, suit) } }
        }
    }
}
