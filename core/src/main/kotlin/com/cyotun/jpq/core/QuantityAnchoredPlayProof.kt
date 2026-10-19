package com.cyotun.jpq.core

/** A short new display needs both its physical cards and independent seat-count
 * evidence. Counts never supply a card identity, and this cannot deduplicate or
 * expand a still-confirmed presentation. */
class QuantityAnchoredPlayProof {
    private data class Version(val round: String,val seat: Seat,val events: List<PlayEvent>,val remaining: Int,
        val phase: RoundPhase,val scene: TableScene)
    private data class Candidate(val version: Version,val first: Long,val anchor: ObservedPlay,
        var latest: ObservedPlay,var lastFrame: Long,var lastVisible: Long,
        var visibleVotes: Int=1,var emptyVotes: Int=0,var strong: Boolean=false,var firstCount: Long?=null,var lastCount: Long?=null,
        var lastSource: Long?=null)
    private var candidate: Candidate?=null
    private var lastTime=-1L
    private var delivered: Version?=null

    fun clear() { candidate=null }

    fun observe(frame: TableFrame,snapshot: RoundSnapshot,seat: Seat,clearBoundaryAt: Long?): ObservedPlay? {
        if(frame.timeMs<=lastTime) { clear(); return null }
        lastTime=frame.timeMs
        val id=snapshot.roundId
        if(id==null || seat==Seat.SELF || snapshot.remaining==null ||
            snapshot.phase !in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED) ||
            frame.scene !in setOf(TableScene.TABLE,TableScene.TEAMMATE) || frame.captureGap ||
            frame.ambiguousPresentation || frame.incompleteReason!=null || clearBoundaryAt==null) { clear(); return null }
        val remaining=27-snapshot.played(seat)
        if(remaining !in 1..27) { clear(); return null }
        val version=Version(id,seat,snapshot.plays[seat].orEmpty().map { it.copy(cards=it.cards.toList()) },remaining,
            snapshot.phase,frame.scene)
        if(version==delivered) return null
        if(candidate?.version!=version) clear()
        val p=frame.plays[seat]
        if(p==null || !p.regionObserved || p.confidence !in .65f..1f) { clear(); return null }
        val empty=p.cards.isEmpty() && p.glyphs.isEmpty()
        var current=candidate
        if(current!=null && (frame.timeMs-current.lastFrame>500 || frame.timeMs-current.first>1000)) { clear(); return null }
        if(!empty) {
            if(!valid(p) || p.cards.size>=remaining) { clear(); return null }
            if(current==null) {
                if(frame.timeMs-clearBoundaryAt !in 0L..1500L || clearBoundaryAt>=frame.timeMs) return null
                val copy=copy(p)
                current=Candidate(version,frame.timeMs,copy,copy,frame.timeMs,frame.timeMs,strong=strong(p))
                candidate=current
            } else {
                if(key(p.cards)!=key(current.anchor.cards) || !sameSlots(p,current.anchor) || !sameSlots(p,current.latest)) {
                    clear(); return null
                }
                current.latest=copy(p); current.lastVisible=frame.timeMs; current.visibleVotes++
                current.strong=current.strong || strong(p)
                current.emptyVotes=0
            }
        }
        current ?: return null
        current.lastFrame=frame.timeMs
        if(empty) current.emptyVotes++
        val reading=frame.remainingHandCounts[seat]
        if(reading!=null) {
            if(reading.sourceTimeMs<0 ||
                reading.sourceTimeMs>frame.timeMs ||
                (current.lastSource!=null && reading.sourceTimeMs<current.lastSource!!)) { clear(); return null }
            current.lastSource=reading.sourceTimeMs
            // An asynchronous pre-arrival badge is not a post-play vote, even
            // when its value happens to equal the expected decrement.
            if(reading.sourceTimeMs>=current.first) {
                if(reading.count!=remaining-current.anchor.cards.size) { clear(); return null }
                // Cached OCR can age while two directly empty frames arrive.
                // It adds no vote; retain the earlier qualified vote only
                // within this candidate's fixed one-second lifetime.
                if(frame.timeMs-reading.sourceTimeMs<=500) {
                    if(current.firstCount==null) current.firstCount=reading.sourceTimeMs
                    current.lastCount=reading.sourceTimeMs
                }
            }
        }
        val firstCount=current.firstCount ?: return null
        val lastCount=current.lastCount ?: return null
        if(current.visibleVotes<2 || !current.strong || current.lastVisible-current.first<100 ||
            (empty && current.emptyVotes<2) ||
            lastCount-firstCount<100 || frame.timeMs-lastCount>500) return null
        delivered=version
        clear()
        return copy(current.latest)
    }

    private fun copy(p: ObservedPlay)=p.copy(cards=p.cards.toList(),glyphs=p.glyphs.toList())
    private fun key(cards: List<Card>)=cards.map { it.colorKey() }.sorted()
    private fun strong(p: ObservedPlay)=p.confidence>=.80f && p.glyphs.all { it.confidence>=.80f }
    private fun valid(p: ObservedPlay)=p.confidence>=.70f && p.cards.isNotEmpty() && p.cards.size==p.glyphs.size &&
        key(p.cards)==key(p.glyphs.map { it.card }) && p.glyphs.all { g ->
            val b=g.box
            g.confidence in .70f..1f && listOf(b.left,b.top,b.right,b.bottom).all { it.isFinite() && it in 0f..1f } &&
                b.width>0 && b.height>0
        } && p.glyphs.indices.all { i -> (i+1 until p.glyphs.size).none { j ->
            val a=p.glyphs[i].box; val b=p.glyphs[j].box
            kotlin.math.abs(a.cx-b.cx)<=minOf(a.width,b.width)*.1f && kotlin.math.abs(a.cy-b.cy)<=minOf(a.height,b.height)*.1f
        } }
    private fun sameSlots(a: ObservedPlay,b: ObservedPlay): Boolean {
        val rest=b.glyphs.toMutableList()
        return a.glyphs.all { g ->
            val i=rest.indexOfFirst { old -> g.card.colorKey()==old.card.colorKey() &&
                kotlin.math.abs(g.box.cx-old.box.cx)<=minOf(g.box.width,old.box.width)*.35f &&
                kotlin.math.abs(g.box.cy-old.box.cy)<=minOf(g.box.height,old.box.height)*.35f &&
                maxOf(g.box.width,old.box.width)<=minOf(g.box.width,old.box.width)*1.4f &&
                maxOf(g.box.height,old.box.height)<=minOf(g.box.height,old.box.height)*1.4f }
            if(i<0) false else { rest.removeAt(i); true }
        } && rest.isEmpty()
    }
}
