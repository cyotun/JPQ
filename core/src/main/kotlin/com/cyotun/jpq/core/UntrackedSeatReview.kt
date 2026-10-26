package com.cyotun.jpq.core

/**
 * A seat-local journal, never a ledger writer. An ambiguous strict superset is
 * held against an immutable checkpoint while other seats may append events.
 * Every returned card has a direct four-frame glyph certificate; badge OCR
 * chooses between explicit interpretations and never invents a missing play.
 */
class UntrackedSeatReview {
    data class Resolution(
        val priorEventId: String,
        val expansions: Map<String,List<Card>>,
        val newObservedGroups: List<ObservedPlay>,
        val latestPresentation: ObservedPlay,
    )
    private data class Certificate(val play: ObservedPlay,val first: Long,val certifiedAt: Long)
    private data class Run(val anchor: ObservedPlay,val first: Long,var last: Long,var votes: Int=1)
    private class Review(val round: String,val seat: Seat,val prior: QuantityAnchoredPlayProof.PriorPresentation,
        val expected: Int,var history: List<PlayEvent>,val targetHistory: List<PlayEvent>,var lastFrame: Long) {
        val groups=mutableListOf<Certificate>()
        var run: Run?=null
        var reacquire: Run?=null
        var needsFreshRow=false
        var countFence=-1L
        var firstExpandable=false
        var emptyVotes=0
        var emptyFirst=-1L
        var boundary=false
        var oldOrEmpty=false
        var lastSource=-1L
        var lastSourceCount: Int?=null
        var count: Int?=null
        var countFirst=-1L
        var countLast=-1L
        var countVotes=0
        var unsafe=false
    }
    private val reviews=mutableMapOf<Seat,Review>()

    fun clear() { reviews.clear() }
    fun clear(seat: Seat) { reviews.remove(seat) }
    fun holds(seat: Seat)=seat in reviews
    fun pendingSeats(): Set<Seat> = reviews.keys.toSet()
    fun unsafe(seat: Seat)=reviews[seat]?.unsafe==true
    /** A true table-wide visibility gap cannot reuse a local no-gap journal. */
    fun interruptCapture() { reviews.values.forEach(::makeUnsafe) }
    fun interruptPresentation(seats: Set<Seat> = reviews.keys.toSet()) {
        seats.forEach { reviews[it]?.let(::softInterrupt) }
    }

    /**
     * Supply the clean source frame, live snapshot and immutable prior visible
     * presentation. Call clear(seat) when a stronger proof commits or the round
     * ends. A returned resolution is repeatable, never consumed; clear(seat)
     * only after atomic ledger validation and application succeed. Null remains
     * pending, including unsafe journals which need terminal reconciliation.
     * A complete >=.65 strict superset may open a hold; it supplies no identity
     * certificate until four independent >=.80 frames establish the group.
     */
    fun observe(frame: TableFrame,snapshot: RoundSnapshot,seat: Seat,
        prior: QuantityAnchoredPlayProof.PriorPresentation): Resolution? {
        var review=reviews[seat]
        if(review==null) {
            if(!clean(frame,seat) || !live(snapshot) || seat==Seat.SELF || snapshot.remaining==null) return null
            val latest=snapshot.plays[seat].orEmpty().lastOrNull() ?: return null
            val play=frame.plays[seat] ?: return null
            if(prior.eventId.isBlank() || latest.eventId!=prior.eventId || latest.cards!=prior.cards ||
                prior.cards.isEmpty() || !valid(play,.65f) || play.cards.size<=prior.cards.size ||
                !subset(keys(prior.cards),keys(play.cards))) return null
            val expected=27-snapshot.played(seat)
            if(expected !in 1..27) return null
            review=Review(snapshot.roundId!!,seat,copyPrior(prior),expected,history(snapshot),
                snapshot.plays[seat].orEmpty().map(::copyEvent),frame.timeMs)
            reviews[seat]=review
        } else {
            if(frame.timeMs==review.lastFrame) return null // one source frame is one glyph vote
            if(frame.timeMs<review.lastFrame ||
                !sameVersion(review,snapshot,prior) || frame.captureGap) makeUnsafe(review)
            else if(frame.timeMs-review.lastFrame>500) softInterrupt(review)
            review.lastFrame=frame.timeMs
        }
        if(review.unsafe) return null
        review.history=history(snapshot)
        if(!clean(frame,seat) || !live(snapshot)) { softInterrupt(review); return null }
        val play=frame.plays[seat]!!
        if(review.needsFreshRow) {
            val latest=review.groups.lastOrNull()?.play
            if(latest==null || !valid(play) || !sameSlots(play,latest)) {
                softInterrupt(review); return null
            }
            val run=review.reacquire
            if(run==null || !sameSlots(run.anchor,play)) review.reacquire=Run(copy(play),frame.timeMs,frame.timeMs)
            else { run.last=frame.timeMs; run.votes++ }
            val fresh=review.reacquire!!
            if(fresh.votes<4 || frame.timeMs-fresh.first<180) return null
            review.needsFreshRow=false
            review.reacquire=null
            review.countFence=frame.timeMs
            clearCounts(review)
        }
        review.oldOrEmpty=false
        if(play.cards.isEmpty() && play.glyphs.isEmpty() && play.confidence in .8f..1f) {
            review.run=null
            if(review.emptyVotes==0) review.emptyFirst=frame.timeMs
            review.emptyVotes++
            review.boundary=review.emptyVotes>=2 && frame.timeMs-review.emptyFirst>=100
            review.oldOrEmpty=review.boundary
        } else if(valid(play)) {
            review.oldOrEmpty=keys(play.cards)==keys(prior.cards) && tracked(play.glyphs,prior.glyphs)
            if(review.oldOrEmpty && !review.boundary) review.run=null
            else observeGroup(review,play,frame.timeMs)
            review.emptyVotes=0
            review.emptyFirst=-1L
        } else {
            // Ordinary weak/incomplete reads suspend this seat. Certified card
            // identities survive; fresh exact geometry must return before OCR
            // counts can choose an interpretation again.
            softInterrupt(review)
            return null
        }
        if(review.unsafe || review.needsFreshRow) return null
        observeCount(review,frame)
        if(review.unsafe || review.run!=null || review.countVotes<3 || review.countLast-review.countFirst<300 ||
            frame.timeMs-review.countLast !in 0L..1000L || review.groups.isEmpty()) return null
        val later=review.groups.drop(1).sumOf { it.play.cards.size }
        val delta=review.expected-(review.count ?: return null)
        val first=review.groups.first().play
        val choices=mutableListOf<String>()
        if(delta==first.cards.size+later) choices+="NEW"
        if(review.firstExpandable && delta==first.cards.size-prior.cards.size+later) choices+="EXPAND"
        if(delta==0 && later==0 && review.oldOrEmpty) choices+="IGNORE"
        if(choices.size!=1) return null
        val mode=choices.single()
        return Resolution(prior.eventId,
            if(mode=="EXPAND") mapOf(prior.eventId to first.cards.toList()) else emptyMap(),
            (if(mode=="NEW") review.groups else review.groups.drop(1)).map { copy(it.play) },
            if(mode=="IGNORE") copy(priorPlay(review.prior)) else copy(review.groups.last().play))
    }

    private fun observeGroup(r: Review,p: ObservedPlay,time: Long) {
        val last=r.groups.lastOrNull()
        if(last==null && (p.cards.size<=r.prior.cards.size || !subset(keys(r.prior.cards),keys(p.cards)))) {
            r.run=null; clearCounts(r); return
        }
        if(last!=null && !r.boundary && sameSlots(p,last.play)) { r.run=null; return }
        // Same ranks with a color flicker, partial groups and supersets are
        // presentation uncertainty, never an additional journal entry.
        val different=last==null || (!subset(ranks(p.cards),ranks(last.play.cards)) &&
            !subset(ranks(last.play.cards),ranks(p.cards)))
        if(last!=null && !r.boundary && !different) { softInterrupt(r); return }
        val run=r.run
        if(run==null || !sameSlots(run.anchor,p) || time-run.last>500) {
            r.run=Run(copy(p),time,time)
            clearCounts(r)
            return
        }
        run.last=time
        run.votes++
        if(run.votes<4 || time-run.first<180) return
        if(r.groups.size>=4 || r.groups.sumOf { it.play.cards.size }+p.cards.size>27) {
            makeUnsafe(r); return
        }
        if(last==null) r.firstExpandable=physicalPrefix(p,r.prior)
        r.groups+=Certificate(copy(p),run.first,time)
        r.countFence=time
        r.run=null
        r.boundary=false
        clearCounts(r)
    }

    private fun observeCount(r: Review,frame: TableFrame) {
        val reading=frame.remainingHandCounts[r.seat] ?: return
        val source=reading.sourceTimeMs
        if(source<0 || source>frame.timeMs) { clearCounts(r); return }
        if(source==r.lastSource) {
            if(reading.count!=r.lastSourceCount) clearCounts(r)
            return
        }
        if(source<r.lastSource) { clearCounts(r); return }
        r.lastSource=source
        r.lastSourceCount=reading.count
        if(reading.count !in 0..r.expected) { clearCounts(r); return }
        if(r.groups.isEmpty()) return
        val fence=r.countFence
        if(source<=fence || frame.timeMs-source>1000) { clearCounts(r); return }
        // A single OCR value is never a monotonic hand-count bound. Even an
        // impossible apparent decrement is discarded by the exact journal
        // delta below; three later independent correct sources may recover.
        if(r.count!=reading.count) {
            clearCounts(r)
            r.count=reading.count
            r.countFirst=source
        }
        r.countLast=source
        r.countVotes++
    }

    private fun sameVersion(r: Review,s: RoundSnapshot,p: QuantityAnchoredPlayProof.PriorPresentation): Boolean {
        val current=history(s)
        return s.roundId==r.round && p==r.prior && s.plays[r.seat].orEmpty()==r.targetHistory &&
            current.take(r.history.size)==r.history &&
            current.drop(r.history.size).all { it.seat!=r.seat } &&
            current.zipWithNext().all { (a,b) -> a.sequence<b.sequence } &&
            current.map { it.eventId }.distinct().size==current.size &&
            current.all { it.roundId==r.round && it.eventId.isNotBlank() && it.cards.isNotEmpty() }
    }
    private fun clean(f: TableFrame,seat: Seat)=f.timeMs>=0 &&
        f.scene in setOf(TableScene.TABLE,TableScene.TEAMMATE) && !f.captureGap &&
        !f.ambiguousPresentation && (f.incompleteReason==null || f.onlyLocalizedOverlay()) &&
        seat !in f.localizedOverlaySeats &&
        f.plays[seat]?.regionObserved==true
    private fun live(s: RoundSnapshot)=s.roundId!=null && s.phase in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED)
    private fun history(s: RoundSnapshot)=s.plays.values.flatten().sortedBy { it.sequence }.map(::copyEvent)
    private fun copyEvent(e: PlayEvent)=e.copy(cards=e.cards.toList())
    private fun copyPrior(p: QuantityAnchoredPlayProof.PriorPresentation)=p.copy(cards=p.cards.toList(),glyphs=p.glyphs.toList())
    private fun priorPlay(p: QuantityAnchoredPlayProof.PriorPresentation)=ObservedPlay(p.cards,1f,p.glyphs)
    private fun copy(p: ObservedPlay)=p.copy(cards=p.cards.toList(),glyphs=p.glyphs.toList())
    private fun keys(cards: List<Card>)=cards.map { it.colorKey() }.sorted()
    private fun ranks(cards: List<Card>)=cards.map { it.rank.name }.sorted()
    private fun subset(a: List<String>,b: List<String>): Boolean {
        val rest=b.toMutableList()
        return a.all { rest.remove(it) }
    }
    private fun clearCounts(r: Review) { r.count=null; r.countFirst=-1; r.countLast=-1; r.countVotes=0 }
    private fun softInterrupt(r: Review) {
        r.run=null; r.reacquire=null; r.needsFreshRow=r.groups.isNotEmpty()
        r.emptyVotes=0; r.emptyFirst=-1; r.boundary=false; r.oldOrEmpty=false
        clearCounts(r)
    }
    private fun makeUnsafe(r: Review) { r.unsafe=true; r.run=null; clearCounts(r) }
    private fun slot(a: GlyphBox,b: GlyphBox)=
        kotlin.math.abs(a.cx-b.cx)<=minOf(a.width,b.width)*.35f &&
            kotlin.math.abs(a.cy-b.cy)<=minOf(a.height,b.height)*.35f &&
            maxOf(a.width,b.width)<=minOf(a.width,b.width)*1.4f &&
            maxOf(a.height,b.height)<=minOf(a.height,b.height)*1.4f
    private fun tracked(current: List<PlayGlyph>,old: List<PlayGlyph>): Boolean {
        val rest=current.toMutableList()
        return old.isNotEmpty() && old.all { previous ->
            val index=rest.indexOfFirst { it.card.colorKey()==previous.card.colorKey() && slot(it.box,previous.box) }
            if(index<0) false else { rest.removeAt(index); true }
        }
    }
    private fun sameSlots(a: ObservedPlay,b: ObservedPlay)=keys(a.cards)==keys(b.cards) && tracked(a.glyphs,b.glyphs)
    private fun physicalPrefix(p: ObservedPlay,old: QuantityAnchoredPlayProof.PriorPresentation)=
        valid(priorPlay(old)) && tracked(p.glyphs.sortedBy { it.box.cx }.take(old.glyphs.size),old.glyphs)
    private fun valid(p: ObservedPlay,minimum: Float=.8f)=p.regionObserved && p.confidence in minimum..1f &&
        p.cards.isNotEmpty() && p.cards.size==p.glyphs.size && keys(p.cards)==keys(p.glyphs.map { it.card }) &&
        p.glyphs.all { g -> g.confidence in minimum..1f &&
            listOf(g.box.left,g.box.top,g.box.right,g.box.bottom).all { it.isFinite() && it in 0f..1f } &&
            g.box.width>0 && g.box.height>0 } &&
        p.glyphs.indices.all { i -> (i+1 until p.glyphs.size).none { j ->
            val a=p.glyphs[i].box; val b=p.glyphs[j].box
            kotlin.math.abs(a.cx-b.cx)<=minOf(a.width,b.width)*.1f &&
                kotlin.math.abs(a.cy-b.cy)<=minOf(a.height,b.height)*.1f
        } }
}
