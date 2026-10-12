package com.cyotun.jpq.vision

import android.content.Context
import android.graphics.*
import android.media.Image
import android.os.SystemClock
import com.cyotun.jpq.CaptureMask
import com.cyotun.jpq.core.GlyphBox

/** Convert and mask on capture's thread, then hand off a separate owned bitmap. */
class CaptureFrameProducer(private val context: Context): AutoCloseable {
    private val converter=FrameBitmapConverter()
    private val probe=CaptureFrameProbe()
    private val pool=ArrayDeque<Bitmap>()
    private var closed=false
    private val maskPaint=Paint().apply { color=Color.rgb(38,38,38) }

    fun capture(image: Image,sourceWidth: Int,sourceHeight: Int,adjusting: Boolean,
                masks: List<CaptureMask>,capturedMs: Long): CapturedFrame {
        check(!closed)
        if(image.width<=image.height || sourceWidth<=sourceHeight || adjusting)
            return CapturedFrame(null,image.width,image.height,sourceWidth,sourceHeight,adjusting,
                image.timestamp,capturedMs,SystemClock.elapsedRealtime(),emptyList(),null,false,::release)
        val converted=converter.convert(image)
        val owned=take(image.width,image.height)
        try {
            val canvas=Canvas(owned)
            // Keep full-display coordinates even if a producer provides only a cropped plane.
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(converted,image.cropRect.left.toFloat(),image.cropRect.top.toFloat(),null)
            val folder=probe.capture(context,image,owned)
            val normalized=mutableListOf<GlyphBox>()
            masks.forEach { mask ->
                if(mask.screenWidth<=0 || mask.screenHeight<=0 || (mask.screenWidth>mask.screenHeight)!=(image.width>image.height)) return@forEach
                val box=GlyphBox(mask.rectangle.left.toFloat()/mask.screenWidth,mask.rectangle.top.toFloat()/mask.screenHeight,
                    mask.rectangle.right.toFloat()/mask.screenWidth,mask.rectangle.bottom.toFloat()/mask.screenHeight)
                canvas.drawRect(box.left*image.width,box.top*image.height,box.right*image.width,box.bottom*image.height,maskPaint)
                normalized.add(box)
            }
            return CapturedFrame(owned,image.width,image.height,sourceWidth,sourceHeight,adjusting,
                image.timestamp,capturedMs,SystemClock.elapsedRealtime(),normalized,folder,
                image.cropRect==Rect(0,0,image.width,image.height),::release)
        } catch(error: Throwable) { release(owned); throw error }
    }

    @Synchronized private fun take(width: Int,height: Int): Bitmap {
        while(pool.isNotEmpty()) {
            val bitmap=pool.removeFirst()
            if(bitmap.width==width && bitmap.height==height) return bitmap
            bitmap.recycle()
        }
        return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
    }
    @Synchronized private fun release(bitmap: Bitmap) {
        if(closed || pool.size>=3) bitmap.recycle() else pool.addLast(bitmap)
    }
    override fun close() {
        synchronized(this) { closed=true; pool.forEach { it.recycle() }; pool.clear() }
        converter.close(); probe.close()
    }
}
