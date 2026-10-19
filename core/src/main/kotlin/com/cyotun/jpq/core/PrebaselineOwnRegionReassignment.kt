package com.cyotun.jpq.core

/** Retracts a temporary deal-animation ownership mistake, never a confirmed play. */
class PrebaselineOwnRegionReassignment {
    private data class Temporary(val glyphs: List<PlayGlyph>,val time: Long)
    private data class Vote(val hand: List<PlayGlyph>,val count: Int,val first: Long,val last: Long)
    private val temporary=mutableMapOf<Seat,Temporary>()
    private val votes=mutableMapOf<Seat,Vote>()
    private val retired=mutableSetOf<Seat>()
    private var eligible=true
    private var lastRound: String?=null

    /** Missing source metadata cannot establish a correction. A retired footprint
     * stays retired until a new nonempty presentation or explicit scene boundary. */
    fun step(scene: TableScene,handCount: Int,handGlyphs: List<PlayGlyph>,
        groups: Map<Seat,List<PlayGlyph>>,roundId: String?,masks: List<GlyphBox>,
        timeMs: Long?,ambiguous: Boolean=false): Set<Seat> {
        if(scene in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.TRIBUTE) ||
            // waitForNext() can retire a round before its result scene arrives.
            // The next deal must not inherit the old round's disabled correction.
            (lastRound!=null && lastRound!=roundId)) {
            temporary.clear(); votes.clear(); retired.clear(); eligible=true
        }
        if(roundId!=null) { eligible=false; temporary.clear(); votes.clear() }
        lastRound=roundId
        for(seat in Seat.entries) if(groups[seat].orEmpty().isNotEmpty()) {
            retired.remove(seat); votes.remove(seat)
            if(!eligible || roundId!=null || handCount !in 1..26) temporary.remove(seat)
        }
        val visible=scene in setOf(TableScene.TABLE,TableScene.DEALING) &&
            masks.all(PlayRegionVisibility::validBox) &&
            Seat.entries.none { PlayRegionVisibility.occluded(scene,it,groups[it].orEmpty(),masks) } &&
            guandanHandRegionObserved(handGlyphs,masks)
        if(!eligible || roundId!=null || !visible || timeMs==null ||
            handGlyphs.size!=handCount || handGlyphs.any { !PlayRegionVisibility.validBox(it.box) || it.confidence<.65f || !it.confidence.isFinite() }) {
            votes.clear(); return retired.toSet()
        }
        for((seat,glyphs) in groups) if(seat!=Seat.SELF && glyphs.isNotEmpty()) {
            if(handCount in 1..26 && glyphs.all { it.confidence>=.80f && PlayRegionVisibility.validBox(it.box) && it.box.cy in .40f.. .56f })
                temporary[seat]=Temporary(glyphs.toList(),timeMs)
            else temporary.remove(seat)
        }
        // This mirrors the guard's provisional footprint learning. A different
        // unowned animation glyph cannot erase a clearly assigned footprint's
        // source; ambiguity is still forbidden for every confirmation vote.
        if(ambiguous) { votes.clear(); return retired.toSet() }
        val outside=listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT)
        if(handCount!=27 || outside.any { groups[it]==null || groups.getValue(it).isNotEmpty() } || !validHand(handGlyphs)) {
            votes.clear(); return retired.toSet()
        }
        for((seat,source) in temporary.toMap()) {
            val matches=matching(handGlyphs,source.glyphs)
            val ownPrints=timeMs-source.time in 0L..2000L && matches!=null &&
                matches.all { it.confidence>=.80f && inHandColumn(it,handGlyphs) }
            if(!ownPrints) { votes.remove(seat); continue }
            val previous=votes[seat]
            val continuous=previous!=null && timeMs-previous.last in 1L..500L &&
                matching(handGlyphs,previous.hand)!=null
            val next=if(continuous) Vote(handGlyphs.toList(),previous.count+1,previous.first,timeMs)
                else Vote(handGlyphs.toList(),1,timeMs,timeMs)
            votes[seat]=next
            if(next.count>=3 && timeMs-next.first>=100L) {
                retired.add(seat); temporary.remove(seat); votes.remove(seat)
            }
        }
        return retired.toSet()
    }
    private fun validHand(hand: List<PlayGlyph>)=hand.groupingBy { it.card.rank }.eachCount().all { (rank,n) -> n<=rank.capacity } &&
        hand.groupingBy { it.card.colorKey() }.eachCount().all { (card,n) -> n<=if(':' in card) 4 else 2 }

    private fun matching(current: List<PlayGlyph>,old: List<PlayGlyph>): List<PlayGlyph>? {
        val available=current.toMutableList(); val result=mutableListOf<PlayGlyph>()
        for(p in old) {
            val i=available.indexOfFirst { q -> q.card==p.card && samePrint(q.box,p.box) }
            if(i<0) return null
            result.add(available.removeAt(i))
        }
        return result
    }
    private fun samePrint(a: GlyphBox,b: GlyphBox): Boolean {
        val intersection=(minOf(a.right,b.right)-maxOf(a.left,b.left)).coerceAtLeast(0f)*
            (minOf(a.bottom,b.bottom)-maxOf(a.top,b.top)).coerceAtLeast(0f)
        val union=a.width*a.height+b.width*b.height-intersection
        return union>0 && intersection/union>=.80f &&
            kotlin.math.abs(a.cx-b.cx)<=minOf(a.width,b.width)*.15f &&
            kotlin.math.abs(a.cy-b.cy)<=minOf(a.height,b.height)*.15f &&
            maxOf(a.width,b.width)<=minOf(a.width,b.width)*1.15f &&
            maxOf(a.height,b.height)<=minOf(a.height,b.height)*1.15f
    }
    /** A solitary table A near another A cannot supply this physical stack.
     * Require two separate hand-sized prints below the reassigned print, with
     * short ordinary stack gaps and a column reaching the normal hand band. */
    private fun inHandColumn(top: PlayGlyph,hand: List<PlayGlyph>): Boolean {
        var current=top
        var links=0
        for(next in hand.filter { it.box.cy>top.box.cy }.sortedBy { it.box.cy }) {
            val a=current.box; val b=next.box
            val gap=b.top-a.bottom
            if(kotlin.math.abs(a.cx-b.cx)>minOf(a.width,b.width)*.25f ||
                maxOf(a.width,b.width)>minOf(a.width,b.width)*1.25f ||
                maxOf(a.height,b.height)>minOf(a.height,b.height)*1.18f ||
                gap !in (-minOf(a.height,b.height)*.10f)..(minOf(a.height,b.height)*.35f)) continue
            links++; current=next
            if(links>=2 && current.box.cy>=.58f) return true
        }
        return false
    }
}
