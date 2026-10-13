package com.cyotun.jpq.core

data class SceneHint(val text: String,val box: GlyphBox?)

/** Completion persists through an OCR gap; new-round/result scenes keep their boundaries. */
fun continueTeammateScene(detected: TableScene,phase: RoundPhase)=
    if(phase==RoundPhase.SELF_FINISHED && detected==TableScene.TABLE) TableScene.TEAMMATE else detected

/** Only completed-player UI slots, never nickname lines or an unpositioned OCR word. */
private fun completionSeat(box: GlyphBox?): Seat? {
    val b=box ?: return null
    if(!listOf(b.left,b.top,b.right,b.bottom).all { it.isFinite() } || b.left<0 || b.top<0 || b.right>1 || b.bottom>1) return null
    val banner=b.height in .035f.. .16f && b.width in .025f.. .24f
    if(banner) {
        when {
            b.cx in .14f.. .36f && b.cy in .285f.. .46f -> return Seat.PREVIOUS
            b.cx in .32f.. .70f && b.cy in .13f.. .28f -> return Seat.OPPOSITE
            b.cx in .64f.. .86f && b.cy in .285f.. .46f -> return Seat.NEXT
            b.cx in .30f.. .70f && b.cy in .60f.. .84f -> return Seat.SELF
        }
    }
    val badge=b.height in .022f.. .065f && b.width in .02f.. .10f
    if(badge) {
        when {
            b.cx in .055f.. .18f && b.cy in .18f.. .275f -> return Seat.PREVIOUS
            b.cx in .82f.. .945f && b.cy in .18f.. .275f -> return Seat.NEXT
            b.cx in .055f.. .18f && b.cy in .74f.. .84f -> return Seat.SELF
        }
    }
    return null
}

private fun threeCompletedFinishers(hints: List<SceneHint>): Boolean {
    val ranks=listOf("头游","二游","三游")
    val seatsByRank=ranks.associateWith { linkedSetOf<Seat>() }
    for(hint in hints) {
        val rank=hint.text.replace(" ","")
        val seats=seatsByRank[rank] ?: continue
        val seat=completionSeat(hint.box) ?: continue
        seats.add(seat)
    }
    // A banner and badge on one player are one proof. Three copies on that same
    // player, conflicting rank assignments, and partial teammate-view ranks do not finish a round.
    if(seatsByRank.values.any { it.size!=1 }) return false
    return seatsByRank.values.map { it.single() }.toSet().size==3
}

fun tencentSelfFinished(hints: List<SceneHint>)=hints.any { hint ->
    val b=hint.box
    hint.text.replace(" ","") in setOf("头游","二游","三游","末游") && b!=null &&
        ((b.cx<.18f && b.cy in .68f.. .84f) || (b.cx in .3f.. .7f && b.cy in .60f.. .84f && b.height>=.035f))
}

/** Match table UI regions, including the last player's revealed unplayed hand. */
fun tencentScene(hints: List<SceneHint>,hasCards: Boolean,hasHandCards: Boolean): TableScene {
    val lines=hints.filter { (it.box?.cy ?: 0f)>.12f }
    val all=lines.joinToString(" ") { it.text.replace(" ","") }
    val lastPlace=lines.any { hint ->
        val b=hint.box
        hint.text.replace(" ","")=="末游" && b!=null &&
            ((b.height>=.035f && b.cy in .13f.. .45f) || (b.cx<.18f && b.cy in .68f.. .84f))
    }
    return when {
        lastPlace || threeCompletedFinishers(lines) -> TableScene.RESULT
        listOf("进贡","还贡","还牌","贡牌").any { all.contains(it) } -> TableScene.TRIBUTE
        lines.count { it.text.replace(" ","") in setOf("加倍","不加倍","超级加倍") }>=2 -> TableScene.DEALING
        all.contains("队友手牌") || all.contains("对家手牌") -> TableScene.TEAMMATE
        listOf("再来一局","继续游戏","本局结束","本局结算").any { all.contains(it) } -> TableScene.RESULT
        listOf("快速开始","私人房","经典","组队").count { all.contains(it) }>=3 && !hasHandCards -> TableScene.LOBBY
        hasCards || tencentSelfFinished(hints) || all.contains("一键理") || (all.contains("理牌") && all.contains("聊天")) -> TableScene.TABLE
        else -> TableScene.UNKNOWN
    }
}
