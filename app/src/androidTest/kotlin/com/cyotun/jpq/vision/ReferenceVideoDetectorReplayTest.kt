package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.AppState
import com.cyotun.jpq.core.Rank
import com.cyotun.jpq.core.Suit
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit offline replay only. This never updates detector qualification/preferences. */
@RunWith(AndroidJUnit4::class)
class ReferenceVideoDetectorReplayTest {
    private data class InputFrame(val frame: Long,val ptsMs: Long,val elapsedMs: Long,val file: File)

    @Test fun replayAnExplicitSafeVideoSequenceOnTheVisionWorker() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("referenceVideoReplay") == "true")
        check(!AppState.running) { "Offline replay requires stopped capture" }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val external=checkNotNull(context.getExternalFilesDir(null))
        val base=File(external,"reference-video-replay")
        val input=File(base,"input").canonicalFile
        val manifest=JSONObject(File(input,"manifest.json").readText())
        require(manifest.getInt("width") == 1920 && manifest.getInt("height") == 882)
        require(manifest.getDouble("selectedStartPts") >= 463.0 && manifest.getDouble("selectedEndPts") <= 650.0)
        require(manifest.getDouble("approvedSafeEndPts") >= manifest.getDouble("selectedEndPts"))
        val entries=manifest.getJSONArray("frames")
        require(entries.length() > 0)
        val frames=ArrayList<InputFrame>(entries.length())
        var previousFrame=-1L; var previousPts=-1L; var previousElapsed=-1L
        for(index in 0 until entries.length()) {
            val entry=entries.getJSONObject(index)
            val number=entry.getLong("frame"); val pts=entry.getLong("ptsMs"); val elapsed=entry.getLong("elapsedMs")
            require(number > previousFrame && pts >= previousPts && elapsed >= previousElapsed)
            require(pts in 463000L..650000L && elapsed == pts)
            val relative=entry.getString("file")
            require(!File(relative).isAbsolute)
            val file=File(input,relative).canonicalFile
            require(file.toPath().startsWith(input.toPath()) && file != input && file.isFile)
            require(file.extension.lowercase() in setOf("jpg","jpeg"))
            frames.add(InputFrame(number,pts,elapsed,file))
            previousFrame=number; previousPts=pts; previousElapsed=elapsed
        }
        val limit=args.getString("referenceVideoFrameLimit")?.toInt() ?: frames.size
        require(limit in 1..frames.size)
        val selected=frames.take(limit)
        val stopRequest=File(base,"stop-requested")
        check(!stopRequest.exists()) { "Remove the prior offline stop-requested marker before replay" }
        val output=File(base,"output-${System.currentTimeMillis()}")
        check(output.mkdirs())
        val canceled=AtomicBoolean(false)
        val completion=CountDownLatch(1)
        val failure=AtomicReference<Throwable?>(null)
        val saveFailureImages=args.getString("referenceVideoFailureImages") == "true"
        check(VisionRuntime.handler.post {
            var full: LocalCardDetector?=null
            var wrapper: ReferenceCardDetector?=null
            var processed=0; var errorFrames=0; var savedImages=0; var lastFrame: InputFrame?=null
            var termination="input_exhausted"
            val started=SystemClock.elapsedRealtime()
            try {
                check(!AppState.running)
                // This test owns the CPU FULL instance. It is shared with the wrapper,
                // rather than borrowing/closing VisionRuntime's application detector.
                val ownedFull=LocalCardDetector(context,forceBackend="CPU",profile=DetectorProfile.FULL)
                full=ownedFull
                val active=ReferenceCardDetector(context,ownedFull,allowCompact=false)
                wrapper=active
                File(output,"run-metadata.json").writeText(JSONObject().put("kind","offline_continuous_native_detector_replay")
                    .put("pipelineVersion",ReferenceCardDetector.PIPELINE_VERSION).put("inputFrames",frames.size)
                    .put("selectedFrames",selected.size).put("initializationFailure",active.initializationFailure ?: JSONObject.NULL)
                    .put("sceneOrOcrValidated",false).toString(2))
                File(output,"detections.jsonl").bufferedWriter().use { writer ->
                    for(entry in selected) {
                        if(canceled.get() || stopRequest.exists()) { termination="stop_requested"; break }
                        check(!AppState.running) { "Capture started during the offline replay" }
                        lastFrame=entry
                        val decodeStarted=SystemClock.elapsedRealtime()
                        val decoded=checkNotNull(BitmapFactory.decodeFile(entry.file.absolutePath,
                            BitmapFactory.Options().apply { inPreferredConfig=Bitmap.Config.ARGB_8888; inMutable=true }))
                        val source=if(decoded.config == Bitmap.Config.ARGB_8888) decoded else {
                            val copy=checkNotNull(decoded.copy(Bitmap.Config.ARGB_8888,true)); decoded.recycle(); copy
                        }
                        val decodeImageMs=SystemClock.elapsedRealtime()-decodeStarted
                        try {
                            require(source.width == 1920 && source.height == 882)
                            val detected=active.detect(source,timestampMs=entry.elapsedMs)
                            val filtered=detected.filter { it.box.centerY()>.13f && it.box.centerX() in .10f.. .88f &&
                                it.box.width()<.12f && it.box.height()>.02f }
                            val row=JSONObject().put("frame",entry.frame).put("ptsMs",entry.ptsMs).put("elapsedMs",entry.elapsedMs)
                                .put("sourceWidth",source.width).put("sourceHeight",source.height).put("selectedProfile",active.selectedProfile.name)
                                .put("route",active.lastPlan?.route?.name ?: "FULL").put("backend",active.backend)
                                .put("decodeImageMs",decodeImageMs).put("nativeMs",finite(active.nativeMs)).put("packMs",finite(active.packMs))
                                .put("preparationMs",active.preparationMs).put("preprocessMs",active.preprocessMs)
                                .put("coverageGuardMs",active.coverageGuardMs).put("uncoveredSurfaceSampleCount",active.uncoveredSurfaceSampleCount)
                                .put("inferenceMs",active.inferenceMs).put("decodeMs",active.decodeMs).put("totalMs",active.totalMs)
                                .put("packedAttemptMs",active.packedAttemptMs).put("cacheHit",active.cacheHit)
                                .put("maxClassScore",finite(active.maxClassScore.toDouble()))
                                .put("fallbackReason",active.fallbackReason ?: JSONObject.NULL).put("errorReason",active.errorReason ?: JSONObject.NULL)
                                .put("initializationFailure",active.initializationFailure ?: JSONObject.NULL)
                                .put("projectionDroppedIndices",JSONArray(active.projectionDroppedIndices)).put("clippedToTileCount",active.clippedToTileCount)
                                .put("nativeRegionCount",active.nativeRegionCount).put("acceptedRegionCount",active.acceptedRegionCount)
                                .put("packedTileCount",active.packedTileCount).put("packedRawDetectionCount",active.packedRawDetectionCount)
                                .put("glyphCount",detected.size).put("glyphs",glyphs(detected)).put("filteredGlyphs",glyphs(filtered))
                                .put("nativeRegions",JSONArray(active.latestNativeRegions.map { region ->
                                    JSONObject().put("id",region.regionId).put("bbox",JSONArray(listOf(region.bbox.left,region.bbox.top,region.bbox.right,region.bbox.bottom)))
                                        .put("sampleCount",region.samplePointCount).put("state",region.recordState).put("source",region.source)
                                }))
                                .put("sourceTiles",JSONArray(active.lastManifest?.tiles.orEmpty().map { tile ->
                                    JSONArray(listOf(tile.sourceBBox.left,tile.sourceBBox.top,tile.sourceBBox.right,tile.sourceBBox.bottom))
                                }))
                            if(active.errorReason != null) {
                                errorFrames++
                                if(saveFailureImages && savedImages < 3) {
                                    writePng(File(output,"error-${entry.frame}-source.png"),source)
                                    val modelInput=runCatching { active.copyInputForProbe() }.getOrNull()
                                    modelInput?.let { try { writePng(File(output,"error-${entry.frame}-model-input.png"),it) } finally { it.recycle() } }
                                    savedImages++
                                }
                            }
                            writer.append(row.toString()).append('\n')
                            processed++
                            if(processed % 100 == 0) {
                                writer.flush()
                                progress(output,processed,selected.size,errorFrames,entry,SystemClock.elapsedRealtime()-started)
                            }
                        } finally { source.recycle() }
                    }
                    writer.flush()
                }
                lastFrame?.let { progress(output,processed,selected.size,errorFrames,it,SystemClock.elapsedRealtime()-started) }
            } catch(error: Throwable) {
                failure.set(error); termination="error"
                File(output,"failure.json").writeText(JSONObject().put("errorClass",error.javaClass.name)
                    .put("processedFrames",processed).put("frame",lastFrame?.frame ?: JSONObject.NULL)
                    .put("ptsMs",lastFrame?.ptsMs ?: JSONObject.NULL).toString(2))
            } finally {
                // Both close operations stay on their owner thread, even on cancellation.
                for(resource in listOf<AutoCloseable?>(wrapper,full)) {
                    try { resource?.close() } catch(error: Throwable) {
                        val prior=failure.get()
                        if(prior == null) failure.set(error) else prior.addSuppressed(error)
                        termination="error"
                    }
                }
                try {
                    File(output,"summary.json").writeText(JSONObject().put("kind","offline_detector_replay")
                        .put("termination",termination).put("inputFrames",frames.size).put("selectedFrames",selected.size)
                        .put("processedFrames",processed).put("errorFrames",errorFrames).put("wallMs",SystemClock.elapsedRealtime()-started)
                        .put("firstPtsMs",selected.first().ptsMs).put("lastProcessedPtsMs",lastFrame?.ptsMs ?: JSONObject.NULL)
                        .put("qualificationPreferencesWritten",false).put("sceneOrOcrValidated",false).toString(2))
                } catch(error: Throwable) { if(failure.get() == null) failure.set(error) }
                completion.countDown()
            }
        })
        if(!completion.await(30,TimeUnit.MINUTES)) {
            canceled.set(true)
            error("Offline replay worker exceeded its time bound; cleanup remains on the vision worker")
        }
        failure.get()?.let { throw it }
    }

    private fun finite(value: Double): Any=if(value.isFinite()) value else JSONObject.NULL
    private fun glyphs(detections: List<CardDetection>)=JSONArray(detections.map { detection ->
        JSONObject().put("rank",detection.card.rank.name).put("color",detection.card.suit.name)
            .put("conf",finite(detection.confidence.toDouble())).put("bbox",JSONArray(listOf(detection.box.left,detection.box.top,
                detection.box.right,detection.box.bottom))).put("classId",classId(detection))
    })
    private fun classId(detection: CardDetection): Int {
        if(detection.card.rank == Rank.BIG) return 26
        if(detection.card.rank == Rank.SMALL) return 27
        val rank=listOf(Rank.A,Rank.TWO,Rank.THREE,Rank.FOUR,Rank.FIVE,Rank.SIX,Rank.SEVEN,Rank.EIGHT,Rank.NINE,Rank.TEN,Rank.J,Rank.Q,Rank.K)
            .indexOf(detection.card.rank)
        return rank+if(detection.card.suit == Suit.BLACK) 13 else 0
    }
    private fun writePng(file: File,bitmap: Bitmap)=file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
    private fun progress(output: File,processed: Int,total: Int,errorFrames: Int,last: InputFrame,wallMs: Long) {
        val data=JSONObject().put("processedFrames",processed).put("selectedFrames",total).put("errorFrames",errorFrames)
            .put("lastFrame",last.frame).put("lastPtsMs",last.ptsMs).put("wallMs",wallMs)
        File(output,"progress.json").writeText(data.toString(2))
        Log.i("JPQVideoReplay",data.toString())
    }
}

