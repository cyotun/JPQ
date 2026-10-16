package com.cyotun.jpq.vision

import android.graphics.Bitmap
import com.cyotun.jpq.core.*
import kotlin.math.ceil

/** Reuses the existing OCR snapshot; never schedules OCR or reads through an overlay. */
object TencentRemainingCountSource {
    fun read(bitmap: Bitmap,time: Long,text: SceneText?,masks: List<GlyphBox>): Map<Seat,RemainingHandCount> {
        if(bitmap.width<=bitmap.height || text==null || text.width!=bitmap.width || text.height!=bitmap.height ||
            time-text.timeMs !in 0L..1000L || text.remainingCountPixels==null) return emptyMap()
        val hints=(text.remainingCountElements+text.lines).distinct().mapNotNull { line ->
            val rect=line.box ?: return@mapNotNull null
            val box=GlyphBox(rect.left.toFloat()/bitmap.width,rect.top.toFloat()/bitmap.height,
                rect.right.toFloat()/bitmap.width,rect.bottom.toFloat()/bitmap.height)
            val hint=SceneHint(line.text,box)
            if(tencentRemainingHandCounts(listOf(hint),text.timeMs,masks).isEmpty() ||
                !text.remainingCountPixels.matches(bitmap,box) || !goldPlate(bitmap,box)) null else hint
        }
        return tencentRemainingHandCounts(hints,text.timeMs,masks)
    }

    private fun goldPlate(bitmap: Bitmap,box: GlyphBox): Boolean {
        val left=(box.left*bitmap.width).toInt(); val top=(box.top*bitmap.height).toInt()
        val right=ceil(box.right*bitmap.width).toInt(); val bottom=ceil(box.bottom*bitmap.height).toInt()
        if(left<0 || top<0 || right>bitmap.width || bottom>bitmap.height || right<=left || bottom<=top) return false
        var samples=0; var gold=0
        for(y in top until bottom step maxOf(1,(bottom-top)/12)) {
            for(x in left until right step maxOf(1,(right-left)/24)) {
                val color=bitmap.getPixel(x,y); val r=(color ushr 16) and 255
                val g=(color ushr 8) and 255; val b=color and 255
                samples++
                if(r>=170 && g>=120 && r>=g*.95f && b<=g*.85f) gold++
            }
        }
        return samples>=12 && gold.toFloat()/samples>=.30f
    }
}
