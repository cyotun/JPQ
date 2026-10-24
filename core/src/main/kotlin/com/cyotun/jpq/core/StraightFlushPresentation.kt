package com.cyotun.jpq.core

data class StraightFlushDisplay(val result: StraightFlushResult,val includesWildcards: Boolean)

/** Display checkpoint only. Uncertain annotations never change the evidence or ledger. */
class StraightFlushPresentation {
    private var roundId: String?=null
    private var confirmed: StraightFlushDisplay?=null
    private var teammateWasExcluded=false
    private var ruleScope: WildcardRuleEvidence?=null
    private val boundedUncertaintyReasons=setOf("suit_uncertain","rendered_substitution_uncertain","wildcard_rule_unknown")

    fun accept(snapshot: RoundSnapshot,result: StraightFlushResult,includesWildcards: Boolean,
               teammateExcluded: Boolean=false,rule: WildcardRuleEvidence?=null): StraightFlushDisplay {
        val ownedRule=rule?.copy(ranks=rule.ranks?.toSet())
        if(ruleScope!=ownedRule) {
            ruleScope=ownedRule
            confirmed=null
        }
        if(snapshot.roundId!=roundId || snapshot.phase==RoundPhase.WAITING) {
            roundId=snapshot.roundId
            confirmed=null
            teammateWasExcluded=false
        }
        if(snapshot.roundId==null || snapshot.phase==RoundPhase.WAITING)
            return StraightFlushDisplay(StraightFlushResult(StraightFlushState.UNKNOWN),false)
        val poolPending=snapshot.phase==RoundPhase.SELF_FINISHED && teammateWasExcluded && !teammateExcluded
        if(snapshot.phase==RoundPhase.SELF_FINISHED && teammateExcluded) teammateWasExcluded=true
        if(!poolPending && snapshot.phase in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED)) {
            val previous=confirmed?.result?.candidateRanks
            // Unknown suits can still rule out windows. Keep ordinary pending
            // reads stable, but accept a strict candidate upper-bound refinement
            // from the same rule/pool instead of waiting for an exact-suit witness.
            val narrows=result.state==StraightFlushState.UNKNOWN && result.reason in boundedUncertaintyReasons &&
                result.candidateRanks.isNotEmpty() && previous!=null &&
                result.candidateRanks.size<previous.size && previous.containsAll(result.candidateRanks)
            if(result.state!=StraightFlushState.UNKNOWN || narrows)
                confirmed=StraightFlushDisplay(result.copy(candidateRanks=result.candidateRanks.toSet()),includesWildcards)
        }
        return confirmed ?: if(poolPending) StraightFlushDisplay(StraightFlushResult(StraightFlushState.UNKNOWN),false)
            else StraightFlushDisplay(result.copy(candidateRanks=result.candidateRanks.toSet()),includesWildcards)
    }
}
