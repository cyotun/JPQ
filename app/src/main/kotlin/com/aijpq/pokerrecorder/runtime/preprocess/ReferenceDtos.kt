package com.aijpq.pokerrecorder.runtime.preprocess

import android.graphics.Bitmap
import androidx.annotation.Keep

/** JNI object names and primary constructor types are part of the native ABI. */
@Keep
class IntBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right.toLong() - left).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    val height: Int get() = (bottom.toLong() - top).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    val area: Int get() = (width.toLong() * height).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

@Keep
class ActiveRegion(
    val regionId: String, val trackId: String?, val bbox: IntBox,
    val supportRatio: Double, val stableFrameCount: Int, val recordState: String,
    val samplePointCount: Int, val evidencePointCount: Int, val source: String,
    val areaType: String?, val observationId: String?, val observationTimestampMs: Long?,
    val colorBootstrapRuleId: String?, val colorBootstrapCandidateBox: IntBox?,
    val colorBootstrapAdjacentWhiteBox: IntBox?
)

@Keep
class PreprocessMetrics(
    val sampleMs: Double, val whiteMaskMs: Double, val pointStateMs: Double,
    val pixelStateScanMs: Double, val prefixRebuildMs: Double,
    val suppressionPropagationMs: Double, val connectedRegionsMs: Double,
    val trackMs: Double, val packMs: Double, val clearMs: Double,
    val totalMs: Double, val densitySplitMs: Double, val nativeMemoryEstimateBytes: Long,
    val trackCount: Int, val rawRegionCount: Int, val stableRegionCount: Int,
    val suppressedPointCount: Int, val excludedPointCount: Int, val sampleCols: Int,
    val sampleRows: Int, val samplePointCount: Int, val densitySplitParentCount: Int,
    val densitySplitChildCount: Int, val densitySplitRejectedChildCount: Int,
    val evidenceRejectedRegionCount: Int, val evidencePointCountTotal: Int,
    val evidenceMaxPointCount: Int
)

@Keep
class NegativeUiRingMatch(
    val ruleId: String, val trackId: String?, val bbox: IntBox,
    val ringPointCount: Int, val ringMatchedPointCount: Int, val centerPointCount: Int,
    val centerActivePointCount: Int, val coveredSectorCount: Int,
    val ringMatchRatio: Double, val centerActiveRatio: Double
)

@Keep
class NativePreprocessResult(
    val frameWidth: Int, val frameHeight: Int, val frameIndex: Long, val timestampMs: Long,
    val regions: List<ActiveRegion>, val metrics: PreprocessMetrics,
    val negativeUiRingMatches: List<NegativeUiRingMatch>, val negativeUiRingScanMs: Double
)

@Keep
class CanonicalPackingFrame(
    val frameClass: String, val longSideLimit: Int, val shortSideLimit: Int,
    val fitScale: Double, val scale: Double, val clamped: Boolean
)

@Keep
class PackedTile(
    val tileId: String, val sourceRegionId: String, val sourceBBox: IntBox,
    val packedBBox: IntBox, val scale: Double, val splitGroupId: String?,
    val splitPart: String?, val trackId: String?, val recordState: String,
    val regionSource: String, val areaType: String?, val observationId: String?,
    val overlayGroupId: String?, val overlayIndex: Int?, val overlayCount: Int?,
    val overlayOverlapPx: Int?
)

@Keep
class PackingManifest(
    val canvasSize: Int, val tiles: List<PackedTile>, val globalScale: Double,
    val scaleMode: String, val canonicalFrame: CanonicalPackingFrame?,
    val layoutBounds: IntBox?, val canvasUtilization: Double
)

/** The caller owns this bitmap. Closing the session does not recycle delivered frames. */
class PackedFrame(val bitmap: Bitmap, val manifest: PackingManifest, val metrics: PreprocessMetrics): AutoCloseable {
    override fun close() { if (!bitmap.isRecycled) bitmap.recycle() }
}

/** Arrays are the audited native wire format; no script, key or JSON is passed to JNI. */
data class NativePreprocessConfig(
    val sampleLongSidePoints: Int = 378,
    val holdSeconds: Double = 5.0,
    val suppressionPropagationSeconds: Double = 3.0,
    val cooldownSeconds: Double = .5,
    val minRegionAreaPx: Int = 400,
    val minRegionSamplePoints: Int = 35,
    val stableMinSupportRatio: Double = .4,
    val regionConfirmFrames: Int = 3,
    val horizontalMergeGapSamplePoints: Int = 8,
    val maxTracks: Int = 32,
    val missingGraceFrames: Int = 2,
    val trackTtlFrames: Int = 45,
    val evidenceEnabled: Boolean = true,
    val evidenceMinPoints: Int = 1,
    val evidenceApplyToHand: Boolean = true,
    val evidenceRules: IntArray = defaultGlyphEvidence(),
    val excludedRects: DoubleArray = doubleArrayOf(),
    val activeRules: IntArray = intArrayOf(),
    val negativeRingRuleIds: Array<String> = emptyArray(),
    val negativeRingColors: IntArray = intArrayOf()
) {
    companion object {
        /** Audited default_black/default_red rules, flattened to the 16-int wire format. */
        fun defaultGlyphEvidence() = intArrayOf(
            0,75,0,75,0,75,45,-1,-1,0,-1000,-1000,-1000,-1000,-1000,-1000,
            140,255,0,100,0,110,-1,0,45,0,-1000,-1000,-1000,-1000,-1000,-1000
        )
    }
    fun validate() {
        require(sampleLongSidePoints > 0 && minRegionAreaPx >= 0 && minRegionSamplePoints >= 0)
        require(regionConfirmFrames > 0 && horizontalMergeGapSamplePoints >= 0 && maxTracks > 0)
        require(missingGraceFrames >= 0 && trackTtlFrames > 0 && evidenceMinPoints >= 0)
        require(listOf(holdSeconds, suppressionPropagationSeconds, cooldownSeconds).all { it.isFinite() && it >= 0 })
        require(stableMinSupportRatio.isFinite() && stableMinSupportRatio in 0.0..1.0)
        require(evidenceRules.size % 16 == 0 && activeRules.size % 16 == 0)
        require(negativeRingColors.size == negativeRingRuleIds.size * 16)
        require(excludedRects.size % 4 == 0)
        for (offset in excludedRects.indices step 4) {
            val x=excludedRects[offset]; val y=excludedRects[offset+1]
            val w=excludedRects[offset+2]; val h=excludedRects[offset+3]
            require(listOf(x,y,w,h).all { it.isFinite() && it >= 0.0 })
            require(x+w <= 1.0 && y+h <= 1.0)
        }
    }
}
