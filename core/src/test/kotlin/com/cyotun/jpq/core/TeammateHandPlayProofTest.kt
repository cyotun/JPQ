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
        remainingHandCounts: Map<Seat,RemainingHandCount> = emptyMap(),
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
            remainingHandCounts = remainingHandCounts,
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

    private val shortHandoffSeed=listOf(
        Card(Rank.TEN,Suit.BLACK),Card(Rank.TEN,Suit.BLACK),Card(Rank.SIX,Suit.RED),
        Card(Rank.SEVEN,Suit.BLACK),Card(Rank.EIGHT,Suit.RED),Card(Rank.NINE,Suit.RED))

    private val shortHandoffPlay=listOf(
        Card(Rank.TEN,Suit.BLACK),Card(Rank.SIX,Suit.RED),Card(Rank.SEVEN,Suit.BLACK),
        Card(Rank.EIGHT,Suit.RED),Card(Rank.NINE,Suit.RED))

    private val shortHandoffPartial=shortHandoffPlay.drop(1)

    private fun shortHandoffTracker(baseTime: Long=1000): Pair<TeammateHandPlayProofTracker,RoundSnapshot> {
        val tracker=TeammateHandPlayProofTracker()
        val snapshot=snapshot(shortHandoffSeed.size,oldOppCards=fullDeck.take(21))
        assertNull(observe(tracker,snapshot,baseTime,shortHandoffSeed,play(emptyList())))
        assertNull(observe(tracker,snapshot,baseTime+116,shortHandoffSeed,play(emptyList())))
        assertNull(observe(tracker,snapshot,baseTime+249,listOf(shortHandoffSeed.first()),play(shortHandoffPartial,.709f,x=.445f)))
        return tracker to snapshot
    }

    private fun oneOpponentBadge(count: Int,source: Long)=mapOf(Seat.OPPOSITE to RemainingHandCount(count,source))

    private data class IssuedShortHandoff(val tracker: TeammateHandPlayProofTracker,val snapshot: RoundSnapshot,
        val proof: ObservedPlay,val issuedAt: Long)

    private fun issueShortHandoff(): IssuedShortHandoff {
        val (tracker,snapshot)=shortHandoffTracker(644061295)
        assertNull(observe(tracker,snapshot,644061827,listOf(shortHandoffSeed.first()),play(shortHandoffPlay,.632f),
            remainingHandCounts=oneOpponentBadge(1,644061827)))
        val strong=play(shortHandoffPlay,.836f)
        assertNull(observe(tracker,snapshot,644062093,listOf(shortHandoffSeed.first()),strong))
        assertNull(observe(tracker,snapshot,644062342,listOf(shortHandoffSeed.first()),play(emptyList())))
        assertNull(observe(tracker,snapshot,644062700,listOf(shortHandoffSeed.first()),play(emptyList())))
        val proof=assertNotNull(observe(tracker,snapshot,644062858,listOf(shortHandoffSeed.first()),play(emptyList()),
            remainingHandCounts=oneOpponentBadge(1,644062342)))
        assertEquals(shortHandoffPlay,proof.cards)
        assertEquals(strong.glyphs,proof.glyphs)
        return IssuedShortHandoff(tracker,snapshot,proof,644062858)
    }

    private fun committedEvent(snapshot: RoundSnapshot,cards: List<Card>,roundId: String=snapshot.roundId!!): RoundSnapshot {
        val old=snapshot.plays[Seat.OPPOSITE].orEmpty()
        val sequence=(old.maxOfOrNull { it.sequence } ?: -1L)+1L
        val event=PlayEvent(roundId,"$roundId:$sequence",sequence,Seat.OPPOSITE,cards)
        return snapshot.copy(plays=snapshot.plays+(Seat.OPPOSITE to old+event))
    }

    @Test fun oneStrongSameSlotFullWitnessSurvivesAnObservedBlankWhenFreshBadgesAndShrinkVotesAgree() {
        val issued=issueShortHandoff()
        assertNull(observe(issued.tracker,issued.snapshot,644063107,listOf(shortHandoffSeed.first()),play(emptyList()),
            remainingHandCounts=oneOpponentBadge(1,644062900)))
    }

    @Test fun postCommitHandoffSeedsTheProvedOneCardHandForTheImmediatelyVisibleSameValuePlay() {
        val issued=issueShortHandoff()
        val committed=committedEvent(issued.snapshot,issued.proof.cards)
        val ten=Card(Rank.TEN,Suit.BLACK)
        assertNull(observe(issued.tracker,committed,644063107,emptyList(),play(listOf(ten),.91f)))
        val next=observe(issued.tracker,committed,644063240,emptyList(),play(listOf(ten),.92f))
        assertEquals(listOf(ten),next?.cards)
        assertEquals(play(listOf(ten),.91f).glyphs,next?.glyphs)
    }

    @Test fun postCommitSeedCannotRebaseWithoutTheExactSingleAppendWrittenToTheLedger() {
        val issued=issueShortHandoff()
        val ten=Card(Rank.TEN,Suit.BLACK)
        // A returned proof alone is not evidence that commit succeeded.
        assertNull(observe(issued.tracker,issued.snapshot,644063107,emptyList(),play(listOf(ten))))
        assertNull(observe(issued.tracker,issued.snapshot,644063240,emptyList(),play(listOf(ten))))
        val laterCommit=committedEvent(issued.snapshot,issued.proof.cards)
        assertNull(observe(issued.tracker,laterCommit,644063300,emptyList(),play(listOf(ten))))
        assertNull(observe(issued.tracker,laterCommit,644063433,emptyList(),play(listOf(ten))))

        val wrong=committedEvent(issued.snapshot,List(5) { Card(Rank.FOUR,Suit.RED) })
        assertNull(observe(issued.tracker,wrong,644063107,emptyList(),play(listOf(ten))))
        assertNull(observe(issued.tracker,wrong,644063240,emptyList(),play(listOf(ten))))
    }

    @Test fun postCommitSeedRejectsMultipleOrRewrittenEventHistoryAndAnotherRound() {
        val ten=Card(Rank.TEN,Suit.BLACK)
        run {
            val issued=issueShortHandoff()
            val first=committedEvent(issued.snapshot,issued.proof.cards)
            val two=committedEvent(first,listOf(ten))
            assertNull(observe(issued.tracker,two,644063107,emptyList(),play(listOf(ten))))
            assertNull(observe(issued.tracker,two,644063240,emptyList(),play(listOf(ten))))
        }
        run {
            val issued=issueShortHandoff()
            val rewritten=issued.snapshot.copy(plays=issued.snapshot.plays+(Seat.OPPOSITE to
                issued.snapshot.plays.getValue(Seat.OPPOSITE).mapIndexed { index,event ->
                    if(index==0) event.copy(eventId="rewritten-prefix") else event
                }))
            val appended=committedEvent(rewritten,issued.proof.cards)
            assertNull(observe(issued.tracker,appended,644063107,emptyList(),play(listOf(ten))))
            assertNull(observe(issued.tracker,appended,644063240,emptyList(),play(listOf(ten))))
        }
        run {
            val issued=issueShortHandoff()
            val otherRound=snapshot(shortHandoffSeed.size,"round-2",fullDeck.take(21))
            val appended=committedEvent(otherRound,issued.proof.cards)
            assertNull(observe(issued.tracker,appended,644063107,emptyList(),play(listOf(ten))))
            assertNull(observe(issued.tracker,appended,644063240,emptyList(),play(listOf(ten))))
        }
    }

    @Test fun postCommitSeedRequiresAFreshUninterruptedQualifiedSourceWithin500Ms() {
        val ten=Card(Rank.TEN,Suit.BLACK)
        for(kind in listOf("late","capture gap","ambiguous","unqualified source")) {
            val issued=issueShortHandoff()
            val committed=committedEvent(issued.snapshot,issued.proof.cards)
            val time=if(kind=="late") issued.issuedAt+501 else issued.issuedAt+249
            assertNull(observe(issued.tracker,committed,time,emptyList(),play(listOf(ten)),
                captureGap=kind=="capture gap",
                incompleteReason=if(kind=="ambiguous") "出牌归属待核对，正在重新识别" else null,
                ambiguous=kind=="ambiguous",
                handConfidence=if(kind=="unqualified source") .79f else .90f))
            assertNull(observe(issued.tracker,committed,time+133,emptyList(),play(listOf(ten))))
        }
    }

    @Test fun sameHandRebaseFrameDoesNotRefreshTheInheritedObservationClock() {
        val issued=issueShortHandoff()
        val committed=committedEvent(issued.snapshot,issued.proof.cards)
        val ten=Card(Rank.TEN,Suit.BLACK)
        assertNull(observe(issued.tracker,committed,644063107,listOf(ten),play(emptyList())))
        // This is 501ms after the last proved shrunken-hand observation at 644062858.
        assertNull(observe(issued.tracker,committed,644063359,emptyList(),play(listOf(ten))))
        assertNull(observe(issued.tracker,committed,644063492,emptyList(),play(listOf(ten))))
    }

    @Test fun shortHandoffNeedsTwoIndependentFreshExactBadgesAndNeverUsesCountsToSupplyCards() {
        val cases=listOf(
            "no badge" to emptyList<Long>(),
            "one badge" to listOf(1300L),
            "cached badge" to listOf(1300L,1300L),
            "wrong count" to listOf(1300L,1450L),
            "stale source" to listOf(1100L,1450L),
            "one weak complete read" to listOf(1300L,1450L),
        )
        for((case,sources) in cases) {
            val (tracker,snapshot)=shortHandoffTracker()
            assertNull(observe(tracker,snapshot,1382,listOf(shortHandoffSeed.first()),play(shortHandoffPlay,
                if(case=="one weak complete read") .632f else .836f),
                remainingHandCounts=sources.getOrNull(0)?.let { oneOpponentBadge(if(case=="wrong count") 2 else 1,it) } ?: emptyMap()))
            assertNull(observe(tracker,snapshot,1515,listOf(shortHandoffSeed.first()),play(emptyList()),
                remainingHandCounts=sources.getOrNull(1)?.let { oneOpponentBadge(1,it) } ?: emptyMap()))
            assertNull(observe(tracker,snapshot,1648,listOf(shortHandoffSeed.first()),play(emptyList())))
        }
    }

    @Test fun shortHandoffNeedsAStrongFullWitnessEvenWithTwoFreshMatchingBadges() {
        val (tracker,snapshot)=shortHandoffTracker()
        assertNull(observe(tracker,snapshot,1382,listOf(shortHandoffSeed.first()),play(shortHandoffPlay,.632f),
            remainingHandCounts=oneOpponentBadge(1,1300)))
        assertNull(observe(tracker,snapshot,1515,listOf(shortHandoffSeed.first()),play(shortHandoffPlay,.632f),
            remainingHandCounts=oneOpponentBadge(1,1450)))
        assertNull(observe(tracker,snapshot,1648,listOf(shortHandoffSeed.first()),play(emptyList()),
            remainingHandCounts=oneOpponentBadge(1,1600)))
    }

    @Test fun shortHandoffDoesNotCountABadgeOlderThanOneSecondAtObservation() {
        val (tracker,snapshot)=shortHandoffTracker()
        assertNull(observe(tracker,snapshot,1382,listOf(shortHandoffSeed.first()),play(shortHandoffPlay,.836f)))
        assertNull(observe(tracker,snapshot,1515,listOf(shortHandoffSeed.first()),play(emptyList()),
            remainingHandCounts=oneOpponentBadge(1,1300)))
        assertNull(observe(tracker,snapshot,1900,listOf(shortHandoffSeed.first()),play(emptyList())))
        assertNull(observe(tracker,snapshot,2300,listOf(shortHandoffSeed.first()),play(emptyList())))
        assertNull(observe(tracker,snapshot,2700,listOf(shortHandoffSeed.first()),play(emptyList()),
            remainingHandCounts=oneOpponentBadge(1,1600))) // Fresh source timestamp is already 1100ms old.
    }

    @Test fun oldBadgePairCannotCompleteWithAFreshFullWitness() {
        val (tracker,snapshot)=shortHandoffTracker()
        assertNull(observe(tracker,snapshot,1382,listOf(shortHandoffSeed.first()),play(shortHandoffPartial,.709f,x=.445f),
            remainingHandCounts=oneOpponentBadge(1,1300)))
        for(t in listOf(1782L,2182L)) assertNull(observe(tracker,snapshot,t,listOf(shortHandoffSeed.first()),play(emptyList())))
        assertNull(observe(tracker,snapshot,2582,listOf(shortHandoffSeed.first()),play(shortHandoffPlay,.836f)))
        assertNull(observe(tracker,snapshot,2982,listOf(shortHandoffSeed.first()),play(emptyList()),
            remainingHandCounts=oneOpponentBadge(1,2300)))
    }

    @Test fun shortHandoffRejectsAmbiguityChangedHandOrChangedDisplayAfterTheWitness() {
        for(kind in listOf("ambiguous","capture gap","changed hand","changed display")) {
            val (tracker,snapshot)=shortHandoffTracker()
            assertNull(observe(tracker,snapshot,1382,listOf(shortHandoffSeed.first()),play(shortHandoffPlay,.836f),
                remainingHandCounts=oneOpponentBadge(1,1300)))
            val changed=when(kind) {
                "changed hand" -> shortHandoffSeed.take(2)
                else -> listOf(shortHandoffSeed.first())
            }
            val display=when(kind) {
                "changed display" -> play(shortHandoffPlay,.836f,x=.62f)
                else -> play(emptyList())
            }
            assertNull(observe(tracker,snapshot,1515,changed,display,
                captureGap=kind=="capture gap",
                incompleteReason=if(kind=="ambiguous") "出牌归属待核对，正在重新识别" else null,
                ambiguous=kind=="ambiguous",
                remainingHandCounts=oneOpponentBadge(1,1500)))
            assertNull(observe(tracker,snapshot,1648,listOf(shortHandoffSeed.first()),play(emptyList()),
                remainingHandCounts=oneOpponentBadge(1,1600)))
        }
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
