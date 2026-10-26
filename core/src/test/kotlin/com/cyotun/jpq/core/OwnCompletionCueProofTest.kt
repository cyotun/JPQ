package com.cyotun.jpq.core

import kotlin.test.*

class OwnCompletionCueProofTest {
    // R3 ended with eight own cards logged as played, leaving this 19-card
    // rank composition. Real suits are immaterial to the contradiction check.
    private val r3OwnHand = listOf(
        Card(Rank.BIG, Suit.NONE),
        Card(Rank.A, Suit.HEART),
        Card(Rank.Q, Suit.DIAMOND),
        Card(Rank.J, Suit.HEART), Card(Rank.J, Suit.SPADE), Card(Rank.J, Suit.CLUB),
        Card(Rank.TEN, Suit.HEART), Card(Rank.TEN, Suit.SPADE),
        Card(Rank.NINE, Suit.CLUB),
        Card(Rank.EIGHT, Suit.HEART), Card(Rank.EIGHT, Suit.DIAMOND), Card(Rank.EIGHT, Suit.CLUB),
        Card(Rank.SEVEN, Suit.CLUB),
        Card(Rank.FIVE, Suit.HEART), Card(Rank.FIVE, Suit.DIAMOND), Card(Rank.FIVE, Suit.SPADE),
        Card(Rank.FOUR, Suit.HEART), Card(Rank.THREE, Suit.CLUB), Card(Rank.TWO, Suit.DIAMOND)
    )

    private fun frame(scene: TableScene = TableScene.TABLE, hand: List<Card>? = r3OwnHand,
                      teammateHand: List<Card>? = null, teammateConfidence: Float = 0f,
                      selfFinished: Boolean = true) = TableFrame(
        timeMs = 44_099_803L,
        scene = scene,
        hand = hand,
        selfFinished = selfFinished,
        teammateHand = teammateHand,
        teammateHandConfidence = teammateConfidence
    )

    @Test fun exactKnownHandOnTableDisprovesEvenAQualifiedFinishCue() {
        val proof = OwnCompletionCueProof()
        val suitFlickered = r3OwnHand.map { card ->
            if (card.rank.isJoker) card else card.copy(suit = if (card.suit in setOf(Suit.HEART, Suit.DIAMOND)) Suit.RED else Suit.BLACK)
        }

        assertTrue(proof.exactOwnHandStillVisible(frame(hand = suitFlickered), r3OwnHand))
        assertTrue(proof.exactOwnHandStillVisible(frame(selfFinished = false), r3OwnHand))
    }

    @Test fun exactKnownHandInTeammateSceneAlsoDisprovesTheCue() {
        val proof = OwnCompletionCueProof()

        assertTrue(proof.exactOwnHandStillVisible(frame(
            scene = TableScene.TEAMMATE,
            hand = null,
            teammateHand = r3OwnHand,
            teammateConfidence = .9f
        ), r3OwnHand))
    }

    @Test fun foreignLowerHandPreservesARealOwnExitPath() {
        val proof = OwnCompletionCueProof()
        val foreign = listOf(Card(Rank.A, Suit.SPADE), Card(Rank.K, Suit.HEART), Card(Rank.SMALL, Suit.NONE))

        assertFalse(proof.exactOwnHandStillVisible(frame(hand = foreign), r3OwnHand))
        assertFalse(proof.exactOwnHandStillVisible(frame(
            scene = TableScene.TEAMMATE,
            hand = null,
            teammateHand = foreign,
            teammateConfidence = .9f
        ), r3OwnHand))
    }

    @Test fun incompleteOrPartialReadDoesNotClaimTheExactOwnHand() {
        val proof = OwnCompletionCueProof()

        assertFalse(proof.exactOwnHandStillVisible(frame(hand = r3OwnHand.drop(1)), r3OwnHand))
        assertFalse(proof.exactOwnHandStillVisible(frame(hand = emptyList()), r3OwnHand))
        assertFalse(proof.exactOwnHandStillVisible(frame(
            scene = TableScene.TEAMMATE,
            hand = null,
            teammateHand = r3OwnHand,
            teammateConfidence = .64f
        ), r3OwnHand))
        assertFalse(proof.exactOwnHandStillVisible(frame().copy(captureGap = true), r3OwnHand))
        assertFalse(proof.exactOwnHandStillVisible(frame().copy(ambiguousPresentation = true), r3OwnHand))
        assertFalse(proof.exactOwnHandStillVisible(frame().copy(incompleteReason = "mask"), r3OwnHand))
        assertFalse(proof.exactOwnHandStillVisible(frame().copy(localizedOverlaySeats = setOf(Seat.PREVIOUS)), r3OwnHand))
    }
}
