package com.cyotun.jpq.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.aijpq.pokerrecorder.runtime.preprocess.NativeActiveRegionSession
import com.aijpq.pokerrecorder.runtime.preprocess.NativePreprocessConfig
import com.aijpq.pokerrecorder.runtime.preprocess.NativePreprocessResult
import com.aijpq.pokerrecorder.runtime.preprocess.ActiveRegion
import com.aijpq.pokerrecorder.runtime.preprocess.PackingManifest

/**
 * Native active regions -> audited per-tile packing -> packed model -> source coordinates.
 * The supplied FULL detector is shared and is never closed by this wrapper. All resources,
 * including native preparation, inference, diagnostic copies and close, stay on VisionRuntime.
 */
class ReferenceCardDetector(
    context: Context,
    private val fullFallback: LocalCardDetector,
    val allowCompact: Boolean = false,
    nativeConfig: NativePreprocessConfig = NativePreprocessConfig(),
    handSeed: DoubleArray = doubleArrayOf(.10,.58,.78,.32)
) : AutoCloseable {
    private val owner = Thread.currentThread()
    private var preprocessor: ReferenceRegionPreprocessor? = null
    private var packed640: LocalCardDetector? = null
    private var packed320: LocalCardDetector? = null
    private var selectedDetector: LocalCardDetector? = null
    private var closed = false
    private var frameIndex = 0L
    private var sourceWidth = 0
    private var sourceHeight = 0
    private val coverageGuard=PackedCoverageGuard()

    var initializationFailure: String? = null; private set
    var selectedProfile = DetectorProfile.FULL; private set
    val backend: String get() = (selectedDetector ?: fullFallback).backend
    val inferenceCount: Long get() = fullFallback.inferenceCount+(packed640?.inferenceCount ?: 0)+(packed320?.inferenceCount ?: 0)
    var lastPlan: ReferenceRegionPreprocessor.Plan? = null; private set
    var lastNativeResult: NativePreprocessResult? = null; private set
    val latestNativeRegions: List<ActiveRegion> get() = lastNativeResult?.regions.orEmpty()
    val lastManifest: PackingManifest? get() = lastPlan?.manifest
    var fallbackReason: String? = null; private set
    var errorReason: String? = null; private set
    var nativeMs = 0.0; private set
    var packMs = 0.0; private set
    var preparationMs = 0L; private set
    // Model-stage totals include a packed attempt followed by FULL when fallback is needed.
    var preprocessMs = 0L; private set
    var inferenceMs = 0L; private set
    var decodeMs = 0L; private set
    var totalMs = 0L; private set
    var packedAttemptMs = 0L; private set
    var cacheHit = false; private set
    var maxClassScore = 0f; private set
    var nativeRegionCount = 0; private set
    var acceptedRegionCount = 0; private set
    var packedTileCount = 0; private set
    var packedRawDetectionCount = 0; private set
    var sourceDetectionCount = 0; private set
    var projectionDroppedIndices: List<Int> = emptyList(); private set
    var clippedToTileCount = 0; private set
    var coverageGuardMs=0L; private set
    var uncoveredSurfaceSampleCount=0; private set

    init {
        check(Looper.myLooper() == VisionRuntime.handler.looper) { "Reference detector requires the vision worker" }
        require(fullFallback.profile == DetectorProfile.FULL) { "A shared FULL fallback is required" }
        var session: NativeActiveRegionSession? = null
        try {
            session = NativeActiveRegionSession()
            // JPQ's explicit Guandan seed matches its existing lower-row partition;
            // it is not presented as a recovered original game configuration.
            preprocessor = ReferenceRegionPreprocessor(session, nativeConfig,
                ReferenceRegionPreprocessor.PackingConfig(allowCompact320 = allowCompact),handSeed)
            packed640 = LocalCardDetector(context.applicationContext, "CPU", DetectorProfile.PACKED_640)
            // 320 can be constructed and selected only through this explicit opt-in.
            if (allowCompact) packed320 = LocalCardDetector(context.applicationContext, "CPU", DetectorProfile.PACKED_320)
        } catch (error: Exception) {
            initializationFailed(error, session)
        } catch (error: LinkageError) {
            initializationFailed(error, session)
        }
    }

    private fun initializationFailed(error: Throwable, session: NativeActiveRegionSession?) {
        initializationFailure = "reference_init:${error.javaClass.simpleName}"
        runCatching { packed320?.close() }; packed320 = null
        runCatching { packed640?.close() }; packed640 = null
        runCatching { session?.close() }; preprocessor = null
        Log.w(TAG, initializationFailure, error)
    }

    fun detect(source: Bitmap, timestampMs: Long = SystemClock.elapsedRealtime()): List<CardDetection> {
        checkOwner()
        require(!source.isRecycled && source.width > 0 && source.height > 0)
        clearFrameMetrics()
        val started = SystemClock.elapsedRealtime()
        try {
            val regions = preprocessor
            if (regions == null) {
                errorReason = initializationFailure ?: "reference_preprocessor_unavailable"
                return detectFull(source, errorReason!!)
            }
            var preparation: ReferenceRegionPreprocessor.Preparation? = null
            val preparationStarted = SystemClock.elapsedRealtime()
            try {
                if (sourceWidth != 0 && (sourceWidth != source.width || sourceHeight != source.height)) {
                    regions.reset("source_dimensions_changed")
                    frameIndex = 0
                }
                sourceWidth = source.width; sourceHeight = source.height
                preparation = regions.prepare(source, ++frameIndex, timestampMs)
            } catch (error: Exception) {
                errorReason = "reference_prepare:${error.javaClass.simpleName}"
            } catch (error: LinkageError) {
                errorReason = "reference_prepare:${error.javaClass.simpleName}"
            } finally {
                preparationMs = SystemClock.elapsedRealtime() - preparationStarted
            }
            val prepared = preparation ?: return detectFull(source, errorReason ?: "reference_prepare_unavailable")
            prepared.use { ready ->
                val plan = ready.plan
                lastPlan = plan
                lastNativeResult = ready.native
                nativeMs = (ready.native?.metrics?.totalMs ?: 0.0)+ready.specialHandScanMs
                packMs = ready.packed?.metrics?.packMs ?: 0.0
                nativeRegionCount = ready.native?.regions?.size ?: 0
                acceptedRegionCount = plan.acceptedRegionIds.size
                packedTileCount = plan.manifest?.tiles?.size ?: 0
                when (plan.route) {
                    ReferenceRegionPreprocessor.Route.NO_ACTIVE -> {
                        // Absence of an effective region is not proof that a seat is empty.
                        return detectFull(source, "no_effective_native_roi")
                    }
                    ReferenceRegionPreprocessor.Route.FULL_FALLBACK -> {
                        if (plan.reason?.startsWith("native_") == true) errorReason = plan.reason
                        return detectFull(source, plan.reason ?: "packing_requires_full")
                    }
                    else -> Unit
                }
                val profile = if (plan.route == ReferenceRegionPreprocessor.Route.PACKED_320)
                    DetectorProfile.PACKED_320 else DetectorProfile.PACKED_640
                val model = if (profile == DetectorProfile.PACKED_320) packed320 else packed640
                val packed = ready.packed
                val manifest = plan.manifest
                if (model == null || packed == null || manifest == null) {
                    errorReason = "packed_route_resources_unavailable"
                    return detectFull(source, errorReason!!)
                }

                val coverageStarted=SystemClock.elapsedRealtime()
                val coverage=coverageGuard.evaluate(source,manifest)
                coverageGuardMs=SystemClock.elapsedRealtime()-coverageStarted
                uncoveredSurfaceSampleCount=coverage.sampleCount
                if(coverage.uncoveredSurface)
                    return detectFull(source,"uncovered_card_surface")

                val attemptStarted = SystemClock.elapsedRealtime()
                val raw = try {
                    runModel(model, packed.bitmap)
                } catch (error: Exception) {
                    errorReason = "packed_inference:${error.javaClass.simpleName}"
                    null
                } catch (error: LinkageError) {
                    errorReason = "packed_inference:${error.javaClass.simpleName}"
                    null
                } finally {
                    packedAttemptMs = SystemClock.elapsedRealtime() - attemptStarted
                }
                if (raw == null) return detectFull(source, errorReason ?: "packed_inference_unavailable")
                packedRawDetectionCount = raw.size
                if (raw.isEmpty() && plan.acceptedRegionIds.isNotEmpty())
                    return detectFull(source, "effective_roi_without_packed_detections")

                val projectionStarted = SystemClock.elapsedRealtime()
                val projected = try {
                    ReferenceRegionPreprocessor.project(raw, manifest, source.width, source.height)
                } catch (error: Exception) {
                    errorReason = "packed_projection:${error.javaClass.simpleName}"
                    null
                } finally {
                    decodeMs += SystemClock.elapsedRealtime() - projectionStarted
                }
                if (projected == null) return detectFull(source, errorReason ?: "packed_projection_unavailable")
                projectionDroppedIndices = projected.droppedIndices
                clippedToTileCount = projected.clippedToTileCount
                if (projected.requiresFallback)
                    return detectFull(source, if (projected.droppedIndices.isNotEmpty())
                        "packed_projection_dropped" else "packed_projection_clipped")
                select(model)
                return finish(projected.detections.map { it.detection })
            }
        } finally {
            totalMs = SystemClock.elapsedRealtime() - started
        }
    }

    private fun runModel(detector: LocalCardDetector, source: Bitmap): List<CardDetection> {
        val detections = detector.detect(source)
        preprocessMs += detector.preprocessMs
        inferenceMs += detector.inferenceMs
        decodeMs += detector.decodeMs
        return detections
    }

    private fun detectFull(source: Bitmap, reason: String): List<CardDetection> {
        fallbackReason = reason
        val detections = runModel(fullFallback, source)
        select(fullFallback)
        return finish(detections)
    }

    private fun select(detector: LocalCardDetector) {
        selectedDetector = detector
        selectedProfile = detector.profile
        cacheHit = detector.cacheHit
        maxClassScore = detector.maxClassScore
    }

    private fun finish(detections: List<CardDetection>): List<CardDetection> {
        val started = SystemClock.elapsedRealtime()
        val selected = ArrayList<CardDetection>()
        for (candidate in detections.sortedByDescending { it.confidence }) {
            if (selected.none { previous -> iou(previous.box, candidate.box) > .45f })
                selected.add(candidate.copy(box = RectF(candidate.box)))
        }
        decodeMs += SystemClock.elapsedRealtime() - started
        sourceDetectionCount = selected.size
        return selected
    }

    /** Copies the raster of the model actually selected for the last completed detect call. */
    fun copyInputForProbe(): Bitmap {
        checkOwner()
        return checkNotNull(selectedDetector) { "No completed detection input is available" }.copyInputForProbe()
    }

    fun reset(reason: String) {
        checkOwner()
        preprocessor?.reset(reason)
        frameIndex = 0
        sourceWidth = 0; sourceHeight = 0
        clearFrameMetrics()
    }

    private fun clearFrameMetrics() {
        selectedDetector = null; selectedProfile = DetectorProfile.FULL
        lastPlan = null; lastNativeResult = null; fallbackReason = null; errorReason = null
        nativeMs = 0.0; packMs = 0.0; preparationMs = 0
        preprocessMs = 0; inferenceMs = 0; decodeMs = 0; totalMs = 0; packedAttemptMs = 0
        cacheHit = false; maxClassScore = 0f
        nativeRegionCount = 0; acceptedRegionCount = 0; packedTileCount = 0
        packedRawDetectionCount = 0; sourceDetectionCount = 0
        projectionDroppedIndices = emptyList(); clippedToTileCount = 0
        coverageGuardMs=0; uncoveredSurfaceSampleCount=0
    }

    private fun checkOwner() {
        check(Thread.currentThread() === owner && Looper.myLooper() == VisionRuntime.handler.looper) {
            "Reference detector must stay on its vision worker"
        }
        check(!closed) { "Reference detector already closed" }
    }

    override fun close() {
        check(Thread.currentThread() === owner && Looper.myLooper() == VisionRuntime.handler.looper)
        if (closed) return
        closed = true
        selectedDetector = null
        lastNativeResult = null; lastPlan = null
        var failure: Throwable? = null
        for (resource in listOf<AutoCloseable?>(packed320, packed640, preprocessor,coverageGuard)) {
            try { resource?.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        packed320 = null; packed640 = null; preprocessor = null
        failure?.let { throw it }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val area = maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left)) *
            maxOf(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        return area / (a.width() * a.height() + b.width() * b.height() - area).coerceAtLeast(.000001f)
    }

    companion object {
        /** Qualification applies only to the explicit eight-fixture CPU test, not whole games. */
        const val PIPELINE_VERSION = "reference-native-hand-exit-coverage-pack640-20261014-v5"
        private const val TAG = "JPQReference"
    }
}
