package com.cyotun.jpq.core

import kotlin.math.abs

/** Physical screen pixels. Edge contact alone is not an occlusion. */
data class GradeOcclusionRect(val left: Int,val top: Int,val right: Int,val bottom: Int) {
    val width get()=right-left
    val height get()=bottom-top
    fun intersects(other: GradeOcclusionRect)=left<other.right && right>other.left && top<other.bottom && bottom>other.top
    fun padded(amount: Int)=GradeOcclusionRect(left-amount,top-amount,right+amount,bottom+amount)
}

/** Owned by a capture session, never by a round or a saved display preference. */
class GradeOcclusionSession {
    var ignored=false
        private set
    fun ignore() { ignored=true }
    fun shouldShow(running: Boolean,preview: Boolean,hidden: Boolean,enabled: Boolean,
        bar: GradeOcclusionRect,grade: GradeOcclusionRect?): Boolean =
        running && !preview && !hidden && enabled && !ignored && grade!=null && bar.intersects(grade)
}

object GradeOcclusionGeometry {
    /**
     * Only move along the very top of a landscape table. If the complete bar
     * cannot fit outside the level and rules HUD, leave movement to the user.
     * This never chooses a lower row over live cards to make a wide bar fit.
     */
    fun safeTopPosition(screenWidth: Int,screenHeight: Int,barWidth: Int,barHeight: Int,
        currentX: Int,grade: GradeOcclusionRect,padding: Int=0): OverlayPoint? {
        require(screenWidth>0 && screenHeight>0 && barWidth>0 && barHeight>0 && padding>=0)
        if(screenWidth<screenHeight || barWidth>screenWidth || barHeight>screenHeight*.14f) return null
        val maxX=screenWidth-barWidth
        val forbidden=listOf(grade.padded(padding),GradeOcclusionRect((screenWidth*.84f).toInt(),0,screenWidth,(screenHeight*.14f).toInt()))
        // Grade is normally on the left. Prefer the nearest valid top anchor,
        // including the left side if a different layout leaves room there.
        val candidates=(listOf(currentX.coerceIn(0,maxX),0,maxX)+forbidden.flatMap {
            listOf(it.right,it.left-barWidth)
        }).filter { it in 0..maxX }.distinct().sortedBy { abs(it-currentX) }
        return candidates.firstOrNull { x ->
            val bar=GradeOcclusionRect(x,0,x+barWidth,barHeight)
            forbidden.none { bar.intersects(it) }
        }?.let { OverlayPoint(it,0) }
    }

    /** Below-bar hint stays left of central played cards; its label may ellipsize. */
    fun hintPosition(screenWidth: Int,screenHeight: Int,bar: GradeOcclusionRect,
        desiredWidth: Int,hintHeight: Int,bodyOffset: Int,gap: Int): GradeOcclusionRect? {
        require(screenWidth>0 && screenHeight>0 && desiredWidth>0 && hintHeight>0 && bodyOffset>=0 && gap>=0)
        if(screenWidth<screenHeight) return null
        // Below 24% of the table, side players' card areas are also occupied.
        // Try the approved below-bar position first, then an above-bar position.
        val y=listOf(bar.bottom+gap,bar.top-gap-hintHeight).firstOrNull {
            it>=0 && it+hintHeight<=screenHeight*.24f
        } ?: return null
        val leftBand=(screenWidth*.40f).toInt()
        val w=minOf(desiredWidth,leftBand)
        val x=(bar.left+bodyOffset).coerceIn(0,maxOf(0,leftBand-w))
        return GradeOcclusionRect(x,y,x+w,y+hintHeight)
    }
}
