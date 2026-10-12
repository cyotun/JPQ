package com.cyotun.jpq.core

/** Reference-compatible integer raster placement; the original scale also maps detections back. */
data class LetterboxGeometry(val scale: Float,val width: Int,val height: Int,val left: Int,val top: Int) {
    companion object {
        fun fit(sourceWidth: Int,sourceHeight: Int,targetWidth: Int,targetHeight: Int): LetterboxGeometry {
            require(sourceWidth>0 && sourceHeight>0 && targetWidth>0 && targetHeight>0)
            val scale=minOf(targetWidth.toFloat()/sourceWidth,targetHeight.toFloat()/sourceHeight)
            val width=(sourceWidth*scale).toInt().coerceIn(1,targetWidth)
            val height=(sourceHeight*scale).toInt().coerceIn(1,targetHeight)
            return LetterboxGeometry(scale,width,height,(targetWidth-width)/2,(targetHeight-height)/2)
        }
    }
}
