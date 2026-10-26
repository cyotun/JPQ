package com.cyotun.jpq.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PassClosedSupersetProofTest {
    private data class R3Fixture(
        val hand: List<Card>,
        val oldSnapshot: RoundSnapshot,
        val appendedSnapshot: RoundSnapshot,
        val prior: QuantityAnchoredPlayProof.PriorPresentation,
        val newGroup: List<Card>,
    )

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

    private fun r3Fixture(): R3Fixture {
        val oldEight=Card(Rank.EIGHT,Suit.RED)
        val earlierOwnTwo=Card(Rank.TWO,Suit.BLACK)
        val ownNine=Card(Rank.NINE,Suit.BLACK)
        val nextKing=Card(Rank.K,Suit.BLACK)
        val straight=listOf(Card(Rank.TWO,Suit.RED),Card(Rank.THREE,Suit.RED),
            Card(Rank.FOUR,Suit.BLACK),Card(Rank.FIVE,Suit.RED),Card(Rank.SIX,Suit.BLACK))
        val newGroup=listOf(oldEight,Card(Rank.SEVEN,Suit.BLACK),Card(Rank.SIX,Suit.RED),
            Card(Rank.FIVE,Suit.RED),Card(Rank.FOUR,Suit.RED))
        val deck=Rank.entries.flatMap { rank ->
            if(rank.isJoker) List(2) { Card(rank,Suit.NONE) }
            else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } }
        }.toMutableList()
        (listOf(oldEight,nextKing)+straight+newGroup).forEach { check(deck.remove(it)) }
        val initialHand=listOf(earlierOwnTwo,ownNine)+deck.filterNot { it==earlierOwnTwo || it==ownNine }.take(25)
        val ledger=RoundLedger()
        ledger.begin("r3-pass-append",initialHand)
        ledger.commit(PlayEvent("r3-pass-append","self-two",1,Seat.SELF,listOf(earlierOwnTwo)))
        ledger.commit(PlayEvent("r3-pass-append","opp-eight",3,Seat.OPPOSITE,listOf(oldEight)))
        ledger.commit(PlayEvent("r3-pass-append","self-nine",4,Seat.SELF,listOf(ownNine)))
        ledger.commit(PlayEvent("r3-pass-append","next-king",5,Seat.NEXT,listOf(nextKing)))
        val ownHand=ledger.currentHand()
        val oldSnapshot=ledger.snapshot()
        val oldBox=GlyphBox(.4773f,.1588f,.4913f,.2035f)
        val trackedPrior=QuantityAnchoredPlayProof.PriorPresentation("opp-eight",listOf(oldEight),
            listOf(PlayGlyph(oldEight,.891f,oldBox)))
        ledger.commit(PlayEvent("r3-pass-append","next-straight",6,Seat.NEXT,straight))
        return R3Fixture(ownHand,oldSnapshot,ledger.snapshot(),trackedPrior,newGroup)
    }

    private fun r3EmptyFrame(f: R3Fixture,time: Long,source: Long?=null): TableFrame {
        val seats=Seat.entries.associateWith { seat ->
            ObservedPlay(emptyList(),1f,regionObserved=seat!=Seat.OPPOSITE)
        }
        return TableFrame(time,TableScene.TABLE,f.hand,seats,
            observedPasses=source?.let { mapOf(Seat.OPPOSITE to it) }.orEmpty())
    }

    private fun r3GroupFrame(f: R3Fixture,time: Long,confidence: Float=.873f): TableFrame {
        val glyphs=f.newGroup.map { card ->
            val (left,right)=when(card.rank) {
                Rank.EIGHT -> .529f to .5413f
                Rank.SEVEN -> .505f to .5174f
                Rank.SIX -> .4808f to .4924f
                Rank.FIVE -> .4561f to .4678f
                else -> .4309f to .4438f
            }
            PlayGlyph(card,confidence,GlyphBox(left,.1673f,right,.2076f))
        }
        val play=ObservedPlay(f.newGroup,confidence,glyphs)
        return TableFrame(time,TableScene.TABLE,f.hand,
            Seat.entries.associateWith { if(it==Seat.OPPOSITE) play else ObservedPlay(emptyList(),1f) })
    }

    private fun openR3Pass(proof: PassClosedSupersetProof,f: R3Fixture) {
        assertNull(proof.observe(r3EmptyFrame(f,1000),f.oldSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
        assertNull(proof.observe(r3EmptyFrame(f,1100,1100),f.oldSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
        assertNull(proof.observe(r3EmptyFrame(f,1250,1250),f.oldSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
    }

    @Test fun r3PassWitnessSurvivesOnlyAnAppendAndCommitsTheDirectNewFiveCardGroupOnce() {
        val f=r3Fixture(); val proof=PassClosedSupersetProof()
        openR3Pass(proof,f)
        assertNull(proof.observe(r3EmptyFrame(f,1500,1400),f.appendedSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
        assertNull(proof.observe(r3GroupFrame(f,1600),f.appendedSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
        assertNull(proof.observe(r3GroupFrame(f,1660,.805f),f.appendedSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
        assertNull(proof.observe(r3GroupFrame(f,1720),f.appendedSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
        val committed=assertNotNull(proof.observe(r3GroupFrame(f,1780),f.appendedSnapshot,
            Seat.OPPOSITE,f.prior,f.hand,900))
        assertEquals(f.newGroup.map { it.colorKey() }.sorted(),committed.cards.map { it.colorKey() }.sorted())
        assertNull(proof.observe(r3GroupFrame(f,1840),f.appendedSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
    }

    @Test fun anAppendDuringAStableCandidateKeepsItsOriginalVotesAndDeliveryFence() {
        val f=r3Fixture(); val proof=PassClosedSupersetProof(); openR3Pass(proof,f)
        assertNull(proof.observe(r3GroupFrame(f,1600),f.oldSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
        assertNull(proof.observe(r3GroupFrame(f,1660),f.appendedSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
        assertNull(proof.observe(r3GroupFrame(f,1720),f.appendedSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
        assertNotNull(proof.observe(r3GroupFrame(f,1780),f.appendedSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
        assertNull(proof.observe(r3GroupFrame(f,1840),f.appendedSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
    }

    @Test fun rewritingRemovingOrReorderingAClosingEventInvalidatesTheOldPassWitness() {
        val f=r3Fixture()
        val self=f.oldSnapshot.plays.getValue(Seat.SELF).last()
        val next=f.oldSnapshot.plays.getValue(Seat.NEXT).single()
        val rewritten=f.oldSnapshot.copy(plays=f.oldSnapshot.plays+
            (Seat.SELF to listOf(self.copy(eventId="rewritten-self-event"))))
        val recarded=f.oldSnapshot.copy(plays=f.oldSnapshot.plays+
            (Seat.SELF to listOf(self.copy(cards=listOf(Card(Rank.A,Suit.RED))))))
        val reassigned=f.oldSnapshot.copy(plays=f.oldSnapshot.plays+
            (Seat.SELF to listOf(self.copy(seat=Seat.PREVIOUS))))
        val removed=f.oldSnapshot.copy(plays=f.oldSnapshot.plays+(Seat.SELF to emptyList()))
        val reordered=f.oldSnapshot.copy(plays=f.oldSnapshot.plays+
            (Seat.SELF to listOf(self.copy(sequence=5)))+
            (Seat.NEXT to listOf(next.copy(sequence=4))))
        for(changed in listOf(rewritten,recarded,reassigned,removed,reordered)) {
            val proof=PassClosedSupersetProof(); openR3Pass(proof,f)
            for(t in 1300L..1480L step 60)
                assertNull(proof.observe(r3GroupFrame(f,t),changed,Seat.OPPOSITE,f.prior,f.hand,900))
        }
    }

    @Test fun anAppendBeforeTwoPassSourcesRebasesTheFenceAndCannotBorrowAnOlderVote() {
        val f=r3Fixture(); val proof=PassClosedSupersetProof()
        assertNull(proof.observe(r3EmptyFrame(f,1000),f.oldSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
        assertNull(proof.observe(r3EmptyFrame(f,1100,1100),f.oldSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
        assertNull(proof.observe(r3EmptyFrame(f,1200,1150),f.appendedSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
        assertNull(proof.observe(r3EmptyFrame(f,1300,1300),f.appendedSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
        for(t in 1360L..1600L step 60)
            assertNull(proof.observe(r3GroupFrame(f,t),f.appendedSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
    }

    @Test fun anAppendAfterCandidateFrameGapCannotReuseTheOldPassWitness() {
        val f=r3Fixture(); val proof=PassClosedSupersetProof(); openR3Pass(proof,f)
        assertNull(proof.observe(r3GroupFrame(f,1600),f.oldSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
        assertNull(proof.observe(r3GroupFrame(f,3301),f.appendedSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
        for(t in 3361L..3541L step 60)
            assertNull(proof.observe(r3GroupFrame(f,t),f.appendedSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
    }

    @Test fun futurePassSourceCannotContributeToAnAppendPreservedWitness() {
        val f=r3Fixture(); val proof=PassClosedSupersetProof()
        assertNull(proof.observe(r3EmptyFrame(f,1000),f.oldSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
        assertNull(proof.observe(r3EmptyFrame(f,1100,1100),f.oldSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
        assertNull(proof.observe(r3EmptyFrame(f,1250,1250),f.oldSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
        assertNull(proof.observe(r3EmptyFrame(f,1500,1600),f.appendedSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
        for(t in 1600L..1780L step 60)
            assertNull(proof.observe(r3GroupFrame(f,t),f.appendedSnapshot,Seat.OPPOSITE,f.prior,f.hand,900))
    }
}
