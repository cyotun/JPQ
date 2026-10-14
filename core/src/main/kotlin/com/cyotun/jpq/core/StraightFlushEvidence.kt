package com.cyotun.jpq.core

import kotlin.math.abs

data class SuitedGroup(val cards: List<Card>, val boxes: List<GlyphBox>,val physicalWildcards: List<Boolean?> = emptyList())
data class WildcardRuleEvidence(val ranks: Set<Rank>?, val confirmed: Boolean,
    val unresolvedReplacements: Int = 0)

/** Bounded, independent annotations. The rank/color ledger is never modified. */
class StraightFlushEvidenceTracker {
    private class Votes {
        private var key: String? = null
        private var presentation = emptyList<Triple<Card, GlyphBox,Boolean?>>()
        private var count = 0
        private var first = 0L
        private var last = -1L

        fun accept(eventKey: String, group: SuitedGroup, time: Long): Boolean {
            if (!valid(group) || time < 0) { clear(); return false }
            if (time <= last) return false
            // A frame's card and box remain paired, even if its input ordering changes.
            val fresh = group.cards.indices.map { Triple(group.cards[it],group.boxes[it],group.physicalWildcards.getOrNull(it)) }
                .sortedWith(compareBy({ it.second.cx }, { it.second.cy }))
            val same = key == eventKey && presentation.size == fresh.size &&
                presentation.zip(fresh).all { (previous, current) ->
                    val (aCard, a,aWild) = previous; val (bCard, b,bWild) = current
                    aCard == bCard && aWild==bWild && abs(a.cx - b.cx) <= minOf(a.width, b.width) * .35f &&
                        abs(a.cy - b.cy) <= minOf(a.height, b.height) * .35f &&
                        maxOf(a.width, b.width) <= minOf(a.width, b.width) * 1.4f &&
                        maxOf(a.height, b.height) <= minOf(a.height, b.height) * 1.4f
                }
            if (!same || last < 0 || time - last > 1000) { count = 1; first = time } else count++
            key = eventKey; presentation = fresh; last = time
            return count >= 3 && time - first >= 500
        }

        fun clear() { key = null; presentation = emptyList(); count = 0; first = 0; last = -1 }

        private fun valid(group: SuitedGroup) = group.cards.isNotEmpty() && group.cards.size <= 40 &&
            group.cards.size == group.boxes.size && group.boxes.all {
                it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite() &&
                    it.width > 0 && it.height > 0
            }
    }

    private var roundId: String? = null
    private var initial = emptyList<Card>()
    private var currentEvents = emptyList<PlayEvent>()
    private var initialConflict = false
    private val annotations = mutableMapOf<String, List<Card>>()
    private val physicalFlags=mutableMapOf<String,List<Boolean?>>()
    private val physicalConflicts=mutableSetOf<String>()
    private val conflicts = mutableSetOf<String>()
    private val votes = Seat.entries.associateWith { Votes() }
    var consumed = emptyList<Card>(); private set
    /** Separate, copied annotations; they never replace the main ledger's rank/color records. */
    val initialCards: List<Card> get() = initial.toList()
    val eventCards: Map<String, List<Card>> get() = currentEvents.associate { event ->
        event.eventId to (annotations[event.eventId]?.takeIf { colors(it) == colors(event.cards) }
            ?: neutral(event.cards)).toList()
    }
    val physicalEventCards: Map<String,List<PhysicalPlayedCard>> get()=eventCards.mapValues { (id,cards) ->
        cards.mapIndexed { index,card -> PhysicalPlayedCard(card,physicalFlags[id]?.getOrNull(index)) }
    }

    fun reset() {
        roundId = null; initial = emptyList(); currentEvents = emptyList()
        annotations.clear(); physicalFlags.clear(); physicalConflicts.clear(); conflicts.clear(); initialConflict = false
        votes.values.forEach { it.clear() }; consumed = emptyList()
    }
    /** Visibility gaps interrupt candidates, not accepted evidence from the same round. */
    fun clearCandidateVotes() { votes.values.forEach { it.clear() } }

    private fun colors(cards: List<Card>) = cards.map { it.colorKey() }.sorted()
    private fun neutral(cards: List<Card>) = cards.map { card ->
        Card(card.rank, when (card.suit) {
            Suit.HEART, Suit.DIAMOND, Suit.RED -> Suit.RED
            Suit.SPADE, Suit.CLUB, Suit.BLACK -> Suit.BLACK
            Suit.NONE -> Suit.NONE
        })
    }

    /** Every earlier physical-suit claim must still fit the presentation's unknown color slots. */
    private fun compatible(old: List<Card>, fresh: List<Card>): Boolean =
        old.groupBy { it.colorKey() }.all { (color, previous) ->
            val next = fresh.filter { it.colorKey() == color }
            val unknown = next.count { it.suit == Suit.RED || it.suit == Suit.BLACK }
            val required = previous.filter { it.suit != Suit.RED && it.suit != Suit.BLACK }
                .groupingBy { it.suit }.eachCount()
            required.entries.sumOf { (suit, n) -> maxOf(0, n - next.count { it.suit == suit }) } <= unknown
        }

    /** Stable fresh evidence may add claims, but an abstention never erases earlier exact suits. */
    private fun merge(old: List<Card>, fresh: List<Card>): List<Card> {
        val result = fresh.toMutableList()
        for ((card, count) in old.filter { it.suit != Suit.RED && it.suit != Suit.BLACK }
            .groupingBy { it }.eachCount()) {
            repeat(maxOf(0, count - result.count { it == card })) {
                val index = result.indexOfFirst {
                    it.colorKey() == card.colorKey() && (it.suit == Suit.RED || it.suit == Suit.BLACK)
                }
                check(index >= 0) { "Compatible suit annotation must have a matching unknown slot" }
                result[index] = card
            }
        }
        return result.toList()
    }

    fun update(time: Long, snapshot: RoundSnapshot, initialColors: List<Card>, own: SuitedGroup?,
        groups: Map<Seat, SuitedGroup>, rule: WildcardRuleEvidence): StraightFlushResult {
        if (snapshot.phase !in setOf(RoundPhase.ACTIVE, RoundPhase.SELF_FINISHED) || snapshot.roundId == null) {
            reset(); return StraightFlushResult(StraightFlushState.UNKNOWN, reason = "round_not_active")
        }
        if (snapshot.roundId != roundId) {
            reset(); roundId = snapshot.roundId; initial = neutral(initialColors)
        }
        if (initial.size != 27 || colors(initial) != colors(initialColors)) {
            votes.values.forEach { it.clear() }
            return StraightFlushResult(StraightFlushState.UNKNOWN, reason = "initial_hand_missing")
        }
        // A fast first play may precede three suit frames. Stabilize the remaining
        // physical hand and reconstruct only its immutable initial rank/color pool:
        // SELF events come from qualified hand differences and supply unknown suits,
        // never extra consumed cards. Teammate reveals cannot enter this path.
        val ownVotes = votes.getValue(Seat.SELF)
        val selfEvents = snapshot.plays[Seat.SELF].orEmpty().sortedWith(compareBy({ it.sequence }, { it.eventId }))
        val selfColors = neutral(selfEvents.flatMap { it.cards })
        val reconstructed = own?.let { it.cards + selfColors }
        if (!initialConflict && snapshot.phase == RoundPhase.ACTIVE && own != null &&
            reconstructed?.size == 27 && colors(reconstructed) == colors(initial)) {
            // Any change in committed SELF provenance starts a fresh vote even if
            // the visible hand happens to have identical points and geometry.
            val ownKey = "initial:" + selfEvents.joinToString("|") { event ->
                "${event.eventId}:${event.cards.size}:${colors(event.cards).joinToString(",")}"
            }
            if (ownVotes.accept(ownKey, own, time)) {
                if (!compatible(initial, reconstructed)) { initialConflict = true; initial = neutral(initial) }
                else initial = merge(initial, reconstructed)
            }
        } else ownVotes.clear()

        val others = snapshot.plays.values.flatten().filter { it.seat != Seat.SELF }
        currentEvents = others.map { it.copy(cards = it.cards.toList()) }
        val eventIds = others.map { it.eventId }.toSet()
        annotations.keys.retainAll(eventIds); physicalFlags.keys.retainAll(eventIds)
        physicalConflicts.retainAll(eventIds); conflicts.retainAll(eventIds)
        for (seat in listOf(Seat.PREVIOUS, Seat.OPPOSITE, Seat.NEXT)) {
            val seatVotes = votes.getValue(seat)
            // Preserve the card/physical-marker pairing when a detector returns
            // the same boxes in a different list order.
            val group = groups[seat]?.let { raw ->
                if(raw.cards.size!=raw.boxes.size) raw else {
                    val order=raw.cards.indices.sortedWith(compareBy({raw.boxes[it].cx},{raw.boxes[it].cy}))
                    SuitedGroup(order.map { raw.cards[it] },order.map { raw.boxes[it] },
                        order.map { raw.physicalWildcards.getOrNull(it) })
                }
            }
            val event = snapshot.plays[seat].orEmpty().lastOrNull()
            if (group == null || event == null || group.cards.isEmpty() ||
                colors(group.cards) != colors(event.cards) || event.eventId in conflicts) {
                seatVotes.clear(); continue
            }
            if (seatVotes.accept(event.eventId, group, time)) {
                val old = annotations[event.eventId]
                // A current presentation can expand, but cannot silently replace known physical suits.
                if (old != null && !compatible(old, group.cards)) {
                    conflicts.add(event.eventId); annotations[event.eventId] = neutral(event.cards); physicalFlags.remove(event.eventId)
                } else {
                    val merged=if (old == null) group.cards.toList() else merge(old, group.cards)
                    val previous=physicalFlags[event.eventId]
                    val fresh=group.cards.indices.map { group.physicalWildcards.getOrNull(it) }
                    // A stable abstention may retain proof only when the exact
                    // card ordering still matches. Conflicting positive/negative
                    // claims withdraw physical identity for this event.
                    if(old==merged && previous!=null && previous.zip(fresh).any { (a,b) -> a!=null && b!=null && a!=b })
                        physicalConflicts.add(event.eventId)
                    physicalFlags[event.eventId]=when {
                        event.eventId in physicalConflicts -> List(merged.size) { null }
                        old==merged && merged==group.cards && previous!=null -> fresh.mapIndexed { i,value -> value ?: previous.getOrNull(i) }
                        merged==group.cards -> fresh
                        else -> List(merged.size) { null }
                    }
                    annotations[event.eventId] = merged
                }
            }
        }
        consumed = initial + others.flatMap { event -> annotations[event.eventId]
            ?.takeIf { colors(it) == colors(event.cards) } ?: neutral(event.cards) }
        return StraightFlushEvaluator.evaluate(consumed, rule.ranks, rule.confirmed,rule.unresolvedReplacements,
            straightFlushRemainingHandSizes(snapshot))
    }
}

enum class WildcardMode { UNKNOWN, SINGLE, DOUBLE }

/** Corner/mode votes are local to one round; coloured HUD evidence is handled by RoundGradeTracker. */
class WildcardRuleTracker {
    private data class RankVote(var count: Int, var first: Long, var last: Long)
    private var mode = WildcardMode.UNKNOWN
    private var modeCandidate = WildcardMode.UNKNOWN
    private var modeVotes = 0
    private var lastOcr = -1L
    private var lastFrame = -1L
    private val rankVotes = mutableMapOf<Rank, RankVote>()
    private val ranks = mutableSetOf<Rank>()
    val observedRanks: Set<Rank> get() = ranks.toSet()

    fun reset() {
        mode = WildcardMode.UNKNOWN; modeCandidate = mode; modeVotes = 0
        lastOcr = -1L; lastFrame = -1L; rankVotes.clear(); ranks.clear()
    }
    fun clearCandidateVotes() {
        rankVotes.clear(); modeCandidate=mode
        modeVotes=if(mode==WildcardMode.UNKNOWN) 0 else 3
    }

    private fun current(): WildcardRuleEvidence {
        val expected = when (mode) { WildcardMode.SINGLE -> 1; WildcardMode.DOUBLE -> 2; else -> 0 }
        return if (expected > 0 && ranks.size == expected) WildcardRuleEvidence(ranks.toSet(), true)
        else WildcardRuleEvidence(null, false)
    }

    fun observe(time: Long, ocrTime: Long?, observedMode: WildcardMode,
        goldHeartRanks: Set<Rank>): WildcardRuleEvidence {
        if (time < 0 || time <= lastFrame) return current()
        lastFrame = time
        // Reusing one OCR result is not a new vote. Stale/future OCR cannot establish a mode.
        if (ocrTime != null && ocrTime > lastOcr && ocrTime <= time && time - ocrTime <= 2000) {
            val changed = observedMode != modeCandidate
            if (changed || ocrTime - lastOcr > 2000) {
                // A distinct contradictory reading immediately withdraws the previous mode.
                if (observedMode != mode && mode != WildcardMode.UNKNOWN) {
                    ranks.clear(); rankVotes.clear()
                }
                mode = WildcardMode.UNKNOWN; modeCandidate = observedMode
                modeVotes = if (observedMode == WildcardMode.UNKNOWN) 0 else 1
            } else if (observedMode != WildcardMode.UNKNOWN) modeVotes++
            lastOcr = ocrTime
            if (modeVotes >= 3 && observedMode != WildcardMode.UNKNOWN) mode = observedMode
        }
        val freshRanks = goldHeartRanks.filterNot { it.isJoker }.toSet()
        // Missing or disjoint frames interrupt unconfirmed candidates, not already proven round rules.
        rankVotes.keys.retainAll(freshRanks)
        for (rank in freshRanks) {
            if (rank in ranks) continue
            val old = rankVotes[rank]
            if (old == null || time - old.last > 1000) rankVotes[rank] = RankVote(1, time, time)
            else {
                old.count++; old.last = time
                if (old.count >= 3 && time - old.first >= 500) {
                    ranks.add(rank); rankVotes.remove(rank)
                }
            }
        }
        return current()
    }
}
