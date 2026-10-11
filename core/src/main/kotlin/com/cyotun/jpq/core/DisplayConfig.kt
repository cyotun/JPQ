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
    fun measure(config: DisplayConfig, textWidth: Float, available: Float): OverlaySize {
        config.validate()
        require(textWidth.isFinite() && textWidth >= 0 && available.isFinite() && available > 0)
        val newGeometry = listOf(config.widthScale,config.gridRatio,config.detailText)
        if (geometry != newGeometry) { expanded = 0f; geometry = newGeometry }
        val inner = 552 * config.widthScale - 3
        val grid = inner * config.gridRatio
        val controls = (if (config.doubleTapHide) 0 else 48) + (if (config.showReset) 48 else 0)
        if (!config.showHistory) {
            val onlyGrid=235.577047f*config.countWidthScale
            return OverlaySize(onlyGrid, 0f, onlyGrid + 2, onlyGrid + 2 + controls)
        }
        val base = inner - grid
        expanded = maxOf(expanded, base, kotlin.math.ceil(32 + textWidth + 12))
        val record = minOf(expanded, maxOf(0f, available - controls - grid - 3))
        return OverlaySize(grid, record, grid + record + 3, grid + record + 3 + controls)
    }
}
