package com.cyotun.jpq.core

/**
 * A positioned first/second-place cue can confirm that an already visible,
 * exact final hand was the player's last play. Recent full reads may precede
 * the cue briefly; the cue never supplies cards or changes the stored group.
 */
class EarlyFinishPlayProof {
    private data class Version(val roundId: String,val seat: Seat,val events: List<String>,val remaining: Int)
    private data class Candidate(val version: Version,val firstTime: Long,val cueMustFollowFirst: Boolean,
        var lastTime: Long,var votes: Int,val anchor: ObservedPlay,var latest: ObservedPlay)

    private var lastTime=-1L
    private var candidate: Candidate?=null
    private var delivered: Version?=null
    private var sourceHighWater=-1L

    fun clear() { candidate=null; lastTime=-1L }

    /** completionSourceTimeMs must come from the qualified OCR source adapter. */
    fun observe(frame: TableFrame,snapshot: RoundSnapshot,seat: Seat,
        completionSourceTimeMs: Long?): ObservedPlay? {
        if(frame.timeMs<=lastTime) {
            if(frame.timeMs<lastTime) candidate=null
            return null
        }
        lastTime=frame.timeMs
        val roundId=snapshot.roundId
        val remaining=27-snapshot.played(seat)
        if(roundId==null || snapshot.remaining==null || seat==Seat.SELF || snapshot.phase !in setOf(RoundPhase.ACTIVE,RoundPhase.SELF_FINISHED) ||
            frame.scene !in setOf(TableScene.TABLE,TableScene.TEAMMATE) ||
            frame.captureGap || frame.ambiguousPresentation || frame.incompleteReason!=null || frame.localizedOverlaySeats.isNotEmpty() ||
            remaining !in 1..27 || completionSourceTimeMs?.let {
                it<0 || frame.timeMs-it !in 0L..MAX_SOURCE_AGE_MS
            }==true) {
            candidate=null
            return null
        }
        val version=Version(roundId,seat,snapshot.plays[seat].orEmpty().map {
            "${it.eventId}:${it.sequence}:${cardKey(it.cards)}"
        },remaining)
        if(version==delivered) { candidate=null; return null }
        val play=frame.plays[seat]
        val current=candidate
        if(completionSourceTimeMs!=null) {
            if(completionSourceTimeMs<sourceHighWater) { candidate=null; return null }
            sourceHighWater=completionSourceTimeMs
        }
        val full=play?.let { isStrongExact(it,remaining) }==true
        val partialCue=completionSourceTimeMs!=null && play!=null && isQualifiedPartial(play)
        if(!full && !partialCue) { candidate=null; return null }
        if(current==null || current.version!=version ||
            frame.timeMs-current.lastTime !in 1L..MAX_CANDIDATE_GAP_MS ||
            frame.timeMs-current.firstTime>MAX_CANDIDATE_LIFETIME_MS ||
            (full && (!sameSlots(play!!,current.anchor) || !sameSlots(play!!,current.latest) || cardKey(play!!.cards)!=cardKey(current.anchor.cards))) ||
            (partialCue && !sameSlotSubset(play!!,current.anchor))) {
            if(full) candidate=Candidate(version,frame.timeMs,completionSourceTimeMs==null,frame.timeMs,1,copy(play!!),copy(play!!))
            else candidate=null
            return null
        }
        if(full) {
            current.votes++
            current.lastTime=frame.timeMs
            current.latest=copy(play!!)
        }
        if(current.votes<REQUIRED_VOTES || current.lastTime-current.firstTime<MIN_VOTE_SPAN_MS) return null
        if(completionSourceTimeMs==null || (current.cueMustFollowFirst && completionSourceTimeMs<current.firstTime)) return null
        delivered=version
        candidate=null
        return copy(current.latest)
    }

    private fun isStrongExact(play: ObservedPlay,remaining: Int): Boolean =
        play.regionObserved && play.confidence in MIN_CONFIDENCE..1f && play.cards.size==remaining &&
            play.cards.size==play.glyphs.size && cardKey(play.cards)==cardKey(play.glyphs.map { it.card }) &&
            play.glyphs.all { it.confidence in MIN_CONFIDENCE..1f && validBox(it.box) } &&
            play.glyphs.indices.all { i -> (i+1 until play.glyphs.size).none { j ->
                val a=play.glyphs[i].box; val b=play.glyphs[j].box
                kotlin.math.abs(a.cx-b.cx)<=minOf(a.width,b.width)*.1f &&
                    kotlin.math.abs(a.cy-b.cy)<=minOf(a.height,b.height)*.1f
            } }

    private fun sameSlots(left: ObservedPlay,right: ObservedPlay): Boolean {
        if(left.cards.size!=right.cards.size || cardKey(left.cards)!=cardKey(right.cards)) return false
        val remaining=right.glyphs.toMutableList()
        return left.glyphs.all { glyph ->
            val index=remaining.indexOfFirst { old -> glyph.card.colorKey()==old.card.colorKey() &&
                kotlin.math.abs(glyph.box.cx-old.box.cx)<=minOf(glyph.box.width,old.box.width)*SLOT_CENTER_FRACTION &&
                kotlin.math.abs(glyph.box.cy-old.box.cy)<=minOf(glyph.box.height,old.box.height)*SLOT_CENTER_FRACTION &&
                maxOf(glyph.box.width,old.box.width)<=minOf(glyph.box.width,old.box.width)*MAX_SLOT_SIZE_RATIO &&
                maxOf(glyph.box.height,old.box.height)<=minOf(glyph.box.height,old.box.height)*MAX_SLOT_SIZE_RATIO }
            if(index<0) false else { remaining.removeAt(index); true }
        } && remaining.isEmpty()
    }

    private fun isQualifiedPartial(play: ObservedPlay): Boolean =
        play.regionObserved && play.confidence in MIN_PARTIAL_CONFIDENCE..1f && play.cards.isNotEmpty() &&
            play.cards.size==play.glyphs.size && cardKey(play.cards)==cardKey(play.glyphs.map { it.card }) &&
            play.glyphs.all { it.confidence in MIN_PARTIAL_CONFIDENCE..1f && validBox(it.box) }

    private fun sameSlotSubset(partial: ObservedPlay,full: ObservedPlay): Boolean {
        val remaining=full.glyphs.toMutableList()
        return partial.glyphs.all { glyph ->
            val index=remaining.indexOfFirst { old -> glyph.card.colorKey()==old.card.colorKey() &&
                kotlin.math.abs(glyph.box.cx-old.box.cx)<=minOf(glyph.box.width,old.box.width)*SLOT_CENTER_FRACTION &&
                kotlin.math.abs(glyph.box.cy-old.box.cy)<=minOf(glyph.box.height,old.box.height)*SLOT_CENTER_FRACTION &&
                maxOf(glyph.box.width,old.box.width)<=minOf(glyph.box.width,old.box.width)*MAX_SLOT_SIZE_RATIO &&
                maxOf(glyph.box.height,old.box.height)<=minOf(glyph.box.height,old.box.height)*MAX_SLOT_SIZE_RATIO }
            if(index<0) false else { remaining.removeAt(index); true }
        }
    }

    private fun validBox(box: GlyphBox)=listOf(box.left,box.top,box.right,box.bottom).all { it.isFinite() && it in 0f..1f } &&
        box.width>0f && box.height>0f
    private fun cardKey(cards: List<Card>)=cards.map { it.colorKey() }.sorted().joinToString(",")
    private fun copy(play: ObservedPlay)=play.copy(cards=play.cards.toList(),glyphs=play.glyphs.toList())

    private companion object {
        const val MIN_CONFIDENCE=.80f
        const val REQUIRED_VOTES=2
        const val MIN_VOTE_SPAN_MS=100L
        const val MAX_SOURCE_AGE_MS=1000L
        const val MAX_CANDIDATE_GAP_MS=500L
        const val MAX_CANDIDATE_LIFETIME_MS=1500L
        const val MIN_PARTIAL_CONFIDENCE=.65f
        const val SLOT_CENTER_FRACTION=.35f
        const val MAX_SLOT_SIZE_RATIO=1.4f
    }
}
