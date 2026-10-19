package com.cyotun.jpq.core

/** Keeps one already visible, uncommitted play across another seat's short mask.
 * No ledger is changed here, and an old empty anchor alone never supplies a play. */
class LocalizedPendingPlayRecovery {
    data class Recovered(val seat: Seat,val play: ObservedPlay)
    private data class Version(val round: String,val own: List<String>,val events: List<PlayEvent>)
    private data class ClearFrame(val time: Long,val plays: Map<Seat,ObservedPlay>)
    private data class Candidate(
        val seat: Seat,val first: ObservedPlay,val firstTime: Long,
        val anchors: Map<Seat,ObservedPlay>,
        var previous: ObservedPlay,var lastPreTime: Long,var preVotes: Int=1,
        var maskedSeat: Seat?=null,var postTime: Long?=null,
        var postMasked: ObservedPlay?=null,var postCandidate: ObservedPlay?=null,
    )
    private val outside=listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT)
    private var version: Version?=null
    private var prior: ClearFrame?=null
    private var candidate: Candidate?=null
    private var lastTime=-1L
    private var delivered=false

    fun reset() { version=null; prior=null; candidate=null; lastTime=-1L; delivered=false }

    fun observe(frame: TableFrame,snapshot: RoundSnapshot,knownOwnHand: List<Card>,
                phaseBeforeRecovery: RoundPhase): Recovered? {
        val id=snapshot.roundId
        if(id==null || phaseBeforeRecovery!=RoundPhase.ACTIVE ||
            snapshot.phase !in setOf(RoundPhase.ACTIVE,RoundPhase.RECOVERING) ||
            !validCards(knownOwnHand) || knownOwnHand.isEmpty() ||
            snapshot.played(Seat.SELF)+knownOwnHand.size!=27 ||
            outside.any { snapshot.played(it) !in 0..27 }) { reset(); return null }
        val next=Version(id,key(knownOwnHand),Seat.entries.flatMap { snapshot.plays[it].orEmpty() }
            .map { it.copy(cards=it.cards.toList()) })
        if(version!=next) { reset(); version=next }
        if(frame.timeMs<=lastTime) return null
        val previousTime=lastTime
        lastTime=frame.timeMs
        if(delivered) return null
        if(frame.scene!=TableScene.TABLE || frame.selfFinished || frame.captureGap || frame.ambiguousPresentation ||
            frame.hand?.let { key(it)==key(knownOwnHand) }!=true ||
            (previousTime>=0 && frame.timeMs-previousTime>500L)) return reject()

        val local=frame.onlyLocalizedOverlay() && frame.localizedOverlaySeats.size==1 &&
            frame.localizedOverlaySeats.single() in outside
        if(frame.incompleteReason!=null && !local) return reject()
        val old=candidate
        if(old?.maskedSeat!=null) return observeFrozen(frame,snapshot,old,local)
        if(local) {
            val masked=frame.localizedOverlaySeats.single()
            if(old==null || masked==old.seat || old.preVotes<2 ||
                old.lastPreTime-old.firstTime<100L || frame.timeMs-old.lastPreTime !in 1L..500L ||
                old.anchors[masked]?.cards.isNullOrEmpty() ||
                !otherAnchorsVisible(frame,old,masked) || !candidateVisible(frame,old)) return reject()
            old.maskedSeat=masked
            prior=null
            return observeFrozen(frame,snapshot,old,true)
        }
        // New candidates can only start on the active, fully visible side of the
        // interruption, from an immediately preceding positive empty boundary.
        if(snapshot.phase!=RoundPhase.ACTIVE || snapshot.remaining?.values?.all { it>=0 }!=true) return reject()
        if(old!=null) {
            if(!candidateVisible(frame,old) || !otherAnchorsVisible(frame,old,null)) return reject()
            old.previous=copy(frame.plays.getValue(old.seat)!!)
            old.lastPreTime=frame.timeMs
            old.preVotes++
            return null
        }
        val current=outside.mapNotNull { seat -> frame.plays[seat]?.let { seat to it } }.toMap()
        if(current.size!=outside.size || current.values.any { !direct(it) }) return reject()
        val before=prior
        val possibilities=if(before==null || frame.timeMs-before.time !in 1L..500L) emptyList() else outside.filter { seat ->
            empty(before.plays.getValue(seat)) && strong(current.getValue(seat)) &&
                current.getValue(seat).cards.size<=27-snapshot.played(seat) &&
                current.getValue(seat).cards.groupingBy { it.rank }.eachCount().all { (rank,n) -> n<=(snapshot.remaining?.get(rank) ?: -1) } &&
                outside.filter { it!=seat }.all { other ->
                    val anchor=before.plays.getValue(other)
                    committedOrEmpty(anchor,other,snapshot) && samePresentation(current.getValue(other),anchor)
                }
        }
        if(possibilities.size==1) {
            val seat=possibilities.single()
            val play=copy(current.getValue(seat))
            candidate=Candidate(seat,play,frame.timeMs,
                outside.filter { it!=seat }.associateWith { copy(before!!.plays.getValue(it)) },play,frame.timeMs)
            prior=null
        } else {
            prior=ClearFrame(frame.timeMs,current.mapValues { copy(it.value) })
        }
        return null
    }

    private fun observeFrozen(frame: TableFrame,snapshot: RoundSnapshot,c: Candidate,local: Boolean): Recovered? {
        val masked=c.maskedSeat ?: return reject()
        if(frame.timeMs-c.lastPreTime !in 1L..1500L ||
            (local && frame.localizedOverlaySeats.single()!=masked) ||
            !candidateVisible(frame,c) || !otherAnchorsVisible(frame,c,masked)) return reject()
        // A fresh independent quantity contradiction cannot be overridden by a
        // visually similar returning group. Pre-arrival/cached sources supply
        // no such statement about this transaction.
        for((seat,reading) in frame.remainingHandCounts) {
            if(seat !in outside) continue
            if(reading.sourceTimeMs<0 || reading.sourceTimeMs>frame.timeMs) return reject()
            if(reading.sourceTimeMs>=c.firstTime && frame.timeMs-reading.sourceTimeMs<=1000) {
                val expected=27-snapshot.played(seat)-(if(seat==c.seat) c.first.cards.size else 0)
                if(reading.count!=expected) return reject()
            }
        }
        val old=c.anchors.getValue(masked)
        val current=frame.plays[masked]
        // A masked/animated fragment supplies no vote. Its visible identities
        // cannot contradict the old group, and no absent identity is filled in.
        if(current==null || !current.regionObserved) {
            if(current!=null && !subsetFragment(current,old)) return reject()
            c.previous=copy(frame.plays.getValue(c.seat)!!)
            c.postTime=null; c.postMasked=null; c.postCandidate=null
            return null
        }
        if(!strong(current) || !sameSlots(current,old)) return reject()
        if(frame.incompleteReason!=null) return reject()
        val ownPlay=frame.plays.getValue(c.seat)!!
        val time=c.postTime
        val post=c.postMasked
        val candidatePost=c.postCandidate
        c.previous=copy(ownPlay)
        if(time==null || post==null || candidatePost==null) {
            c.postTime=frame.timeMs; c.postMasked=copy(current); c.postCandidate=copy(ownPlay)
            return null
        }
        if(frame.timeMs-time !in 1L..500L || !sameSlots(current,post) || !sameSlots(ownPlay,candidatePost)) return reject()
        if(frame.timeMs-time<100L) return null
        delivered=true
        candidate=null; prior=null
        return Recovered(c.seat,copy(ownPlay))
    }

    private fun candidateVisible(frame: TableFrame,c: Candidate): Boolean {
        val p=frame.plays[c.seat] ?: return false
        return strong(p) && sameSlots(p,c.first) && sameSlots(p,c.previous)
    }
    private fun otherAnchorsVisible(frame: TableFrame,c: Candidate,except: Seat?)=
        c.anchors.all { (seat,old) -> seat==except || frame.plays[seat]?.let { samePresentation(it,old) }==true }
    private fun committedOrEmpty(p: ObservedPlay,seat: Seat,snapshot: RoundSnapshot)=empty(p) ||
        (strong(p) && snapshot.plays[seat]?.lastOrNull()?.let { key(it.cards)==key(p.cards) }==true)
    private fun samePresentation(p: ObservedPlay,old: ObservedPlay)=
        if(old.cards.isEmpty()) empty(p) else strong(p) && sameSlots(p,old)
    private fun reject(): Recovered? { candidate=null; prior=null; return null }
    private fun copy(p: ObservedPlay)=p.copy(cards=p.cards.toList(),glyphs=p.glyphs.toList())
    private fun key(cards: List<Card>)=cards.map { it.colorKey() }.sorted()
    private fun validCards(cards: List<Card>)=cards.size<=27 &&
        cards.groupingBy { it.rank }.eachCount().all { (r,n) -> n<=r.capacity } &&
        cards.groupingBy { it.colorKey() }.eachCount().all { (k,n) -> n<=if(':' in k) 4 else 2 }
    private fun valid(b: GlyphBox)=listOf(b.left,b.top,b.right,b.bottom).all { it.isFinite() && it in 0f..1f } &&
        b.width>0 && b.height>0
    private fun paired(p: ObservedPlay)=validCards(p.cards) && p.cards.size==p.glyphs.size &&
        key(p.cards)==key(p.glyphs.map { it.card }) && p.glyphs.all { it.confidence in 0f..1f && valid(it.box) }
    private fun empty(p: ObservedPlay)=p.regionObserved && p.confidence in .65f..1f && p.cards.isEmpty() && p.glyphs.isEmpty()
    private fun strong(p: ObservedPlay)=p.regionObserved && p.confidence in .80f..1f && p.cards.isNotEmpty() && paired(p) &&
        p.glyphs.all { it.confidence>=.80f } && p.glyphs.indices.all { i ->
            (i+1 until p.glyphs.size).none { j ->
                val a=p.glyphs[i].box; val b=p.glyphs[j].box
                kotlin.math.abs(a.cx-b.cx)<=minOf(a.width,b.width)*.1f &&
                    kotlin.math.abs(a.cy-b.cy)<=minOf(a.height,b.height)*.1f
            }
        }
    private fun direct(p: ObservedPlay)=empty(p) || strong(p)
    private fun subsetFragment(p: ObservedPlay,old: ObservedPlay): Boolean {
        if(!paired(p)) return false
        val rest=old.cards.map { it.colorKey() }.toMutableList()
        return p.cards.all { rest.remove(it.colorKey()) }
    }
    private fun sameSlots(p: ObservedPlay,old: ObservedPlay): Boolean {
        if(key(p.cards)!=key(old.cards) || p.glyphs.size!=old.glyphs.size) return false
        val rest=old.glyphs.toMutableList()
        return p.glyphs.all { g ->
            val index=rest.indexOfFirst { h -> g.card.colorKey()==h.card.colorKey() &&
                kotlin.math.abs(g.box.cx-h.box.cx)<=minOf(g.box.width,h.box.width)*.35f &&
                kotlin.math.abs(g.box.cy-h.box.cy)<=minOf(g.box.height,h.box.height)*.35f &&
                maxOf(g.box.width,h.box.width)<=minOf(g.box.width,h.box.width)*1.4f &&
                maxOf(g.box.height,h.box.height)<=minOf(g.box.height,h.box.height)*1.4f }
            if(index<0) false else { rest.removeAt(index); true }
        } && rest.isEmpty()
    }
}
