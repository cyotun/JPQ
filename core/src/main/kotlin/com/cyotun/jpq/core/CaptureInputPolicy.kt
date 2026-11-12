package com.cyotun.jpq.core

enum class CaptureScope { DISPLAY, APP_TASK }
data class CaptureDimensions(val width: Int,val height: Int)

/** No task pixels are accepted until the system has identified the content size. */
class CaptureContentGeometry(private val scope: CaptureScope) {
    @Volatile private var confirmed: CaptureDimensions?=null
    fun confirm(width: Int,height: Int) {
        require(width>0 && height>0)
        confirmed=CaptureDimensions(width,height)
    }
    fun accepts(width: Int,height: Int)=width>0 && height>0 &&
        (scope==CaptureScope.DISPLAY || confirmed==CaptureDimensions(width,height))
}

/** Screen-space overlays and captured app pixels are different coordinate domains. */
object CaptureInputPolicy {
    fun dimensions(width: Int,height: Int,scope: CaptureScope,reference: Boolean): CaptureDimensions {
        require(width>0 && height>0)
        if(scope==CaptureScope.APP_TASK) return CaptureDimensions(width,height)
        if(reference) return CaptureDimensions(maxOf(width,height),minOf(width,height))
        val scale=minOf(1.0,1280.0/maxOf(width,height))
        return CaptureDimensions((width*scale).toInt().coerceAtLeast(1),(height*scale).toInt().coerceAtLeast(1))
    }
    fun usesScreenOcclusions(scope: CaptureScope)=scope==CaptureScope.DISPLAY
    fun pausesForOverlayInteraction(scope: CaptureScope,interacting: Boolean)=usesScreenOcclusions(scope) && interacting
    /** Build identity controls the BETA floor; capability controls supported input. */
    fun requestedScope(singleAppCaptureEnabled: Boolean,candidate: Boolean,sdk: Int): CaptureScope {
        require(!candidate || (singleAppCaptureEnabled && sdk>=37)) {
            "BETA capture requires app-only support on API 37 or later"
        }
        return if(singleAppCaptureEnabled && sdk>=37) CaptureScope.APP_TASK else CaptureScope.DISPLAY
    }
    fun acceptsScope(scope: CaptureScope,singleAppCaptureEnabled: Boolean,sdk: Int)=
        scope==CaptureScope.DISPLAY || (singleAppCaptureEnabled && sdk>=37)
}
