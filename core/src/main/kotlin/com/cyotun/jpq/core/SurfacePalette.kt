package com.cyotun.jpq.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** A calibrated screen surface color in brightness-independent RGB chromaticity. */
class SurfacePalette private constructor(
    val red: Float,val green: Float,val blue: Float,val samples: Int,val coverage: Float,
) {
    /** Null means that the ROI could not be measured, not an observed empty region. */
    fun tableLikeRatio(pixels: IntArray,width: Int,height: Int,region: GlyphBox): Float? {
        val bounds=bounds(pixels,width,height,region) ?: return null
        var sampled=0; var matched=0
        visit(pixels,width,bounds) { color ->
            sampled++
            val sum=brightness(color)
            if(sum>=MIN_RGB_SUM) {
                val r=((color ushr 16) and 255).toFloat()/sum
                val g=((color ushr 8) and 255).toFloat()/sum
                val b=(color and 255).toFloat()/sum
                if(abs(r-red)+abs(g-green)+abs(b-blue)<=MAX_CHROMA_DISTANCE) matched++
            }
        }
        return if(sampled<MIN_MEASUREMENT_SAMPLES) null else matched.toFloat()/sampled
    }

    companion object {
        const val MAX_CHROMA_DISTANCE=.20f
        const val MIN_EMPTY_RATIO=.70f
        const val MIN_CALIBRATION_SAMPLES=256
        const val MIN_CALIBRATION_COVERAGE=.45f
        const val MIN_MEASUREMENT_SAMPLES=32
        private const val MIN_RGB_SUM=60
        private const val BINS=16
        private const val TARGET_SAMPLES=16384
        private val fullFrame=GlyphBox(0f,0f,1f,1f)

        /** Learn only a well-supported dominant color; a card face or a dark screen cannot calibrate it. */
        fun learn(pixels: IntArray,width: Int,height: Int,region: GlyphBox=fullFrame): SurfacePalette? {
            val bounds=bounds(pixels,width,height,region) ?: return null
            val counts=IntArray(BINS*BINS)
            val redSums=DoubleArray(BINS*BINS); val greenSums=DoubleArray(BINS*BINS); val blueSums=DoubleArray(BINS*BINS)
            var sampled=0
            visit(pixels,width,bounds) { color ->
                sampled++
                val sum=brightness(color)
                if(sum>=MIN_RGB_SUM) {
                    val r=((color ushr 16) and 255).toDouble()/sum
                    val g=((color ushr 8) and 255).toDouble()/sum
                    val b=(color and 255).toDouble()/sum
                    val bin=(r*BINS).toInt().coerceIn(0,BINS-1)*BINS+(g*BINS).toInt().coerceIn(0,BINS-1)
                    counts[bin]++; redSums[bin]+=r; greenSums[bin]+=g; blueSums[bin]+=b
                }
            }
            val dominant=counts.indices.maxByOrNull { counts[it] } ?: return null
            if(counts[dominant]==0) return null
            val seedRed=(redSums[dominant]/counts[dominant]).toFloat()
            val seedGreen=(greenSums[dominant]/counts[dominant]).toFloat()
            val seedBlue=(blueSums[dominant]/counts[dominant]).toFloat()
            var count=0; var red=0.0; var green=0.0; var blue=0.0
            visit(pixels,width,bounds) { color ->
                val sum=brightness(color)
                if(sum>=MIN_RGB_SUM) {
                    val r=((color ushr 16) and 255).toFloat()/sum
                    val g=((color ushr 8) and 255).toFloat()/sum
                    val b=(color and 255).toFloat()/sum
                    if(abs(r-seedRed)+abs(g-seedGreen)+abs(b-seedBlue)<=MAX_CHROMA_DISTANCE) {
                        count++; red+=r; green+=g; blue+=b
                    }
                }
            }
            val coverage=if(sampled==0) 0f else count.toFloat()/sampled
            if(count<MIN_CALIBRATION_SAMPLES || coverage<MIN_CALIBRATION_COVERAGE) return null
            val r=(red/count).toFloat(); val g=(green/count).toFloat(); val b=(blue/count).toFloat()
            // White/gray card faces have no distinct surface chroma and must not become a palette.
            if(maxOf(r,g,b)-minOf(r,g,b)<.08f) return null
            return SurfacePalette(r,g,b,count,coverage)
        }

        fun isCleanEmpty(ratio: Float?)=ratio!=null && ratio.isFinite() && ratio>=MIN_EMPTY_RATIO

        private data class Bounds(val left: Int,val top: Int,val right: Int,val bottom: Int)
        private fun bounds(pixels: IntArray,width: Int,height: Int,region: GlyphBox): Bounds? {
            if(width<=0 || height<=0 || pixels.size.toLong()<width.toLong()*height ||
                !listOf(region.left,region.top,region.right,region.bottom).all { it.isFinite() }) return null
            val left=floor(region.left.coerceIn(0f,1f)*width).toInt()
            val top=floor(region.top.coerceIn(0f,1f)*height).toInt()
            val right=ceil(region.right.coerceIn(0f,1f)*width).toInt().coerceAtMost(width)
            val bottom=ceil(region.bottom.coerceIn(0f,1f)*height).toInt().coerceAtMost(height)
            return if(left>=right || top>=bottom) null else Bounds(left,top,right,bottom)
        }
        private fun brightness(color: Int)=((color ushr 16) and 255)+((color ushr 8) and 255)+(color and 255)
        private inline fun visit(pixels: IntArray,width: Int,bounds: Bounds,consume: (Int)->Unit) {
            val area=(bounds.right-bounds.left).toLong()*(bounds.bottom-bounds.top)
            val step=maxOf(1,sqrt(area.toDouble()/TARGET_SAMPLES).toInt())
            var y=bounds.top
            while(y<bounds.bottom) {
                var x=bounds.left
                while(x<bounds.right) { consume(pixels[y*width+x]); x+=step }
                y+=step
            }
        }
    }
}
