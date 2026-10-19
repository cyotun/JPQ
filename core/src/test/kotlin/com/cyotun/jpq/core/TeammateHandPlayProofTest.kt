package com.cyotun.jpq.core

import kotlin.test.*

class TeammateHandPlayProofTest {
    private val fullDeck = Rank.entries.flatMap { rank ->
        if (rank.isJoker) List(rank.capacity) { Card(rank, Suit.NONE) }
        else List(4) { Card(rank, Suit.RED) } + List(4) { Card(rank, Suit.BLACK) }
    }

    private fun snapshot(seedSize: Int, roundId: String = "round-1", oldOppCards: List<Card> = emptyList()): RoundSnapshot {
        val played = 27 - seedSize
        val cards = if (oldOppCards.isNotEmpty()) oldOppCards else fullDeck.take(played)
        val events = cards.mapIndexed { index, card ->
            PlayEvent(roundId, "opp-$index", index.toLong(), Seat.OPPOSITE, listOf(card))
        }
        return RoundSnapshot(
            roundId,
            RoundPhase.SELF_FINISHED,
            Rank.entries.associateWith { it.capacity },
            Seat.entries.associateWith { seat -> if (seat == Seat.OPPOSITE) events else emptyList() },
            null,
        )
    }

    private fun play(cards: List<Card>, confidence: Float = .90f, x: Float = .42f): ObservedPlay {
        val glyphs = cards.mapIndexed { index, card ->
            val left = x + index * .025f
            PlayGlyph(card, confidence, GlyphBox(left, .18f, left + .02f, .23f))
        }
        return ObservedPlay(cards, confidence, glyphs)
    }

    private fun observe(
        tracker: TeammateHandPlayProofTracker,
        snapshot: RoundSnapshot,
        time: Long,
        hand: List<Card>?,
        desk: ObservedPlay?,
        handConfidence: Float = .90f,
        scene: TableScene = TableScene.TEAMMATE,
        captureGap: Boolean = false,
        incompleteReason: String? = null,
        ambiguous: Boolean = false,
    ): ObservedPlay? = tracker.observe(
        TableFrame(
            timeMs = time,
            scene = scene,
            hand = null,
            plays = mapOf(Seat.OPPOSITE to desk),
            incompleteReason = incompleteReason,
            ambiguousPresentation = ambiguous,
            captureGap = captureGap,
            teammateHand = hand,
            teammateHandConfidence = handConfidence,
        ),
        snapshot,
    )

    @Test fun sameVisibleBigCanProveANewBigWhenTheCompleteTeammateHandShrinks() {
        val tracker = TeammateHandPlayProofTracker()
        val snapshot = snapshot(3)
        val big = Card(Rank.BIG, Suit.NONE)
        val seed = listOf(big, Card(Rank.A, Suit.RED), Card(Rank.K, Suit.BLACK))
        val smaller = seed.drop(1)
        val oldVisibleBig = play(listOf(big))

        assertNull(observe(tracker, snapshot, 1000, seed, oldVisibleBig))
        assertNull(observe(tracker, snapshot, 1116, seed, oldVisibleBig))
        assertNull(observe(tracker, snapshot, 1249, smaller, oldVisibleBig))
        val proof = observe(tracker, snapshot, 1370, smaller, oldVisibleBig)

        assertEquals(listOf(big), proof?.cards)
        assertEquals(oldVisibleBig.glyphs, proof?.glyphs)
        assertNull(observe(tracker, snapshot, 1500, smaller, oldVisibleBig))
    }

    @Test fun completeSingleThreeThenEmptyHandMayReuseTheRecentSameGeometryStrongDeskRead() {
        val tracker = TeammateHandPlayProofTracker()
        val snapshot = snapshot(1)
        val three = Card(Rank.THREE, Suit.RED)
        val seed = listOf(three)
        val direct = play(seed, .87f)

        assertNull(observe(tracker, snapshot, 1641624535, seed, direct))
        assertNull(observe(tracker, snapshot, 1641624651, seed, direct))
        assertNull(observe(tracker, snapshot, 1641624784, emptyList(), direct))
        val lowSameGeometry = play(seed, .604f)
        val proof = observe(tracker, snapshot, 1641624917, emptyList(), lowSameGeometry)

        assertEquals(direct.cards, proof?.cards)
        assertEquals(direct.glyphs, proof?.glyphs)
    }

    @Test fun completeFourCardHandAndMatchingDeskProveTheLastPlayAcross329Ms() {
        val tracker = TeammateHandPlayProofTracker()
        val snapshot = snapshot(4)
        val seed = listOf(
            Card(Rank.EIGHT, Suit.BLACK), Card(Rank.EIGHT, Suit.RED),
            Card(Rank.EIGHT, Suit.RED), Card(Rank.J, Suit.RED),
        )
        val direct = play(seed, .857f)

        assertNull(observe(tracker, snapshot, 1690868334, seed, direct))
        assertNull(observe(tracker, snapshot, 1690868567, seed, direct))
        assertNull(observe(tracker, snapshot, 1690868837, emptyList(), play(seed, .834f)))
        val proof = observe(tracker, snapshot, 1690869166, emptyList(), direct)

        assertEquals(direct.cards, proof?.cards)
        assertEquals(play(seed, .834f).glyphs, proof?.glyphs)
    }

    @Test fun highFrameRateShrinkVotesAccumulateUntilTheirTotalSpanReaches100Ms() {
        val tracker = TeammateHandPlayProofTracker()
        val snapshot = snapshot(1)
        val three = Card(Rank.THREE, Suit.RED)
        val seed = listOf(three)
        val desk = play(seed)

        assertNull(observe(tracker, snapshot, 0, seed, desk))
        assertNull(observe(tracker, snapshot, 66, seed, desk))
        assertNull(observe(tracker, snapshot, 132, seed, desk))
        assertNull(observe(tracker, snapshot, 198, emptyList(), desk))
        assertNull(observe(tracker, snapshot, 264, emptyList(), desk))
        assertEquals(desk.cards, observe(tracker, snapshot, 330, emptyList(), desk)?.cards)
    }

    @Test fun oneSeedVoteOrOneShrinkVoteIsInsufficient() {
        val seed = listOf(Card(Rank.THREE, Suit.RED))
        val snapshot = snapshot(seed.size)
        val desk = play(seed)

        val oneSeed = TeammateHandPlayProofTracker()
        assertNull(observe(oneSeed, snapshot, 0, seed, desk))
        assertNull(observe(oneSeed, snapshot, 133, emptyList(), desk))
        assertNull(observe(oneSeed, snapshot, 266, emptyList(), desk))

        val oneShrink = TeammateHandPlayProofTracker()
        assertNull(observe(oneShrink, snapshot, 1000, seed, desk))
        assertNull(observe(oneShrink, snapshot, 1116, seed, desk))
        assertNull(observe(oneShrink, snapshot, 1249, emptyList(), desk))
    }

    @Test fun aGapUnknownOrNonTeammateFrameBreaksTheProofSequence() {
        val seed = listOf(Card(Rank.THREE, Suit.RED))
        val snapshot = snapshot(seed.size)
        val desk = play(seed)
        for (breakFrame in listOf(
            { t: Long -> Triple(TableScene.UNKNOWN, false, null as String?) },
            { t: Long -> Triple(TableScene.TEAMMATE, true, null as String?) },
            { t: Long -> Triple(TableScene.TABLE, false, null as String?) },
            { t: Long -> Triple(TableScene.TEAMMATE, false, "incomplete") },
        )) {
            val tracker = TeammateHandPlayProofTracker()
            assertNull(observe(tracker, snapshot, 0, seed, desk))
            assertNull(observe(tracker, snapshot, 116, seed, desk))
            val (scene, gap, reason) = breakFrame(200)
            assertNull(observe(tracker, snapshot, 200, emptyList(), desk, scene = scene,
                captureGap = gap, incompleteReason = reason))
            assertNull(observe(tracker, snapshot, 333, emptyList(), desk))
        }
    }

    @Test fun mismatchedOrMovedDeskAndAHandThatIsNotASubsetCannotProveAPlay() {
        val seed = listOf(Card(Rank.THREE, Suit.RED))
        val snapshot = snapshot(seed.size)
        val tracker = TeammateHandPlayProofTracker()
        val desk = play(seed)
        assertNull(observe(tracker, snapshot, 0, seed, desk))
        assertNull(observe(tracker, snapshot, 116, seed, desk))
        assertNull(observe(tracker, snapshot, 249, emptyList(), play(listOf(Card(Rank.FOUR, Suit.RED)))))
        assertNull(observe(tracker, snapshot, 382, emptyList(), desk))

        tracker.reset()
        assertNull(observe(tracker, snapshot, 1000, seed, desk))
        assertNull(observe(tracker, snapshot, 1116, seed, desk))
        assertNull(observe(tracker, snapshot, 1249, emptyList(), desk))
        assertNull(observe(tracker, snapshot, 1382, emptyList(), play(seed, .604f, x = .62f)))
        assertNull(observe(tracker, snapshot, 1515, emptyList(), play(seed, .604f, x = .62f)))

        tracker.reset()
        assertNull(observe(tracker, snapshot, 2000, seed, desk))
        assertNull(observe(tracker, snapshot, 2116, seed, desk))
        assertNull(observe(tracker, snapshot, 2249, listOf(Card(Rank.FOUR, Suit.RED)), play(seed)))
    }

    @Test fun weakSeedOrUnobservedEmptyBoardCannotBorrowOldEvidence() {
        val seed = listOf(Card(Rank.THREE, Suit.RED))
        val snapshot = snapshot(seed.size)
        val tracker = TeammateHandPlayProofTracker()
        val desk = play(seed)
        assertNull(observe(tracker, snapshot, 0, seed, desk, handConfidence = .79f))
        assertNull(observe(tracker, snapshot, 116, seed, desk))
        assertNull(observe(tracker, snapshot, 249, emptyList(), desk))
        assertNull(observe(tracker, snapshot, 382, emptyList(), null))
    }

    @Test fun duplicateTimestampsAndChangedOpponentEventVersionDoNotReuseVotes() {
        val seed = listOf(Card(Rank.THREE, Suit.RED))
        val snapshot = snapshot(seed.size)
        val tracker = TeammateHandPlayProofTracker()
        val desk = play(seed)
        assertNull(observe(tracker, snapshot, 0, seed, desk))
        assertNull(observe(tracker, snapshot, 116, seed, desk))
        assertNull(observe(tracker, snapshot, 249, emptyList(), desk))
        assertNull(observe(tracker, snapshot, 249, emptyList(), desk))
        assertEquals(desk.cards, observe(tracker, snapshot, 382, emptyList(), desk)?.cards)

        val changedVersion = snapshot.copy(
            plays = snapshot.plays + (Seat.OPPOSITE to snapshot.plays[Seat.OPPOSITE].orEmpty() +
                PlayEvent("round-1", "next-opp", 100, Seat.OPPOSITE, listOf(Card(Rank.FOUR, Suit.RED)))),
        )
        assertNull(observe(tracker, changedVersion, 500, emptyList(), desk))
    }

    @Test fun ambiguousOtherGlyphsAreAllowedOnlyWhenOppositeGroupIsDirectAndComplete() {
        val seed = listOf(Card(Rank.THREE, Suit.RED))
        val snapshot = snapshot(seed.size)
        val tracker = TeammateHandPlayProofTracker()
        val desk = play(seed)
        assertNull(observe(tracker, snapshot, 0, seed, desk, incompleteReason = "归属待核对", ambiguous = true))
        assertNull(observe(tracker, snapshot, 116, seed, desk, incompleteReason = "归属待核对", ambiguous = true))
        assertNull(observe(tracker, snapshot, 249, emptyList(), desk, incompleteReason = "归属待核对", ambiguous = true))
        assertEquals(desk.cards, observe(tracker, snapshot, 382, emptyList(), desk,
            incompleteReason = "归属待核对", ambiguous = true)?.cards)
    }

    private val finalFive = List(3) { Card(Rank.THREE,Suit.BLACK) } +
        listOf(Card(Rank.A,Suit.RED),Card(Rank.A,Suit.BLACK))

    private fun handoffTracker(): TeammateHandPlayProofTracker = TeammateHandPlayProofTracker().also { tracker ->
        val snapshot=snapshot(finalFive.size)
        assertNull(observe(tracker,snapshot,750,finalFive,play(emptyList())))
        assertNull(observe(tracker,snapshot,1000,finalFive,play(emptyList())))
        assertNull(observe(tracker,snapshot,1267,emptyList(),play(finalFive.dropLast(1),.7263f)))
    }

    @Test fun partialLastFiveWaitsForTwoLateCompleteStrongFramesWithoutInventingTheBlackAce() {
        val tracker=handoffTracker()
        val snapshot=snapshot(finalFive.size)
        assertNull(observe(tracker,snapshot,1549,emptyList(),play(finalFive.dropLast(1),.7553f)))
        val weak=play(finalFive,.874f).let { full ->
            full.copy(confidence=.588f,glyphs=full.glyphs.dropLast(1)+full.glyphs.last().copy(confidence=.588f))
        }
        assertNull(observe(tracker,snapshot,1815,emptyList(),weak))
        assertNull(observe(tracker,snapshot,2065,emptyList(),play(finalFive,.874f)))
        assertNull(observe(tracker,snapshot,2314,emptyList(),play(finalFive.take(2),.8034f)))
        assertNull(observe(tracker,snapshot,2597,emptyList(),null,
            incompleteReason="出牌归属待核对，正在重新识别",ambiguous=true))
        assertNull(observe(tracker,snapshot,2880,emptyList(),play(finalFive.take(1),.848f),
            incompleteReason="出牌归属待核对，正在重新识别",ambiguous=true))
        for(time in listOf(3129L,3378L)) assertNull(observe(tracker,snapshot,time,emptyList(),null,
            incompleteReason="出牌归属待核对，正在重新识别",ambiguous=true))
        val full=play(finalFive,.874f)
        assertNull(observe(tracker,snapshot,3711,emptyList(),full,
            incompleteReason="出牌归属待核对，正在重新识别",ambiguous=true))
        val proof=observe(tracker,snapshot,3977,emptyList(),full,
            incompleteReason="出牌归属待核对，正在重新识别",ambiguous=true)
        assertEquals(full,proof)
        assertNull(observe(tracker,snapshot,4100,emptyList(),full))
    }

    @Test fun weakFullFrameAndDuplicateTimestampCannotProvideTheSecondHandoffVote() {
        val tracker=handoffTracker()
        val snapshot=snapshot(finalFive.size)
        assertNull(observe(tracker,snapshot,1549,emptyList(),play(finalFive,.588f)))
        val full=play(finalFive)
        assertNull(observe(tracker,snapshot,1800,emptyList(),full))
        repeat(3) { assertNull(observe(tracker,snapshot,1800,emptyList(),full)) }
        assertNull(observe(tracker,snapshot,1880,emptyList(),full))
        assertEquals(full,observe(tracker,snapshot,1900,emptyList(),full))
    }

    @Test fun staleFullGroupWithoutAFreshQualifiedPartialOrConfirmedSeedStillCannotStartHandoff() {
        val snapshot=snapshot(finalFive.size)
        for(seedVotes in listOf(1,2)) {
            val tracker=TeammateHandPlayProofTracker()
            if(seedVotes==2) assertNull(observe(tracker,snapshot,750,finalFive,null))
            assertNull(observe(tracker,snapshot,1000,finalFive,null))
            // No partial arrives in the seed's 500ms window.
            assertNull(observe(tracker,snapshot,1400,emptyList(),null))
            assertNull(observe(tracker,snapshot,1600,emptyList(),play(finalFive)))
            assertNull(observe(tracker,snapshot,1750,emptyList(),play(finalFive)))
        }
        val oneSeed=TeammateHandPlayProofTracker()
        assertNull(observe(oneSeed,snapshot,1000,finalFive,null))
        assertNull(observe(oneSeed,snapshot,1267,emptyList(),play(finalFive.dropLast(1))))
        assertNull(observe(oneSeed,snapshot,1549,emptyList(),play(finalFive)))
        assertNull(observe(oneSeed,snapshot,1750,emptyList(),play(finalFive)))
    }

    @Test fun malformedWeakUnobservedOrWrongColorPartialNeverExtendsTheSeedWindow() {
        val partial=play(finalFive.dropLast(1))
        val wrong=play(List(3) { Card(Rank.THREE,Suit.RED) }+Card(Rank.A,Suit.RED))
        val invalidBox=partial.copy(glyphs=partial.glyphs.map { it.copy(box=it.box.copy(left=Float.NaN)) })
        for(candidate in listOf(partial.copy(regionObserved=false),play(partial.cards,.64f),play(emptyList()),
            partial.copy(glyphs=partial.glyphs.dropLast(1)),invalidBox,wrong)) {
            val tracker=TeammateHandPlayProofTracker()
            val snapshot=snapshot(finalFive.size)
            assertNull(observe(tracker,snapshot,750,finalFive,null))
            assertNull(observe(tracker,snapshot,1000,finalFive,null))
            assertNull(observe(tracker,snapshot,1267,emptyList(),candidate))
            assertNull(observe(tracker,snapshot,1534,emptyList(),null))
            assertNull(observe(tracker,snapshot,1800,emptyList(),play(finalFive)))
            assertNull(observe(tracker,snapshot,1950,emptyList(),play(finalFive)))
        }
    }

    @Test fun returningOrChangingHandClearsThePendingDifference() {
        val snapshot=snapshot(finalFive.size)
        for(hand in listOf(finalFive,listOf(finalFive.first()))) {
            val tracker=handoffTracker()
            assertNull(observe(tracker,snapshot,1600,hand,null))
            // Let any genuinely returned complete hand expire as a normal seed.
            assertNull(observe(tracker,snapshot,2000,emptyList(),null))
            assertNull(observe(tracker,snapshot,2300,emptyList(),play(finalFive)))
            assertNull(observe(tracker,snapshot,2450,emptyList(),play(finalFive)))
        }
    }

    @Test fun pendingDifferenceCannotSurviveGlobalFaultInvalidHandGapOrEventVersionChange() {
        val snapshot=snapshot(finalFive.size)
        for(mode in listOf("gap","unknown","mask","maskWithAmbiguity","weakHand","missingHand","invalidHand","version","round")) {
            val tracker=handoffTracker()
            val changed=when(mode) {
                "round" -> snapshot(finalFive.size,"round-2")
                "version" -> snapshot.copy(plays=snapshot.plays+(Seat.OPPOSITE to
                    snapshot.plays.getValue(Seat.OPPOSITE).mapIndexed { i,event ->
                        if(i==0) event.copy(eventId="changed-event") else event
                    }))
                else -> snapshot
            }
            assertNull(observe(tracker,changed,1500,
                when(mode) { "missingHand" -> null; "invalidHand" -> List(5) { Card(Rank.A,Suit.RED) }; else -> emptyList() },
                null,handConfidence=if(mode=="weakHand") .79f else .9f,
                scene=if(mode=="unknown") TableScene.UNKNOWN else TableScene.TEAMMATE,
                captureGap=mode=="gap",incompleteReason=if(mode.startsWith("mask")) "悬浮条遮住手牌区" else null,
                ambiguous=mode=="maskWithAmbiguity"))
            assertNull(observe(tracker,changed,1700,emptyList(),play(finalFive)))
            assertNull(observe(tracker,changed,1850,emptyList(),play(finalFive)))
        }
        val delayed=handoffTracker()
        assertNull(observe(delayed,snapshot,1800,emptyList(),play(finalFive))) // Actual frame gap >500ms.
        assertNull(observe(delayed,snapshot,1950,emptyList(),play(finalFive)))
    }

    @Test fun handoffExpiryIsMeasuredFromTheFirstPartialAndCannotBeRefreshedByMorePartials() {
        val tracker=handoffTracker()
        val snapshot=snapshot(finalFive.size)
        for(time in 1667L..5267L step 400L)
            assertNull(observe(tracker,snapshot,time,emptyList(),play(finalFive.dropLast(1))))
        assertNull(observe(tracker,snapshot,5268,emptyList(),play(finalFive)))
        assertNull(observe(tracker,snapshot,5418,emptyList(),play(finalFive)))
    }

    @Test fun movedConflictingExpandedOrInvalidWholeGroupCannotInheritThePartialAnchor() {
        val full=play(finalFive)
        val duplicatedSlot=full.copy(glyphs=listOf(full.glyphs[0],full.glyphs[0])+full.glyphs.drop(2))
        for(candidate in listOf(play(finalFive,x=.62f),play(finalFive.dropLast(1)+Card(Rank.A,Suit.RED)),
            play(finalFive+Card(Rank.FOUR,Suit.RED)),full.copy(glyphs=full.glyphs.dropLast(1)),duplicatedSlot)) {
            val tracker=handoffTracker()
            val snapshot=snapshot(finalFive.size)
            assertNull(observe(tracker,snapshot,1500,emptyList(),candidate))
            assertNull(observe(tracker,snapshot,1700,emptyList(),full))
            assertNull(observe(tracker,snapshot,1850,emptyList(),full))
        }
    }
}
