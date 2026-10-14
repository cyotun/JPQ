package com.cyotun.jpq

import android.content.Context
import com.cyotun.jpq.core.*
import org.json.JSONObject

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("jpq-display", Context.MODE_PRIVATE)
    fun load(): DisplayConfig = runCatching {
        decode(JSONObject(prefs.getString("config", "{}")!!))
    }.getOrDefault(DisplayConfig())
    fun save(config: DisplayConfig) { prefs.edit().putString("config",encode(config.validate()).toString()).apply() }
    fun draftJson(config: DisplayConfig): String = encode(config).toString()
    fun readDraft(text: String): DisplayConfig = decode(JSONObject(text))
    fun export(): String = JSONObject().put("schemaVersion",1).put("display",encode(load())).toString(2)
    fun import(text: String): DisplayConfig {
        require(text.toByteArray().size <= 32768)
        val root=JSONObject(text); require(root.getInt("schemaVersion")==1)
        val config=decode(root.getJSONObject("display")).validate(); save(config); return config
    }
    private fun decode(j: JSONObject): DisplayConfig {
        val d=DisplayConfig()
        return DisplayConfig(j.optBoolean("history",d.showHistory),j.optBoolean("reset",d.showReset),j.optBoolean("doubleTap",d.doubleTapHide),
            j.optDouble("width",d.widthScale.toDouble()).toFloat(),j.optDouble("ratio",d.gridRatio.toDouble()).toFloat(),
            j.optDouble("gridHeight",d.gridHeight.toDouble()).toFloat(),j.optDouble("recordHeight",d.recordHeight.toDouble()).toFloat(),
            j.optDouble("headerText",d.headerText.toDouble()).toFloat(),j.optDouble("valueText",d.valueText.toDouble()).toFloat(),
            j.optDouble("counterText",d.counterText.toDouble()).toFloat(),j.optDouble("detailText",d.detailText.toDouble()).toFloat(),
            if(j.has("order")) j.getString("order").split(",").map { Rank.valueOf(it) } else d.order,
            j.optDouble("x",0.0).toFloat(),j.optDouble("y",0.0).toFloat(),j.optDouble("countWidth",1.0).toFloat(),
            j.optBoolean("historyDetails",d.showHistoryDetails),j.optBoolean("positionScreenRelative",d.positionIsScreenRelative),
            j.optBoolean("straightFlush",d.showStraightFlush),
            j.optBoolean("gradeOcclusionTip",d.showGradeOcclusionTip)).validate()
    }
    private fun encode(c: DisplayConfig)=JSONObject().put("history",c.showHistory).put("reset",c.showReset).put("doubleTap",c.doubleTapHide)
        .put("width",c.widthScale).put("ratio",c.gridRatio).put("gridHeight",c.gridHeight).put("recordHeight",c.recordHeight)
        .put("headerText",c.headerText).put("valueText",c.valueText).put("counterText",c.counterText).put("detailText",c.detailText)
        .put("order",c.order.joinToString(",") { it.name }).put("x",c.x).put("y",c.y).put("countWidth",c.countWidthScale)
        .put("historyDetails",c.showHistoryDetails).put("positionScreenRelative",c.positionIsScreenRelative)
        .put("straightFlush",c.showStraightFlush).put("gradeOcclusionTip",c.showGradeOcclusionTip)
}
