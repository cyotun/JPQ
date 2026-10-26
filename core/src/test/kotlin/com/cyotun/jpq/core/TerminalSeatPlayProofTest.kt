package com.cyotun.jpq.core

import kotlin.test.*

class TerminalSeatPlayProofTest {
    private val seat = Seat.PREVIOUS
    private val fourSevens = listOf(
        Card(Rank.SEVEN, Suit.CLUB), Card(Rank.SEVEN, Suit.SPADE),
        Card(Rank.SEVEN, Suit.HEART), Card(Rank.SEVEN, Suit.DIAMOND)
    )
    private val boxes = (0..26).map { i -> GlyphBox(.08f + i * .026f, .28f, .10f + i * .026f, .33f) }
    private val cards = Rank.entries.flatMap { rank ->
        if (rank.isJoker) List(2) { Card(rank, Suit.NONE) }
        else listOf(Suit.SPADE, Suit.HEART, Suit.CLUB, Suit.DIAMOND).flatMap { suit -> List(2) { Card(rank, suit) } }
    }
    private val selfFinal = Card(Rank.FIVE, Suit.DIAMOND)
    private val initialHistory: List<PlayEvent> = run {
        val available = cards.toMutableList()
        fourSevens.forEach { assertTrue(available.remove(it)) }
        assertTrue(available.remove(selfFinal))
        val prev = available.take(23); prev.forEach { assertTrue(available.remove(it)) }
        val opposite = available.take(16); opposite.forEach { assertTrue(available.remove(it)) }
        val next = available.take(27); next.forEach { assertTrue(available.remove(it)) }
        val own = available.take(26)
        listOf(
            PlayEvent("r1", "p0", 1, Seat.PREVIOUS, prev),
            PlayEvent("r1", "o0", 2, Seat.OPPOSITE, opposite),
            PlayEvent("r1", "n0", 3, Seat.NEXT, next),
            PlayEvent("r1", "s0", 33, Seat.SELF, own)
        )
    }
    private val base = snapshot()
    private val lateFive = listOf(
        Card(Rank.A, Suit.SPADE), Card(Rank.A, Suit.HEART), Card(Rank.K, Suit.SPADE),
        Card(Rank.K, Suit.HEART), Card(Rank.Q, Suit.CLUB)
    )
    private val lateSix = listOf(
        Card(Rank.Q, Suit.SPADE), Card(Rank.Q, Suit.HEART), Card(Rank.J, Suit.SPADE),
        Card(Rank.J, Suit.HEART), Card(Rank.TEN, Suit.SPADE), Card(Rank.TEN, Suit.HEART)
    )

    private fun lateSnapshot(): RoundSnapshot {
        val available = cards.toMutableList()
        (lateFive + lateSix).forEach { assertTrue(available.remove(it)) }
        fun take(n: Int) = available.take(n).also { batch -> batch.forEach { assertTrue(available.remove(it)) } }
        val history = listOf(
            PlayEvent("late", "p", 1, Seat.PREVIOUS, take(27)),
            PlayEvent("late", "o", 2, Seat.OPPOSITE, take(21)),
            PlayEvent("late", "n", 3, Seat.NEXT, take(22)),
            PlayEvent("late", "s", 33, Seat.SELF, take(26))
        )
        return RoundSnapshot("late", RoundPhase.ACTIVE, Rank.entries.associateWith { if (it.isJoker) 2 else 8 },
            Seat.entries.associateWith { seat -> history.filter { it.seat == seat } }, null)
    }

    private fun snapshot(appendSelf: Boolean = false, rewriteTarget: Boolean = false): RoundSnapshot {
        val history = initialHistory.map { event ->
            if (rewriteTarget && event.seat == Seat.PREVIOUS) event.copy(eventId = "p-rewritten") else event
        }.toMutableList()
        if (appendSelf) history += PlayEvent("r1", "s1", 34, Seat.SELF, listOf(selfFinal))
        val plays = Seat.entries.associateWith { seat -> history.filter { it.seat == seat } }
        return RoundSnapshot("r1", RoundPhase.ACTIVE, Rank.entries.associateWith { if (it.isJoker) 2 else 8 }, plays, null)
    }

    private fun glyphs(cards: List<Card>, indices: List<Int>, confidence: Float = .9f) =
        indices.map { i -> PlayGlyph(cards[i], confidence, boxes[i]) }

    private fun play(cards: List<Card>, indices: List<Int>, confidence: Float = .9f) =
        ObservedPlay(cards, confidence, glyphs(cards, indices, confidence), regionObserved = true)

    private fun table(time: Long, cue: Long = time - 50, gap: Boolean = false, count: Int = 1,
                      extraCue: Boolean = false, extraPartial: Boolean = false, ambiguous: Boolean = false,
                      incomplete: String? = null) = TableFrame(
        timeMs = time, scene = TableScene.TABLE, hand = emptyList(),
        plays = buildMap {
            put(seat, play(fourSevens.take(count), (0 until count).toList(), .82f))
            if (extraPartial) {
                val card = initialHistory.first { it.seat == Seat.OPPOSITE }.cards.first()
                put(Seat.OPPOSITE, ObservedPlay(listOf(card), .82f,
                    listOf(PlayGlyph(card, .82f, GlyphBox(.76f, .28f, .78f, .33f)))))
            }
        },
        captureGap = gap, ambiguousPresentation = ambiguous, incompleteReason = incomplete,
        observedEarlyFinishes = buildMap { put(seat, cue); if (extraCue) put(Seat.OPPOSITE, cue) }
    )

    private fun result(time: Long, cards: List<Card> = fourSevens, indices: List<Int> = listOf(0, 1, 2, 3),
                       confidence: Float = .9f, gap: Boolean = false) = TableFrame(
        timeMs = time, scene = TableScene.RESULT, hand = null,
        plays = mapOf(seat to play(cards, indices, confidence)), captureGap = gap
    )

    private fun targetedTable(time: Long, seat: Seat, cards: List<Card>, indices: List<Int>) = TableFrame(
        timeMs = time, scene = TableScene.TABLE, hand = emptyList(),
        plays = mapOf(seat to ObservedPlay(cards, .86f, cards.mapIndexed { i, card -> PlayGlyph(card, .86f, boxes[indices[i]]) })))

    private fun targetedResult(time: Long, seat: Seat, cards: List<Card>, lastPlace: Seat? = Seat.SELF,
                               sourceTime: Long? = time - 50,
                               indices: List<Int> = cards.indices.toList()) = TableFrame(
        timeMs = time, scene = TableScene.RESULT, hand = null,
        plays = mapOf(seat to ObservedPlay(cards, .91f, cards.mapIndexed { i, card -> PlayGlyph(card, .91f, boxes[indices[i]]) })),
        observedLastPlaceSeat = lastPlace,
        observedLastPlaceSourceTimeMs = sourceTime
    )

    @Test fun terminalFullPlayNeedsPreResultAuthorityAndTwoStrongStableVotes() {
        val proof = TerminalSeatPlayProof()
        assertNull(proof.observe(table(1000, cue = 950), base))
        assertNull(proof.observe(table(1100, cue = 1050, count = 2), base))
        assertNull(proof.observe(result(1200), base))
        val recovered = proof.observe(result(1400), base)
        assertEquals(seat, recovered?.seat)
        assertEquals(fourSevens.map { it.colorKey() }.sorted(), recovered?.play?.cards?.map { it.colorKey() }?.sorted())
    }

    @Test fun establishedResultHoldUsesItsOwnDeadlineAfterPartialAuthorityAgesOut() {
        val proof = TerminalSeatPlayProof()
        assertNull(proof.observe(table(1000, cue = 950), base))
        assertNull(proof.observe(result(2400, cards = emptyList(), indices = emptyList()), base))
        assertTrue(proof.holdResult(2400, base))
        assertNull(proof.observe(result(2450), base))
        val recovered = proof.observe(result(2600), base)
        assertEquals(seat, recovered?.seat)
    }

    @Test fun unrelatedRevealedElevenCardHandCannotFillFourCardTarget() {
        val proof = TerminalSeatPlayProof()
        assertNull(proof.observe(table(1000), base))
        val eleven = initialHistory.first { it.seat == Seat.OPPOSITE }.cards.take(11)
        val glyphs = eleven.mapIndexed { i, card -> PlayGlyph(card, .95f, GlyphBox(.1f + i * .02f, .28f, .115f + i * .02f, .33f)) }
        val revealed = TableFrame(1200, TableScene.RESULT, null,
            plays = mapOf(Seat.OPPOSITE to ObservedPlay(eleven, .95f, glyphs)))
        assertNull(proof.observe(revealed, base))
        assertTrue(proof.holdResult(1200, base))
    }

    @Test fun stalePartialOrStaleFinishCueCannotSeedOrHoldResult() {
        val staleCue = TerminalSeatPlayProof()
        assertNull(staleCue.observe(table(1000, cue = -1501), base))
        assertFalse(staleCue.holdResult(1100, base))

        val stalePartial = TerminalSeatPlayProof()
        assertNull(stalePartial.observe(table(1000), base))
        assertNull(stalePartial.observe(result(2601), base))
        assertFalse(stalePartial.holdResult(2601, base))
    }

    @Test fun changedEventHistoryAndCaptureGapInvalidateAuthority() {
        val changed = TerminalSeatPlayProof()
        assertNull(changed.observe(table(1000), base))
        val appendedSelf = snapshot(appendSelf = true)
        assertNull(changed.observe(result(1200), appendedSelf))
        assertTrue(changed.holdResult(1200, appendedSelf))

        val rewritten = TerminalSeatPlayProof()
        assertNull(rewritten.observe(table(1000), base))
        assertNull(rewritten.observe(result(1200), snapshot(rewriteTarget = true)))
        assertFalse(rewritten.holdResult(1200, snapshot(rewriteTarget = true)))

        val gap = TerminalSeatPlayProof()
        assertNull(gap.observe(table(1000, gap = true), base))
        assertNull(gap.observe(result(1200), base))
        assertFalse(gap.holdResult(1200, base))
    }

    @Test fun incompleteWrongRankOrUnstableResultDoesNotCommit() {
        val proof = TerminalSeatPlayProof()
        assertNull(proof.observe(table(1000), base))
        assertNull(proof.observe(result(1200, confidence = .79f), base))
        assertNull(proof.observe(result(1400, cards = listOf(
            Card(Rank.SEVEN, Suit.CLUB), Card(Rank.SEVEN, Suit.SPADE), Card(Rank.SEVEN, Suit.HEART), Card(Rank.SIX, Suit.DIAMOND)
        )), base))
        assertNull(proof.observe(result(1600, gap = true), base))
        assertNull(proof.observe(result(1800), base))
    }

    @Test fun multipleUnfinishedSeatsRequireUniqueCueAndDirectPartial() {
        val proof = TerminalSeatPlayProof()
        assertNull(proof.observe(table(1000, extraCue = true, extraPartial = true), base))
        assertFalse(proof.holdResult(1200, base))
        assertNull(proof.observe(table(1300), base))
        assertTrue(proof.holdResult(1400, base))
    }

    @Test fun tableOwnershipAmbiguityAbstainsAndKeepsRecentAuthority() {
        val proof = TerminalSeatPlayProof()
        assertNull(proof.observe(table(1000), base))
        assertNull(proof.observe(table(1100, ambiguous = true, incomplete = "出牌归属待核对，正在重新识别"), base))
        assertNull(proof.observe(result(1200), base))
        assertNotNull(proof.observe(result(1400), base))
    }

    @Test fun duplicatePhysicalSlotsCannotProveFullGroup() {
        val proof = TerminalSeatPlayProof()
        assertNull(proof.observe(table(1000), base))
        val duplicateBoxes = listOf(boxes[0], boxes[0], boxes[2], boxes[3])
        val duplicate = ObservedPlay(fourSevens, .9f,
            fourSevens.mapIndexed { i, card -> PlayGlyph(card, .9f, duplicateBoxes[i]) })
        assertNull(proof.observe(TableFrame(1200, TableScene.RESULT, null, mapOf(seat to duplicate)), base))
        assertNull(proof.observe(result(1400), base))
    }

    @Test fun lastPlaceCueAuthorizesOnlyOtherSeatsKnownFinalPlay() {
        val snap = lateSnapshot()
        val nextProof = TerminalSeatPlayProof(Seat.NEXT)
        assertNull(nextProof.observe(targetedTable(1000, Seat.NEXT, lateFive.take(2), listOf(0, 1)), snap))
        assertNull(nextProof.observe(targetedResult(1100, Seat.NEXT, lateFive), snap))
        val next = nextProof.observe(targetedResult(1250, Seat.NEXT, lateFive), snap)
        assertEquals(Seat.NEXT, next?.seat)
        assertEquals(lateFive.map { it.colorKey() }.sorted(), next?.play?.cards?.map { it.colorKey() }?.sorted())

        val oppositeProof = TerminalSeatPlayProof(Seat.OPPOSITE)
        assertNull(oppositeProof.observe(targetedTable(2000, Seat.OPPOSITE, lateSix.take(2), listOf(0, 1)), snap))
        assertNull(oppositeProof.observe(targetedResult(2100, Seat.OPPOSITE, lateSix, lastPlace = Seat.PREVIOUS), snap))
        val opposite = oppositeProof.observe(targetedResult(2250, Seat.OPPOSITE, lateSix, lastPlace = Seat.PREVIOUS), snap)
        assertEquals(Seat.OPPOSITE, opposite?.seat)
        assertEquals(lateSix.map { it.colorKey() }.sorted(), opposite?.play?.cards?.map { it.colorKey() }?.sorted())
    }

    @Test fun targetLastPlaceCueCannotTurnItsRevealedHandIntoAPlay() {
        val snap = lateSnapshot()
        val proof = TerminalSeatPlayProof(Seat.NEXT)
        assertFails { TerminalSeatPlayProof(Seat.SELF) }
        assertNull(proof.observe(targetedTable(1000, Seat.NEXT, lateFive.take(2), listOf(0, 1)), snap))
        assertNull(proof.observe(targetedResult(1100, Seat.NEXT, lateFive, lastPlace = Seat.NEXT), snap))
        assertFalse(proof.holdResult(1100, snap))
        assertNull(proof.observe(targetedResult(1250, Seat.NEXT, lateFive, lastPlace = Seat.NEXT), snap))
    }

    @Test fun tentativePartialCannotCommitOrHoldWithoutOtherPlayersLastPlaceCue() {
        val snap = lateSnapshot()
        val proof = TerminalSeatPlayProof(Seat.NEXT)
        assertNull(proof.observe(targetedTable(1000, Seat.NEXT, lateFive.take(2), listOf(0, 1)), snap))
        assertNull(proof.observe(targetedResult(1100, Seat.NEXT, lateFive, lastPlace = null), snap))
        assertFalse(proof.holdResult(1100, snap))
    }

    @Test fun lastPlaceAuthorizationExpiresFromOcrSourceTimeNotDeliveryTime() {
        val snap = lateSnapshot()
        val proof = TerminalSeatPlayProof(Seat.OPPOSITE)
        assertNull(proof.observe(targetedTable(1000, Seat.OPPOSITE, lateSix.take(2), listOf(0, 1)), snap))

        // OCR captured the opposite-team last-place badge at 1000, but a
        // delayed callback delivers it at 2000, the adapter's 1000 ms limit.
        assertNull(proof.observe(targetedResult(2000, Seat.OPPOSITE, lateSix,
            lastPlace = Seat.PREVIOUS, sourceTime = 1000), snap))
        assertNull(proof.observe(targetedResult(3350, Seat.OPPOSITE, lateSix,
            lastPlace = Seat.PREVIOUS, sourceTime = 1000), snap))
        assertNull(proof.observe(targetedResult(3500, Seat.OPPOSITE, lateSix,
            lastPlace = Seat.PREVIOUS, sourceTime = 1000), snap))
    }

    @Test fun missingInvalidOrStaleLastPlaceSourceTimeCannotAuthorizeResult() {
        val snap = lateSnapshot()
        for(sourceTime in listOf<Long?>(null,-1L,1101L,0L)) {
            val proof = TerminalSeatPlayProof(Seat.OPPOSITE)
            assertNull(proof.observe(targetedTable(1000, Seat.OPPOSITE, lateSix.take(2), listOf(0, 1)), snap))
            val deliveredAt=if(sourceTime==1101L) 1100L else 1100L
            assertNull(proof.observe(targetedResult(deliveredAt, Seat.OPPOSITE, lateSix,
                lastPlace = Seat.PREVIOUS, sourceTime = sourceTime), snap))
            assertFalse(proof.holdResult(deliveredAt, snap))
        }
    }

    @Test fun sameTeamAutomaticThirdCannotAuthorizeRemainingSixCards() {
        val snap = lateSnapshot()
        val proof = TerminalSeatPlayProof(Seat.OPPOSITE)
        assertNull(proof.observe(targetedTable(1000, Seat.OPPOSITE, lateSix.take(2), listOf(0, 1)), snap))
        assertNull(proof.observe(targetedResult(1100, Seat.OPPOSITE, lateSix, lastPlace = Seat.SELF), snap))
        assertFalse(proof.holdResult(1100, snap))
        assertNull(proof.observe(targetedResult(1250, Seat.OPPOSITE, lateSix, lastPlace = Seat.SELF), snap))
    }
}
