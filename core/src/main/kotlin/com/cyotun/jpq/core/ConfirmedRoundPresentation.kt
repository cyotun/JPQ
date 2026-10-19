package com.cyotun.jpq.core

data class RoundDisplayState(val snapshot: RoundSnapshot,val remaining: Map<Rank,Int>?,val teammateExcluded: Boolean=false)

/**
 * A display-only checkpoint. Pending recognition cannot replace confirmed counts
 * or history with a waiting panel. Held values never enter the evidence ledger.
 * Stop, a new round, and a positively observed round boundary clear the cache.
 */
class ConfirmedRoundPresentation {
    private var confirmed: RoundDisplayState?=null

    fun accept(snapshot: RoundSnapshot,projectedRemaining: Map<Rank,Int>?,unobserved: Boolean=false,
               running: Boolean=true,ready: Boolean=true,teammateExcluded: Boolean=false): RoundDisplayState {
        if(!running || !ready || snapshot.roundId!=confirmed?.snapshot?.roundId ||
            snapshot.phase in setOf(RoundPhase.WAITING,RoundPhase.FINISHED)) confirmed=null
        if(!ready) return RoundDisplayState(snapshot.copy(phase=RoundPhase.WAITING,remaining=null,reason=null),null)
        val pendingPool=snapshot.phase==RoundPhase.SELF_FINISHED && confirmed?.teammateExcluded==true && !teammateExcluded
        val pending=unobserved || pendingPool || snapshot.phase in setOf(RoundPhase.RECOVERING,RoundPhase.UNAVAILABLE)
        if(running && !pending && snapshot.roundId!=null && snapshot.remaining!=null &&
            snapshot.phase in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED) && projectedRemaining!=null) {
            val owned=snapshot.copy(remaining=snapshot.remaining.toMap(),
                plays=snapshot.plays.mapValues { (_,events) -> events.map { it.copy(cards=it.cards.toList()) } })
            confirmed=RoundDisplayState(owned,projectedRemaining.toMap(),teammateExcluded)
        }
        val previous=confirmed
        if(running && pending && previous!=null && previous.snapshot.roundId==snapshot.roundId)
            return previous.copy(snapshot=previous.snapshot.copy(phase=RoundPhase.RECOVERING,reason=snapshot.reason))
        return RoundDisplayState(snapshot,if(unobserved || pending) null else projectedRemaining,teammateExcluded)
    }
}
