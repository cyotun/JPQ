package com.cyotun.jpq.core

import kotlin.test.*

class StraightFlushCandidateRanksTest {
    private val suits = listOf(Suit.SPADE, Suit.HEART, Suit.CLUB, Suit.DIAMOND)
    private val ordinary = Rank.entries.filterNot { it.isJoker }.toSet()
    private val order = listOf(Rank.A, Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE,
        Rank.SIX, Rank.SEVEN, Rank.EIGHT, Rank.NINE, Rank.TEN, Rank.J, Rank.Q, Rank.K, Rank.A)
    private val deck = Rank.entries.flatMap { rank ->
        (if (rank.isJoker) listOf(Suit.NONE) else suits).flatMap { suit -> List(2) { Card(rank, suit) } }
    }
    private fun cards(ranks: List<Rank>, suit: Suit = Suit.SPADE) = ranks.map { Card(it, suit) }
    private fun consumedLeaving(remaining: List<Card>) = deck.toMutableList().apply {
        remaining.forEach { check(remove(it)) }
    }
    private fun evaluate(remaining: List<Card>, wild: Set<Rank> = emptySet()) =
        StraightFlushEvaluator.evaluate(consumedLeaving(remaining), wild, true)

    @Test fun disjointWindowsIncludeEveryCandidateRatherThanOnlyTheFirstWitness() {
        val low = order.take(5)
        val high = listOf(Rank.NINE, Rank.TEN, Rank.J, Rank.Q, Rank.K)
        val result = evaluate(cards(low) + cards(high, Suit.DIAMOND))
        assertEquals(StraightFlushState.POSSIBLE, result.state)
        assertEquals(low, result.witness?.ranks)
        assertEquals((low + high).toSet(), result.candidateRanks)
    }

    @Test fun aceBelongsToLowAndHighWindowsWithoutWrapping() {
        for (window in listOf(order.take(5), order.takeLast(5))) {
            assertEquals(window.toSet(), evaluate(cards(window)).candidateRanks)
        }
        val wrap = listOf(Rank.Q, Rank.K, Rank.A, Rank.TWO, Rank.THREE)
        val result = evaluate(cards(wrap))
        assertEquals(StraightFlushState.IMPOSSIBLE, result.state)
        assertTrue(result.candidateRanks.isEmpty())
    }

    @Test fun wildcardHighlightsTheLogicalGapAndCannotBeUsedTwiceInAHeartRun() {
        val run = order.take(5)
        val remaining = cards(run.take(4)) + Card(Rank.SEVEN, Suit.HEART)
        val result = evaluate(remaining, setOf(Rank.SEVEN))
        assertEquals(run.toSet(), result.candidateRanks)
        assertEquals(1, result.witness?.wildcardsNeeded)
        assertFalse(Rank.SEVEN in result.candidateRanks)

        val heartRun = cards(run.take(4), Suit.HEART) + Card(Rank.K, Suit.DIAMOND)
        assertTrue(evaluate(heartRun, setOf(Rank.TWO)).candidateRanks.isEmpty())
        assertEquals(run.toSet(), evaluate(heartRun + Card(Rank.TWO, Suit.HEART),
            setOf(Rank.TWO)).candidateRanks)
    }

    @Test fun provenWindowDoesNotHideAdditionalWindowsWithUncertainSuits() {
        val low = order.take(5)
        val high = listOf(Rank.NINE, Rank.TEN, Rank.J, Rank.Q, Rank.K)
        val consumed = consumedLeaving(cards(low) + cards(high, Suit.DIAMOND))
        high.forEach { rank ->
            assertTrue(consumed.remove(Card(rank, Suit.HEART)))
            consumed += Card(rank, Suit.RED)
        }
        val result = StraightFlushEvaluator.evaluate(consumed, emptySet(), true)
        assertEquals(StraightFlushState.POSSIBLE, result.state)
        assertEquals((low + high).toSet(), result.candidateRanks)

        val uncertainOnly = consumedLeaving(cards(low))
        low.forEach { rank ->
            assertTrue(uncertainOnly.remove(Card(rank, Suit.CLUB)))
            uncertainOnly += Card(rank, Suit.BLACK)
        }
        val unknown = StraightFlushEvaluator.evaluate(uncertainOnly, emptySet(), true)
        assertEquals(StraightFlushState.UNKNOWN, unknown.state)
        assertEquals(low.toSet(), unknown.candidateRanks)
    }

    @Test fun initialAndInvalidInputsKeepAllOrdinaryRanksWhileCapacityProofClearsThem() {
        assertEquals(ordinary, StraightFlushEvaluator.evaluate(emptyList(), null, false).candidateRanks)
        val invalid = StraightFlushEvaluator.evaluate(List(3) { Card(Rank.A, Suit.SPADE) }, emptySet(), true)
        assertEquals(StraightFlushState.UNKNOWN, invalid.state)
        assertEquals(ordinary, invalid.candidateRanks)
        val cards = cards(order.take(5)) + cards(listOf(Rank.J, Rank.Q, Rank.K), Suit.DIAMOND)
        val consumed = consumedLeaving(cards)
        // A known teammate contributes zero unknown cards; two four-card opponents cannot form a run.
        val dark = StraightFlushEvaluator.evaluate(consumed, null, false, 4, listOf(4, 0, 4))
        assertEquals(StraightFlushState.IMPOSSIBLE, dark.state)
        assertTrue(dark.candidateRanks.isEmpty())
        val malformed = StraightFlushEvaluator.evaluate(consumed, emptySet(), true, 0, listOf(4, 0, 3))
        assertEquals(StraightFlushState.UNKNOWN, malformed.state)
        assertEquals(ordinary, malformed.candidateRanks)
        assertTrue(ordinary.none { it.isJoker })
    }

    @Test fun everySmallPhysicalPoolMatchesAnIndependentAllWindowSlotOracle() {
        val pool = cards(order.take(5), Suit.HEART) + Card(Rank.TWO, Suit.HEART) +
            cards(listOf(Rank.NINE, Rank.TEN, Rank.J, Rank.Q, Rank.K), Suit.SPADE)
        for (mask in 0 until (1 shl pool.size)) {
            val remaining = pool.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            for (wild in listOf(emptySet(), setOf(Rank.TWO))) {
                assertEquals(oracleCandidates(remaining, wild), evaluate(remaining, wild).candidateRanks,
                    "mask=$mask wild=$wild")
            }
        }
    }

    private fun oracleCandidates(remaining: List<Card>, wild: Set<Rank>): Set<Rank> {
        if (remaining.size < 5) return emptySet()
        val candidates = mutableSetOf<Rank>()
        for (suit in suits) for (start in 0..9) {
            val window = order.subList(start, start + 5)
            val used = BooleanArray(remaining.size)
            fun fill(slot: Int): Boolean {
                if (slot == 5) return true
                for (i in remaining.indices) {
                    val card = remaining[i]
                    if (!used[i] && ((card.rank == window[slot] && card.suit == suit) ||
                        (card.rank in wild && card.suit == Suit.HEART))) {
                        used[i] = true
                        if (fill(slot + 1)) return true
                        used[i] = false
                    }
                }
                return false
            }
            if (fill(0)) candidates.addAll(window)
        }
        return candidates
    }
}
