package com.cyotun.jpq.core

/** The displayed pair is one confirmed transaction, independent of pending
 * physical-suit analysis. This never feeds held values back into the ledger. */
class GradeCounterPresentation {
    private var confirmed: GradeCounterState?=null

    fun accept(value: GradeCounterState): GradeCounterState {
        val owned=value.copy(gradeRanks=value.gradeRanks.toSet(),ordinaryCounts=value.ordinaryCounts.toMap())
        val previous=confirmed
        if(previous!=null && (previous.roundId!=owned.roundId || previous.gradeRanks!=owned.gradeRanks))
            confirmed=null
        val complete=owned.roundId!=null && owned.gradeRanks.isNotEmpty() &&
            owned.gradeRanks.size<=2 && owned.gradeRanks.none { it.isJoker } &&
            owned.reason==null && owned.heartCount?.exact!=null &&
            owned.gradeRanks.all { owned.ordinaryCounts[it]?.exact!=null }
        when {
            complete -> confirmed=owned
            owned.reason !in setOf("awaiting_physical_evidence","physical_identity_uncertain","round_recovering") ->
                confirmed=null
        }
        return confirmed ?: owned
    }
}
