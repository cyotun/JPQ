package com.cyotun.jpq.core

/** Accepts confirmed observations only. No UI editing or guessed recovery API. */
class RoundLedger {
    private var id: String? = null
    private var phase = RoundPhase.WAITING
    private var reason: String? = null
    private var recoveryPhase: RoundPhase? = null
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
        check(phase !in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED,RoundPhase.RECOVERING)) { "Previous round must end before a new baseline" }
        id = roundId; phase = RoundPhase.ACTIVE; reason = null; recoveryPhase=null
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

    /** Commit a directly proved final own play after the completion cue cleared selfHand. */
    @Synchronized internal fun commitLateOwnExit(proof: OwnExitPlayProof.Certificate,event: PlayEvent): RoundSnapshot {
        check(phase==RoundPhase.SELF_FINISHED && reason==null && recoveryPhase==null) {
            "Late own-exit commit requires a valid self-finished round"
        }
        val roundId=id ?: error("No active round")
        require(proof.roundId==roundId && event.roundId==roundId) { "Wrong round" }
        require(event.seat==Seat.SELF) { "Late own-exit commit must be SELF" }
        require(event.eventId.isNotBlank() && event.eventId=="$roundId:${event.sequence}") { "Invalid event identity" }
        require(event.eventId !in events) { "Event already committed" }
        require(event.sequence>lastSequence) { "New event boundary required" }
        require(selfHand.isEmpty()) { "SELF hand was not cleared by completion" }
        require(proof.originalHand.isNotEmpty() && proof.cards.isNotEmpty())
        require(proof.ownEvents==events.values.filter { it.seat==Seat.SELF }) { "SELF event version changed" }

        val remaining=initial.toMutableList()
        for(ownEvent in events.values.filter { it.seat==Seat.SELF }) {
            require(ownEvent.cards.all { remaining.remove(it) }) { "SELF event history exceeds the initial hand" }
        }
        require(sameCards(remaining,proof.originalHand)) { "Proved hand does not match the confirmed history" }
        require(sameCards(proof.cards,proof.originalHand) && sameCards(event.cards,proof.originalHand)) {
            "Late event must exactly match the proved final hand"
        }

        // All proof/version/sequence checks happen before this temporary restore.
        // Keep a rollback snapshot so even an unexpected commit failure is atomic.
        val oldHand=selfHand
        val oldPhase=phase
        val oldEvents=events.toMap()
        val oldSequence=lastSequence
        return try {
            selfHand=proof.originalHand.toList()
            commit(event)
        } catch(failure: Throwable) {
            selfHand=oldHand
            phase=oldPhase
            events.clear(); events.putAll(oldEvents)
            lastSequence=oldSequence
            throw failure
        }
    }

    private fun sameCards(left: List<Card>,right: List<Card>): Boolean =
        left.size==right.size && left.groupingBy { it }.eachCount()==right.groupingBy { it }.eachCount()

    @Synchronized fun invalidate(message: String): RoundSnapshot {
        phase = RoundPhase.UNAVAILABLE; reason = message; recoveryPhase=null
        return snapshot()
    }
    /** Retain the immutable checkpoint, but never expose its counts as current. */
    @Synchronized fun suspendForRecovery(message: String): RoundSnapshot {
        if(phase in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED)) {
            recoveryPhase=phase; phase=RoundPhase.RECOVERING; reason=message
        }
        return snapshot()
    }
    @Synchronized fun phaseBeforeRecovery(): RoundPhase = recoveryPhase ?: phase

    /** A reconciled display and any independently proved new play are one transaction. */
    @Synchronized internal fun resumeAfterRecovery(expansions: Map<String,List<Card>>,
        additional: List<PlayEvent> = emptyList()): RoundSnapshot {
        check((phase==RoundPhase.RECOVERING && recoveryPhase!=null) ||
            phase in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED))
        val replacements=events.toMutableMap()
        for((eventId,cards) in expansions) {
            val old=events[eventId] ?: error("Unknown presentation")
            require(old.seat!=Seat.SELF && events.values.lastOrNull { it.seat==old.seat }?.eventId==eventId)
            val rest=cards.toMutableList()
            require(old.cards.all { rest.remove(it) })
            replacements[eventId]=old.copy(cards=cards.toList())
        }
        var nextSequence=lastSequence
        for(event in additional) {
            require(event.roundId==id && event.seat!=Seat.SELF)
            require(event.eventId.isNotBlank() && event.cards.isNotEmpty() && event.eventId !in replacements)
            require(event.sequence>nextSequence) { "New event boundary required" }
            replacements[event.eventId]=event.copy(cards=event.cards.toList())
            nextSequence=event.sequence
        }
        require(Seat.entries.all { seat -> replacements.values.filter { it.seat==seat }.sumOf { it.cards.size }<=27 })
        checkCapacity(initial+replacements.values.filter { it.seat!=Seat.SELF }.flatMap { it.cards })
        events.clear(); events.putAll(replacements)
        lastSequence=nextSequence
        phase=recoveryPhase ?: phase; recoveryPhase=null; reason=null
        return snapshot()
    }
    /** Internal visual-track review of the latest presentation for one opponent. No manual editing flow. */
    @Synchronized fun replaceLatestObservedPresentation(eventId: String,cards: List<Card>): RoundSnapshot {
        check(phase==RoundPhase.ACTIVE || phase==RoundPhase.SELF_FINISHED) { "Round has no valid baseline" }
        val old=events[eventId] ?: error("Unknown presentation")
        require(old.seat!=Seat.SELF) { "Self plays require the hand difference proof" }
        require(events.values.lastOrNull { it.seat==old.seat }?.eventId==eventId) { "Only the current presentation can be reviewed" }
        require(cards.isNotEmpty())
        val remainder=cards.toMutableList()
        require(old.cards.all { remainder.remove(it) }) { "An expansion must keep every previously tracked card" }
        val otherEvents=events.values.filter { it.eventId!=eventId }
        require(otherEvents.filter { it.seat==old.seat }.sumOf { it.cards.size }+cards.size<=27)
        checkCapacity(initial+otherEvents.filter { it.seat!=Seat.SELF }.flatMap { it.cards }+cards)
        // Everything is checked before mutating the event; preserve its identity and sequence.
        events[eventId]=old.copy(cards=cards.toList())
        return snapshot()
    }
    @Synchronized fun finish(): RoundSnapshot {
        // A result boundary may end a suspended round, but cannot certify its counts.
        if(phase in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED,RoundPhase.RECOVERING)) {
            phase = RoundPhase.FINISHED; recoveryPhase=null
        }
        return snapshot()
    }
    @Synchronized fun selfFinished(): RoundSnapshot {
        if(phase==RoundPhase.ACTIVE) { phase=RoundPhase.SELF_FINISHED; selfHand=emptyList() }
        return snapshot()
    }
    @Synchronized fun waitForNext(): RoundSnapshot {
        id = null; initial = emptyList(); selfHand = emptyList(); events.clear()
        phase = RoundPhase.WAITING; reason = null; recoveryPhase=null; lastSequence = -1
        return snapshot()
    }
    @Synchronized fun snapshot(): RoundSnapshot {
        val plays = Seat.entries.associateWith { seat -> events.values.filter { it.seat == seat }.toList() }
        val known = phase in setOf(RoundPhase.ACTIVE, RoundPhase.SELF_FINISHED, RoundPhase.FINISHED) && reason==null
        val consumed = initial + events.values.filter { it.seat != Seat.SELF }.flatMap { it.cards }
        val byRank = consumed.groupingBy { it.rank }.eachCount()
        return RoundSnapshot(id, phase,
            if (known) Rank.entries.associateWith { it.capacity - (byRank[it] ?: 0) } else null,
            plays, reason)
    }
    private fun checkCapacity(cards: List<Card>) {
        require(cards.filter { it.suit !in setOf(Suit.RED,Suit.BLACK) }.groupingBy { it }.eachCount().values.all { it <= 2 }) { "Physical card capacity exceeded" }
        require(cards.groupingBy { it.colorKey() }.eachCount().all { (key,count) -> count <= if(key.contains(':')) 4 else 2 }) { "Card color capacity exceeded" }
        require(cards.groupingBy { it.rank }.eachCount().all { (rank, count) -> count <= rank.capacity })
    }
    @Synchronized fun initialHand(): List<Card> = initial.toList()
    @Synchronized fun currentHand(): List<Card> = selfHand.toList()
}
