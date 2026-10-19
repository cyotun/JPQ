package com.cyotun.jpq.core

import kotlin.test.*

/** Exercises the short, localized-mask recovery proof without private capture fixtures. */
class LocalizedPendingPlayRecoveryTest {
    private data class Setup(
        val ledger: RoundLedger,
        val helper: LocalizedPendingPlayRecovery,
        val own: List<Card>,
        val candidate: List<Card>,
        val oldMask: List<Card>,
        val empty: ObservedPlay,
        val prior: TableFrame,
        val first: TableFrame,
        val second: TableFrame,
        val mask: TableFrame,
        val before: List<PlayEvent>,
    )

    private val deck = Rank.entries.flatMap { rank ->
        if (rank.isJoker) List(2) { Card(rank, Suit.NONE) }
        else listOf(Suit.RED, Suit.BLACK).flatMap { suit -> List(4) { Card(rank, suit) } }
    }

    private fun glyphs(cards: List<Card>, start: Float, confidence: Float = .96f,
                      shift: Float = 0f): List<PlayGlyph> = cards.mapIndexed { index, card ->
        val left = start + index * .035f + shift
        PlayGlyph(card, confidence, GlyphBox(left, .31f, left + .025f, .36f))
    }

    private fun play(cards: List<Card>, start: Float, confidence: Float = .96f,
                     glyphConfidence: Float = confidence, shift: Float = 0f) = ObservedPlay(
        cards, confidence, glyphs(cards, start, glyphConfidence, shift)
    )

    private fun make(): Setup {
        val initial = deck.take(27)
        val ledger = RoundLedger()
        ledger.begin("localized", initial)
        ledger.commit(PlayEvent("localized", "self-8", 1, Seat.SELF, initial.take(8)))
        val oldMask = deck.drop(27).take(5)
        ledger.commit(PlayEvent("localized", "opposite-old-5", 2, Seat.OPPOSITE, oldMask))
        val candidate = deck.drop(32).take(5)
        val own = ledger.currentHand()
        val empty = ObservedPlay(emptyList(), .98f)
        fun frame(time: Long, previous: ObservedPlay, opposite: ObservedPlay = play(oldMask, .47f),
                   next: ObservedPlay = empty) = TableFrame(time, TableScene.TABLE, own,
            mapOf(Seat.PREVIOUS to previous, Seat.OPPOSITE to opposite, Seat.NEXT to next))

        val prior = frame(100, empty)
        val first = frame(200, play(candidate, .16f))
        val second = frame(350, play(candidate, .16f))
        val mask = frame(450, play(candidate, .16f),
            ObservedPlay(emptyList(), .3f, regionObserved = false)).copy(
            incompleteReason = "localized overlay", localizedOverlaySeats = setOf(Seat.OPPOSITE))
        val before = ledger.snapshot().plays.values.flatten()
        val helper = LocalizedPendingPlayRecovery()
        return Setup(ledger, helper, own, candidate, oldMask, empty, prior, first, second, mask, before)
    }

    private fun pre(s: Setup) {
        assertNull(s.helper.observe(s.prior, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        assertNull(s.helper.observe(s.first, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        assertNull(s.helper.observe(s.second, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        assertNull(s.helper.observe(s.mask, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
    }

    private fun post(s: Setup, time: Long, old: ObservedPlay = play(s.oldMask, .47f),
                     candidate: ObservedPlay = play(s.candidate, .16f),
                     frame: TableFrame? = null) = frame ?: TableFrame(time, TableScene.TABLE, s.own,
        mapOf(Seat.PREVIOUS to candidate, Seat.OPPOSITE to old, Seat.NEXT to s.empty))

    @Test fun recoversOnlyAfterClearCandidateAndTwoStablePostMaskReads() {
        val s = make(); pre(s)
        assertNull(s.helper.observe(post(s, 600), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        val recovered = s.helper.observe(post(s, 750), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE)
        assertNotNull(recovered)
        assertEquals(Seat.PREVIOUS, recovered.seat)
        assertEquals(s.candidate, recovered.play.cards)
        assertEquals(s.before, s.ledger.snapshot().plays.values.flatten())
        assertNull(s.helper.observe(post(s, 900), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
    }

    @Test fun requiresImmediatePositiveEmptySeedAndTwoPreMaskCandidateReads() {
        run {
            val s = make()
            assertNull(s.helper.observe(s.first, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(s.second, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(s.mask, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 600), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 750), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        }
        run {
            val s = make()
            assertNull(s.helper.observe(s.prior, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(s.first, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(s.mask, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 600), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 750), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        }
        run {
            val s = make()
            assertNull(s.helper.observe(s.prior, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(s.first, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            val tooSoon = s.second.copy(timeMs = 280)
            assertNull(s.helper.observe(tooSoon, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(s.mask, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 600), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 750), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        }
    }

    @Test fun rejectsMaskOnCandidateAndUncommittedOldAnchor() {
        run {
            val s = make()
            s.helper.observe(s.prior, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE)
            s.helper.observe(s.first, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE)
            s.helper.observe(s.second, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE)
            val maskedCandidate = s.second.copy(timeMs = 450, incompleteReason = "overlay",
                localizedOverlaySeats = setOf(Seat.PREVIOUS),
                plays = s.second.plays + (Seat.PREVIOUS to ObservedPlay(emptyList(), .3f, regionObserved = false)))
            assertNull(s.helper.observe(maskedCandidate, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 600), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 750), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        }
        run {
            val s = make()
            val noOldCommit = RoundLedger().apply { begin("no-old", deck.take(27)); commit(
                PlayEvent("no-old", "self-8", 1, Seat.SELF, deck.take(8))) }
            val own = noOldCommit.currentHand()
            val empty = ObservedPlay(emptyList(), .98f)
            fun frame(t: Long, prev: ObservedPlay, opp: ObservedPlay) = TableFrame(t, TableScene.TABLE, own,
                mapOf(Seat.PREVIOUS to prev, Seat.OPPOSITE to opp, Seat.NEXT to empty))
            val helper = s.helper
            helper.reset()
            val uncommittedOld = play(s.oldMask, .47f)
            helper.observe(frame(100, empty, uncommittedOld), noOldCommit.snapshot(), own, RoundPhase.ACTIVE)
            helper.observe(frame(200, play(s.candidate, .16f), uncommittedOld), noOldCommit.snapshot(), own, RoundPhase.ACTIVE)
            helper.observe(frame(350, play(s.candidate, .16f), uncommittedOld), noOldCommit.snapshot(), own, RoundPhase.ACTIVE)
            helper.observe(frame(450, play(s.candidate, .16f), ObservedPlay(emptyList(), .3f, regionObserved = false)).copy(
                incompleteReason = "overlay", localizedOverlaySeats = setOf(Seat.OPPOSITE)),
                noOldCommit.snapshot(), own, RoundPhase.ACTIVE)
            assertNull(helper.observe(frame(600, play(s.candidate, .16f), uncommittedOld),
                noOldCommit.snapshot(), own, RoundPhase.ACTIVE))
            assertNull(helper.observe(frame(750, play(s.candidate, .16f), uncommittedOld),
                noOldCommit.snapshot(), own, RoundPhase.ACTIVE))
        }
    }

    @Test fun rejectsGlobalFaultsAndUnknownSceneDuringCandidateOrMask() {
        val faults = listOf<(TableFrame) -> TableFrame>(
            { it.copy(captureGap = true) },
            { it.copy(ambiguousPresentation = true) },
            { it.copy(incompleteReason = "global fault") },
            { it.copy(scene = TableScene.UNKNOWN) },
        )
        for (fault in faults) {
            val s = make(); pre(s)
            assertNull(s.helper.observe(fault(post(s, 600)), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 750), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        }
        for (fault in faults) {
            val s = make()
            s.helper.observe(s.prior, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE)
            s.helper.observe(s.first, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE)
            assertNull(s.helper.observe(fault(s.second), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(s.mask, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 600), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 750), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        }
    }

    @Test fun rejectsWeakPartialMovedOrDuplicatedCandidateTracks() {
        val variants = listOf<(Setup) -> ObservedPlay>(
            { play(it.candidate, .16f, confidence = .72f) },
            { ObservedPlay(it.candidate.take(4), .96f, glyphs(it.candidate.take(4), .16f)) },
            { play(it.candidate, .16f, shift = .04f) },
            { s ->
                val cards = s.candidate
                ObservedPlay(cards, .96f, cards.mapIndexed { index, card ->
                    val left = if (index == 1) .16f else .16f + index * .035f
                    PlayGlyph(card, .96f, GlyphBox(left, .31f, left + .025f, .36f))
                })
            },
        )
        for (variant in variants) {
            val s = make()
            s.helper.observe(s.prior, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE)
            s.helper.observe(s.first, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE)
            val changed = s.second.copy(plays = s.second.plays + (Seat.PREVIOUS to variant(s)))
            assertNull(s.helper.observe(changed, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(s.mask, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 600), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 750), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        }
    }

    @Test fun rejectsOwnChangesAndUnexpectedCardsAtAnOtherClearSeat() {
        run {
            val s = make(); pre(s)
            val alteredOwn = s.own.drop(1)
            assertNull(s.helper.observe(post(s, 600, frame = post(s, 600).copy(hand = alteredOwn)),
                s.ledger.snapshot(), alteredOwn, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 750), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        }
        run {
            val s = make()
            s.helper.observe(s.prior, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE)
            val extra = deck.drop(37).take(1)
            val changed = s.first.copy(plays = s.first.plays + (Seat.NEXT to play(extra, .75f)))
            assertNull(s.helper.observe(changed, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(s.second, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(s.mask, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 600), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 750), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        }
    }

    @Test fun rejectsDifferentOrExpandedOldMaskGroupAndIncompletePostProof() {
        val wrong = sCardsExtra()
        run {
            val s = make(); pre(s)
            assertNull(s.helper.observe(post(s, 600, old = play(wrong, .47f)), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 750), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        }
        run {
            val s = make(); pre(s)
            assertNull(s.helper.observe(post(s, 600, old = play(s.oldMask + wrong.first(), .47f)),
                s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 750), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        }
        run {
            val s = make(); pre(s)
            assertNull(s.helper.observe(post(s, 600, old = play(s.oldMask.take(4), .47f)),
                s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 750), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        }
        run {
            val s = make(); pre(s)
            assertNull(s.helper.observe(post(s, 600), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 750).copy(plays = post(s, 750).plays +
                (Seat.PREVIOUS to play(s.candidate, .16f, shift = .04f))),
                s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        }
    }

    @Test fun freshContradictorySeatCountRejectsButMatchingCountCannotReplaceCardReads() {
        run {
            val s = make(); pre(s)
            val contradictory = post(s, 600).copy(remainingHandCounts = mapOf(
                Seat.PREVIOUS to RemainingHandCount(21, 600)))
            assertNull(s.helper.observe(contradictory, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 750), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        }
        run {
            val s = make()
            s.helper.observe(s.prior, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE)
            val expected = post(s, 200).copy(remainingHandCounts = mapOf(
                Seat.PREVIOUS to RemainingHandCount(22, 200)))
            s.helper.observe(expected, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE)
            val expectedAgain = post(s, 350).copy(remainingHandCounts = mapOf(
                Seat.PREVIOUS to RemainingHandCount(22, 350)))
            s.helper.observe(expectedAgain, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE)
            val localMask = s.mask.copy(remainingHandCounts = mapOf(
                Seat.PREVIOUS to RemainingHandCount(22, 450)))
            s.helper.observe(localMask, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE)
            // Matching counts are consistency checks; they accompany both
            // required full old-mask reads and do not replace them.
            assertNull(s.helper.observe(post(s, 600).copy(remainingHandCounts = mapOf(
                Seat.PREVIOUS to RemainingHandCount(22, 600))), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            val recovered = s.helper.observe(post(s, 750).copy(remainingHandCounts = mapOf(
                Seat.PREVIOUS to RemainingHandCount(22, 750))), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE)
            assertNotNull(recovered)
        }
        run {
            val s = make(); pre(s)
            assertNull(s.helper.observe(post(s, 600).copy(remainingHandCounts = mapOf(
                Seat.PREVIOUS to RemainingHandCount(22, 600))), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            val maskedAgain = post(s, 750).copy(
                plays = post(s, 750).plays + (Seat.OPPOSITE to ObservedPlay(emptyList(), .3f, regionObserved = false)),
                remainingHandCounts = mapOf(Seat.PREVIOUS to RemainingHandCount(22, 750)))
            assertNull(s.helper.observe(maskedAgain, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        }
    }

    @Test fun enforcesMaskAndPostDeadlinesAndSnapshotVersion() {
        run {
            val s = make(); pre(s)
            val lateMask = s.mask.copy(timeMs = 1851)
            assertNull(s.helper.observe(lateMask, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 1900), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        }
        run {
            val s = make(); pre(s)
            assertNull(s.helper.observe(post(s, 600), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 1101), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        }
        run {
            val s = make(); pre(s)
            val extra = deck.drop(38).take(1)
            s.ledger.commit(PlayEvent("localized", "next-after-seed", 3, Seat.NEXT, extra))
            assertNull(s.helper.observe(s.mask, s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 600), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
            assertNull(s.helper.observe(post(s, 750), s.ledger.snapshot(), s.own, RoundPhase.ACTIVE))
        }
    }

    private fun sCardsExtra() = deck.drop(40).take(2)

    @Test fun continuousMaskFramesCannotExtendTheFixedLastPreDeadline() {
        val s=make(); pre(s)
        for(t in listOf(700L,950L,1200L,1450L,1700L)) {
            assertNull(s.helper.observe(s.mask.copy(timeMs=t),s.ledger.snapshot(),s.own,RoundPhase.ACTIVE))
        }
        assertNull(s.helper.observe(post(s,1800),s.ledger.snapshot(),s.own,RoundPhase.ACTIVE))
        assertNull(s.helper.observe(post(s,1950),s.ledger.snapshot(),s.own,RoundPhase.ACTIVE))
        assertNull(s.helper.observe(post(s,2100),s.ledger.snapshot(),s.own,RoundPhase.ACTIVE))
    }
}
