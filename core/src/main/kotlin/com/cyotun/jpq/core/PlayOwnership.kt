package com.cyotun.jpq.core

data class GlyphBox(val left: Float,val top: Float,val right: Float,val bottom: Float) {
    val cx get()=(left+right)/2; val cy get()=(top+bottom)/2
    val width get()=right-left; val height get()=bottom-top
}
data class PlayGlyph(val card: Card,val confidence: Float,val box: GlyphBox)
data class OwnedPlays(val seats: Map<Seat,List<PlayGlyph>>,val ambiguous: List<PlayGlyph>)

/** A whole card group has one owner; never split a run at an arbitrary column. */
fun assignPlayGroups(glyphs: List<PlayGlyph>,passing: Set<Seat>,selfDifference: List<Card>): OwnedPlays {
    val seats=Seat.entries.associateWith { mutableListOf<PlayGlyph>() }
    val ambiguous=mutableListOf<PlayGlyph>()
    val remaining=glyphs.toMutableList()
    val width=glyphs.map { it.box.width }.average().toFloat()
    val height=glyphs.map { it.box.height }.average().toFloat()
    while(remaining.isNotEmpty()) {
        val group=mutableListOf(remaining.removeAt(0))
        var changed=true
        while(changed) {
            val connected=remaining.filter { p -> group.any { q ->
                kotlin.math.abs(p.box.cy-q.box.cy)<height*.8f && kotlin.math.abs(p.box.cx-q.box.cx)<width*4
            } }
            changed=connected.isNotEmpty(); group.addAll(connected); remaining.removeAll(connected.toSet())
        }
        val cy=group.map { it.box.cy }.average()
        fun key(cards: List<Card>)=cards.map { it.colorKey() }.sorted()
        val owner=when {
            group.maxOf { it.box.cy }<.30f -> Seat.OPPOSITE
            group.minOf { it.box.left }<.32f -> Seat.PREVIOUS
            group.maxOf { it.box.right }>.70f -> Seat.NEXT
            cy>.42 && selfDifference.isNotEmpty() && key(selfDifference)==key(group.map { it.card }) -> Seat.SELF
            passing.size==2 -> listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).singleOrNull { it !in passing }
            else -> null
        }
        if(owner==null) ambiguous.addAll(group) else seats[owner]!!.addAll(group)
    }
    return OwnedPlays(seats,ambiguous)
}
