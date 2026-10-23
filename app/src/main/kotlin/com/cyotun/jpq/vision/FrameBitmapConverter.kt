package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Copies an RGBA_8888 capture plane into an opaque Bitmap.
 *
 * Android's ARGB_8888 buffer packing is A<<24 | B<<16 | G<<8 | R, so its
 * little-endian bytes are RGBA; this path must not swap red and blue. Capture
 * alpha is normalized to opaque, matching the previous Color.argb(255,...) path.
 * The source buffer's position, limit, and byte order are preserved.
 */
class FrameBitmapConverter: AutoCloseable {
    private val owner=Thread.currentThread()
    private var bitmap: Bitmap?=null
    private var packed: ByteBuffer?=null
    private var opaquePixels=IntArray(0)
    private var closed=false

    /** Returns a converter-owned crop-sized bitmap, retained for compatibility. */
    fun convert(image: Image): Bitmap {
        require(image.format==PixelFormat.RGBA_8888) { "RGBA_8888 capture required" }
        val plane=image.planes.single()
        val crop=image.cropRect
        requireCrop(image.width,image.height,crop.left,crop.top,crop.right,crop.bottom)
        val width=crop.width(); val height=crop.height()
        val target=ownedBitmap(width,height)
        convertInto(plane.buffer,width,height,plane.rowStride,plane.pixelStride,crop.left,crop.top,
            target,0,0,clearOutside=false)
        return target
    }

    /** Writes the visible crop directly into a caller-owned full-display bitmap. */
    fun convertInto(image: Image,destination: Bitmap) {
        require(image.format==PixelFormat.RGBA_8888) { "RGBA_8888 capture required" }
        require(destination.config==Bitmap.Config.ARGB_8888 && !destination.isRecycled) {
            "A live ARGB_8888 destination is required"
        }
        val plane=image.planes.single()
        val crop=image.cropRect
        requireCrop(image.width,image.height,crop.left,crop.top,crop.right,crop.bottom)
        require(destination.width==image.width && destination.height==image.height) {
            "Destination must match the full image dimensions"
        }
        convertInto(plane.buffer,crop.width(),crop.height(),plane.rowStride,plane.pixelStride,
            crop.left,crop.top,destination,crop.left,crop.top,
            clearOutside=crop.left!=0 || crop.top!=0 || crop.right!=image.width || crop.bottom!=image.height)
    }

    fun convert(source: ByteBuffer,width: Int,height: Int,rowStride: Int,pixelStride: Int,
                cropLeft: Int=0,cropTop: Int=0): Bitmap {
        checkOwner()
        check(!closed) { "Frame converter already closed" }
        val target=ownedBitmap(width,height)
        convertInto(source,width,height,rowStride,pixelStride,cropLeft,cropTop,target,0,0,clearOutside=false)
        return target
    }

    internal fun convertInto(source: ByteBuffer,width: Int,height: Int,rowStride: Int,pixelStride: Int,
                             cropLeft: Int,cropTop: Int,target: Bitmap,destinationLeft: Int,
                             destinationTop: Int,clearOutside: Boolean) {
        checkOwner()
        check(!closed) { "Frame converter already closed" }
        require(width>0 && height>0 && pixelStride>=4 && cropLeft>=0 && cropTop>=0)
        require(destinationLeft>=0 && destinationTop>=0 &&
            destinationLeft.toLong()+width<=target.width && destinationTop.toLong()+height<=target.height)
        require(target.config==Bitmap.Config.ARGB_8888 && !target.isRecycled)
        val rowPayload=(cropLeft+width-1L)*pixelStride+4L
        require(rowStride.toLong()>=rowPayload) { "Capture row stride is too short" }
        val required=(cropTop+height-1L)*rowStride+rowPayload
        require(required<=source.remaining().toLong()) { "Capture plane is truncated" }
        require(target.byteCount%4==0) { "ARGB_8888 destination has invalid row alignment" }

        val destination=packed?.takeIf { it.capacity()>=target.byteCount }
            ?: ByteBuffer.allocateDirect(target.byteCount).order(ByteOrder.LITTLE_ENDIAN).also { packed=it }
        destination.clear()
        if(clearOutside) {
            // Fill in managed memory and transfer in bulk. Per-pixel direct-buffer
            // calls make cropped task frames much slower than the full-frame path.
            val count=target.byteCount/4
            if(opaquePixels.size!=count) opaquePixels=IntArray(count)
            java.util.Arrays.fill(opaquePixels,0xff000000.toInt())
            destination.asIntBuffer().put(opaquePixels)
        }

        val data=source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base=data.position()+cropTop*rowStride+cropLeft*pixelStride
        if(!clearOutside && destinationLeft==0 && destinationTop==0 && width==target.width && height==target.height &&
            pixelStride==4 && cropLeft==0 && rowStride==target.rowBytes &&
            data.limit()-base>=target.byteCount) {
            // Keep the whole-frame path as one bulk copy. It is the common capture layout
            // and avoids per-pixel buffer access overhead on DirectByteBuffer.
            data.limit(base+target.byteCount); data.position(base)
            destination.put(data)
        } else {
            for(y in 0 until height) {
                val sourceStart=base+y*rowStride
                val destinationStart=(destinationTop+y)*target.rowBytes+destinationLeft*4
                destination.position(destinationStart)
                if(pixelStride==4) {
                    data.limit(source.limit()); data.position(sourceStart); data.limit(sourceStart+width*4)
                    destination.put(data)
                } else {
                    for(x in 0 until width) destination.putInt(data.getInt(sourceStart+x*pixelStride))
                }
                if(!clearOutside && destinationLeft==0 && width==target.width) {
                    while(destination.position()<(destinationTop+y+1)*target.rowBytes) destination.put(0.toByte())
                }
            }
        }

        copyOpaque(destination,target)
    }

    private fun ownedBitmap(width: Int,height: Int): Bitmap {
        checkOwner()
        check(!closed) { "Frame converter already closed" }
        require(width>0 && height>0 && width.toLong()*height*4L<=Int.MAX_VALUE)
        val target=bitmap?.takeIf { !it.isRecycled && it.width==width && it.height==height } ?: run {
            val next=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
            bitmap?.takeUnless { it.isRecycled }?.recycle()
            bitmap=next
            next
        }
        return target
    }

    private fun requireCrop(imageWidth: Int,imageHeight: Int,left: Int,top: Int,right: Int,bottom: Int) {
        require(left>=0 && top>=0 && right<=imageWidth && bottom<=imageHeight && right>left && bottom>top)
    }

    private fun checkOwner() {
        check(Thread.currentThread()===owner) { "Frame converter must stay on its owning thread" }
    }

    private fun copyOpaque(destination: ByteBuffer,target: Bitmap) {
        val count=target.byteCount/4
        if(opaquePixels.size!=count) opaquePixels=IntArray(count)
        // The view starts at ByteBuffer.position(), so explicitly rewind before creating it.
        destination.position(0); destination.limit(target.byteCount)
        destination.asIntBuffer().get(opaquePixels)
        for(index in 0 until count) opaquePixels[index]=opaquePixels[index] or 0xff000000.toInt()
        destination.clear(); destination.asIntBuffer().put(opaquePixels)
        destination.position(0); destination.limit(target.byteCount)
        target.setHasAlpha(false)
        target.copyPixelsFromBuffer(destination)
    }

    override fun close() {
        checkOwner()
        if(closed) return
        closed=true
        bitmap?.takeUnless { it.isRecycled }?.recycle(); bitmap=null; packed=null; opaquePixels=IntArray(0)
    }
}
