package com.cyotun.jpq.core

data class RemainingHandCount(val count: Int,val sourceTimeMs: Long)

private val remainingCountPattern=Regex("^剩([0-9]{1,2})张$")

/** Parse only the three opponents' small remaining-card badges; caller owns freshness policy. */
fun tencentRemainingHandCounts(
    hints: List<SceneHint>,
    sourceTimeMs: Long,
    masks: List<GlyphBox> = emptyList()
): Map<Seat,RemainingHandCount> {
    fun validBox(box: GlyphBox): Boolean =
        listOf(box.left,box.top,box.right,box.bottom).all { it.isFinite() } &&
            box.left>=0f && box.top>=0f && box.right<=1f && box.bottom<=1f &&
            box.width>0f && box.height>0f

    // An invalid mask makes occlusion unknowable, so conservatively reject all evidence.
    if(masks.any { !validBox(it) }) return emptyMap()

    val counts=Seat.entries.filter { it!=Seat.SELF }.associateWith { linkedSetOf<Int>() }
    for(hint in hints) {
        val match=remainingCountPattern.matchEntire(hint.text) ?: continue
        val count=match.groupValues[1].toIntOrNull()?.takeIf { it in 1..27 } ?: continue
        val box=hint.box ?: continue
        if(!validBox(box) || box.width !in .025f.. .16f || box.height !in .018f.. .075f) continue
        if(masks.any { it.left<box.right && box.left<it.right && it.top<box.bottom && box.top<it.bottom }) continue
        val seat=when {
            box.cx in .055f.. .18f && box.cy in .34f.. .44f -> Seat.PREVIOUS
            box.cx in .82f.. .945f && box.cy in .34f.. .44f -> Seat.NEXT
            box.cx in .40f.. .62f && box.cy in .10f.. .17f -> Seat.OPPOSITE
            else -> null
        } ?: continue
        counts[seat]!!.add(count)
    }

    return counts.mapNotNull { (seat,values) ->
        if(values.size!=1) null else seat to RemainingHandCount(values.single(),sourceTimeMs)
    }.toMap()
}
