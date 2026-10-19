package com.cyotun.jpq.core

/** A current, unmasked corner-symbol reading, separate from the rank/color detector. */
data class SingleCardIdentity(val card: Card,val confidence: Float,val margin: Float,val sourceTimeMs: Long)

/**
 * Distinguishes consecutive same-color single plays (for example A-club then
 * A-spade) when the intervening table-clear animation is not observable.
 * Both the old and new physical face need three stable, independent readings.
 * No card is inferred from an empty region or a remaining-count badge.
 */
class RepeatedSingleCardProof {
    private data class Version(val roundId: String,val eventId: String,val color: String)
    private var version: Version?=null
    private var accepted: Card?=null
    private var acceptedAt=-1L
    private var candidate: Card?=null
    private var candidateBox: GlyphBox?=null
    private var candidateVotes=0
    private var candidateFirst=-1L
    private var candidateLast=-1L
    private var lastFrame=-1L
    private var lastSource=-1L
    private var delivered=false

    fun clear() {
        version=null; accepted=null; acceptedAt=-1; delivered=false
        clearCandidate()
        // A cleared candidate must not acquire an earlier image as new evidence.
    }
    fun unobserved(time: Long) {
        clearCandidate()
        if(time-acceptedAt>2000) { accepted=null; acceptedAt=-1 }
    }
    private fun clearCandidate() {
        candidate=null; candidateBox=null; candidateVotes=0; candidateFirst=-1; candidateLast=-1
    }

    fun observe(time: Long,roundId: String?,eventId: String?,expectedRemaining: Int,
                play: ObservedPlay,remaining: RemainingHandCount?): Boolean {
        if(time<=lastFrame) { clearCandidate(); return false }
        lastFrame=time
        val card=play.cards.singleOrNull()
        if(roundId.isNullOrBlank() || eventId.isNullOrBlank() || card==null || card.rank.isJoker ||
            expectedRemaining !in 1..27) { clear(); return false }
        val next=Version(roundId,eventId,card.colorKey())
        if(next!=version) { clear(); version=next }
        if(delivered) return false
        val identity=play.singleCardIdentity
        val glyph=play.glyphs.singleOrNull()
        val box=glyph?.box
        if(!play.regionObserved || play.confidence<.80f || glyph==null || glyph.card!=card || box==null ||
            listOf(box.left,box.top,box.right,box.bottom).any { !it.isFinite() || it !in 0f..1f } ||
            box.width<=0 || box.height<=0 || identity==null ||
            identity.card.suit !in setOf(Suit.SPADE,Suit.CLUB,Suit.HEART,Suit.DIAMOND) ||
            identity.card.colorKey()!=card.colorKey() || !identity.confidence.isFinite() || !identity.margin.isFinite() ||
            identity.confidence<.80f || identity.margin<.12f ||
            identity.sourceTimeMs>time || time-identity.sourceTimeMs !in 0L..500L ||
            identity.sourceTimeMs<=lastSource) {
            unobserved(time); return false
        }
        lastSource=identity.sourceTimeMs
        if(accepted!=null && time-acceptedAt>2000) { accepted=null; clearCandidate() }
        val physical=identity.card
        if(physical==accepted) { acceptedAt=time; clearCandidate(); return false }
        if(candidate!=physical || candidateLast<0 || identity.sourceTimeMs-candidateLast>500 ||
            !sameBox(box,candidateBox)) {
            candidate=physical; candidateBox=box; candidateVotes=1
            candidateFirst=identity.sourceTimeMs; candidateLast=identity.sourceTimeMs
            return false
        }
        if(identity.sourceTimeMs-candidateLast<100) return false
        candidateVotes++; candidateLast=identity.sourceTimeMs; candidateBox=box
        if(candidateVotes<3 || candidateLast-candidateFirst<500) return false
        if(accepted==null) {
            accepted=physical; acceptedAt=time; clearCandidate(); return false
        }
        // A fresh unchanged badge contradicts a second play; wait for agreement.
        if(remaining!=null && remaining.sourceTimeMs<=time && time-remaining.sourceTimeMs in 0L..1000L &&
            remaining.count!=expectedRemaining-1) return false
        delivered=true
        return true
    }

    private fun sameBox(a: GlyphBox,b: GlyphBox?)=b!=null &&
        kotlin.math.abs(a.cx-b.cx)<=minOf(a.width,b.width)*.35f &&
        kotlin.math.abs(a.cy-b.cy)<=minOf(a.height,b.height)*.35f &&
        maxOf(a.width,b.width)<=minOf(a.width,b.width)*1.4f &&
        maxOf(a.height,b.height)<=minOf(a.height,b.height)*1.4f
}
