package com.cyotun.jpq.core

data class GlyphBox(val left: Float,val top: Float,val right: Float,val bottom: Float) {
    val cx get()=(left+right)/2; val cy get()=(top+bottom)/2
    val width get()=right-left; val height get()=bottom-top
}
data class PlayGlyph(val card: Card,val confidence: Float,val box: GlyphBox)
data class OwnedPlays(val seats: Map<Seat,List<PlayGlyph>>,val ambiguous: List<PlayGlyph>)

/** A whole card group has one owner; never split a run at an arbitrary column. */
fun assignPlayGroups(glyphs: List<PlayGlyph>,passing: Set<Seat>,selfDifference: List<Card>,teammateView: Boolean=false,
                     ownExitPresentation: List<PlayGlyph> = emptyList(),finishedSeats: Set<Seat> = emptySet(),
                     ownExitUnresolved: Boolean = false): OwnedPlays {
    val seats=Seat.entries.associateWith { mutableListOf<PlayGlyph>() }
    val groupsBySeat=Seat.entries.associateWith { mutableListOf<List<PlayGlyph>>() }
    val ambiguous=mutableListOf<PlayGlyph>()
    val eligiblePlayers=listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).filter { it !in passing && it !in finishedSeats }
    val remaining=glyphs.toMutableList()
    val widths=glyphs.map { it.box.width }.sorted()
    val heights=glyphs.map { it.box.height }.sorted()
    val width=widths.getOrNull(widths.size/2) ?: 0f
    val height=heights.getOrNull(heights.size/2) ?: 0f
    while(remaining.isNotEmpty()) {
        val group=mutableListOf(remaining.removeAt(0))
        var changed=true
        while(changed) {
            val connected=remaining.filter { p -> group.any { q ->
                kotlin.math.abs(p.box.top-q.box.top)<height*.5f && kotlin.math.abs(p.box.cx-q.box.cx)<width*2.5f
            } }
            changed=connected.isNotEmpty(); group.addAll(connected); remaining.removeAll(connected.toSet())
        }
        val cy=group.map { it.box.cy }.average()
        fun key(cards: List<Card>)=cards.map { it.colorKey() }.sorted()
        val exitMatches=(if(group.size>=ownExitPresentation.size) group else ownExitPresentation).toMutableList()
        val exitTracked=if(group.size>=ownExitPresentation.size) ownExitPresentation else group
        val ownExitGeometry=ownExitPresentation.isNotEmpty() && exitTracked.isNotEmpty() &&
            exitTracked.all { old -> val match=exitMatches.indexOfFirst { p ->
                kotlin.math.abs(p.box.cx-old.box.cx)<=minOf(p.box.width,old.box.width)*.35f &&
                kotlin.math.abs(p.box.cy-old.box.cy)<=minOf(p.box.height,old.box.height)*.35f &&
                maxOf(p.box.width,old.box.width)<=minOf(p.box.width,old.box.width)*1.4f &&
                maxOf(p.box.height,old.box.height)<=minOf(p.box.height,old.box.height)*1.4f
            }; if(match<0) false else { exitMatches.removeAt(match); true } }
        val owner=when {
            group.minOf { it.box.left }<.32f && cy<.48 -> Seat.PREVIOUS
            group.maxOf { it.box.right }>.70f && cy<.48 -> Seat.NEXT
            cy<.285 && group.minOf { it.box.cx }>.32f && group.maxOf { it.box.cx }<.70f -> Seat.OPPOSITE
            // Even an incomplete own-exit anchor reserves this physical row.
            // A new glyph must first be verified against the complete own hand
            // by OwnExitPresentationTracker; unique-opponent fallback must never
            // steal the pending group while that verification is in flight.
            teammateView && ownExitGeometry -> if(key(group.map { it.card })==key(ownExitPresentation.map { it.card })) Seat.SELF else null
            cy in .285.. .48 && !teammateView -> Seat.SELF
            // Expiring a geometric proof does not turn an unresolved own exit
            // into evidence for the only eligible opponent. A clean boundary
            // must retire that reservation before this fallback can be used.
            teammateView && ownExitUnresolved && cy in .285.. .48 -> null
            // A completed opponent no longer displays a pass label. In teammate
            // view one visible pass plus that positive completion can identify
            // the only player still eligible to own a central presentation.
            // Overlapping pass/finish evidence excludes a player only once.
            teammateView && eligiblePlayers.size==1 -> eligiblePlayers.single()
            selfDifference.isNotEmpty() && key(selfDifference)==key(group.map { it.card }) -> Seat.SELF
            else -> null
        }
        if(owner==null) ambiguous.addAll(group) else groupsBySeat[owner]!!.add(group)
    }
    for((seat,groups) in groupsBySeat) {
        if(groups.size==1) seats[seat]!!.addAll(groups.single())
        else if(groups.size>1) ambiguous.addAll(groups.flatten())
    }
    return OwnedPlays(seats,ambiguous)
}
