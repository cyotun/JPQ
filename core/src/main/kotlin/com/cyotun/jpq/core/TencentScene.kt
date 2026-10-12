package com.cyotun.jpq.core

data class SceneHint(val text: String,val box: GlyphBox?)

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
        lastPlace -> TableScene.RESULT
        listOf("进贡","还贡","还牌","贡牌").any { all.contains(it) } -> TableScene.TRIBUTE
        lines.count { it.text.replace(" ","") in setOf("加倍","不加倍","超级加倍") }>=2 -> TableScene.DEALING
        all.contains("队友手牌") || all.contains("对家手牌") -> TableScene.TEAMMATE
        listOf("再来一局","继续游戏","本局结束","本局结算").any { all.contains(it) } -> TableScene.RESULT
        listOf("快速开始","私人房","经典","组队").count { all.contains(it) }>=3 && !hasHandCards -> TableScene.LOBBY
        hasCards || tencentSelfFinished(hints) || all.contains("一键理") || (all.contains("理牌") && all.contains("聊天")) -> TableScene.TABLE
        else -> TableScene.UNKNOWN
    }
}
