package com.cyotun.jpq.core

private fun validTencentMetadataBox(box: GlyphBox?): Boolean = box != null &&
    listOf(box.left, box.top, box.right, box.bottom).all { it.isFinite() } &&
    box.left >= 0f && box.top >= 0f && box.right <= 1f && box.bottom <= 1f &&
    box.width > 0f && box.height > 0f

/** Only the table's central room caption establishes a known preset. */
fun tencentWildcardMode(hints: List<SceneHint>): WildcardMode {
    val captions = hints.filter { hint ->
        val box = hint.box
        box != null && validTencentMetadataBox(box) && box.cx in .3f.. .7f &&
            box.cy in .36f.. .58f
    }.map { hint -> hint.text.filterNot { it.isWhitespace() } }
    // A named custom room is not proof that ordinary or dual-grade rules apply.
    if (captions.any { it.contains("私人") || it.contains("自定义") }) return WildcardMode.UNKNOWN
    if (captions.any { it.contains("双级") }) return WildcardMode.DOUBLE
    // Room tiers such as '经典初级场' do not distinguish a rule variant.
    return if (captions.any { it.contains("单级") || it == "经典玩法" })
        WildcardMode.SINGLE else WildcardMode.UNKNOWN
}

/**
 * Associate a standalone OCR '级' with one visible physical hand-card rank.
 *
 * Inputs use screen-normalized boxes. Horizontal distances are converted to
 * rank-height units using width / height; rank width is deliberately irrelevant
 * because a '10' does not move its card's corner marker. The caller must also
 * confirm green card-corner pixels and stabilize this evidence across frames.
 * This function never derives a grade from the two HUD team levels, previous
 * rounds, upgrading order, or assumptions about a round's random grade.
 */
fun tencentGradeMarkerRanks(hand: List<PlayGlyph>, hints: List<SceneHint>, aspectRatio: Float): Set<Rank> {
    if (!aspectRatio.isFinite() || aspectRatio <= 0f) return emptySet()
    val ranks = hand.filter { glyph ->
        val box = glyph.box
        !glyph.card.rank.isJoker && glyph.confidence.isFinite() && glyph.confidence in .65f..1f &&
            validTencentMetadataBox(box) && box.top >= .25f && box.cy <= .90f &&
            box.height in .012f.. .16f && box.width * aspectRatio / box.height in .12f..1.6f
    }
    val result = linkedSetOf<Rank>()
    for (hint in hints) {
        if (hint.text.trim() != "级" || !validTencentMetadataBox(hint.box)) continue
        val marker = hint.box!!
        val candidates = ranks.filter { glyph ->
            val rank = glyph.box
            val dx = (marker.cx - rank.left) * aspectRatio / rank.height
            val dy = (marker.cy - rank.top) / rank.height
            val markerHeight = marker.height / rank.height
            val markerWidth = marker.width * aspectRatio / rank.height
            val shape = marker.width * aspectRatio / marker.height
            dx in 1.5f..2.3f && dy in -.12f.. .70f &&
                markerHeight in .12f.. .90f && markerWidth in .12f.. .95f && shape in .45f..1.7f
        }
        // Even two detections of the same point are two competing physical-card
        // assignments. A repeated rank alone must not resolve that ambiguity.
        candidates.singleOrNull()?.let { result.add(it.card.rank) }
    }
    return result
}
