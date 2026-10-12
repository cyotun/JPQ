package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Copies an RGBA_8888 capture plane into a reusable opaque Bitmap.
 *
 * Android's ARGB_8888 buffer packing is A<<24 | B<<16 | G<<8 | R, so its
 * little-endian bytes are RGBA; this path must not swap red and blue.
 * See https://developer.android.com/reference/android/graphics/Bitmap.Config#ARGB_8888
 * and https://developer.android.com/reference/android/graphics/Bitmap#copyPixelsFromBuffer(java.nio.Buffer).
 *
 * The source position/limit are preserved. The returned Bitmap is owned by this
 * converter and remains valid until the next differently sized frame or close.
 * Capture RGB is treated as opaque, matching the previous Color.argb(255,...) path.
 */
class FrameBitmapConverter: AutoCloseable {
    private val owner=Thread.currentThread()
    private var bitmap: Bitmap?=null
    private var packed: ByteBuffer?=null
    private var closed=false

    fun convert(image: Image): Bitmap {
        require(image.format==PixelFormat.RGBA_8888) { "RGBA_8888 capture required" }
        val plane=image.planes.single()
        return convert(plane.buffer,image.width,image.height,plane.rowStride,plane.pixelStride)
    }

    fun convert(source: ByteBuffer,width: Int,height: Int,rowStride: Int,pixelStride: Int): Bitmap {
        check(Thread.currentThread()===owner) { "Frame converter must stay on its owning thread" }
        check(!closed) { "Frame converter already closed" }
        require(width>0 && height>0 && pixelStride>=4)
        val rowPayload=(width-1L)*pixelStride+4L
        require(rowStride.toLong()>=rowPayload) { "Capture row stride is too short" }
        val required=(height-1L)*rowStride+rowPayload
        require(required<=source.remaining().toLong()) { "Capture plane is truncated" }
        require(width.toLong()*height*4L<=Int.MAX_VALUE) { "Capture dimensions are too large" }

        val target=bitmap?.takeIf { !it.isRecycled && it.width==width && it.height==height } ?: run {
            val next=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
            bitmap?.takeUnless { it.isRecycled }?.recycle()
            bitmap=next
            next
        }
        val data=source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base=data.position()
        if(pixelStride==4 && rowStride==target.rowBytes && data.remaining()>=target.byteCount) {
            // Normal ImageReader frames: one native copy, no Kotlin per-pixel conversion.
            data.limit(base+target.byteCount)
            target.copyPixelsFromBuffer(data)
            return target
        }

        val destination=packed?.takeIf { it.capacity()>=target.byteCount } ?: ByteBuffer.allocateDirect(target.byteCount)
            .order(ByteOrder.LITTLE_ENDIAN).also { packed=it }
        destination.clear()
        for(y in 0 until height) {
            val start=base+y*rowStride
            destination.position(y*target.rowBytes)
            if(pixelStride==4) {
                // Strip only each row's padding; the visible RGBA bytes still use a bulk copy.
                data.limit(source.limit()); data.position(start); data.limit(start+width*4)
                destination.put(data)
            } else {
                // Uncommon inter-pixel padding: copy raw four-byte RGBA words without Color.argb.
                for(x in 0 until width) destination.putInt(data.getInt(start+x*pixelStride))
            }
            while(destination.position()<(y+1)*target.rowBytes) destination.put(0.toByte())
        }
        destination.position(target.byteCount); destination.flip()
        target.copyPixelsFromBuffer(destination)
        return target
    }

    override fun close() {
        check(Thread.currentThread()===owner) { "Frame converter must close on its owning thread" }
        if(closed) return
        closed=true
        bitmap?.takeUnless { it.isRecycled }?.recycle(); bitmap=null; packed=null
    }
}
