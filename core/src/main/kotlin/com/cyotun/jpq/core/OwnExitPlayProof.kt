package com.cyotun.jpq.core

/** Records a directly seen last own play before teammate view clears the own hand.
 * A completion label alone never supplies cards or changes an outside count. */
class OwnExitPlayProof {
    data class Certificate internal constructor(
        val roundId: String,
        val originalHand: List<Card>,
        val ownEvents: List<PlayEvent>,
        val cards: List<Card>
    )
    private data class Version(val round: String,val handKey: String,val hand: List<Card>,val ownEvents: List<PlayEvent>)
    private var version: Version?=null
    private var anchor: ObservedPlay?=null
    private var previous: ObservedPlay?=null
    private var firstTime=-1L
    private var lastTime=-1L
    private var votes=0
    private var ownSeenAt=-1L
    private var lastObservationTime=-1L

    fun reset() { version=null; ownSeenAt=-1L; lastObservationTime=-1L; clearVotes() }
    private fun clearVotes() { anchor=null; previous=null; firstTime=-1L; lastTime=-1L; votes=0 }

    fun observe(frame: TableFrame,snapshot: RoundSnapshot,knownOwnHand: List<Card>): List<Card>? =
        observeWithCertificate(frame,snapshot,knownOwnHand)?.cards

    /**
     * A certificate can continue only when a direct, exact-own-hand candidate
     * was already anchored while ACTIVE. SELF_FINISHED cannot seed a candidate.
     */
    fun observeWithCertificate(frame: TableFrame,snapshot: RoundSnapshot,knownOwnHand: List<Card>): Certificate? {
        val id=snapshot.roundId
        if(id==null || snapshot.remaining==null) { reset(); return null }
        val active=snapshot.phase==RoundPhase.ACTIVE
        val latched=snapshot.phase==RoundPhase.SELF_FINISHED && version!=null && anchor!=null && votes>0 &&
            version?.round==id && knownOwnHand.isEmpty() &&
            version?.ownEvents==snapshot.plays[Seat.SELF].orEmpty()
        if(!active && !latched) { reset(); return null }
        val originalHand=if(active) knownOwnHand else version!!.hand
        if(originalHand.isEmpty() || snapshot.played(Seat.SELF)+originalHand.size!=27) { reset(); return null }
        if(active) {
            val next=Version(id,key(originalHand),originalHand.toList(),snapshot.plays[Seat.SELF].orEmpty().map { it.copy(cards=it.cards.toList()) })
            if(version!=next) { reset(); version=next }
        }
        if(frame.timeMs<=lastObservationTime) return null
        lastObservationTime=frame.timeMs
        if(frame.captureGap || frame.incompleteReason!=null || frame.ambiguousPresentation ||
            frame.scene !in setOf(TableScene.TABLE,TableScene.TEAMMATE)) {
            if(latched) reset() else { ownSeenAt=-1L; clearVotes() }
            return null
        }
        val frameHand=if(frame.scene==TableScene.TEAMMATE)
            frame.teammateHand.takeIf { frame.teammateHandConfidence>=.65f } else frame.hand
        if(active && frame.scene==TableScene.TABLE && frameHand!=null && key(frameHand)==key(originalHand)) {
            ownSeenAt=frame.timeMs; clearVotes(); return null
        }
        val rest=originalHand.toMutableList()
        // Teammate cards can replace the lower hand before its scene label arrives.
        // Null, a partial old hand, and a returned old hand cannot prove departure.
        val departed=frameHand!=null && (frameHand.isEmpty() || !frameHand.all { rest.remove(it) })
        val play=frame.plays[Seat.SELF]
        if(!departed || play==null || !strong(play) || key(play.cards)!=key(originalHand)) {
            if(latched) reset() else clearVotes()
            return null
        }
        val start=anchor
        if(start==null || frame.timeMs-lastTime>500L || frame.timeMs-firstTime>1500L ||
            !sameSlots(play,start) || !sameSlots(play,previous!!)) {
            if(latched) { reset(); return null }
            clearVotes()
            if(ownSeenAt<0 || frame.timeMs-ownSeenAt !in 0L..500L || frame.scene!=TableScene.TABLE) return null
            anchor=play.copy(cards=play.cards.toList(),glyphs=play.glyphs.toList())
            firstTime=frame.timeMs
        }
        previous=play.copy(cards=play.cards.toList(),glyphs=play.glyphs.toList())
        lastTime=frame.timeMs
        // Each eligible frame is direct evidence, including a complete
        // terminal-frame display. The terminal cue still cannot supply cards.
        votes++
        if(!frame.selfFinished && frame.scene!=TableScene.TEAMMATE) return null
        // With our teammate already independently out, a positive own-finish
        // cue ends the round immediately. The single last owned card still
        // needs three complete, strong, same-slot direct reads before that cue.
        val terminalSingle=votes>=3 && originalHand.size==1 && snapshot.played(Seat.OPPOSITE)==27 && frame.selfFinished
        if((votes<4 && !terminalSingle) || frame.timeMs-firstTime<100L ||
            (frame.scene!=TableScene.TEAMMATE && !frame.selfFinished)) return null
        // Return ledger-owned identities after matching every directly visible card.
        // The immutable initial hand already excludes these from outside remaining.
        val cards=version!!.hand.toList()
        val result=Certificate(version!!.round,cards,version!!.ownEvents.map { it.copy(cards=it.cards.toList()) },cards.toList())
        clearVotes()
        return result
    }

    private fun key(cards: List<Card>)=cards.map { it.colorKey() }.sorted().joinToString(",")
    private fun valid(box: GlyphBox)=listOf(box.left,box.top,box.right,box.bottom).all { it.isFinite() && it in 0f..1f } &&
        box.width>0f && box.height>0f
    private fun strong(play: ObservedPlay)=play.regionObserved && play.confidence in .80f..1f && play.cards.isNotEmpty() &&
        play.cards.size==play.glyphs.size && key(play.cards)==key(play.glyphs.map { it.card }) &&
        play.glyphs.all { it.confidence in .80f..1f && valid(it.box) } &&
        play.glyphs.indices.all { i -> (i+1 until play.glyphs.size).none { j ->
            val a=play.glyphs[i].box; val b=play.glyphs[j].box
            // Reject two detections of the same print, while preserving real
            // partly overlapping/stacked card ranks as separate physical slots.
            kotlin.math.abs(a.cx-b.cx)<=minOf(a.width,b.width)*.1f &&
                kotlin.math.abs(a.cy-b.cy)<=minOf(a.height,b.height)*.1f
        } }
    private fun sameSlots(current: ObservedPlay,old: ObservedPlay): Boolean {
        val remaining=old.glyphs.toMutableList()
        return current.glyphs.all { glyph ->
            val index=remaining.indexOfFirst { tracked -> glyph.card.colorKey()==tracked.card.colorKey() &&
                kotlin.math.abs(glyph.box.cx-tracked.box.cx)<=minOf(glyph.box.width,tracked.box.width)*.35f &&
                kotlin.math.abs(glyph.box.cy-tracked.box.cy)<=minOf(glyph.box.height,tracked.box.height)*.35f &&
                maxOf(glyph.box.width,tracked.box.width)<=minOf(glyph.box.width,tracked.box.width)*1.4f &&
                maxOf(glyph.box.height,tracked.box.height)<=minOf(glyph.box.height,tracked.box.height)*1.4f }
            if(index<0) false else { remaining.removeAt(index); true }
        } && remaining.isEmpty()
    }
}
