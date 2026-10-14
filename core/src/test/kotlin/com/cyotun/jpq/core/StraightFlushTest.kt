package com.cyotun.jpq.core

import kotlin.random.Random
import kotlin.test.*

class StraightFlushTest {
    private val physicalSuits = listOf(Suit.SPADE, Suit.HEART, Suit.CLUB, Suit.DIAMOND)
    private val deck = Rank.entries.flatMap { rank ->
        (if (rank.isJoker) listOf(Suit.NONE) else physicalSuits).flatMap { suit ->
            List(2) { Card(rank, suit) }
        }
    }
    private fun card(rank: Rank, suit: Suit = Suit.SPADE) = Card(rank, suit)
    private fun consumedLeaving(cards: List<Card>): List<Card> = deck.toMutableList().apply {
        cards.forEach { check(remove(it)) }
    }
    private fun evaluateRemaining(cards: List<Card>, wild: Set<Rank>? = emptySet(),
        confirmed: Boolean = true) = StraightFlushEvaluator.evaluate(consumedLeaving(cards), wild, confirmed)

    @Test fun aLowAndHighNaturalRunsAreSupportedWithoutWrapping() {
        val low = listOf(Rank.A, Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE).map { card(it) }
        val high = listOf(Rank.TEN, Rank.J, Rank.Q, Rank.K, Rank.A).map { card(it, Suit.DIAMOND) }
        val wrap = listOf(Rank.Q, Rank.K, Rank.A, Rank.TWO, Rank.THREE).map { card(it) }
        assertEquals(StraightFlushState.POSSIBLE, evaluateRemaining(low).state)
        assertEquals(Suit.SPADE, evaluateRemaining(low).witness?.suit)
        assertEquals(StraightFlushState.POSSIBLE, evaluateRemaining(high).state)
        assertEquals(StraightFlushState.IMPOSSIBLE, evaluateRemaining(wrap).state)
    }

    @Test fun unknownRuleKeepsEveryPotentialHeartWildcardAndStillExcludesTooFewCards() {
        val natural = listOf(Rank.A, Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE).map { card(it) }
        assertEquals(StraightFlushState.POSSIBLE, evaluateRemaining(natural, null, false).state)
        assertEquals(StraightFlushState.IMPOSSIBLE, evaluateRemaining(emptyList(), null, false).state)
        assertEquals(StraightFlushState.IMPOSSIBLE, evaluateRemaining(emptyList(), emptySet(), false).state)
        assertEquals(StraightFlushState.IMPOSSIBLE, evaluateRemaining(emptyList(), null, true).state)
        assertEquals(StraightFlushState.IMPOSSIBLE, evaluateRemaining(emptyList()).state)
        val unrelated = listOf(Rank.A, Rank.THREE, Rank.SIX, Rank.NINE, Rank.K).map { card(it, Suit.HEART) }
        assertEquals(StraightFlushState.UNKNOWN, evaluateRemaining(unrelated, null, false).state)
        assertEquals(StraightFlushState.IMPOSSIBLE, evaluateRemaining(unrelated).state)
    }

    @Test fun oneConfirmedHeartWildcardCanCompleteOneMissingRank() {
        val cards = listOf(Rank.A, Rank.TWO, Rank.THREE, Rank.FOUR).map { card(it) } +
            card(Rank.SEVEN, Suit.HEART)
        val result = evaluateRemaining(cards, setOf(Rank.SEVEN))
        assertEquals(StraightFlushState.POSSIBLE, result.state)
        assertEquals(1, result.witness?.wildcardsNeeded)
        assertEquals(StraightFlushState.IMPOSSIBLE, evaluateRemaining(cards).state)
        assertEquals(StraightFlushState.UNKNOWN, evaluateRemaining(cards, null, false).state)
    }

    @Test fun twoMissingRanksRequireTwoSeparateWildcardCards() {
        val natural = listOf(Rank.A, Rank.TWO, Rank.THREE).map { card(it) }
        val one = natural + card(Rank.SEVEN, Suit.HEART)
        val two = one + card(Rank.SEVEN, Suit.HEART)
        assertEquals(StraightFlushState.IMPOSSIBLE, evaluateRemaining(one, setOf(Rank.SEVEN)).state)
        val result = evaluateRemaining(two, setOf(Rank.SEVEN))
        assertEquals(StraightFlushState.POSSIBLE, result.state)
        assertEquals(2, result.witness?.wildcardsNeeded)
    }

    @Test fun aHeartWildcardUsedNaturallyCannotAlsoFillAnotherSlot() {
        val cards = listOf(Rank.A, Rank.TWO, Rank.THREE, Rank.FOUR).map { card(it, Suit.HEART) }
        assertEquals(StraightFlushState.IMPOSSIBLE, evaluateRemaining(cards, setOf(Rank.TWO)).state)
        val result = evaluateRemaining(cards + card(Rank.TWO, Suit.HEART), setOf(Rank.TWO))
        assertEquals(StraightFlushState.POSSIBLE, result.state)
        assertEquals(1, result.witness?.wildcardsNeeded)
    }

    @Test fun twoWildcardRanksContributeIndependentlyAndOnlyWhenConfigured() {
        val cards = listOf(Rank.A, Rank.TWO, Rank.THREE).map { card(it, Suit.CLUB) } +
            listOf(card(Rank.SIX, Suit.HEART), card(Rank.SEVEN, Suit.HEART))
        assertEquals(StraightFlushState.IMPOSSIBLE, evaluateRemaining(cards, setOf(Rank.SEVEN)).state)
        assertEquals(StraightFlushState.POSSIBLE,
            evaluateRemaining(cards, setOf(Rank.SIX, Rank.SEVEN)).state)
    }

    @Test fun redUnknownCardsAreNeitherAssignedHeartsNorDiscarded() {
        val known = listOf(Rank.A, Rank.TWO, Rank.THREE, Rank.FOUR).map { card(it) }
        val consumed = consumedLeaving(known + card(Rank.SEVEN, Suit.HEART)).toMutableList()
        assertTrue(consumed.remove(card(Rank.SEVEN, Suit.DIAMOND)))
        consumed += Card(Rank.SEVEN, Suit.RED)
        val result = StraightFlushEvaluator.evaluate(consumed, setOf(Rank.SEVEN), true)
        assertEquals(StraightFlushState.UNKNOWN, result.state)
        assertEquals("suit_uncertain", result.reason)
    }

    @Test fun blackUnknownCardsPreservePossibleButUnprovenNaturalSuits() {
        val ranks = listOf(Rank.A, Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE)
        val consumed = consumedLeaving(ranks.map { card(it) }).toMutableList()
        ranks.forEach { rank ->
            assertTrue(consumed.remove(card(rank, Suit.CLUB)))
            consumed += Card(rank, Suit.BLACK)
        }
        assertEquals(StraightFlushState.UNKNOWN,
            StraightFlushEvaluator.evaluate(consumed, emptySet(), true).state)
    }

    @Test fun diamondNaturalAndHeartWildcardUpperBoundsCannotUseTheSameUnknownRedCard() {
        val remaining = listOf(Rank.A, Rank.THREE, Rank.FOUR, Rank.SIX).map { card(it, Suit.DIAMOND) } +
            card(Rank.TWO, Suit.DIAMOND)
        val consumed = consumedLeaving(remaining).toMutableList()
        assertTrue(consumed.remove(card(Rank.TWO, Suit.HEART)))
        consumed += Card(Rank.TWO, Suit.RED)
        assertEquals(StraightFlushState.IMPOSSIBLE,
            StraightFlushEvaluator.evaluate(consumed, setOf(Rank.TWO), true).state)
    }

    @Test fun knownLowerBoundsCanProveWildRunsDespiteOtherSuitUncertainty() {
        val remaining = listOf(Rank.A, Rank.TWO, Rank.THREE, Rank.FOUR).map { card(it) } +
            List(2) { card(Rank.SEVEN, Suit.HEART) } + card(Rank.K, Suit.CLUB)
        val consumed = consumedLeaving(remaining).toMutableList()
        assertTrue(consumed.remove(card(Rank.K, Suit.SPADE)))
        consumed += Card(Rank.K, Suit.BLACK)
        assertEquals(StraightFlushState.POSSIBLE,
            StraightFlushEvaluator.evaluate(consumed, setOf(Rank.SEVEN), true).state)
    }

    @Test fun malformedCapacityIsUnknownAndDoesNotThrow() {
        val illegalPhysical = List(3) { card(Rank.A) }
        val illegalColor = List(5) { Card(Rank.A, Suit.RED) }
        val illegalJokers = List(3) { Card(Rank.BIG, Suit.NONE) }
        for (consumed in listOf(illegalPhysical, illegalColor, illegalJokers)) {
            val result = StraightFlushEvaluator.evaluate(consumed, emptySet(), true)
            assertEquals(StraightFlushState.UNKNOWN, result.state)
            assertEquals("invalid_card_capacity", result.reason)
        }
        assertEquals(StraightFlushState.UNKNOWN,
            evaluateRemaining(emptyList(), setOf(Rank.BIG)).state)
    }

    @Test fun jokersAreNeverNaturalSuitsOrSubstitutes() {
        val cards = listOf(Rank.A, Rank.TWO, Rank.THREE, Rank.FOUR).map { card(it) } +
            listOf(Card(Rank.BIG, Suit.NONE), Card(Rank.SMALL, Suit.NONE))
        assertEquals(StraightFlushState.IMPOSSIBLE, evaluateRemaining(cards).state)
    }

    @Test fun evaluatorIsPureAndConsumedOwnBaselineMustNotBeCountedTwice() {
        val input = mutableListOf<Card>()
        val result = StraightFlushEvaluator.evaluate(input, emptySet(), true)
        assertEquals(StraightFlushState.POSSIBLE, result.state)
        assertTrue(input.isEmpty())
        val cards = listOf(Rank.A, Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE).map { card(it) }
        val consumed = consumedLeaving(cards)
        assertEquals(StraightFlushState.POSSIBLE,
            StraightFlushEvaluator.evaluate(consumed, emptySet(), true).state)
        assertEquals(StraightFlushState.IMPOSSIBLE,
            StraightFlushEvaluator.evaluate(consumed + cards.first(), emptySet(), true).state)
    }

    @Test fun exactPhysicalPoolsMatchAnIndependentCardSlotOracle() {
        val universe = listOf(card(Rank.A, Suit.HEART), card(Rank.TWO, Suit.HEART),
            card(Rank.THREE, Suit.HEART), card(Rank.FOUR, Suit.HEART), card(Rank.FIVE, Suit.HEART),
            card(Rank.TWO, Suit.HEART), card(Rank.SIX, Suit.SPADE), card(Rank.SEVEN, Suit.SPADE),
            card(Rank.EIGHT, Suit.SPADE))
        val wildcardRules = listOf(emptySet(), setOf(Rank.TWO), setOf(Rank.TWO, Rank.FIVE))
        for (mask in 0 until (1 shl universe.size)) {
            val remaining = universe.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            for (wild in wildcardRules) {
                val expected = if (oracleCanBuild(remaining, wild)) StraightFlushState.POSSIBLE
                    else StraightFlushState.IMPOSSIBLE
                assertEquals(expected, evaluateRemaining(remaining, wild).state,
                    "mask=$mask wild=$wild remaining=$remaining")
            }
        }
    }

    @Test fun uncertainColorsAreSoundAgainstAllPhysicalAllocations() {
        val random = Random(76104)
        val smallRanks = listOf(Rank.A, Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.SIX, Rank.SEVEN)
        val pool = smallRanks.flatMap { rank -> physicalSuits.map { card(rank, it) } }
        repeat(100) { case ->
            val remaining = pool.shuffled(random).take(random.nextInt(3, 9))
            val consumed = consumedLeaving(remaining).toMutableList()
            // Lose only the suit of actual consumed physical cards; color remains correct.
            repeat(random.nextInt(1, 5)) {
                val eligible = consumed.indices.filter { index ->
                    consumed[index].rank in smallRanks && consumed[index].suit in physicalSuits
                }
                val index = eligible.random(random)
                val known = consumed[index]
                consumed[index] = known.copy(suit = if (known.suit in setOf(Suit.HEART, Suit.DIAMOND))
                    Suit.RED else Suit.BLACK)
            }
            val wild = when (case % 3) {
                0 -> emptySet()
                1 -> setOf(Rank.TWO)
                else -> setOf(Rank.TWO, Rank.SEVEN)
            }
            val physicalPossibilities = oracleRemainingAllocations(consumed)
                .map { oracleCanBuild(it, wild) }
            assertTrue(physicalPossibilities.isNotEmpty())
            when (StraightFlushEvaluator.evaluate(consumed, wild, true).state) {
                StraightFlushState.POSSIBLE -> assertTrue(physicalPossibilities.all { it }, "case=$case")
                StraightFlushState.IMPOSSIBLE -> assertTrue(physicalPossibilities.none { it }, "case=$case")
                StraightFlushState.UNKNOWN -> assertTrue(physicalPossibilities.any { it }, "case=$case")
            }
        }
    }

    /** Physical-card matching, independently trying natural and wildcard choices for every slot. */
    private fun oracleCanBuild(remaining: List<Card>, wild: Set<Rank>): Boolean {
        if (remaining.size < 5) return false
        val ranks = listOf(Rank.A, Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.SIX, Rank.SEVEN,
            Rank.EIGHT, Rank.NINE, Rank.TEN, Rank.J, Rank.Q, Rank.K, Rank.A)
        for (suit in physicalSuits) for (start in 0..9) {
            val used = BooleanArray(remaining.size)
            fun fill(offset: Int): Boolean {
                if (offset == 5) return true
                val required = ranks[start + offset]
                for (index in remaining.indices) {
                    val candidate = remaining[index]
                    if (!used[index] && ((candidate.rank == required && candidate.suit == suit) ||
                        (candidate.rank in wild && candidate.suit == Suit.HEART))) {
                        used[index] = true
                        if (fill(offset + 1)) return true
                        used[index] = false
                    }
                }
                return false
            }
            if (fill(0)) return true
        }
        return false
    }

    /** Assigns each uncertain consumed card to an actual suit, respecting its two-copy capacity. */
    private fun oracleRemainingAllocations(consumed: List<Card>): List<List<Card>> {
        val used = consumed.filter { it.suit !in setOf(Suit.RED, Suit.BLACK) }
            .groupingBy { it }.eachCount().toMutableMap()
        val uncertain = consumed.filter { it.suit in setOf(Suit.RED, Suit.BLACK) }
        val answers = mutableSetOf<List<Card>>()
        fun assign(index: Int) {
            if (index == uncertain.size) {
                val left = used.toMutableMap()
                answers += deck.filter { candidate ->
                    val consumedCount = left[candidate] ?: 0
                    if (consumedCount == 0) true else {
                        left[candidate] = consumedCount - 1
                        false
                    }
                }
                return
            }
            val unknown = uncertain[index]
            val choices = if (unknown.suit == Suit.RED) listOf(Suit.HEART, Suit.DIAMOND)
                else listOf(Suit.SPADE, Suit.CLUB)
            for (suit in choices) {
                val physical = unknown.copy(suit = suit)
                val count = used[physical] ?: 0
                if (count < 2) {
                    used[physical] = count + 1
                    assign(index + 1)
                    used[physical] = count
                }
            }
        }
        assign(0)
        return answers.toList()
    }

    @Test fun logicalWildcardFacesCannotExcludeAnActualPhysicalRun() {
        val random=Random(142026)
        repeat(180) { case ->
            val wild=if(case%2==0) setOf(Rank.SEVEN) else setOf(Rank.SEVEN,Rank.J)
            val suit=physicalSuits[case%4]
            val run=listOf(Rank.TWO,Rank.THREE,Rank.FOUR,Rank.FIVE,Rank.SIX).map { card(it,suit) }
            val remaining=(run+deck.shuffled(random).take(3)).groupingBy { it }.eachCount()
                .flatMap { (c,n) -> List(minOf(n,2)) { c } }
            val physicalConsumed=consumedLeaving(remaining)
            val renderedConsumed=physicalConsumed.toMutableList()
            // A played physical wildcard is rendered as one of the cards that
            // actually remains. This deliberately depletes a natural run slot.
            val replacements=remaining.filter { !it.rank.isJoker }.shuffled(random).take(2)
            for(target in replacements) {
                val actual=renderedConsumed.firstOrNull { it.rank in wild && it.suit==Suit.HEART } ?: break
                renderedConsumed.remove(actual); renderedConsumed.add(target)
            }
            assertTrue(oracleCanBuild(remaining,wild))
            assertNotEquals(StraightFlushState.IMPOSSIBLE,
                StraightFlushEvaluator.evaluate(renderedConsumed,wild,true).state,"case=$case")
            assertNotEquals(StraightFlushState.IMPOSSIBLE,
                StraightFlushEvaluator.evaluate(renderedConsumed,null,false).state,"unknown rule case=$case")
            assertNotEquals(StraightFlushState.IMPOSSIBLE,
                StraightFlushEvaluator.evaluate(renderedConsumed,emptySet(),true,2).state,"natural-only case=$case")
        }
    }

    @Test fun restorationAllowanceIsUncertaintyNotWildcardConstruction() {
        val cards=listOf(Rank.A,Rank.TWO,Rank.THREE,Rank.FOUR).map { card(it) }+card(Rank.K,Suit.CLUB)
        val consumed=consumedLeaving(cards)
        assertEquals(StraightFlushState.IMPOSSIBLE,StraightFlushEvaluator.evaluate(consumed,emptySet(),true).state)
        assertEquals(StraightFlushState.UNKNOWN,StraightFlushEvaluator.evaluate(consumed,emptySet(),true,1).state)
        assertEquals(StraightFlushState.IMPOSSIBLE,
            StraightFlushEvaluator.evaluate(consumedLeaving(cards.take(4)),emptySet(),true,4).state)
    }

    @Test fun fiveRunCardsSpreadAcrossOnlyFourCardHandsCannotKeepTheIconLit() {
        val cards=listOf(Rank.A,Rank.TWO,Rank.THREE,Rank.FOUR,Rank.FIVE).map { card(it) }+
            listOf(card(Rank.K,Suit.CLUB),card(Rank.Q,Suit.DIAMOND),card(Rank.J,Suit.HEART))
        val consumed=consumedLeaving(cards)
        val split=StraightFlushEvaluator.evaluate(consumed,emptySet(),true,0,listOf(4,4,0))
        assertEquals(StraightFlushState.IMPOSSIBLE,split.state)
        assertEquals("insufficient_seat_hand",split.reason)
        // Capacity is a necessary constraint, not a claim of the actual deal.
        assertEquals(StraightFlushState.POSSIBLE,
            StraightFlushEvaluator.evaluate(consumed,emptySet(),true,0,listOf(5,3,0)).state)
    }

    @Test fun twelveTotalCardsWithNoPlayerHoldingFiveAreExcludedEvenWithUnknownWildcards() {
        val cards=deck.takeLast(12)
        val result=StraightFlushEvaluator.evaluate(consumedLeaving(cards),null,false,4,listOf(4,4,4))
        assertEquals(StraightFlushState.IMPOSSIBLE,result.state)
        assertEquals("insufficient_seat_hand",result.reason)
        // Trusted ledger cardinalities remain decisive if suit annotations conflict.
        assertEquals(StraightFlushState.IMPOSSIBLE,
            StraightFlushEvaluator.evaluate(List(96) { card(Rank.A) },null,false,4,listOf(4,4,4)).state)
    }

    @Test fun insufficientOrdinaryCardsCannotBeRescuedByJokersOrSubstitutionAllowance() {
        val cards=listOf(Rank.A,Rank.TWO,Rank.THREE,Rank.FOUR).map { card(it) }+
            listOf(Card(Rank.BIG,Suit.NONE),Card(Rank.SMALL,Suit.NONE))
        assertEquals(StraightFlushState.IMPOSSIBLE,
            StraightFlushEvaluator.evaluate(consumedLeaving(cards),emptySet(),true,4,listOf(5,1,0)).state)
    }

    @Test fun malformedSeatCountsDoNotTurnUncertaintyIntoAnAbsenceClaim() {
        val cards=listOf(Rank.A,Rank.TWO,Rank.THREE,Rank.FOUR,Rank.FIVE).map { card(it) }
        for(counts in listOf(listOf(4,0,0),listOf(4,1),listOf(-1,3,3),listOf(28,0,0))) {
            val result=StraightFlushEvaluator.evaluate(consumedLeaving(cards),emptySet(),true,0,counts)
            assertEquals(StraightFlushState.UNKNOWN,result.state)
            assertEquals("invalid_hand_capacity",result.reason)
        }
    }

    @Test fun unresolvedRenderedCardsAreBoundedByPhysicalWildcardsRatherThanEveryPlay() {
        assertEquals(2,tencentUnresolvedReplacementBound(70,WildcardMode.SINGLE))
        assertEquals(4,tencentUnresolvedReplacementBound(70,WildcardMode.DOUBLE))
        assertEquals(4,tencentUnresolvedReplacementBound(70,WildcardMode.UNKNOWN))
        assertEquals(1,tencentUnresolvedReplacementBound(1,WildcardMode.UNKNOWN))
        assertEquals(0,tencentUnresolvedReplacementBound(0,WildcardMode.SINGLE))
        assertFailsWith<IllegalArgumentException> { tencentUnresolvedReplacementBound(-1,WildcardMode.UNKNOWN) }
    }

    @Test fun everySmallExactPoolWithHandCapacityMatchesTheIndependentSlotOracle() {
        val candidates=listOf(Rank.A,Rank.TWO,Rank.THREE,Rank.FOUR,Rank.FIVE).map { card(it) }+
            listOf(card(Rank.SEVEN,Suit.HEART),card(Rank.K,Suit.DIAMOND))
        for(mask in 0 until (1 shl candidates.size)) {
            val remaining=candidates.filterIndexed { i,_ -> mask and (1 shl i)!=0 }
            for(a in 0..remaining.size) for(b in 0..remaining.size-a) {
                val counts=listOf(a,b,remaining.size-a-b)
                val expected=if(counts.maxOrNull()!!>=5 && oracleCanBuild(remaining,setOf(Rank.SEVEN)))
                    StraightFlushState.POSSIBLE else StraightFlushState.IMPOSSIBLE
                assertEquals(expected,StraightFlushEvaluator.evaluate(consumedLeaving(remaining),setOf(Rank.SEVEN),true,0,counts).state,
                    "pool=$remaining hands=$counts")
            }
        }
    }
}
