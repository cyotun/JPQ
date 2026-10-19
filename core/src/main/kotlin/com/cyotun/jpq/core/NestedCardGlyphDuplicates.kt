package com.cyotun.jpq.core

import kotlin.math.abs

/** A weak, narrow fragment of the same printed rank can survive ordinary IoU NMS.
 * Keep separate stacked cards: proximity or overlap alone never removes a glyph. */
fun suppressNestedCardGlyphDuplicates(glyphs: List<PlayGlyph>): List<PlayGlyph> {
    fun valid(glyph: PlayGlyph): Boolean {
        val b=glyph.box
        return glyph.confidence.isFinite() && glyph.confidence in 0f..1f &&
            listOf(b.left,b.top,b.right,b.bottom).all { it.isFinite() } &&
            b.left>=0f && b.top>=0f && b.right<=1f && b.bottom<=1f && b.width>0f && b.height>0f
    }
    // Most frames have no weak rank fragments. Validate geometry once per
    // candidate rather than allocating box lists for every pair of real cards.
    val weak=glyphs.filter { !it.card.rank.isJoker && it.confidence<.65f && valid(it) }.toSet()
    if(weak.isEmpty()) return glyphs
    val strong=glyphs.filter { !it.card.rank.isJoker && it.confidence>=.80f && valid(it) }
    if(strong.isEmpty()) return glyphs
    fun duplicate(inner: PlayGlyph,outer: PlayGlyph): Boolean {
        if(inner.card!=outer.card || outer.confidence-inner.confidence<.25f) return false
        val a=inner.box; val b=outer.box
        // Exact containment is intentional: two partially overlapping card faces,
        // even with unequal scores, remain independent observations.
        if(a.left<b.left || a.top<b.top || a.right>b.right || a.bottom>b.bottom) return false
        return a.width<=b.width*.35f && a.height<=b.height*.75f &&
            a.width*a.height<=b.width*b.height*.25f &&
            abs(a.top-b.top)<=b.height*.05f &&
            abs(a.cx-b.cx)<=b.width*.35f && abs(a.cy-b.cy)<=b.height*.25f
    }
    // Strong outer glyphs cannot themselves meet the weak-inner score condition,
    // so order does not create suppression chains or choose between real copies.
    return glyphs.filter { inner -> inner !in weak || strong.none { outer -> duplicate(inner,outer) } }
}
