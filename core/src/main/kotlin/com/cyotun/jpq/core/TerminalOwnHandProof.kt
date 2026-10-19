package com.cyotun.jpq.core

/** Proves a last own play across a short own animation while every unfinished
 * outside region stays directly empty. It never resumes or mutates a ledger. */
class TerminalOwnHandProof {
    private data class Version(val roundId: String,val own: String,val events: List<PlayEvent>)
    private data class Evidence(val time: Long,val play: ObservedPlay)
    private var version: Version?=null
    private var anchor: Evidence?=null
    private var full: Evidence?=null
    private var lastTime: Long?=null
    private var blocked=false
    private var delivered=false

    fun reset() {
        version=null; anchor=null; full=null; lastTime=null; blocked=false; delivered=false
    }

    fun observe(frame: TableFrame,snapshot: RoundSnapshot,knownOwnHand: List<Card>,
                phaseBeforeRecovery: RoundPhase): ObservedPlay? {
        val id=snapshot.roundId ?: run { reset(); return null }
        val events=Seat.entries.flatMap { snapshot.plays[it].orEmpty() }.map { it.copy(cards=it.cards.toList()) }
        val next=Version(id,key(knownOwnHand),events)
        if(version!=next) {
            val hadVersion=version!=null
            anchor=null; full=null; lastTime=null; delivered=false; blocked=hadVersion; version=next
            if(hadVersion) return null
        }
        val outside=listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT)
        val finished=outside.filter { snapshot.played(it)==27 }
        val unfinished=outside.filter { snapshot.played(it)<27 }
        if(phaseBeforeRecovery!=RoundPhase.ACTIVE ||
            snapshot.phase !in setOf(RoundPhase.ACTIVE,RoundPhase.RECOVERING) ||
            finished.size!=2 || Seat.OPPOSITE !in finished || unfinished.size!=1 ||
            outside.any { snapshot.played(it) !in 0..27 } || !validHand(knownOwnHand) ||
            snapshot.played(Seat.SELF)+knownOwnHand.size!=27 || frame.captureGap ||
            frame.incompleteReason!=null || frame.ambiguousPresentation ||
            frame.scene !in setOf(TableScene.TABLE,TableScene.UNKNOWN)) return invalidate()
        val outsidePlay=frame.plays[unfinished.single()]
        if(outsidePlay==null || !outsidePlay.regionObserved || !(outsidePlay.confidence>=.65f) ||
            outsidePlay.cards.isNotEmpty() || outsidePlay.glyphs.isNotEmpty()) return invalidate()
        val previousTime=lastTime
        if(previousTime!=null && frame.timeMs<previousTime) return invalidate()
        if(previousTime!=null && frame.timeMs==previousTime) return null
        lastTime=frame.timeMs
        if(anchor!=null && previousTime!=null && frame.timeMs-previousTime>500L) return invalidate()
        // A returned complete hand disproves this exit. It can establish a new
        // observation boundary, but a suspended ledger still cannot start one.
        if(frame.scene==TableScene.TABLE && frame.hand?.let { key(it)==key(knownOwnHand) }==true) {
            anchor=null; full=null; blocked=false
            return null
        }
        if(frame.hand==null || frame.hand.isNotEmpty()) return invalidate()
        if(blocked || delivered) return null
        val current=frame.plays[Seat.SELF]
        val initial=anchor
        if(initial==null) {
            if(snapshot.phase!=RoundPhase.ACTIVE || snapshot.remaining?.values?.all { it>=0 }!=true || frame.scene!=TableScene.TABLE ||
                current==null || !strong(current) || key(current.cards)!=key(knownOwnHand)) return null
            anchor=Evidence(frame.timeMs,current.copy(cards=current.cards.toList(),glyphs=current.glyphs.toList()))
            return null
        }
        if(frame.timeMs-initial.time !in 0L..1500L) return invalidate()
        if(frame.scene==TableScene.UNKNOWN) {
            // The outside region must still have passed its direct empty check.
            // A scene gap supplies no own-card vote and cannot extend the anchor.
            if(current!=null && !withinAnchor(current,initial.play)) return invalidate()
            full=null
            return null
        }
        if(current==null || !current.regionObserved || current.cards.isEmpty() || !withinAnchor(current,initial.play)) return invalidate()
        // Missing old slots and colour effects abstain. No absent slot is filled,
        // and only a new, complete reading can contribute to the two final votes.
        if(!strong(current) || !sameSlots(current.glyphs,initial.play.glyphs,true)) {
            full=null
            return null
        }
        val earlier=full
        if(earlier==null || frame.timeMs-earlier.time !in 1L..500L ||
            !sameSlots(current.glyphs,earlier.play.glyphs,true)) {
            full=Evidence(frame.timeMs,current.copy(cards=current.cards.toList(),glyphs=current.glyphs.toList()))
            return null
        }
        if(frame.timeMs-earlier.time<100L || !frame.selfFinished) return null
        delivered=true
        return earlier.play.copy(cards=earlier.play.cards.toList(),glyphs=earlier.play.glyphs.toList())
    }

    private fun invalidate(): ObservedPlay? {
        anchor=null; full=null; blocked=true
        return null
    }

    private fun key(cards: List<Card>)=cards.map { it.colorKey() }.sorted().joinToString(",")
    private fun validHand(cards: List<Card>)=cards.isNotEmpty() && cards.size<=27 &&
        cards.groupingBy { it.rank }.eachCount().all { (r,n) -> n<=r.capacity } &&
        cards.groupingBy { it.colorKey() }.eachCount().all { (k,n) -> n<=if(':' in k) 4 else 2 }
    private fun validBox(b: GlyphBox)=listOf(b.left,b.top,b.right,b.bottom).all { it.isFinite() && it in 0f..1f } &&
        b.width>0f && b.height>0f
    private fun paired(p: ObservedPlay)=p.cards.size==p.glyphs.size &&
        p.cards.map { it.colorKey() }==p.glyphs.map { it.card.colorKey() } && p.glyphs.all { validBox(it.box) }
    private fun strong(p: ObservedPlay)=p.regionObserved && p.cards.isNotEmpty() && p.confidence>=.80f &&
        paired(p) && p.glyphs.all { it.confidence>=.80f }
    private fun withinAnchor(p: ObservedPlay,old: ObservedPlay)=paired(p) &&
        p.glyphs.size<=old.glyphs.size && sameSlots(p.glyphs,old.glyphs,false)
    /** A subset can only occupy the old physical slots, once each. */
    private fun sameSlots(current: List<PlayGlyph>,old: List<PlayGlyph>,colors: Boolean): Boolean {
        if(colors && current.size!=old.size) return false
        val remaining=old.toMutableList()
        return current.all { glyph ->
            val index=remaining.indexOfFirst { tracked ->
                (if(colors) glyph.card.colorKey()==tracked.card.colorKey() else glyph.card.rank==tracked.card.rank) &&
                    validBox(glyph.box) && validBox(tracked.box) &&
                    kotlin.math.abs(glyph.box.cx-tracked.box.cx)<=minOf(glyph.box.width,tracked.box.width)*.35f &&
                    kotlin.math.abs(glyph.box.cy-tracked.box.cy)<=minOf(glyph.box.height,tracked.box.height)*.35f &&
                    maxOf(glyph.box.width,tracked.box.width)<=minOf(glyph.box.width,tracked.box.width)*1.4f &&
                    maxOf(glyph.box.height,tracked.box.height)<=minOf(glyph.box.height,tracked.box.height)*1.4f
            }
            if(index<0) false else { remaining.removeAt(index); true }
        }
    }
}
