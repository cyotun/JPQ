package com.cyotun.jpq.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OwnDelayedHandDifferenceProofTest {
    private val handAces = listOf(
        Card(Rank.A,Suit.BLACK),Card(Rank.A,Suit.BLACK),Card(Rank.A,Suit.BLACK),
        Card(Rank.A,Suit.RED),Card(Rank.A,Suit.RED),Card(Rank.A,Suit.RED))
    private val queen = Card(Rank.Q,Suit.RED)
    private val nines = listOf(Card(Rank.NINE,Suit.RED),Card(Rank.NINE,Suit.BLACK),Card(Rank.NINE,Suit.BLACK))
    private val known = handAces + queen + nines
    private val partialHand = listOf(
        Card(Rank.A,Suit.BLACK),Card(Rank.A,Suit.BLACK),
        Card(Rank.A,Suit.RED),Card(Rank.A,Suit.RED),Card(Rank.A,Suit.RED),queen)
    private val returnedHand = handAces + queen
    private val round = "44123782-4"
    private val priorSelfCards = deck().filterNot { it in known }.take(17)
    private val boxes = listOf(
        GlyphBox(.4536f,.3266f,.4667f,.3703f),
        GlyphBox(.5025f,.3264f,.5158f,.3701f),
        GlyphBox(.4780f,.3265f,.4914f,.3702f)
    )

    private fun play(cards: List<Card> = nines,slots: List<GlyphBox> = boxes): ObservedPlay =
        ObservedPlay(cards,.89f,cards.mapIndexed { i,card -> PlayGlyph(card,.88f,slots[i]) },true)

    private fun frame(
        time: Long,
        hand: List<Card> = partialHand,
        selfPlay: ObservedPlay? = play(),
        scene: TableScene = TableScene.TABLE,
        ambiguous: Boolean = false,
        captureGap: Boolean = false,
        incomplete: String? = null,
        overlays: Set<Seat> = emptySet()
    ) = TableFrame(time,scene,hand,mapOf(Seat.SELF to selfPlay),false,
        incomplete,ambiguous,captureGap,localizedOverlaySeats=overlays)

    private fun snapshot(eventId: String = "prior-self") = RoundSnapshot(
        round,RoundPhase.ACTIVE,Rank.entries.associateWith { 1 },
        mapOf(Seat.SELF to listOf(PlayEvent(round,eventId,1,Seat.SELF,priorSelfCards))),null)

    private fun seed(proof: OwnDelayedHandDifferenceProof,s: RoundSnapshot = snapshot()) {
        assertNull(proof.observe(frame(44_222_913),s,known))
        assertNull(proof.observe(frame(44_223_095),s,known))
        assertNull(proof.observe(frame(44_223_279),s,known))
    }

    @Test fun c47R4WaitsForTheHandToReturnThenConfirmsTheThreeDirectNines() {
        val proof=OwnDelayedHandDifferenceProof()
        val s=snapshot()
        seed(proof,s)

        assertNull(proof.observe(frame(44_224_176,hand=returnedHand),s,known))
        val result=assertNotNull(proof.observe(frame(44_224_392,hand=returnedHand),s,known))

        assertEquals(nines,result.cards)
        assertEquals(nines,result.glyphs.map { it.card })
    }

    @Test fun aStablePartialSelfDisplayCanConfirmWithoutGuessingItsHiddenCards() {
        val proof=OwnDelayedHandDifferenceProof()
        val s=snapshot()
        seed(proof,s)
        val oneVisibleNine=play(listOf(nines.first()),listOf(boxes.first()))

        assertNull(proof.observe(frame(44_224_300,hand=returnedHand,selfPlay=oneVisibleNine),s,known))
        val result=assertNotNull(proof.observe(frame(44_224_500,hand=returnedHand,selfPlay=oneVisibleNine),s,known))

        assertEquals(nines,result.cards)
        assertEquals(3,result.glyphs.size)
    }

    @Test fun aHandDifferenceWithAnAdditionalMissingAceDoesNotConfirmTheNinePlay() {
        val proof=OwnDelayedHandDifferenceProof()
        val s=snapshot()
        seed(proof,s)
        val handMissingFour = partialHand

        assertNull(proof.observe(frame(44_224_176,hand=handMissingFour),s,known))
        assertNull(proof.observe(frame(44_224_392,hand=handMissingFour),s,known))
    }

    @Test fun directlyObservedCardsMustBeAbsentFromThePartialHandBeforeSeeding() {
        val proof=OwnDelayedHandDifferenceProof()
        val s=snapshot()
        val handMissingOnlyAnAce = known.drop(1)
        for (t in listOf(1000L,1200L,1400L)) {
            assertNull(proof.observe(frame(t,hand=handMissingOnlyAnAce),s,known))
        }

        assertNull(proof.observe(frame(1600,hand=returnedHand),s,known))
        assertNull(proof.observe(frame(1800,hand=returnedHand),s,known))
    }

    @Test fun returnOfTheOriginalHandCancelsTheRetainedCandidate() {
        val proof=OwnDelayedHandDifferenceProof()
        val s=snapshot()
        seed(proof,s)

        assertNull(proof.observe(frame(44_224_000,hand=known),s,known))
        assertNull(proof.observe(frame(44_224_176,hand=returnedHand),s,known))
        assertNull(proof.observe(frame(44_224_392,hand=returnedHand),s,known))
    }

    @Test fun unknownDirectCardsCannotSeedAGuessedOwnedPlay() {
        val proof=OwnDelayedHandDifferenceProof()
        val s=snapshot()
        val impossible=List(3) { Card(Rank.NINE,Suit.BLACK) }
        val wrongPlay=play(impossible)
        for (t in listOf(1000L,1100L,1200L,1300L,1400L)) {
            assertNull(proof.observe(frame(t,hand=partialHand,selfPlay=wrongPlay),s,known))
        }
    }

    @Test fun proofRequiresCleanFramesAndAnUnchangedOwnLedgerVersion() {
        val s=snapshot()
        val proof=OwnDelayedHandDifferenceProof()
        assertNull(proof.observe(frame(1000),s,known))
        seed(proof,s)
        assertNull(proof.observe(frame(44_224_176,hand=returnedHand,captureGap=true),s,known))
        assertNull(proof.observe(frame(44_224_392,hand=returnedHand),s,known))

        val changedOwnLedger=OwnDelayedHandDifferenceProof()
        seed(changedOwnLedger,s)
        assertNull(changedOwnLedger.observe(frame(44_224_176,hand=returnedHand),snapshot("new-own-event"),known))
        assertNull(changedOwnLedger.observe(frame(44_224_392,hand=returnedHand),snapshot("new-own-event"),known))
    }

    @Test fun ambiguityOverlayAndMovedGlyphSlotsCannotCarryTheProof() {
        val s=snapshot()
        val faults=listOf<(Long)->TableFrame>(
            { t -> frame(t,ambiguous=true) },
            { t -> frame(t,overlays=setOf(Seat.SELF)) },
            { t -> frame(t,incomplete="incomplete") }
        )
        for (fault in faults) {
            val proof=OwnDelayedHandDifferenceProof()
            assertNull(proof.observe(frame(1000),s,known))
            assertNull(proof.observe(frame(1100),s,known))
            assertNull(proof.observe(fault(1200),s,known))
            assertNull(proof.observe(frame(1300,hand=returnedHand),s,known))
            assertNull(proof.observe(frame(1500,hand=returnedHand),s,known))
        }

        val moved=OwnDelayedHandDifferenceProof()
        assertNull(moved.observe(frame(1000),s,known))
        assertNull(moved.observe(frame(1100),s,known))
        val shifted=boxes.map { it.copy(left=it.left+.08f,right=it.right+.08f) }
        assertNull(moved.observe(frame(1200,selfPlay=play(slots=shifted)),s,known))
        assertNull(moved.observe(frame(1300,hand=returnedHand,selfPlay=play(slots=shifted)),s,known))
        assertNull(moved.observe(frame(1500,hand=returnedHand,selfPlay=play(slots=shifted)),s,known))
    }

    private fun deck(): List<Card> = Rank.entries.flatMap { rank ->
        if (rank.isJoker) List(2) { Card(rank,Suit.NONE) }
        else listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } }
    }
}
