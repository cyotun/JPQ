package com.cyotun.jpq.core

data class GradeCountBounds(val minimum: Int,val maximum: Int) {
    init { require(minimum>=0 && maximum>=minimum) }
    val exact: Int? get()=minimum.takeIf { it==maximum }
}
data class GradeCounterState(val roundId: String?=null,val gradeRanks: Set<Rank> = emptySet(),
    val heartCount: GradeCountBounds?=null,val ordinaryCounts: Map<Rank,GradeCountBounds?> = emptyMap(),
    val reason: String?=null)

/** A displayed replacement is not a second physical card. Null means not proved either way. */
data class PhysicalPlayedCard(val rendered: Card,val wildcard: Boolean?=null)

/** Capacity-constrained physical allocations, independent of the rank ledger and SF indicator. */
object GradeCardCounter {
    private val ordinary=Rank.entries.filterNot { it.isJoker }
    private val suits=listOf(Suit.SPADE,Suit.HEART,Suit.CLUB,Suit.DIAMOND)
    private val deck=ordinary.flatMap { rank -> suits.map { Card(rank,it) } }+
        Rank.entries.filter { it.isJoker }.map { Card(it,Suit.NONE) }

    fun evaluate(snapshot: RoundSnapshot,initial: List<Card>,events: Map<String,List<PhysicalPlayedCard>>,
        rule: WildcardRuleEvidence): GradeCounterState {
        val grades=rule.ranks.orEmpty().filterNot { it.isJoker }.toSet()
        if(!rule.confirmed || grades.isEmpty() || grades.size>2 || snapshot.remaining==null || initial.size!=27)
            return GradeCounterState(snapshot.roundId,reason="grade_or_baseline_unconfirmed")
        val wild=deck.indices.filter { deck[it].rank in grades && deck[it].suit==Suit.HEART }.toSet()
        fun natural(card: Card)=deck.indices.filter { index ->
            val p=deck[index]
            p.rank==card.rank && when(card.suit) {
                Suit.RED -> p.suit in setOf(Suit.HEART,Suit.DIAMOND)
                Suit.BLACK -> p.suit in setOf(Suit.SPADE,Suit.CLUB)
                else -> p.suit==card.suit
            }
        }.toSet()
        val choices=initial.map { natural(it) }.toMutableList()
        for(event in snapshot.plays.values.flatten().filter { it.seat!=Seat.SELF }) {
            val annotation=events[event.eventId]?.takeIf { cards -> cards.size==event.cards.size &&
                cards.map { it.rendered.colorKey() }.sorted()==event.cards.map { it.colorKey() }.sorted() }
                ?: event.cards.map { PhysicalPlayedCard(it) }
            annotation.forEach { slot ->
                val direct=natural(slot.rendered)
                choices += when {
                    event.cards.size==1 || slot.rendered.rank.isJoker -> direct
                    slot.wildcard==true -> wild
                    slot.wildcard==false -> direct-wild
                    else -> direct+wild
                }
            }
        }
        if(choices.size>108 || choices.any { it.isEmpty() })
            return GradeCounterState(snapshot.roundId,grades,reason="physical_evidence_conflict")
        fun remaining(target: Set<Int>): GradeCountBounds? {
            val low=minimumCost(choices,target,1) ?: return null
            val high=minimumCost(choices,target,-1)?.let { -it } ?: return null
            return GradeCountBounds(target.size*2-high,target.size*2-low)
        }
        val hearts=remaining(wild) ?: return GradeCounterState(snapshot.roundId,grades,reason="physical_evidence_conflict")
        val regular=grades.associateWith { rank -> remaining(deck.indices.filter {
            deck[it].rank==rank && deck[it].suit!=Suit.HEART
        }.toSet()) }
        return GradeCounterState(snapshot.roundId,grades,hearts,regular,
            if(hearts.exact==null || regular.values.any { it?.exact==null }) "physical_identity_uncertain" else null)
    }

    private class Edge(val to: Int,val reverse: Int,var capacity: Int,val cost: Int)
    /** Successive shortest augmenting paths; at most 108 units and 54 physical types. */
    private fun minimumCost(choices: List<Set<Int>>,target: Set<Int>,sign: Int): Int? {
        val source=0; val types=1+choices.size; val sink=types+deck.size
        val graph=Array(sink+1) { mutableListOf<Edge>() }
        fun edge(a: Int,b: Int,capacity: Int,cost: Int) {
            val forward=Edge(b,graph[b].size,capacity,cost)
            val reverse=Edge(a,graph[a].size,0,-cost)
            graph[a].add(forward); graph[b].add(reverse)
        }
        choices.forEachIndexed { i,options ->
            edge(source,i+1,1,0); options.forEach { edge(i+1,types+it,1,0) }
        }
        deck.indices.forEach { edge(types+it,sink,2,if(it in target) sign else 0) }
        var cost=0
        repeat(choices.size) {
            val distance=IntArray(graph.size) { Int.MAX_VALUE }; distance[source]=0
            val previous=IntArray(graph.size) { -1 }; val previousEdge=IntArray(graph.size)
            val queued=BooleanArray(graph.size); val queue=ArrayDeque<Int>(); queue.add(source); queued[source]=true
            while(queue.isNotEmpty()) {
                val a=queue.removeFirst(); queued[a]=false
                graph[a].forEachIndexed { index,e ->
                    if(e.capacity>0 && distance[a]!=Int.MAX_VALUE && distance[e.to]>distance[a]+e.cost) {
                        distance[e.to]=distance[a]+e.cost; previous[e.to]=a; previousEdge[e.to]=index
                        if(!queued[e.to]) { queue.add(e.to); queued[e.to]=true }
                    }
                }
            }
            if(previous[sink]<0) return null
            var p=sink
            while(p!=source) {
                val e=graph[previous[p]][previousEdge[p]]; e.capacity--; graph[p][e.reverse].capacity++; p=previous[p]
            }
            cost+=distance[sink]
        }
        return cost
    }
}
