package com.cyotun.jpq.core

/** Possibility under each remaining player's capacity, not proof of their actual cards. */
enum class StraightFlushState { UNKNOWN, POSSIBLE, IMPOSSIBLE }

data class StraightFlushWitness(
    val suit: Suit,
    val ranks: List<Rank>,
    val wildcardsNeeded: Int,
)

data class StraightFlushResult(
    val state: StraightFlushState,
    val witness: StraightFlushWitness? = null,
    val reason: String? = null,
    /** Logical slots in every feasible window, including slots filled by a heart wildcard. */
    val candidateRanks: Set<Rank> = if (state == StraightFlushState.IMPOSSIBLE) emptySet()
        else Rank.entries.filterNot { it.isJoker }.toSet(),
)

/** Accepted ledger counts are independent of uncertain suit/substitution metadata. */
fun straightFlushRemainingHandSizes(snapshot: RoundSnapshot,teammate: RemainingPoolExclusion?=null): List<Int>? {
    if(snapshot.roundId==null || snapshot.phase !in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED,RoundPhase.FINISHED)) return null
    val counts=listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).map { 27-snapshot.played(it) }
    if(counts.any { it !in 0..27 }) return null
    if(snapshot.remaining!=null && snapshot.remaining.values.sum()!=counts.sum()) return null
    // Preserve seat positions; a revealed teammate contributes no unknown cards.
    return if(teammate?.matches(snapshot)==true) listOf(counts[0],0,counts[2]) else counts
}

fun straightFlushHandCapacityExclusion(snapshot: RoundSnapshot,teammate: RemainingPoolExclusion?=null): StraightFlushResult? {
    val hands=straightFlushRemainingHandSizes(snapshot,teammate) ?: return null
    if(hands.maxOrNull()!!<5) return StraightFlushResult(StraightFlushState.IMPOSSIBLE,reason="insufficient_seat_hand")
    val ranks=remainingOutsidePool(snapshot,teammate)
    if(ranks!=null && ranks.keys==Rank.entries.toSet() && ranks.all { (rank,n) -> n in 0..rank.capacity } &&
        ranks.filterKeys { !it.isJoker }.values.sum()<5)
        return StraightFlushResult(StraightFlushState.IMPOSSIBLE,reason="insufficient_ordinary_cards")
    return null
}

/** A deal has at most two physical heart wildcards per effective rank. */
fun tencentUnresolvedReplacementBound(playedOrdinary: Int,mode: WildcardMode): Int {
    require(playedOrdinary>=0)
    return minOf(playedOrdinary,if(mode==WildcardMode.SINGLE) 2 else 4)
}

/**
 * Side-channel analysis only: consumed must be the immutable initial own hand plus confirmed plays
 * from the other three seats, plus any confirmed current teammate hand excluded from the
 * unknown pool. Own plays must not be consumed a second time.
 *
 * RED and BLACK preserve uncertainty about the physical suit. POSSIBLE requires a constructive
 * proof using remaining lower bounds. IMPOSSIBLE requires ruling out every compatible physical
 * allocation, including HEART cards of all confirmed wildcard ranks. Otherwise the result is
 * UNKNOWN. Until the effective rule is confirmed, every ordinary HEART rank is
 * treated as a potential wildcard: this is an ability superset, not a guessed
 * level. Absence must also hold under that superset.
 * candidateRanks unions the logical slots of every window not ruled out by those same
 * constraints; one constructive witness does not exclude other uncertain windows.
 *
 * TABLE cards may show a wildcard's declared replacement. In that case the
 * displayed residual pool loses the replacement but retains an extra heart
 * wildcard. The retained wildcard can occupy the lost card's slot, so this
 * pool is conservative for absence when the wildcard set is complete. It is
 * not a reconstruction of the precise physical cards each player holds.
 */
object StraightFlushEvaluator {
    private val suits = listOf(Suit.SPADE, Suit.HEART, Suit.CLUB, Suit.DIAMOND)
    private val ascending = listOf(Rank.A, Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE,
        Rank.SIX, Rank.SEVEN, Rank.EIGHT, Rank.NINE, Rank.TEN, Rank.J, Rank.Q, Rank.K, Rank.A)
    // A is low or high. K-A-2 and any other wrapping sequence are absent.
    private val windows = (0..9).map { ascending.subList(it, it + 5).toList() }
    private val ordinaryRanks = Rank.entries.filterNot { it.isJoker }

    fun evaluate(
        consumed: List<Card>,
        wildcardRanks: Set<Rank>?,
        ruleConfirmed: Boolean,
        unresolvedReplacements: Int = 0,
        remainingHandSizes: List<Int>? = null,
    ): StraightFlushResult {
        if(remainingHandSizes!=null) {
            if(remainingHandSizes.size!=3 || remainingHandSizes.any { it !in 0..27 } ||
                remainingHandSizes.sum()!=108-consumed.size)
                return StraightFlushResult(StraightFlushState.UNKNOWN,reason="invalid_hand_capacity")
            // This count proof does not require a grade, suit or logical-wildcard
            // face. Three separate four-card hands cannot supply one five-card run.
            if(remainingHandSizes.maxOrNull()!!<5)
                return StraightFlushResult(StraightFlushState.IMPOSSIBLE,reason="insufficient_seat_hand")
        }
        if(unresolvedReplacements !in 0..104) return StraightFlushResult(StraightFlushState.UNKNOWN,reason="invalid_replacement_bound")
        if(wildcardRanks?.any { it.isJoker }==true) return StraightFlushResult(StraightFlushState.UNKNOWN,reason="unsupported_wildcard_rank")
        val counts = consumed.groupingBy { it }.eachCount()
        val byRank = consumed.groupingBy { it.rank }.eachCount()
        val byColor = consumed.groupingBy { it.colorKey() }.eachCount()
        if (counts.any { (card, count) ->
                card.suit !in setOf(Suit.RED, Suit.BLACK) && count > 2
            } || byRank.any { (rank, count) -> count > rank.capacity } ||
            byColor.any { (color, count) -> count > if (color.contains(':')) 4 else 2 }) {
            return StraightFlushResult(StraightFlushState.UNKNOWN, reason = "invalid_card_capacity")
        }

        val allocations = ordinaryRanks.associateWith { rank -> remainingAllocations(rank, counts) }
        if (allocations.values.any { it.isEmpty() }) {
            return StraightFlushResult(StraightFlushState.UNKNOWN, reason = "invalid_card_capacity")
        }
        // Display substitutions preserve card count, even if their physical rank
        // is unresolved. Fewer than five ordinary cards can never make a run.
        if(104-consumed.count { !it.rank.isJoker }<5) return StraightFlushResult(StraightFlushState.IMPOSSIBLE)
        val lower = allocations.mapValues { (_, choices) ->
            IntArray(suits.size) { suitIndex -> choices.minOf { it[suitIndex] } }
        }

        var witness: StraightFlushWitness? = null
        for (suitIndex in suits.indices) for (window in windows) {
            if (window.all { lower.getValue(it)[suitIndex] > 0 }) {
                if (witness == null) witness = StraightFlushWitness(suits[suitIndex], window, 0)
            }
        }
        val completeRule = ruleConfirmed && wildcardRanks != null
        val possibleWildRanks = if (completeRule) wildcardRanks!! else ordinaryRanks.toSet()

        val heartIndex = suits.indexOf(Suit.HEART)
        val guaranteedWildcards = possibleWildRanks.sumOf { lower.getValue(it)[heartIndex] }
        for (suitIndex in suits.indices) for (window in windows) {
            val missing = window.count { lower.getValue(it)[suitIndex] == 0 }
            val reservedNaturalWildcards = if (suits[suitIndex] == Suit.HEART) {
                window.count { it in possibleWildRanks && lower.getValue(it)[heartIndex] > 0 }
            } else 0
            if (witness == null && completeRule && missing <= guaranteedWildcards - reservedNaturalWildcards) {
                witness = StraightFlushWitness(suits[suitIndex], window, missing)
            }
        }

        val candidateRanks = linkedSetOf<Rank>()
        val outside = ordinaryRanks.associateWith { rank ->
            allocations.getValue(rank).maxOf { remaining ->
                if (rank in possibleWildRanks) remaining[heartIndex] else 0
            }
        }
        val outsideBalance = outside.values.sum()
        for (suitIndex in suits.indices) {
            // Each rank's RED/BLACK allocation is independent of other ranks. For a chosen
            // allocation, natural cards cover a slot first. HEART wildcards used naturally
            // are reserved, and remaining HEART wildcards must cover the missing slots.
            // Maximizing this resource balance independently per rank is an exact existence
            // check, while retaining HEART/DIAMOND correlation within each rank.
            // Cache both contributions once per rank/suit. A window only changes whether
            // a rank is a required slot; it does not change its compatible allocations.
            val insideDelta = ordinaryRanks.associateWith { rank ->
                allocations.getValue(rank).maxOf { remaining ->
                    val natural = remaining[suitIndex] > 0
                    val wild = if (rank in possibleWildRanks) remaining[heartIndex] else 0
                    val missing = if (!natural) 1 else 0
                    val reserved = if (natural && suits[suitIndex] == Suit.HEART &&
                        rank in possibleWildRanks) 1 else 0
                    wild - missing - reserved
                } - outside.getValue(rank)
            }
            for (window in windows) {
                val bestBalance = outsideBalance + window.sumOf { insideDelta.getValue(it) }
                if (bestBalance + unresolvedReplacements >= 0) candidateRanks.addAll(window)
            }
        }
        if (witness != null) return StraightFlushResult(StraightFlushState.POSSIBLE,
            witness = witness, candidateRanks = candidateRanks)
        if (candidateRanks.isNotEmpty()) return StraightFlushResult(StraightFlushState.UNKNOWN,
            reason = if(unresolvedReplacements>0) "rendered_substitution_uncertain"
            else if (completeRule) "suit_uncertain" else "wildcard_rule_unknown",
            candidateRanks = candidateRanks)
        return StraightFlushResult(StraightFlushState.IMPOSSIBLE)
    }

    /** Enumerates compatible remaining counts in SPADE, HEART, CLUB, DIAMOND order. */
    private fun remainingAllocations(rank: Rank, consumed: Map<Card, Int>): List<IntArray> {
        fun count(suit: Suit) = consumed[Card(rank, suit)] ?: 0
        val available = IntArray(suits.size) { 2 - count(suits[it]) }
        val red = count(Suit.RED)
        val black = count(Suit.BLACK)
        val result = mutableListOf<IntArray>()
        for (heartConsumed in 0..red) for (spadeConsumed in 0..black) {
            val diamondConsumed = red - heartConsumed
            val clubConsumed = black - spadeConsumed
            if (heartConsumed <= available[1] && diamondConsumed <= available[3] &&
                spadeConsumed <= available[0] && clubConsumed <= available[2]) {
                result += intArrayOf(available[0] - spadeConsumed, available[1] - heartConsumed,
                    available[2] - clubConsumed, available[3] - diamondConsumed)
            }
        }
        return result
    }
}
