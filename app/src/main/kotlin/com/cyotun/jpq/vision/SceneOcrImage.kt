package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.ceil

/** One OCR request: unchanged scene scale, plus isolated copies of actual HUD panels.
 * Every returned text box is mapped to its source pixels before it becomes evidence.
 */
internal class SceneOcrImage(source: Bitmap) : AutoCloseable {
    private val sourceWidth=source.width
    private val sourceHeight=source.height
    private val scale=minOf(1f,1280f/maxOf(sourceWidth,sourceHeight))
    val sceneWidth=(sourceWidth*scale).toInt().coerceAtLeast(1)
    val sceneHeight=(sourceHeight*scale).toInt().coerceAtLeast(1)
    internal data class Region(val source: Rect,val target: Rect,val scale: Int)
    private val padding=32
    // Detect the complete two-panel structure in source pixels. Putting each
    // panel on its own line prevents QQ/JJ word boxes spanning both teams.
    // If no complete structure is visible, retain the previous broad HUD crop;
    // neither this fallback nor a fixed search area declares a grade panel.
    val hudRegions: List<Region> = if(sourceWidth<=sourceHeight) emptyList() else {
        val panels=TencentHudPanels.detect(source)
        val crops=if(panels.isNotEmpty()) panels.map { panel ->
            Rect((minOf(panel.header.left,panel.body.left)-4).coerceAtLeast(0),
                (minOf(panel.header.top,panel.body.top)-4).coerceAtLeast(0),
                (maxOf(panel.header.right,panel.body.right)+4).coerceAtMost(sourceWidth),
                (maxOf(panel.header.bottom,panel.body.bottom)+4).coerceAtMost(sourceHeight)) to 3
        } else listOf(Rect((sourceWidth*.055f).toInt(),0,ceil(sourceWidth*.23f).toInt(),ceil(sourceHeight*.13f).toInt()) to 2)
        var nextY=sceneHeight+padding
        crops.map { (crop,scale) ->
            Region(crop,Rect(padding,nextY,padding+crop.width()*scale,nextY+crop.height()*scale),scale)
                .also { nextY=it.target.bottom+padding*2 }
        }
    }
    val bitmap=Bitmap.createBitmap(maxOf(sceneWidth,hudRegions.maxOfOrNull { it.target.right+padding } ?: 0),
        hudRegions.lastOrNull()?.let { it.target.bottom+padding } ?: sceneHeight,Bitmap.Config.ARGB_8888)
    init {
        val canvas=Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val paint=Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source,null,Rect(0,0,sceneWidth,sceneHeight),paint)
        hudRegions.forEach { canvas.drawBitmap(source,it.source,it.target,paint) }
    }
    fun isScene(box: Rect?)=box!=null && box.left>=0 && box.top>=0 &&
        box.right<=sceneWidth && box.bottom<=sceneHeight && box.width()>0 && box.height()>0

    fun toSource(box: Rect?): Rect? {
        if(box==null || box.width()<=0 || box.height()<=0) return null
        val mapped=when {
            isScene(box) -> Rect((box.left*sourceWidth.toFloat()/sceneWidth).toInt(),
                (box.top*sourceHeight.toFloat()/sceneHeight).toInt(),
                ceil(box.right*sourceWidth.toFloat()/sceneWidth).toInt(),
                ceil(box.bottom*sourceHeight.toFloat()/sceneHeight).toInt())
            else -> {
                val region=hudRegions.singleOrNull { it.target.contains(box) } ?: return null
                val crop=region.source; val target=region.target; val hudScale=region.scale
                Rect(crop.left+(box.left-target.left)/hudScale,crop.top+(box.top-target.top)/hudScale,
                    crop.left+ceil((box.right-target.left).toFloat()/hudScale).toInt(),
                    crop.top+ceil((box.bottom-target.top).toFloat()/hudScale).toInt())
            }
        }
        return mapped.takeIf { it.left>=0 && it.top>=0 && it.right<=sourceWidth && it.bottom<=sourceHeight }
    }
    fun symbolToSource(symbol: Rect?,element: Rect?): Rect? {
        if(symbol==null || element==null || !element.contains(symbol) || toSource(element)==null) return null
        return toSource(symbol)
    }
    override fun close() { bitmap.recycle() }
}
