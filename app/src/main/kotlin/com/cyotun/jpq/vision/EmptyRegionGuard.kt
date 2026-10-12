package com.cyotun.jpq.vision

import android.graphics.Bitmap
import com.cyotun.jpq.core.*

/** Zero detections cannot clear a presentation while its region still contains foreground pixels. */
class EmptyRegionGuard {
    private var pixels=IntArray(0)
    private val evidence=EmptyRegionEvidence()

    fun evaluate(bitmap: Bitmap,scene: TableScene,handCount: Int,groups: Map<Seat,List<PlayGlyph>>,
        roundId: String?,masks: List<GlyphBox> = emptyList()): Map<Seat,EmptyEvidence> {
        val width=bitmap.width; val height=bitmap.height
        if(pixels.size!=width*height) pixels=IntArray(width*height)
        bitmap.getPixels(pixels,0,width,0,0,width,height)
        return evidence.evaluate(pixels,width,height,scene,handCount,groups,roundId,masks)
    }
}
