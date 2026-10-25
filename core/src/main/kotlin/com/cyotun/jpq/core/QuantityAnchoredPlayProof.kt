package com.cyotun.jpq.core

/** A short new display needs directly observed cards and independent seat-count
 * evidence. Counts never supply a card identity or alter a previously accepted event. */
class QuantityAnchoredPlayProof {
    private data class SupersetKey(val round: String,val seat: Seat,val eventId: String)
    private data class Version(val round: String,val seat: Seat,val events: List<PlayEvent>,val remaining: Int,
        val phase: RoundPhase,val scene: TableScene,val supersededEventId: String?=null)
    private data class PreCountSample(val source: Long,val count: Int)
    data class PriorPresentation(val eventId: String,val cards: List<Card>,val glyphs: List<PlayGlyph>)
    private data class Candidate(val version: Version,val first: Long,val anchor: ObservedPlay,
        var latest: ObservedPlay,var lastFrame: Long,var lastVisible: Long,
        var visibleVotes: Int=1,var emptyVotes: Int=0,var strong: Boolean=false,var firstCount: Long?=null,var lastCount: Long?=null,
        var lastSource: Long?=null,var lastSourceCount: Int?=null,
        val preCounts: List<PreCountSample> = emptyList(),var strongRun: Int=0,var shortEligible: Boolean=false,
        var shortPostSource: Long?=null,val startedAt: Long=first)
    private var candidate: Candidate?=null
    private var lastTime=-1L
    private var delivered: Version?=null
    private var preCountVersion: Version?=null
    private val preCounts=mutableListOf<PreCountSample>()
    private var lastPreSource: Long?=null
    private var lastPreCount: Int?=null
    private var activeSuperset: SupersetKey?=null
    private var activeSupersetSince=-1L
    private var expiredSuperset: SupersetKey?=null

    fun clear() {
        clearCandidate()
        clearPreCounts()
        preCountVersion=null
    }

    private fun clearCandidate() { candidate=null }

    fun observe(frame: TableFrame,snapshot: RoundSnapshot,seat: Seat,clearBoundaryAt: Long?): ObservedPlay? =
        observe(frame,snapshot,seat,clearBoundaryAt,null,null)

    fun observe(frame: TableFrame,snapshot: RoundSnapshot,seat: Seat,clearBoundaryAt: Long?,
        prior: PriorPresentation?): ObservedPlay? = observe(frame,snapshot,seat,clearBoundaryAt,prior,null)

    /**
     * A physically new strict superset may be a new play even though the card
     * identities include the previous presentation. Require an exact fresh
     * badge decrement for the whole directly observed new group; ordinary
     * expansion decrements only by the added cards and therefore cannot pass.
     * expectedRemaining may be supplied from a suspended recovery checkpoint.
     */
    fun observeStrictSuperset(frame: TableFrame,snapshot: RoundSnapshot,seat: Seat,
        prior: PriorPresentation,expectedRemaining: Int?=null,phaseVersion: RoundPhase?=null): ObservedPlay? =
        observe(frame,snapshot,seat,null,prior,expectedRemaining,phaseVersion)

    private fun observe(frame: TableFrame,snapshot: RoundSnapshot,seat: Seat,clearBoundaryAt: Long?,
        prior: PriorPresentation?,expectedOverride: Int?,phaseVersion: RoundPhase?=null): ObservedPlay? {
        if(frame.timeMs<=lastTime) { clear(); clearPreCounts(); return null }
        lastTime=frame.timeMs
        val id=snapshot.roundId
        val expectedRemaining=expectedOverride ?: snapshot.remaining?.let { 27-snapshot.played(seat) }
        val phaseValid=snapshot.phase in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED) ||
            (expectedOverride!=null && snapshot.phase==RoundPhase.RECOVERING)
        if(id==null || seat==Seat.SELF || expectedRemaining==null || !phaseValid ||
            frame.scene !in setOf(TableScene.TABLE,TableScene.TEAMMATE) || frame.captureGap ||
            frame.ambiguousPresentation || frame.incompleteReason!=null || frame.localizedOverlaySeats.isNotEmpty()) {
            clear(); return null
        }
        val remaining=expectedRemaining
        if(remaining !in 1..27) { clear(); return null }
        val p=frame.plays[seat]
        val supersetBoundary=prior!=null && prior.eventId.isNotBlank() &&
            snapshot.plays[seat].orEmpty().lastOrNull()?.let { it.eventId==prior.eventId && it.cards==prior.cards }==true &&
            prior.cards.isNotEmpty() && prior.glyphs.size==prior.cards.size &&
            p!=null && p.regionObserved && valid(p) && p.cards.size>prior.cards.size && isSubset(prior.cards,p.cards) &&
            !containsTracked(p.glyphs,prior.glyphs)
        val supersetKey=if(supersetBoundary) SupersetKey(id,seat,prior.eventId) else null
        if(supersetKey!=null) {
            if(activeSuperset!=supersetKey) { activeSuperset=supersetKey; activeSupersetSince=frame.timeMs }
            if(expiredSuperset==supersetKey) return null
            if(frame.timeMs-activeSupersetSince>MAX_SUPERSET_LIFETIME_MS) {
                expiredSuperset=supersetKey; clearCandidate(); return null
            }
        }
        val versionEvents=if(supersetBoundary) snapshot.plays.values.flatten().sortedBy { it.sequence }
            else snapshot.plays[seat].orEmpty()
        val version=Version(id,seat,versionEvents.map { it.copy(cards=it.cards.toList()) },remaining,
            phaseVersion ?: snapshot.phase,frame.scene,if(supersetBoundary) prior.eventId else null)
        if(preCountVersion!=version) resetPreCounts(version)
        val boundary=clearBoundaryAt!=null || supersetBoundary
        if(candidate==null && !supersetBoundary) rememberPreCount(frame,snapshot,seat,remaining,version)
        if(!boundary) {
            if(isUncommittedPresentation(frame.plays[seat],snapshot,seat)) clearPreCounts()
            clearCandidate(); return null
        }
        if(version==delivered) return null
        if(candidate?.version!=version) clearCandidate()
        if(p==null || !p.regionObserved || p.confidence !in .65f..1f) {
            if(isUncommittedPresentation(p,snapshot,seat)) clearPreCounts()
            clearCandidate(); return null
        }
        val empty=p.cards.isEmpty() && p.glyphs.isEmpty()
        var current=candidate
        if(current!=null && current.version.supersededEventId==null && frame.timeMs-current.startedAt>1000L) { clearCandidate(); return null }
        if(current!=null && frame.timeMs-current.lastFrame>500) { clearCandidate(); return null }
        if(!empty) {
            if(!valid(p) || p.cards.size>=remaining) { clearCandidate(); return null }
            if(current==null) {
                if(!supersetBoundary && (clearBoundaryAt==null || frame.timeMs-clearBoundaryAt !in 0L..1500L || clearBoundaryAt>=frame.timeMs)) return null
                val copy=copy(p)
                current=Candidate(version,frame.timeMs,copy,copy,frame.timeMs,frame.timeMs,strong=strong(p),
                    preCounts=preCounts.takeLast(2),strongRun=if(strong(p)) 1 else 0,
                    shortEligible=!supersetBoundary && strong(p))
                candidate=current
            } else {
                if(key(p.cards)!=key(current.anchor.cards) || !sameSlots(p,current.anchor) || !sameSlots(p,current.latest)) {
                    val restartShortCandidate=current.shortEligible && current.version.supersededEventId==null &&
                        !supersetBoundary && strong(p) && clearBoundaryAt!=null &&
                        frame.timeMs-clearBoundaryAt in 0L..1500L && clearBoundaryAt<frame.timeMs
                    val currentStartedAt=current.startedAt
                    clearCandidate()
                    if(!restartShortCandidate) { clearPreCounts(); return null }
                    val copy=copy(p)
                    current=Candidate(version,frame.timeMs,copy,copy,frame.timeMs,frame.timeMs,strong=true,
                        preCounts=preCounts.takeLast(2),strongRun=1,shortEligible=true,startedAt=currentStartedAt)
                    candidate=current
                } else {
                    current.latest=copy(p); current.lastVisible=frame.timeMs; current.visibleVotes++
                    current.strong=current.strong || strong(p)
                    if(strong(p)) current.strongRun++ else current.shortEligible=false
                    current.emptyVotes=0
                }
            }
        }
        current ?: return null
        current.lastFrame=frame.timeMs
        if(empty) { current.emptyVotes++; current.shortEligible=false }
        val reading=frame.remainingHandCounts[seat]
        if(reading!=null) {
            if(reading.sourceTimeMs<0 ||
                reading.sourceTimeMs>frame.timeMs ||
                (current.lastSource!=null && reading.sourceTimeMs<current.lastSource!!)) { clear(); return null }
            val sameSource=reading.sourceTimeMs==current.lastSource
            val sourceChangedCount=sameSource && current.lastSourceCount!=reading.count
            current.lastSource=reading.sourceTimeMs
            current.lastSourceCount=reading.count
            if(current.version.supersededEventId!=null) {
                // The old ledger count is a valid pre-play reading. A strict
                // superset only earns a new event from the exact full-group
                // decrement; an expansion-sized or other change resets votes.
                if(sourceChangedCount || (reading.count!=remaining && reading.count!=remaining-current.anchor.cards.size)) {
                    current.firstCount=null; current.lastCount=null
                } else if(reading.count==remaining) {
                    if(current.firstCount!=null) { current.firstCount=null; current.lastCount=null }
                } else if(reading.sourceTimeMs>=current.first && frame.timeMs-reading.sourceTimeMs<=500 && !sameSource) {
                    if(current.firstCount==null) current.firstCount=reading.sourceTimeMs
                    current.lastCount=reading.sourceTimeMs
                }
            } else {
                // An asynchronous pre-arrival badge is not a post-play vote,
                // even when its value happens to equal the expected decrement.
                if(reading.sourceTimeMs>=current.first) {
                    if(reading.count!=remaining-current.anchor.cards.size) { clear(); return null }
                    if(frame.timeMs-reading.sourceTimeMs<=1000) current.shortPostSource=reading.sourceTimeMs
                    if(frame.timeMs-reading.sourceTimeMs<=500) {
                        if(current.firstCount==null) current.firstCount=reading.sourceTimeMs
                        current.lastCount=reading.sourceTimeMs
                    }
                }
            }
        }
        val requiredVisibleVotes=if(current.version.supersededEventId!=null) 4 else 2
        val preSamples=current.preCounts
        val shortPreValid=current.shortEligible && current.version.supersededEventId==null && current.strongRun>=3 &&
            current.lastVisible-current.first>=100 && preSamples.size>=2 &&
            preSamples.last().source-preSamples.first().source>=100 &&
            current.first-preSamples.last().source in 0L..1500L &&
            preSamples.all { it.count==remaining && current.first-it.source in 0L..2200L } &&
            current.shortPostSource?.let { it !in preSamples.map(PreCountSample::source) &&
                it>=current.first && frame.timeMs-it<=1000 }==true
        if(shortPreValid && current.lastVisible==frame.timeMs && current.lastSourceCount==remaining-current.anchor.cards.size) {
            delivered=version
            clearCandidate()
            clearPreCounts()
            return copy(current.latest)
        }
        val firstCount=current.firstCount ?: return null
        val lastCount=current.lastCount ?: return null
        if(current.visibleVotes<requiredVisibleVotes || !current.strong || current.lastVisible-current.first<100 ||
            (empty && current.emptyVotes<2) ||
            lastCount-firstCount<100 || frame.timeMs-lastCount>500) return null
        delivered=version
        clearCandidate()
        clearPreCounts()
        return copy(current.latest)
    }

    private fun rememberPreCount(frame: TableFrame,snapshot: RoundSnapshot,seat: Seat,remaining: Int,version: Version) {
        val reading=frame.remainingHandCounts[seat] ?: return
        val p=frame.plays[seat]
        val empty=p?.let { it.regionObserved && it.cards.isEmpty() && it.glyphs.isEmpty() }==true
        val last=snapshot.plays[seat].orEmpty().lastOrNull()
        val showsLast=last!=null && p?.let { it.regionObserved && valid(it) && key(it.cards)==key(last.cards) }==true
        if(p==null || !p.regionObserved) { clearPreCounts(); preCountVersion=version; return }
        if(!empty && !showsLast) return
        if(reading.sourceTimeMs<0 || reading.sourceTimeMs>frame.timeMs || frame.timeMs-reading.sourceTimeMs>1500 || reading.count!=remaining) {
            clearPreCounts()
            preCountVersion=version
            return
        }
        val previous=lastPreSource
        if(previous!=null && reading.sourceTimeMs<previous) { clearPreCounts(); preCountVersion=version; return }
        if(reading.sourceTimeMs==previous) {
            if(lastPreCount!=reading.count) { clearPreCounts(); preCountVersion=version }
            return
        }
        preCounts+=PreCountSample(reading.sourceTimeMs,reading.count)
        while(preCounts.size>16) preCounts.removeAt(0)
        lastPreSource=reading.sourceTimeMs
        lastPreCount=reading.count
    }

    private fun resetPreCounts(version: Version) {
        clearPreCounts()
        preCountVersion=version
    }

    private fun clearPreCounts() {
        preCounts.clear()
        lastPreSource=null
        lastPreCount=null
    }

    private fun isUncommittedPresentation(p: ObservedPlay?,snapshot: RoundSnapshot,seat: Seat): Boolean {
        if(p==null || !p.regionObserved || p.cards.isEmpty()) return false
        val last=snapshot.plays[seat].orEmpty().lastOrNull() ?: return true
        return key(p.cards)!=key(last.cards)
    }

    private fun copy(p: ObservedPlay)=p.copy(cards=p.cards.toList(),glyphs=p.glyphs.toList())
    private fun key(cards: List<Card>)=cards.map { it.colorKey() }.sorted()
    private fun isSubset(a: List<Card>,b: List<Card>): Boolean {
        val rest=b.toMutableList(); return a.all { rest.remove(it) }
    }
    private fun containsTracked(current: List<PlayGlyph>,old: List<PlayGlyph>): Boolean {
        if(old.isEmpty()) return false
        val rest=current.toMutableList()
        return old.all { tracked ->
            val match=rest.indexOfFirst { next -> next.card.colorKey()==tracked.card.colorKey() &&
                kotlin.math.abs(next.box.cx-tracked.box.cx)<=minOf(next.box.width,tracked.box.width)*.35f &&
                kotlin.math.abs(next.box.cy-tracked.box.cy)<=minOf(next.box.height,tracked.box.height)*.35f &&
                maxOf(next.box.width,tracked.box.width)<=minOf(next.box.width,tracked.box.width)*1.4f &&
                maxOf(next.box.height,tracked.box.height)<=minOf(next.box.height,tracked.box.height)*1.4f
            }
            if(match<0) false else { rest.removeAt(match); true }
        }
    }
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

    private companion object { const val MAX_SUPERSET_LIFETIME_MS=2200L }
}
