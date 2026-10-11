package com.cyotun.jpq.core

/** Accepts confirmed observations only. No UI editing or guessed recovery API. */
class RoundLedger {
    private var id: String? = null
    private var phase = RoundPhase.WAITING
    private var reason: String? = null
    private var initial = emptyList<Card>()
    private var selfHand = emptyList<Card>()
    private val events = linkedMapOf<String, PlayEvent>()
    private var lastSequence = -1L

    @Synchronized fun begin(roundId: String, cardsAfterTribute: List<Card>): RoundSnapshot {
        require(roundId.isNotBlank())
        require(cardsAfterTribute.size == 27) { "Complete 27-card baseline required" }
        checkCapacity(cardsAfterTribute)
        if (id == roundId) {
            require(initial.groupingBy { it }.eachCount() == cardsAfterTribute.groupingBy { it }.eachCount()) { "Initial hand is immutable within a round" }
            return snapshot()
        }
        check(phase != RoundPhase.ACTIVE && phase != RoundPhase.SELF_FINISHED) { "Previous round must end before a new baseline" }
        id = roundId; phase = RoundPhase.ACTIVE; reason = null
        initial = cardsAfterTribute.toList(); selfHand = initial
        events.clear(); lastSequence = -1
        return snapshot()
    }

    @Synchronized fun commit(event: PlayEvent): RoundSnapshot {
        require(event.roundId == id) { "Wrong round" }
        val previous = events[event.eventId]
        if (previous != null) {
            require(previous == event) { "Conflicting duplicate event" }
            return snapshot()
        }
        check(phase == RoundPhase.ACTIVE || phase == RoundPhase.SELF_FINISHED) { "Round has no valid baseline" }
        require(event.eventId.isNotBlank() && event.cards.isNotEmpty())
        require(event.sequence > lastSequence) { "New event boundary required" }
        if (event.seat == Seat.SELF) {
            val next = selfHand.toMutableList()
            event.cards.forEach { require(next.remove(it)) { "Self play not in current hand" } }
            selfHand = next
        } else {
            require(events.values.filter { it.seat == event.seat }.sumOf { it.cards.size } + event.cards.size <= 27)
            checkCapacity(initial + events.values.filter { it.seat != Seat.SELF }.flatMap { it.cards } + event.cards)
        }
        events[event.eventId] = event.copy(cards = event.cards.toList())
        lastSequence = event.sequence
        if (selfHand.isEmpty()) phase = RoundPhase.SELF_FINISHED
        return snapshot()
    }

    @Synchronized fun invalidate(message: String): RoundSnapshot {
        phase = RoundPhase.UNAVAILABLE; reason = message
        return snapshot()
    }
    @Synchronized fun finish(): RoundSnapshot { phase = RoundPhase.FINISHED; return snapshot() }
    @Synchronized fun waitForNext(): RoundSnapshot {
        id = null; initial = emptyList(); selfHand = emptyList(); events.clear()
        phase = RoundPhase.WAITING; reason = null; lastSequence = -1
        return snapshot()
    }
    @Synchronized fun snapshot(): RoundSnapshot {
        val plays = Seat.entries.associateWith { seat -> events.values.filter { it.seat == seat }.toList() }
        val known = phase in setOf(RoundPhase.ACTIVE, RoundPhase.SELF_FINISHED, RoundPhase.FINISHED)
        val consumed = initial + events.values.filter { it.seat != Seat.SELF }.flatMap { it.cards }
        val byRank = consumed.groupingBy { it.rank }.eachCount()
        return RoundSnapshot(id, phase,
            if (known) Rank.entries.associateWith { it.capacity - (byRank[it] ?: 0) } else null,
            plays, reason)
    }
    private fun checkCapacity(cards: List<Card>) {
        require(cards.groupingBy { it }.eachCount().values.all { it <= 2 }) { "Physical card capacity exceeded" }
        require(cards.groupingBy { it.rank }.eachCount().all { (rank, count) -> count <= rank.capacity })
    }
}
