package com.aijpq.pokerrecorder.runtime.preprocess

import android.graphics.Bitmap
import androidx.annotation.Keep

/** Optional bridge. Only constructing a session loads the audited arm64 library. */
@Keep
class NativeActiveRegionSession: AutoCloseable {
    companion object {
        private val libraryLoaded by lazy { System.loadLibrary("mnnyolodetector") }
    }
    private var handle: Long
    private var lastFrameIndex = -1L
    private var lastTimestampMs = -1L
    private var lastSource: Bitmap? = null

    init {
        libraryLoaded
        handle = nativeCreate()
        check(handle != 0L) { "Reference native session was not created" }
    }

    @Synchronized
    fun update(source: Bitmap, frameIndex: Long, timestampMs: Long, config: NativePreprocessConfig): NativePreprocessResult {
        check(handle != 0L) { "Reference native session is closed" }
        require(!source.isRecycled && source.config == Bitmap.Config.ARGB_8888)
        require(frameIndex > lastFrameIndex && timestampMs >= lastTimestampMs)
        config.validate()
        val result = nativeUpdate(handle, source, frameIndex, timestampMs,
            config.sampleLongSidePoints, config.holdSeconds, config.suppressionPropagationSeconds,
            config.cooldownSeconds, config.minRegionAreaPx, config.minRegionSamplePoints,
            config.stableMinSupportRatio, config.regionConfirmFrames, config.horizontalMergeGapSamplePoints,
            config.maxTracks, config.missingGraceFrames, config.trackTtlFrames, config.evidenceEnabled,
            config.evidenceMinPoints, config.evidenceApplyToHand, config.evidenceRules,
            config.excludedRects, config.activeRules, config.negativeRingRuleIds, config.negativeRingColors)
        check(result.frameWidth == source.width && result.frameHeight == source.height) { "Native source dimensions differ" }
        check(result.frameIndex == frameIndex && result.timestampMs == timestampMs) { "Native frame identity differs" }
        lastFrameIndex=frameIndex; lastTimestampMs=timestampMs; lastSource=source
        return result
    }

    @Synchronized
    fun composePacked(source: Bitmap, manifest: PackingManifest): PackedFrame {
        check(handle != 0L) { "Reference native session is closed" }
        check(source === lastSource && !source.isRecycled) { "Compose must use the most recently updated source" }
        require(manifest.canvasSize == 320 || manifest.canvasSize == 640)
        require(manifest.tiles.isNotEmpty())
        manifest.tiles.forEach { tile ->
            val a=tile.sourceBBox; val b=tile.packedBBox
            require(a.left >= 0 && a.top >= 0 && a.right <= source.width && a.bottom <= source.height && a.width > 0 && a.height > 0)
            require(b.left >= 0 && b.top >= 0 && b.right <= manifest.canvasSize && b.bottom <= manifest.canvasSize && b.width > 0 && b.height > 0)
        }
        val target=Bitmap.createBitmap(manifest.canvasSize,manifest.canvasSize,Bitmap.Config.ARGB_8888)
        try {
            val metrics=nativeComposePacked(handle,source,target,manifest)
            return PackedFrame(target,manifest,metrics)
        } catch (error: Throwable) {
            target.recycle()
            throw error
        }
    }

    @Synchronized
    fun reset(reason: String) {
        check(handle != 0L) { "Reference native session is closed" }
        nativeReset(handle,reason)
        lastSource=null; lastFrameIndex=-1; lastTimestampMs=-1
    }

    @Synchronized
    override fun close() {
        val previous=handle; handle=0; lastSource=null
        if(previous != 0L) nativeRelease(previous)
    }

    // Unused detect* branches are deliberately absent: their DTOs are not integrated.
    private external fun nativeCreate(): Long
    private external fun nativeUpdate(handle: Long, source: Bitmap, frameIndex: Long, timestampMs: Long,
        sampleLongSidePoints: Int, holdSeconds: Double, suppressionPropagationSeconds: Double,
        cooldownSeconds: Double, minRegionAreaPx: Int, minRegionSamplePoints: Int,
        stableMinSupportRatio: Double, regionConfirmFrames: Int, horizontalMergeGapSamplePoints: Int,
        maxTracks: Int, missingGraceFrames: Int, trackTtlFrames: Int, evidenceEnabled: Boolean,
        evidenceMinPoints: Int, evidenceApplyToHand: Boolean, evidenceRules: IntArray,
        excludedRects: DoubleArray, activeRules: IntArray, negativeRingRuleIds: Array<String>,
        negativeRingColors: IntArray): NativePreprocessResult
    private external fun nativeComposePacked(handle: Long, source: Bitmap, target: Bitmap, manifest: PackingManifest): PreprocessMetrics
    private external fun nativeReset(handle: Long, reason: String)
    private external fun nativeRelease(handle: Long)
}
