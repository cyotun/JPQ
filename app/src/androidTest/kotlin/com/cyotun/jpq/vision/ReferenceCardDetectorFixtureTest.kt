package com.cyotun.jpq.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.AppState
import com.cyotun.jpq.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Explicit stopped-capture fixture checks only; never writes production qualification. */
@RunWith(AndroidJUnit4::class)
class ReferenceCardDetectorFixtureTest {
    @Test fun uncoveredGrayJokerMustUseTheCurrentFullRaster() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("smallJokerFixtureProbe")=="true")
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext
        val fixture=File(app.getExternalFilesDir(null),"reference-video-replay/input/frames/frame-000975.jpg")
        check(fixture.isFile) { "Stage the reviewed private frame at PTS528.062 before this explicit test" }
        val done=CompletableFuture<Unit>()
        check(VisionRuntime.handler.post {
            try {
                val source=checkNotNull(BitmapFactory.decodeFile(fixture.absolutePath))
                try {
                    LocalCardDetector(app,"CPU").use { full ->
                        ReferenceCardDetector(app,full).use { prepared ->
                            var found=emptyList<CardDetection>()
                            val now=SystemClock.elapsedRealtime()
                            repeat(4) { found=prepared.detect(source,now+it*100L) }
                            assertEquals(DetectorProfile.FULL,prepared.selectedProfile)
                            assertEquals("uncovered_card_surface",prepared.fallbackReason)
                            val selected=layout(found)
                            assertEquals(listOf("SMALL"),selected.seats.getValue(Seat.NEXT.name))
                            assertTrue(found.filter { it.card.rank==Rank.SMALL }.all { it.confidence>=.65f })
                        }
                    }
                } finally { source.recycle() }
                done.complete(Unit)
            } catch(error: Throwable) { done.completeExceptionally(error) }
        })
        done.get(30,TimeUnit.SECONDS)
    }

    @Before fun explicitStoppedFixtureProbeOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("referenceDetectorProbe") == "true")
        check(!AppState.running) { "Reference detector fixtures require stopped capture" }
    }

    @Test(timeout = 240000) fun eightFixturesMatchFullAndIndependentLayoutChecks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = CompletableFuture<File>()
        check(VisionRuntime.handler.post {
            try { result.complete(runProbe(instrumentation.targetContext, instrumentation.context)) }
            catch (error: Throwable) { result.completeExceptionally(error) }
        })
        val folder = result.get(180, TimeUnit.SECONDS)
        Log.i(TAG, "fixture_checks_passed_no_qualification_written results=${folder.absolutePath}")
    }

    private fun runProbe(app: Context, test: Context): File {
        check(!AppState.running) { "Capture started before the fixture worker ran" }
        val folder = File(checkNotNull(app.getExternalFilesDir(null)),
            "reference-detector-fixtures/${System.currentTimeMillis()}")
        check(folder.isDirectory || folder.mkdirs())
        val cases = JSONArray()
        val errors = mutableListOf<String>()
        val bitmaps = mutableListOf<Bitmap>()
        var packedCases = 0
        val report = JSONObject().put("schemaVersion", 1)
            .put("pipelineVersion", ReferenceCardDetector.PIPELINE_VERSION)
            .put("deviceFingerprint", Build.FINGERPRINT)
            .put("scope", "eight_static_cpu_fixtures_only_not_complete_game_or_phone_capture_validation")
            .put("allowCompact", false).put("backend", "CPU")
            .put("fixtures", cases)
            .put("expectedHandCounts", JSONArray(listOf(27, 25, 26, 25)))
            .put("nativeUpdatesPerFixture", 4)
            .put("threshold", .55).put("globalNmsIou", .45)
            .put("modelSha256", JSONObject().put("FULL", DetectorProfile.FULL.sha256)
                .put("PACKED_640", DetectorProfile.PACKED_640.sha256)
                .put("PACKED_320", DetectorProfile.PACKED_320.sha256))
        try {
            val originals = List(4) { index ->
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                }
                val decoded = test.assets.open("fixtures/case${index + 1}.jpg").use {
                    checkNotNull(BitmapFactory.decodeStream(it, null, options))
                }
                val source = if (decoded.config == Bitmap.Config.ARGB_8888) decoded else {
                    val copy = checkNotNull(decoded.copy(Bitmap.Config.ARGB_8888, true))
                    decoded.recycle(); copy
                }
                bitmaps.add(source)
                source
            }
            val scaled = originals.map { original ->
                Bitmap.createScaledBitmap(original, 1280, 588, true).also { bitmaps.add(it) }
            }
            val sources = originals + scaled
            LocalCardDetector(app, "CPU", DetectorProfile.FULL).use { full ->
                ReferenceCardDetector(app, full, allowCompact = false).use { reference ->
                    assertNull("Reference route initialization failed", reference.initializationFailure)
                    sources.forEachIndexed { index, source ->
                        check(!AppState.running) { "Capture started during the fixture probe" }
                        val name = "case${index % 4 + 1}-${if (index < 4) "original" else "1280x588"}"
                        val record = JSONObject().put("fixture", name).put("index", index)
                            .put("sourceWidth", source.width).put("sourceHeight", source.height)
                        cases.put(record)
                        try {
                            val baseline = full.detect(source)
                            record.put("full", layoutJson(baseline)).put("fullMetrics", localMetrics(full))
                            checkCase(index, baseline)
                            reference.reset("fixture_boundary:$name")
                            val attempts = JSONArray()
                            record.put("attempts", attempts)
                            var selected = emptyList<CardDetection>()
                            val timestamp = SystemClock.elapsedRealtime()
                            repeat(4) { attempt ->
                                selected = reference.detect(source, timestamp + attempt * 100L)
                                val metrics = referenceMetrics(reference).put("attempt", attempt + 1)
                                    .put("selected", layoutJson(selected))
                                attempts.put(metrics)
                                reference.copyInputForProbe().useBitmap { raster ->
                                    val filename = "$name-attempt${attempt + 1}-selected-input.png"
                                    File(folder, filename).outputStream().use {
                                        check(raster.compress(Bitmap.CompressFormat.PNG, 100, it))
                                    }
                                    metrics.put("modelInputFile", filename)
                                        .put("modelInputWidth", raster.width).put("modelInputHeight", raster.height)
                                }
                                reference.errorReason?.let { errors.add("$name attempt=${attempt + 1}: $it") }
                            }
                            record.put("attempts", attempts).put("selected", layoutJson(selected))
                                .put("selectedMetrics", referenceMetrics(reference))
                            // Export before correctness checks so a failed comparison keeps its raster.
                            reference.copyInputForProbe().useBitmap { raster ->
                                val filename = "$name-selected-input.png"
                                File(folder, filename).outputStream().use {
                                    check(raster.compress(Bitmap.CompressFormat.PNG, 100, it))
                                }
                                record.put("modelInputFile", filename)
                                assertEquals(reference.selectedProfile.width, raster.width)
                                assertEquals(reference.selectedProfile.height, raster.height)
                            }
                            assertFalse("320 must remain opt-in", reference.selectedProfile == DetectorProfile.PACKED_320)
                            assertEquals("$name full card/color multiset", keys(baseline), keys(selected))
                            val baselineLayout = layout(baseline)
                            val selectedLayout = layout(selected)
                            assertEquals("$name hand rank/color multiset", baselineLayout.hand, selectedLayout.hand)
                            assertEquals("$name ownership rank/color multisets", baselineLayout.seats, selectedLayout.seats)
                            assertEquals("$name ambiguous groups", baselineLayout.ambiguous, selectedLayout.ambiguous)
                            checkCase(index, selected)
                            if (reference.selectedProfile == DetectorProfile.PACKED_640) packedCases++
                            record.put("passed", true)
                        } catch (error: AssertionError) {
                            errors.add("$name: ${error.message}")
                            record.put("passed", false).put("error", error.message ?: error.javaClass.simpleName)
                        } catch (error: Exception) {
                            errors.add("$name: ${error.javaClass.simpleName}:${error.message}")
                            record.put("passed", false).put("error", "${error.javaClass.simpleName}:${error.message}")
                        }
                    }
                }
                // Closing the wrapper must leave the supplied shared FULL detector usable.
                full.copyInputForProbe().useBitmap { raster ->
                    assertEquals(DetectorProfile.FULL.width, raster.width)
                    assertEquals(DetectorProfile.FULL.height, raster.height)
                }
            }
        } catch (error: Throwable) {
            errors.add("probe: ${error.javaClass.simpleName}:${error.message}")
        } finally {
            bitmaps.distinct().forEach { if (!it.isRecycled) it.recycle() }
        }
        val passed = cases.length() == 8 && errors.isEmpty() && packedCases > 0 &&
            (0 until cases.length()).all { cases.getJSONObject(it).optBoolean("passed", false) }
        report.put("packed640FixtureCount", packedCases).put("errors", JSONArray(errors))
            .put("fixtureChecksPassed", passed)
            .put("productionQualificationWritten", false)
        File(folder, "results.json").writeText(report.toString(2), Charsets.UTF_8)
        assertTrue("8 fixture checks and packed coverage must pass; results=${folder.absolutePath}; errors=$errors", passed)
        return folder
    }

    private data class Layout(val hand: List<String>, val seats: Map<String, List<String>>, val ambiguous: List<String>)

    private fun glyphs(found: List<CardDetection>) = found.filter {
        it.box.centerY() > .13f && it.box.centerX() in .10f.. .88f
    }.map { PlayGlyph(it.card, it.confidence, GlyphBox(it.box.left, it.box.top, it.box.right, it.box.bottom)) }

    private fun layout(found: List<CardDetection>): Layout {
        val partition = partitionGuandanHand(glyphs(found))
        val ownership = assignPlayGroups(partition.table, emptySet(), emptyList())
        return Layout(partition.hand.map { it.card.colorKey() }.sorted(),
            ownership.seats.mapKeys { it.key.name }.mapValues { (_, cards) -> cards.map { it.card.colorKey() }.sorted() },
            ownership.ambiguous.map { it.card.colorKey() }.sorted())
    }

    /** Independent expected hand/seat checks preserved from DeviceDetectorTest. */
    private fun checkCase(index: Int, found: List<CardDetection>) {
        val partition = partitionGuandanHand(glyphs(found))
        assertEquals("fixture=$index hand count", listOf(27, 25, 26, 25)[index % 4], partition.hand.size)
        val ownership = assignPlayGroups(partition.table, emptySet(), emptyList())
        assertTrue("fixture=$index ambiguous ownership", ownership.ambiguous.isEmpty())
        when (index % 4) {
            0 -> assertEquals(listOf(Card(Rank.FOUR, Suit.RED)), ownership.seats.getValue(Seat.NEXT).map { it.card })
            1, 3 -> {
                assertEquals(4, ownership.seats.getValue(Seat.PREVIOUS).size)
                assertTrue(ownership.seats.getValue(Seat.OPPOSITE).isEmpty())
            }
            2 -> {
                assertEquals(listOf(Card(Rank.K, Suit.RED)), ownership.seats.getValue(Seat.SELF).map { it.card })
                assertEquals(listOf(Card(Rank.A, Suit.RED)), ownership.seats.getValue(Seat.OPPOSITE).map { it.card })
            }
        }
    }

    private fun keys(found: List<CardDetection>) = found.map { it.card.colorKey() }.sorted()

    private fun layoutJson(found: List<CardDetection>): JSONObject {
        val layout = layout(found)
        return JSONObject().put("cardColorMultiset", JSONArray(keys(found)))
            .put("hand", JSONArray(layout.hand)).put("handCount", layout.hand.size)
            .put("seats", JSONObject(layout.seats)).put("ambiguous", JSONArray(layout.ambiguous))
            .put("sourceNormalizedDetections", JSONArray(found.map { detection ->
                JSONObject().put("card", detection.card.colorKey()).put("confidence", detection.confidence)
                    .put("box", JSONArray(listOf(detection.box.left, detection.box.top, detection.box.right, detection.box.bottom)))
            }))
    }

    private fun localMetrics(detector: LocalCardDetector) = JSONObject()
        .put("profile", detector.profile.name).put("backend", detector.backend)
        .put("preprocessMs", detector.preprocessMs).put("inferenceMs", detector.inferenceMs)
        .put("decodeMs", detector.decodeMs).put("cacheHit", detector.cacheHit)
        .put("maxClassScore", detector.maxClassScore)

    private fun referenceMetrics(detector: ReferenceCardDetector) = JSONObject()
        .put("profile", detector.selectedProfile.name).put("backend", detector.backend)
        .put("route", detector.lastPlan?.route?.name ?: JSONObject.NULL)
        .put("fallbackReason", detector.fallbackReason ?: JSONObject.NULL)
        .put("errorReason", detector.errorReason ?: JSONObject.NULL)
        .put("nativeMs", detector.nativeMs).put("packMs", detector.packMs)
        .put("preparationMs", detector.preparationMs).put("preprocessMs", detector.preprocessMs)
        .put("inferenceMs", detector.inferenceMs).put("decodeMs", detector.decodeMs)
        .put("totalMs", detector.totalMs).put("packedAttemptMs", detector.packedAttemptMs)
        .put("cacheHit", detector.cacheHit).put("maxClassScore", detector.maxClassScore)
        .put("nativeRegionCount", detector.nativeRegionCount).put("acceptedRegionCount", detector.acceptedRegionCount)
        .put("packedTileCount", detector.packedTileCount).put("packedRawDetectionCount", detector.packedRawDetectionCount)
        .put("sourceDetectionCount", detector.sourceDetectionCount)
        .put("projectionDroppedIndices", JSONArray(detector.projectionDroppedIndices))
        .put("clippedToTileCount", detector.clippedToTileCount)
        .put("acceptedRegionIds", JSONArray(detector.lastPlan?.acceptedRegionIds.orEmpty()))
        .put("filteredRegionIds", JSONArray(detector.lastPlan?.filteredRegionIds.orEmpty()))
        .put("nativeRegions", JSONArray(detector.latestNativeRegions.map { region ->
            JSONObject().put("regionId", region.regionId).put("trackId", region.trackId ?: JSONObject.NULL)
                .put("bbox", JSONArray(listOf(region.bbox.left, region.bbox.top, region.bbox.right, region.bbox.bottom)))
                .put("samplePointCount", region.samplePointCount).put("evidencePointCount", region.evidencePointCount)
                .put("supportRatio", region.supportRatio).put("recordState", region.recordState)
                .put("source", region.source).put("areaType", region.areaType ?: JSONObject.NULL)
        }))
        .put("manifest", detector.lastManifest?.let { manifest ->
            JSONObject().put("canvasSize", manifest.canvasSize).put("globalScale", manifest.globalScale)
                .put("scaleMode", manifest.scaleMode).put("tiles", JSONArray(manifest.tiles.map { tile ->
                    JSONObject().put("tileId", tile.tileId).put("sourceRegionId", tile.sourceRegionId)
                        .put("sourceBBox", JSONArray(listOf(tile.sourceBBox.left, tile.sourceBBox.top, tile.sourceBBox.right, tile.sourceBBox.bottom)))
                        .put("packedBBox", JSONArray(listOf(tile.packedBBox.left, tile.packedBBox.top, tile.packedBBox.right, tile.packedBBox.bottom)))
                        .put("scale", tile.scale)
                }))
        } ?: JSONObject.NULL)

    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T = try { block(this) } finally { recycle() }

    private companion object { const val TAG = "JPQReferenceFixture" }
}
