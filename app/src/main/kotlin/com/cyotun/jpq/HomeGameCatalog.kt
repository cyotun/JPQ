package com.cyotun.jpq

/** UI choices are independent from recognition adapters. A future choice stays
 * disabled until it has an explicit supported profile, not just an app label. */
data class HomeGamePlatform(val id: String,val name: String,val publisher: String,val profile: HomeGameProfile?=null) {
    val label get()="$name（$publisher）"
}

data class HomeGameProfile(val play: String,val variant: String,val players: Int,val decks: Int)

object HomeGameCatalog {
    const val DEFAULT_PLATFORM="tencent-daguandan"
    val platforms=listOf(
        HomeGamePlatform(DEFAULT_PLATFORM,"大掼蛋","腾讯",HomeGameProfile("掼蛋","默认",4,2)),
        HomeGamePlatform("tencent-doudizhu","欢乐斗地主","腾讯"),
        HomeGamePlatform("tencent-guandan","欢乐掼蛋","腾讯"),
        HomeGamePlatform("jj-doudizhu","JJ斗地主","JJ"),
    )
    val plays=listOf("斗地主","跑得快","双扣","掼蛋","510K","干瞪眼","锄大地","红十","A3纸牌","自定义")
    val variants=listOf("默认","团团转")
    val players=listOf(2,3,4,5,6)
    val decks=listOf(1,2,3,4,5,6)
    fun platform(id: String?)=platforms.firstOrNull { it.id==id }
    fun canStart(id: String?)=platform(id)?.profile!=null
    fun search(query: String)=platforms.filter { "${it.name}${it.publisher}".contains(query.trim(),ignoreCase=true) }
}
