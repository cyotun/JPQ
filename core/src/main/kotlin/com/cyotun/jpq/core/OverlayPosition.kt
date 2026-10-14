package com.cyotun.jpq.core

import kotlin.math.max
import kotlin.math.roundToInt

data class OverlayPoint(val x: Int,val y: Int)
data class ScreenRelativePosition(val x: Float,val y: Float)

/**
 * Tencent plays in landscape, while its counter starts/stops from the portrait
 * app. A temporary portrait clamp must not overwrite the last game position.
 */
class OverlayPositionMemory {
    private data class LandscapePoint(val point: OverlayPoint,val width: Int,val height: Int)
    private var landscape: LandscapePoint?=null

    fun observe(point: OverlayPoint,screenWidth: Int,screenHeight: Int) {
        if(screenWidth>=screenHeight) landscape=LandscapePoint(point,screenWidth,screenHeight)
    }

    fun userMoved(point: OverlayPoint,screenWidth: Int,screenHeight: Int) {
        landscape=null
        observe(point,screenWidth,screenHeight)
    }

    fun onStop(point: OverlayPoint,screenWidth: Int,screenHeight: Int): ScreenRelativePosition? =
        if(screenWidth>=screenHeight) OverlayPosition.save(point.x,point.y,screenWidth,screenHeight)
        else landscape?.let { OverlayPosition.save(it.point.x,it.point.y,it.width,it.height) }
}

/** A window's top-left anchor is independent of its waiting, hidden or playing width. */
object OverlayPosition {
    fun restore(x: Float,y: Float,screenRelative: Boolean,screenWidth: Int,screenHeight: Int,
        windowWidth: Int,windowHeight: Int): OverlayPoint {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
        dimensions(screenWidth,screenHeight,windowWidth,windowHeight)
        val maxX=max(0,screenWidth-windowWidth); val maxY=max(0,screenHeight-windowHeight)
        // Old installations stored a fraction of the current window's available
        // travel. Read it once at the old position before migrating to the screen.
        val basisX=if(screenRelative) screenWidth else maxX
        val basisY=if(screenRelative) screenHeight else maxY
        return OverlayPoint((x*basisX).roundToInt().coerceIn(0,maxX),
            (y*basisY).roundToInt().coerceIn(0,maxY))
    }

    fun save(x: Int,y: Int,screenWidth: Int,screenHeight: Int): ScreenRelativePosition {
        require(screenWidth>0 && screenHeight>0)
        return ScreenRelativePosition(x.toFloat().coerceIn(0f,screenWidth.toFloat())/screenWidth,
            y.toFloat().coerceIn(0f,screenHeight.toFloat())/screenHeight)
    }

    fun clamp(x: Int,y: Int,screenWidth: Int,screenHeight: Int,windowWidth: Int,windowHeight: Int): OverlayPoint {
        dimensions(screenWidth,screenHeight,windowWidth,windowHeight)
        return OverlayPoint(x.coerceIn(0,max(0,screenWidth-windowWidth)),
            y.coerceIn(0,max(0,screenHeight-windowHeight)))
    }

    private fun dimensions(screenWidth: Int,screenHeight: Int,windowWidth: Int,windowHeight: Int) {
        require(screenWidth>0 && screenHeight>0 && windowWidth>=0 && windowHeight>=0)
    }
}
