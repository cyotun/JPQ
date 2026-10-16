package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.cyotun.jpq.core.GlyphBox
import kotlin.math.ceil

/** Small immutable source-image patches retained with an asynchronous OCR result. */
class RemainingCountPixels private constructor(
    private val width: Int,private val height: Int,private val patches: List<Patch>
) {
    private data class Patch(val rect: Rect,val pixels: IntArray)

    fun matches(bitmap: Bitmap,box: GlyphBox): Boolean {
        if(bitmap.width!=width || bitmap.height!=height ||
            !listOf(box.left,box.top,box.right,box.bottom).all { it.isFinite() }) return false
        val rect=Rect((box.left*width).toInt(),(box.top*height).toInt(),ceil(box.right*width).toInt(),ceil(box.bottom*height).toInt())
        if(rect.width()<=0 || rect.height()<=0) return false
        val patch=patches.singleOrNull { it.rect.contains(rect) } ?: return false
        val current=IntArray(rect.width()*rect.height())
        bitmap.getPixels(current,0,rect.width(),rect.left,rect.top,rect.width(),rect.height())
        // Exact pixels deliberately abstain on animation/compression changes.
        // A still-gold background cannot validate stale digits such as 8 -> 7.
        for(y in 0 until rect.height()) for(x in 0 until rect.width()) {
            val old=(rect.top-patch.rect.top+y)*patch.rect.width()+rect.left-patch.rect.left+x
            if(current[y*rect.width()+x]!=patch.pixels[old]) return false
        }
        return true
    }

    companion object {
        fun capture(bitmap: Bitmap): RemainingCountPixels? {
            if(bitmap.width<=bitmap.height || bitmap.width>4096 || bitmap.height>2160) return null
            val w=bitmap.width; val h=bitmap.height
            val regions=listOf(
                GlyphBox(.03f,.30f,.21f,.48f),GlyphBox(.79f,.30f,.97f,.48f),GlyphBox(.32f,.06f,.70f,.21f))
            val patches=regions.map { box ->
                val rect=Rect((box.left*w).toInt(),(box.top*h).toInt(),ceil(box.right*w).toInt(),ceil(box.bottom*h).toInt())
                val pixels=IntArray(rect.width()*rect.height())
                bitmap.getPixels(pixels,0,rect.width(),rect.left,rect.top,rect.width(),rect.height())
                Patch(rect,pixels)
            }
            return RemainingCountPixels(w,h,patches)
        }
    }
}
