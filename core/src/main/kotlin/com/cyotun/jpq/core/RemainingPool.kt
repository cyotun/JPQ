package com.cyotun.jpq.core

/** A complete, stable physical hand reveal belongs to one teammate-event version. */
data class RemainingPoolExclusion(val roundId: String,val epoch: Long,
    val teammateEvents: List<PlayEvent>,val cards: List<Card>) {
    fun sameHandVersion(other: RemainingPoolExclusion): Boolean = roundId==other.roundId && epoch==other.epoch &&
        teammateEvents==other.teammateEvents && cards.map { it.colorKey() }.sorted()==other.cards.map { it.colorKey() }.sorted()
    fun matches(snapshot: RoundSnapshot): Boolean = snapshot.roundId==roundId &&
        snapshot.phase==RoundPhase.SELF_FINISHED && snapshot.remaining!=null &&
        teammateEvents==snapshot.plays[Seat.OPPOSITE].orEmpty() &&
        cards.size==27-snapshot.played(Seat.OPPOSITE)
}

/** Display projection only. The immutable baseline and committed plays remain untouched. */
fun remainingOutsidePool(snapshot: RoundSnapshot,teammate: RemainingPoolExclusion?=null): Map<Rank,Int>? {
    val remaining=snapshot.remaining ?: return null
    val hand=teammate?.takeIf { it.matches(snapshot) }?.cards ?: return remaining
    val counts=hand.groupingBy { it.rank }.eachCount()
    val projected=remaining.mapValues { (rank,count) -> count-(counts[rank] ?: 0) }
    // An incompatible reveal must never produce negative displayed counts.
    return projected.takeIf { it.values.all { count -> count>=0 } } ?: remaining
}

/** Rank/color confirmation is independent of the slower suit side channel. */
class RemainingPoolTracker {
    private var round: String?=null
    private var epoch: Long?=null
    private var accepted: RemainingPoolExclusion?=null
    private var candidate: List<Card>?=null
    private var candidateEvents=emptyList<PlayEvent>()
    private var votes=0
    private var first=0L
    private var last=-1L

    fun reset() { round=null; epoch=null; accepted=null; clearVotes() }
    private fun clearVotes() { candidate=null; candidateEvents=emptyList(); votes=0; first=0; last=-1 }
    private fun colors(cards: List<Card>)=cards.map { it.colorKey() }.sorted()
    private fun events(snapshot: RoundSnapshot)=snapshot.plays[Seat.OPPOSITE].orEmpty()
        .map { it.copy(cards=it.cards.toList()) }

    /** Call on ledger changes too; stale teammate versions cannot remain published. */
    fun projection(snapshot: RoundSnapshot,visibilityEpoch: Long): RemainingPoolExclusion? {
        if(snapshot.roundId!=round) { reset(); round=snapshot.roundId }
        if(epoch!=visibilityEpoch) { epoch=visibilityEpoch; accepted=null; clearVotes() }
        if(snapshot.phase==RoundPhase.RECOVERING) { clearVotes(); return null }
        if(snapshot.phase!=RoundPhase.SELF_FINISHED) { accepted=null; clearVotes(); return null }
        val previous=accepted ?: return null
        if(previous.matches(snapshot)) return previous
        val fresh=events(snapshot)
        // A single is its actual physical rank. Multi-card rendered faces may
        // contain wildcard substitutions and require a fresh complete reveal.
        if(fresh.take(previous.teammateEvents.size)!=previous.teammateEvents) { accepted=null; clearVotes(); return null }
        var hand=previous.cards
        for(event in fresh.drop(previous.teammateEvents.size)) {
            if(event.cards.size!=1) { accepted=null; clearVotes(); return null }
            val played=event.cards.single()
            val choices=hand.indices.filter { hand[it].colorKey()==played.colorKey() }
            if(choices.isEmpty()) { accepted=null; clearVotes(); return null }
            val next=hand.toMutableList()
            next.removeAt(choices.first())
            // If either of two physical suits could have left, only their color
            // is still proved. Keep exact rank counts without inventing a suit.
            if(choices.map { hand[it].suit }.distinct().size>1) {
                val color=if(played.suit in setOf(Suit.RED,Suit.HEART,Suit.DIAMOND)) Suit.RED else if(played.rank.isJoker) Suit.NONE else Suit.BLACK
                hand=next.map { if(it.colorKey()==played.colorKey()) Card(it.rank,color) else it }
            } else hand=next
        }
        accepted=previous.copy(teammateEvents=fresh,cards=hand.toList()).takeIf { it.matches(snapshot) }
        clearVotes()
        return accepted
    }

    /** null means this frame did not prove a complete teammate hand, not a new empty hand. */
    fun observe(time: Long,visibilityEpoch: Long,snapshot: RoundSnapshot,hand: List<Card>?): RemainingPoolExclusion? {
        val current=projection(snapshot,visibilityEpoch)
        if(snapshot.phase!=RoundPhase.SELF_FINISHED || snapshot.roundId==null || snapshot.remaining==null) return null
        val complete=hand?.takeIf { it.size==27-snapshot.played(Seat.OPPOSITE) }?.map {
            Card(it.rank,when(it.suit) {
                Suit.RED,Suit.HEART,Suit.DIAMOND -> Suit.RED
                Suit.BLACK,Suit.SPADE,Suit.CLUB -> Suit.BLACK
                Suit.NONE -> Suit.NONE
            })
        }?.takeIf { cards -> cards.groupingBy { it.colorKey() }.eachCount().all { (key,count) ->
            count<=if(':' in key) 4 else 2
        } }
        if(complete==null || time<0) { clearVotes(); return current }
        if(time<=last) return current
        val currentEvents=events(snapshot)
        if(candidate==null || colors(candidate!!)!=colors(complete) || candidateEvents!=currentEvents || time-last>1000) {
            candidate=complete.toList(); candidateEvents=currentEvents; votes=1; first=time
        } else { candidate=complete.toList(); votes++ }
        last=time
        if(votes>=3 && time-first>=500) {
            val next=RemainingPoolExclusion(snapshot.roundId,visibilityEpoch,currentEvents,complete.toList())
            val raw=snapshot.remaining
            if(complete.groupingBy { it.rank }.eachCount().all { (rank,count) -> count<=(raw[rank] ?: 0) }) accepted=next
        }
        return accepted?.takeIf { it.matches(snapshot) }
    }
}
