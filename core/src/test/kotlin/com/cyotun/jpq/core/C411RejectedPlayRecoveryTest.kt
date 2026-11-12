package com.cyotun.jpq.core

import kotlin.test.*

/** Rejected observations must leave a valid ledger available to later evidence. */
class C411RejectedPlayRecoveryTest {
    private val wrong = List(2) { Card(Rank.A, Suit.RED) }
    private val correct = List(2) { Card(Rank.Q, Suit.RED) }
    private val deck = Rank.entries.flatMap { r ->
        if (r.isJoker) List(2) { Card(r, Suit.NONE) }
        else listOf(Suit.RED, Suit.BLACK).flatMap { s -> List(4) { Card(r, s) } }
    }
    private fun initial() = List(4) { Card(Rank.A, Suit.RED) } +
        deck.filter { it.rank !in setOf(Rank.A, Rank.Q, Rank.J) }.take(23)
    private fun play(cards: List<Card>, shift: Float = 0f) = ObservedPlay(cards, .9f,
        cards.mapIndexed { i, c -> PlayGlyph(c, .9f,
            GlyphBox(.70f + i * .03f + shift, .28f, .72f + i * .03f + shift, .32f)) })
    private fun frame(t: Long, cards: List<Card>, count: Int, source: Long = t,
        scene: TableScene = TableScene.TEAMMATE) = TableFrame(t, scene, null,
        Seat.entries.associateWith { if (it == Seat.NEXT) play(cards) else ObservedPlay(emptyList(), 1f) },
        remainingHandCounts = mapOf(Seat.NEXT to RemainingHandCount(count, source)))

    @Test fun rejectedWrongGroupMustNotFenceLaterCorrectShortGroupForTheSameSeatVersion() {
        val ledger = RoundLedger().apply { begin("retry", initial()); selfFinished() }
        val observer = RoundObserver(ledger)
        val before = ledger.snapshot().remaining!!
        observer.observe(frame(500, emptyList(), 27))
        observer.observe(frame(700, emptyList(), 27))
        // Three strong frames, two pre-count sources, one fresh post source.
        // A-red is already exhausted by our initial hand: ledger must reject.
        for (t in listOf(900L, 1000L, 1100L)) observer.observe(frame(t, wrong, 25, 900))
        assertEquals(1L, observer.diagnosticStatus().rejectedPlayCandidates)
        assertEquals(0, ledger.snapshot().played(Seat.NEXT))
        assertEquals(before, ledger.snapshot().remaining)
        // Correct identities return for only three frames, below normal four votes.
        // Independent post sources span 100ms, so quantity proof can recover.
        observer.observe(frame(1200, correct, 25, 1200))
        observer.observe(frame(1300, correct, 25, 1200))
        observer.observe(frame(1400, correct, 25, 1300))
        assertEquals(2, ledger.snapshot().played(Seat.NEXT))
        assertEquals(correct, ledger.snapshot().plays.getValue(Seat.NEXT).single().cards)
        assertEquals(before.getValue(Rank.Q) - 2, ledger.snapshot().remaining!![Rank.Q])
        assertEquals(RoundPhase.SELF_FINISHED, ledger.snapshot().phase)
    }

    @Test fun strictSupersetCapacityRejectionMustAllowFreshCorrectedGroupOnTheSamePrior() {
        val ledger = RoundLedger().apply {
            begin("strict", initial())
            commit(PlayEvent("strict", "prior", 1, Seat.NEXT, listOf(Card(Rank.Q, Suit.RED))))
        }
        val old = play(listOf(Card(Rank.Q, Suit.RED)))
        val prior = QuantityAnchoredPlayProof.PriorPresentation("prior", old.cards, old.glyphs)
        val proof = QuantityAnchoredPlayProof()
        val bad = listOf(Card(Rank.Q, Suit.RED), Card(Rank.A, Suit.RED))
        val good = listOf(Card(Rank.Q, Suit.RED), Card(Rank.J, Suit.RED))
        fun next(t: Long, cards: List<Card>) = frame(t, cards, 24, scene = TableScene.TABLE)
            .let { it.copy(plays = it.plays + (Seat.NEXT to play(cards, -.1f))) }
        var proposed: ObservedPlay? = null
        for (t in listOf(1000L, 1060L, 1120L, 1180L))
            proposed = proof.observeStrictSuperset(next(t, bad), ledger.snapshot(), Seat.NEXT, prior) ?: proposed
        val invalid = assertNotNull(proposed)
        assertFails { ledger.commit(PlayEvent("strict", "new", 2, Seat.NEXT, invalid.cards)) }
        var corrected: ObservedPlay? = null
        for (t in listOf(1240L, 1300L, 1360L, 1420L))
            corrected = proof.observeStrictSuperset(next(t, good), ledger.snapshot(), Seat.NEXT, prior) ?: corrected
        assertEquals(good, assertNotNull(corrected).cards)
    }

    @Test fun rejectedTerminalProposalCannotCloseTheRoundAsVerifiedOnTheNextResultFrame() {
        val initial = initial()
        val available = deck.toMutableList().apply { initial.forEach { assertTrue(remove(it)) } }
        val ledger = RoundLedger().apply {
            begin("terminal-retry", initial)
            commit(PlayEvent("terminal-retry", "old", 1, Seat.NEXT, available.take(25)))
        }
        val emitted = mutableListOf<RoundSnapshot>()
        val observer = RoundObserver(ledger) { emitted += it }
        observer.observe(frame(1000, wrong.take(1), 2, scene = TableScene.TABLE)
            .copy(hand = initial, observedEarlyFinishes = mapOf(Seat.NEXT to 950L)))
        observer.observe(frame(1100, wrong, 0, scene = TableScene.RESULT))
        observer.observe(frame(1200, wrong, 0, scene = TableScene.RESULT))
        assertEquals(1L, observer.diagnosticStatus().rejectedPlayCandidates)
        assertEquals(25, ledger.snapshot().played(Seat.NEXT))
        observer.observe(frame(1300, wrong, 0, scene = TableScene.RESULT))
        assertFalse(emitted.any { it.phase == RoundPhase.FINISHED && it.remaining != null })
        // Deadline expiry must not certify an uncommitted last play.
        observer.observe(frame(2800, wrong, 0, scene = TableScene.RESULT))
        val closed=assertNotNull(emitted.lastOrNull { it.phase==RoundPhase.FINISHED })
        assertNull(closed.remaining)
        assertEquals(25,closed.played(Seat.NEXT))
        assertEquals(RoundPhase.WAITING,ledger.snapshot().phase)
    }

    @Test fun contradictoryCountSourceMustNotBeReusedAfterAnUnchangedCountsReset() {
        val initial = initial()
        val old = play(listOf(Card(Rank.Q, Suit.RED)))
        val recovery = RoundRecovery(100, initial, false,
            mapOf(Seat.NEXT to RoundRecovery.Anchor(old.cards, old.glyphs, "old", 0)),
            false, mapOf(Seat.NEXT to 26))
        fun sample(t: Long, count: Int, source: Long) =
            frame(t, emptyList(), count, source, TableScene.TABLE).copy(hand = initial)
        assertNull(recovery.reconcile(sample(5000, 26, 5000)))
        assertNull(recovery.reconcile(sample(5010, 25, 5000))) // same source contradicts itself
        assertNull(recovery.reconcile(sample(5020, 26, 5000))) // must remain discarded
        assertNull(recovery.reconcile(sample(5150, 26, 5150)))
        assertNull(recovery.reconcile(sample(5300, 26, 5300)))
        // Only 5150/5300 are independent, uncontradicted sources; require a third.
    }
}
