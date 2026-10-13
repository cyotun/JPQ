package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.aijpq.pokerrecorder.runtime.preprocess.PackingManifest
import com.cyotun.jpq.core.GlyphBox
import com.cyotun.jpq.core.PackedCoverageEvidence

/** Reads the current source; it never reuses prior counts or prior detections. */
class PackedCoverageGuard: AutoCloseable {
    private var raster: Bitmap?=null
    private var pixels=IntArray(0)
    fun evaluate(source: Bitmap,manifest: PackingManifest): PackedCoverageEvidence.Result {
        val width=minOf(378,source.width)
        val height=(source.height.toLong()*width/source.width).toInt().coerceAtLeast(1)
        val sampled=raster?.takeIf { it.width==width && it.height==height } ?: run {
            raster?.recycle()
            Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also { raster=it; pixels=IntArray(width*height) }
        }
        Canvas(sampled).drawBitmap(source,null,Rect(0,0,width,height),null)
        sampled.getPixels(pixels,0,width,0,0,width,height)
        val covered=manifest.tiles.map { tile -> tile.sourceBBox.let { box ->
            GlyphBox(box.left.toFloat()/source.width,box.top.toFloat()/source.height,
                box.right.toFloat()/source.width,box.bottom.toFloat()/source.height)
        } }
        return PackedCoverageEvidence.evaluate(pixels,width,height,covered)
    }
    override fun close() { raster?.recycle(); raster=null; pixels=IntArray(0) }
}
