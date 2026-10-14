package com.cyotun.jpq.core

data class HandPartition(val hand: List<PlayGlyph>,val table: List<PlayGlyph>)
/** Includes raised selected cards; joker glyphs may overlap vertically. */
fun partitionGuandanHand(glyphs: List<PlayGlyph>): HandPartition {
    if(glyphs.isEmpty()) return HandPartition(emptyList(),emptyList())
    val hand=glyphs.filter { it.box.cy>=.58f && it.box.cy<.9f }.toMutableList()
    val rest=glyphs.filter { it !in hand }.toMutableList()
    val widths=glyphs.map { it.box.width }.sorted()
    val heights=glyphs.map { it.box.height }.sorted()
    val width=widths[widths.size/2]; val height=heights[heights.size/2]
    val ordinaryHandHeights=hand.filterNot { it.card.rank.isJoker }.map { it.box.height }.sorted()
    val handGlyphHeight=ordinaryHandHeights.getOrNull(ordinaryHandHeights.size/2)
    var frontier=hand.toList()
    while(frontier.isNotEmpty()) {
        val found=rest.filter { p -> frontier.any { q ->
            val dy=q.box.top-p.box.bottom
            val overlap=if(p.card.rank.isJoker || q.card.rank.isJoker) height*.75f else height*.2f
            // A tall stack can approach the central play row. The central card
            // print is smaller than hand print even when their vertical gap is
            // short; it cannot join a hand column merely by sharing its x.
            val sameHandScale=p.box.cy>=.4f || p.card.rank.isJoker || handGlyphHeight==null ||
                p.box.height>=handGlyphHeight*.84f
            // Actual stacked/raised rows have a short glyph gap. The larger former
            // gap could bridge a separate central presentation into the own-hand chain.
            sameHandScale && p.box.cy<q.box.cy && dy>=-overlap && dy<height*.6f && kotlin.math.abs(p.box.cx-q.box.cx)<width*.65f
        } }
        hand.addAll(found); rest.removeAll(found.toSet()); frontier=found
    }
    return HandPartition(hand.sortedWith(compareBy({ it.box.cx },{ it.box.cy })),rest)
}
