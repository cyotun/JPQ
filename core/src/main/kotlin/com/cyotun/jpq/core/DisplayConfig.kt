package com.cyotun.jpq.core

data class DisplayConfig(
    val showHistory: Boolean = true,
    val showReset: Boolean = true,
    val doubleTapHide: Boolean = false,
    val widthScale: Float = 1.01191494f,
    val gridRatio: Float = .42402228f,
    val gridHeight: Float = .94622684f,
    val recordHeight: Float = .98167837f,
    val headerText: Float = .89647275f,
    val valueText: Float = .96344686f,
    val counterText: Float = 1.0216086f,
    val detailText: Float = .90082836f,
    val order: List<Rank> = guandanOrder,
    val x: Float = 0f,
    val y: Float = 0f,
    val countWidthScale: Float = 1f,
    val showHistoryDetails: Boolean = true,
    val positionIsScreenRelative: Boolean = false,
    val showStraightFlush: Boolean = true,
    val showGradeOcclusionTip: Boolean = true,
) {
    fun validate(): DisplayConfig {
        require(validOrder(order))
        require(widthScale in .8f..1.35f && gridRatio in .3f.. .6f)
        require(gridHeight in .85f..1.3f && recordHeight in .85f..1.3f)
        require(listOf(headerText,valueText,counterText,detailText).all { it in .8f..1.35f })
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
        require(countWidthScale in .8f..1.35f)
        return this
    }
}

data class OverlaySize(val grid: Float, val history: Float, val content: Float, val total: Float)
class HistoryWidth {
    private var expanded = 0f
    private var geometry: List<Float>? = null
    fun newRound() { expanded = 0f }
    /** All values dp. Typeface-dependent occupied text width supplied by UI. */
    fun measure(config: DisplayConfig, textWidth: Float, available: Float,minimumGrid: Float=0f): OverlaySize {
        config.validate()
        require(textWidth.isFinite() && textWidth >= 0 && available.isFinite() && available > 0)
        require(minimumGrid.isFinite() && minimumGrid>=0)
        val newGeometry = listOf(config.widthScale,config.gridRatio,config.detailText,
            if(config.showHistory) 1f else 0f,if(config.showHistoryDetails) 1f else 0f,
            if(config.showStraightFlush) 1f else 0f)
        if (geometry != newGeometry) { expanded = 0f; geometry = newGeometry }
        val inner = 552 * config.widthScale - 3
        val requestedGrid = maxOf(inner * config.gridRatio,minimumGrid)
        val controls = (if (config.doubleTapHide) 0 else 48) + (if (config.showReset) 48 else 0)
        val indicator = if(config.showStraightFlush) STRAIGHT_FLUSH_SLOT else 0f
        if (!config.showHistory || !config.showHistoryDetails) {
            val requestedCompactGrid=maxOf(235.577047f*config.countWidthScale,minimumGrid)
            // Count mode ends just after the two 14dp seat/count cells. It does
            // not inherit a previous round's expanded text or a detail gutter.
            val record=if(config.showHistory) 32f else 0f
            val margin=if(config.showHistory) 3f else 2f
            // Keep the complete seat/count cells when the saved scale reaches
            // a narrower screen. The icon owns its own slot, never a count cell.
            val onlyGrid=minOf(requestedCompactGrid,maxOf(0f,available-controls-indicator-record-margin))
            val content=onlyGrid+indicator+record+margin
            return OverlaySize(onlyGrid,record,content,content+controls)
        }
        val base = inner - requestedGrid
        expanded = maxOf(expanded, base, kotlin.math.ceil(32 + textWidth + 12))
        val grid=minOf(requestedGrid,maxOf(0f,available-controls-indicator-32-3))
        val record = minOf(expanded, maxOf(32f, available - controls - grid - indicator - 3))
        val content=grid+indicator+record+3
        return OverlaySize(grid, record, content, content+controls)
    }

    companion object { const val STRAIGHT_FLUSH_SLOT = 16f }
}
