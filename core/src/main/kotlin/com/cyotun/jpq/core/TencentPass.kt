package com.cyotun.jpq.core

/** Only seat pass bubbles. The player's central action button and nicknames are excluded. */
fun tencentPassSeats(hints: List<SceneHint>): Set<Seat> = hints.mapNotNull { hint ->
    // The game's outlined 出 is consistently read as 础 on reviewed source
    // images. Accept only that exact two-character variant in the bubble ROI.
    if(hint.text.filterNot(Char::isWhitespace) !in setOf("不出","不要","不础")) return@mapNotNull null
    val b=hint.box ?: return@mapNotNull null
    if(!listOf(b.left,b.top,b.right,b.bottom).all { it.isFinite() && it in 0f..1f } ||
        b.width !in .035f.. .16f || b.height !in .025f.. .11f) return@mapNotNull null
    when {
        b.cx in .42f.. .58f && b.cy in .13f.. .43f -> Seat.OPPOSITE
        b.cx in .14f.. .28f && b.cy in .27f.. .43f -> Seat.PREVIOUS
        b.cx in .72f.. .86f && b.cy in .27f.. .43f -> Seat.NEXT
        else -> null
    }
}.toSet()
