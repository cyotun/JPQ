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
    var frontier=hand.toList()
    while(frontier.isNotEmpty()) {
        val found=rest.filter { p -> frontier.any { q ->
            val dy=q.box.top-p.box.bottom
            val overlap=if(p.card.rank.isJoker || q.card.rank.isJoker) height*.75f else height*.2f
            // Actual stacked/raised rows have a short glyph gap. The larger former
            // gap could bridge a separate central presentation into the own-hand chain.
            p.box.cy<q.box.cy && dy>=-overlap && dy<height*.6f && kotlin.math.abs(p.box.cx-q.box.cx)<width*.65f
        } }
        hand.addAll(found); rest.removeAll(found.toSet()); frontier=found
    }
    return HandPartition(hand.sortedWith(compareBy({ it.box.cx },{ it.box.cy })),rest)
}
