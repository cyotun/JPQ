package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aijpq.pokerrecorder.runtime.preprocess.*
import com.cyotun.jpq.AppState
import com.cyotun.jpq.core.Card
import com.cyotun.jpq.core.Rank
import com.cyotun.jpq.core.Suit
import com.cyotun.jpq.core.GlyphBox
import com.cyotun.jpq.core.PlayGlyph
import com.cyotun.jpq.core.partitionGuandanHand
import com.cyotun.jpq.core.colorKey
import java.io.File
import java.lang.reflect.Modifier
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Dormant unless referenceNativeProbe=true. These probes do not establish card accuracy. */
@RunWith(AndroidJUnit4::class)
class ReferenceNativePreprocessTest {
    @Before fun explicitProbeOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("referenceNativeProbe") == "true")
        check(!AppState.running) { "Reference fixture probe requires stopped capture" }
    }

    @Test fun auditedJvmConstructorsAndNativeSignaturesExist() {
        val intType=Int::class.javaPrimitiveType!!; val longType=Long::class.javaPrimitiveType!!
        val doubleType=Double::class.javaPrimitiveType!!
        IntBox::class.java.getConstructor(intType,intType,intType,intType)
        NativePreprocessResult::class.java.getConstructor(intType,intType,longType,longType,
            List::class.java,PreprocessMetrics::class.java,List::class.java,doubleType)
        val metricTypes=List(12) { doubleType }+longType+List(14) { intType }
        PreprocessMetrics::class.java.getConstructor(*metricTypes.toTypedArray())
        val regionTypes=arrayOf(String::class.java,String::class.java,IntBox::class.java,doubleType,intType,
            String::class.java,intType,intType,String::class.java,String::class.java,String::class.java,
            java.lang.Long::class.java,String::class.java,IntBox::class.java,IntBox::class.java)
        ActiveRegion::class.java.getConstructor(*regionTypes)
        assertEquals(intType,IntBox::class.java.getMethod("getArea").returnType)
        val update=NativeActiveRegionSession::class.java.declaredMethods.single { it.name == "nativeUpdate" }
        assertTrue(Modifier.isNative(update.modifiers))
        assertEquals(NativePreprocessResult::class.java,update.returnType)
        assertEquals(24,update.parameterTypes.size)
        val compose=NativeActiveRegionSession::class.java.getDeclaredMethod("nativeComposePacked",
            longType,Bitmap::class.java,Bitmap::class.java,PackingManifest::class.java)
        assertTrue(Modifier.isNative(compose.modifiers))
        assertEquals(PreprocessMetrics::class.java,compose.returnType)
    }

    @Test fun packingNeverSilentlyDropsOverflowOrInvalidRegions() {
        val giant=region("giant",IntBox(0,0,1000,500))
        val overflow=ReferenceRegionPreprocessor.plan(listOf(giant),1000,500,
            ReferenceRegionPreprocessor.PackingConfig(overlaySplit=false))
        assertEquals(ReferenceRegionPreprocessor.Route.FULL_FALLBACK,overflow.route)
        assertEquals(listOf("giant"),overflow.overflowRegionIds)
        assertNull(overflow.manifest)
        val invalid=ReferenceRegionPreprocessor.plan(listOf(region("outside",IntBox(-1,0,30,30))),1000,500)
        assertEquals(listOf("outside"),invalid.invalidRegionIds)
        assertTrue(invalid.requiresFallback)
        val small=region("small",IntBox(100,100,180,180))
        val defaultPlan=ReferenceRegionPreprocessor.plan(listOf(small),1000,500)
        assertEquals(ReferenceRegionPreprocessor.Route.PACKED_640,defaultPlan.route)
        val compact=ReferenceRegionPreprocessor.plan(listOf(small),1000,500,
            ReferenceRegionPreprocessor.PackingConfig(allowCompact320=true))
        assertEquals(ReferenceRegionPreprocessor.Route.PACKED_320,compact.route)
        assertEquals(defaultPlan.manifest!!.globalScale,compact.manifest!!.globalScale,0.0)
        assertEquals(setOf("small"),compact.manifest!!.tiles.map { it.sourceRegionId }.toSet())
    }

    @Test fun packedBoundsReturnToSourceAndUnmappedBoxesAreReported() {
        val tile=PackedTile("tile","region",IntBox(100,200,300,400),IntBox(8,8,108,108),.5,
            null,null,null,"stable","fixture",null,null,null,null,null,null)
        val manifest=PackingManifest(320,listOf(tile),.5,"fixture",null,tile.packedBBox,0.0)
        val card=Card(Rank.A,Suit.RED)
        val detections=listOf(CardDetection(card,.9f,RectF(33f/320,58f/320,83f/320,108f/320)),
            CardDetection(card,.8f,RectF(.7f,.7f,.8f,.8f)))
        val projection=ReferenceRegionPreprocessor.project(detections,manifest,1000,500)
        assertEquals(1,projection.detections.size)
        val mapped=projection.detections.single().detection.box
        assertEquals(.15f,mapped.left,.00001f); assertEquals(.6f,mapped.top,.00001f)
        assertEquals(.25f,mapped.right,.00001f); assertEquals(.8f,mapped.bottom,.00001f)
        assertEquals(listOf(1),projection.droppedIndices)
        assertTrue(projection.requiresFallback)
    }

    @Test fun numericSamplesDoNotUseTheMissingSamplePixelAreaFallback() {
        // The native snapshot always contains an integer sample count. Even 400 source
        // pixels do not override a numeric count below the effective sample threshold.
        val low=region("large-pixel-small-sample",IntBox(100,100,120,120),303)
        assertEquals(400,low.bbox.area)
        val rejected=ReferenceRegionPreprocessor.plan(listOf(low),1000,500)
        assertEquals(ReferenceRegionPreprocessor.Route.NO_ACTIVE,rejected.route)
        assertEquals(listOf(low.regionId),rejected.filteredRegionIds)
        assertTrue(rejected.acceptedRegionIds.isEmpty())
        val boundary=region("sample-boundary",IntBox(100,100,120,120),304)
        val accepted=ReferenceRegionPreprocessor.plan(listOf(boundary),1000,500)
        assertEquals(ReferenceRegionPreprocessor.Route.PACKED_640,accepted.route)
        assertEquals(listOf(boundary.regionId),accepted.acceptedRegionIds)
        val zero=region("numeric-zero",IntBox(100,100,150,150),0)
        assertEquals(ReferenceRegionPreprocessor.Route.NO_ACTIVE,
            ReferenceRegionPreprocessor.plan(listOf(zero),1000,500).route)
    }

    @Test fun nativeFixtureUpdateAndComposeWriteLocalEvidence() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val arguments=InstrumentationRegistry.getArguments()
        val fixturePath=checkNotNull(arguments.getString("fixturePath")) { "Explicit fixturePath is required" }
        val fixture=File(fixturePath)
        require(fixture.isAbsolute && fixture.isFile && fixture.extension.lowercase() in setOf("png","jpg","jpeg"))
        val folder=File(instrumentation.targetContext.filesDir,"reference-native-probe/${System.currentTimeMillis()}")
        check(folder.mkdirs())
        val summary=JSONArray()
        // Missing libraries/ABI errors deliberately fail this explicitly requested probe.
        NativeActiveRegionSession().use { session ->
            var frameIndex=0L; var timestamp=SystemClock.elapsedRealtime()
            val name=fixture.name
            val source=fixture.inputStream().use { stream ->
                    val decoded=checkNotNull(BitmapFactory.decodeStream(stream))
                    if(decoded.config == Bitmap.Config.ARGB_8888 && decoded.isMutable) decoded else {
                        val copy=checkNotNull(decoded.copy(Bitmap.Config.ARGB_8888,true)); decoded.recycle(); copy
                    }
            }
                try {
                    session.reset("fixture_boundary")
                    var native: NativePreprocessResult?=null
                    repeat(4) { native=session.update(source,++frameIndex,timestamp,NativePreprocessConfig()); timestamp+=100 }
                    val result=checkNotNull(native)
                    assertEquals(source.width,result.frameWidth); assertEquals(source.height,result.frameHeight)
                    assertTrue(result.metrics.totalMs.isFinite() && result.metrics.totalMs >= 0)
                    val detectedPlan=ReferenceRegionPreprocessor.plan(result.regions,source.width,source.height)
                    val data=planJson(detectedPlan).put("fixture",name).put("frameIndex",result.frameIndex)
                        .put("sourceWidth",source.width).put("sourceHeight",source.height)
                        .put("nativeRegionCount",result.regions.size).put("nativeMs",result.metrics.totalMs)
                        .put("samplePointCount",result.metrics.samplePointCount)
                    detectedPlan.manifest?.let { manifest ->
                        session.composePacked(source,manifest).use { packed ->
                            writePng(File(folder,"native-packed.png"),packed.bitmap)
                            data.put("nativePackMs",packed.metrics.packMs)
                            data.put("nativePipeline",runPipeline(instrumentation.targetContext,source,packed.bitmap,
                                detectedPlan,folder,"native"))
                        }
                    }
                    if(detectedPlan.route == ReferenceRegionPreprocessor.Route.FULL_FALLBACK) {
                        data.put("nativePipeline",runPipeline(instrumentation.targetContext,source,source,detectedPlan,folder,"native-fallback"))
                    } else if(detectedPlan.route == ReferenceRegionPreprocessor.Route.NO_ACTIVE) {
                        data.put("nativePipeline",JSONObject().put("observed",false).put("reason","no_effective_native_roi"))
                    }
                    // Controlled compose exercises the ABI even when global detection reports no active ROI.
                    // This crop is explicitly a fixture rectangle, not an assertion of native card ownership.
                    val controlled=region("manual-fixture",IntBox(0,0,minOf(220,source.width),minOf(120,source.height)))
                    val controlledPlan=ReferenceRegionPreprocessor.plan(listOf(controlled),source.width,source.height,
                        ReferenceRegionPreprocessor.PackingConfig(sourcePadding=0,overlaySplit=false))
                    val controlledManifest=checkNotNull(controlledPlan.manifest)
                    session.composePacked(source,controlledManifest).use { packed ->
                        assertEquals(640,packed.bitmap.width); assertEquals(640,packed.bitmap.height)
                        writePng(File(folder,"controlled-packed.png"),packed.bitmap)
                        data.put("controlledCompose",planJson(controlledPlan)).put("controlledPackMs",packed.metrics.packMs)
                        data.put("controlledPipeline",runPipeline(instrumentation.targetContext,source,packed.bitmap,
                            controlledPlan,folder,"controlled"))
                    }
                    if(arguments.getString("referenceProbeFullBaseline") != "false") {
                        val fullPlan=ReferenceRegionPreprocessor.Plan(ReferenceRegionPreprocessor.Route.FULL_FALLBACK,null,
                            0,emptyList(),emptyList(),emptyList(),emptyList(),"explicit_full_baseline")
                        data.put("fullBaseline",runPipeline(instrumentation.targetContext,source,source,fullPlan,folder,"full-baseline"))
                    }
                    File(folder,"manifest.json").writeText(data.toString(2))
                    summary.put(data)
                } finally { source.recycle() }
        }
        File(folder,"probe-summary.json").writeText(JSONObject().put("kind","fixture_preprocess_probe")
            .put("fixtures",summary).toString(2))
    }

    private fun runPipeline(context: android.content.Context,source: Bitmap,modelSource: Bitmap,
                            plan: ReferenceRegionPreprocessor.Plan,folder: File,prefix: String): JSONObject {
        val profile=when(plan.route) {
            ReferenceRegionPreprocessor.Route.PACKED_320 -> DetectorProfile.PACKED_320
            ReferenceRegionPreprocessor.Route.PACKED_640 -> DetectorProfile.PACKED_640
            ReferenceRegionPreprocessor.Route.FULL_FALLBACK -> DetectorProfile.FULL
            ReferenceRegionPreprocessor.Route.NO_ACTIVE -> error("Unobserved areas do not provide model inputs")
        }
        val constructorStarted=SystemClock.elapsedRealtime()
        val detector=LocalCardDetector(context,forceBackend="CPU",profile=profile)
        val initializedAt=SystemClock.elapsedRealtime()
        return detector.use {
            val started=SystemClock.elapsedRealtime()
            val raw=detector.detect(modelSource)
            val detectedAt=SystemClock.elapsedRealtime()
            val modelInput=detector.copyInputForProbe()
            try { writePng(File(folder,"$prefix-model-input.png"),modelInput) } finally { modelInput.recycle() }
            val projectionStarted=SystemClock.elapsedRealtime()
            val projection=plan.manifest?.let { ReferenceRegionPreprocessor.project(raw,it,source.width,source.height) }
            val projected=projection?.detections?.map { it.detection } ?: raw
            val global=globalNms(projected)
            val filtered=global.filter { it.box.centerY()>.13f && it.box.centerX() in .10f.. .88f && it.box.width()<.12f && it.box.height()>.02f }
            val glyphs=filtered.map { PlayGlyph(it.card,it.confidence,GlyphBox(it.box.left,it.box.top,it.box.right,it.box.bottom)) }
            val partition=partitionGuandanHand(glyphs)
            val projectedAt=SystemClock.elapsedRealtime()
            val data=JSONObject().put("profile",profile.name).put("route",plan.route.name)
                .put("partition","current_guandan_diagnostic").put("sourceWidth",source.width).put("sourceHeight",source.height)
                .put("modelSourceWidth",modelSource.width).put("modelSourceHeight",modelSource.height)
                .put("modelInitMs",initializedAt-constructorStarted).put("detectWallMs",detectedAt-started).put("preprocessMs",detector.preprocessMs)
                .put("inferenceMs",detector.inferenceMs).put("decodeMs",detector.decodeMs)
                .put("projectionAndPartitionMs",projectedAt-projectionStarted).put("modelInputExportMs",projectionStarted-detectedAt)
                .put("rawDetectionCount",raw.size).put("projectedDetectionCount",projected.size)
                .put("globalNmsCount",global.size).put("filteredGlyphCount",glyphs.size)
                .put("globalNmsThreshold",.45).put("rawDetections",detectionsJson(raw))
                .put("sourceDetections",detectionsJson(global)).put("glyphs",detectionsJson(filtered))
                .put("projectedGlyphs",JSONArray(projection?.detections?.map { projectedDetection ->
                    JSONObject().put("card",projectedDetection.detection.card.colorKey())
                        .put("tileId",projectedDetection.tile.tileId).put("sourceRegionId",projectedDetection.tile.sourceRegionId)
                        .put("trackId",projectedDetection.tile.trackId ?: JSONObject.NULL)
                        .put("areaType",projectedDetection.tile.areaType ?: JSONObject.NULL)
                        .put("observationId",projectedDetection.tile.observationId ?: JSONObject.NULL)
                }.orEmpty()))
                .put("hand",JSONArray(partition.hand.map { it.card.colorKey() })).put("handCount",partition.hand.size)
                .put("tableGlyphCount",partition.table.size).put("modelInputFile","$prefix-model-input.png")
                .put("projectionDroppedIndices",JSONArray(projection?.droppedIndices.orEmpty()))
                .put("clippedToTileCount",projection?.clippedToTileCount ?: 0)
                .put("projectionRequiresFallback",projection?.requiresFallback ?: false)
            if(projection?.requiresFallback == true) {
                val fallbackPlan=ReferenceRegionPreprocessor.Plan(ReferenceRegionPreprocessor.Route.FULL_FALLBACK,null,
                    0,emptyList(),emptyList(),emptyList(),emptyList(),"projection_dropped_detections")
                data.put("selectedFallback",runPipeline(context,source,source,fallbackPlan,folder,"$prefix-projection-fallback"))
            }
            File(folder,"$prefix-glyphs.json").writeText(data.toString(2))
            data
        }
    }

    private fun detectionsJson(detections: List<CardDetection>)=JSONArray(detections.map { detection ->
        JSONObject().put("card",detection.card.colorKey()).put("confidence",detection.confidence)
            .put("box",JSONArray(listOf(detection.box.left,detection.box.top,detection.box.right,detection.box.bottom)))
    })

    private fun globalNms(detections: List<CardDetection>): List<CardDetection> {
        val selected=ArrayList<CardDetection>()
        for(candidate in detections.sortedByDescending { it.confidence }) {
            if(selected.none { previous ->
                val a=previous.box; val b=candidate.box
                val area=maxOf(0f,minOf(a.right,b.right)-maxOf(a.left,b.left))*maxOf(0f,minOf(a.bottom,b.bottom)-maxOf(a.top,b.top))
                val union=a.width()*a.height()+b.width()*b.height()-area
                union>0 && area/union>.45f
            }) selected.add(candidate)
        }
        return selected
    }

    private fun region(id: String,box: IntBox,samplePointCount: Int=1000)=ActiveRegion(id,null,box,1.0,3,"stable",samplePointCount,1,
        "manual_fixture_compose",null,null,null,null,null,null)

    private fun writePng(file: File,bitmap: Bitmap) = file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
    private fun bbox(box: IntBox)=JSONArray(listOf(box.left,box.top,box.right,box.bottom))
    private fun planJson(plan: ReferenceRegionPreprocessor.Plan): JSONObject {
        val data=JSONObject().put("route",plan.route.name).put("variant",plan.variant)
            .put("reason",plan.reason ?: JSONObject.NULL).put("inputRegionCount",plan.inputRegionCount)
            .put("acceptedRegionIds",JSONArray(plan.acceptedRegionIds)).put("filteredRegionIds",JSONArray(plan.filteredRegionIds))
            .put("invalidRegionIds",JSONArray(plan.invalidRegionIds)).put("overflowRegionIds",JSONArray(plan.overflowRegionIds))
        plan.manifest?.let { manifest ->
            data.put("canvasSize",manifest.canvasSize).put("globalScale",manifest.globalScale)
                .put("tiles",JSONArray(manifest.tiles.map { tile -> JSONObject().put("tileId",tile.tileId)
                    .put("sourceRegionId",tile.sourceRegionId).put("sourceBBox",bbox(tile.sourceBBox))
                    .put("packedBBox",bbox(tile.packedBBox)).put("scale",tile.scale)
                    .put("trackId",tile.trackId ?: JSONObject.NULL).put("recordState",tile.recordState)
                    .put("regionSource",tile.regionSource).put("observationId",tile.observationId ?: JSONObject.NULL)
                    .put("areaType",tile.areaType ?: JSONObject.NULL).put("splitGroupId",tile.splitGroupId ?: JSONObject.NULL)
                    .put("overlayIndex",tile.overlayIndex ?: JSONObject.NULL).put("overlayCount",tile.overlayCount ?: JSONObject.NULL) }))
        }
        return data
    }
}
