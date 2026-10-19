package com.cyotun.jpq.vision

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.AppState
import com.cyotun.jpq.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Private, source-bound images remain outside the distributed test APK. */
@RunWith(AndroidJUnit4::class)
class RepeatedAcesFixtureTest {
    @Test fun actualSingleSuitsAndUnobscuredBadgesQualifyWhileCroppedBadgesAbstain() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("repeatedAcesFixtureProbe")=="true")
        check(!AppState.running)
        val instrument=InstrumentationRegistry.getInstrumentation()
        val folder=File(instrument.targetContext.getExternalFilesDir(null),"repeated-aces-fixtures")
        val manifest=JSONObject(File(folder,"manifest.json").readText())
        val samples=manifest.getJSONArray("samples")
        assertEquals(4,samples.length())
        val report=JSONArray()
        for(index in 0 until samples.length()) {
            val sample=samples.getJSONObject(index)
            val name=sample.getString("file")
            require(name==File(name).name && name.endsWith(".jpg"))
            val file=File(folder,name)
            val sha=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(sample.getString("sha256"),sha)
            val bitmap=checkNotNull(BitmapFactory.decodeFile(file.path))
            val ocr=SceneOcr()
            try {
                val sourceTime=sample.getLong("sourceElapsedMs")
                val masks=sample.getJSONArray("overlayMasks").let { list -> (0 until list.length()).map { i ->
                    list.getJSONArray(i).let { b -> GlyphBox(b.getDouble(0).toFloat(),b.getDouble(1).toFloat(),b.getDouble(2).toFloat(),b.getDouble(3).toFloat()) }
                } }
                ocr.request(bitmap,sourceTime,captureRemainingCounts=true,masks=masks)
                val deadline=SystemClock.elapsedRealtime()+15000
                var snapshot: SceneText?=null
                while(snapshot==null && SystemClock.elapsedRealtime()<deadline) {
                    snapshot=ocr.snapshot(bitmap.width,bitmap.height,sourceTime)
                    if(snapshot==null) SystemClock.sleep(40)
                }
                assertNotNull("OCR returned no source-bound snapshot for $name",snapshot)
                val counts=TencentRemainingCountSource.read(bitmap,sourceTime,snapshot,masks)
                fun lines(values: List<OcrLine>)=JSONArray(values.map { line -> JSONObject().put("text",line.text)
                    .put("box",line.box?.let { b -> JSONArray(listOf(b.left,b.top,b.right,b.bottom)) } ?: JSONObject.NULL) })
                File(folder,"observed-${sample.getLong("sourceElapsedMs")}.json").writeText(JSONObject()
                    .put("sourceFile",name).put("sourceSha256",sha)
                    .put("lines",lines(snapshot!!.lines)).put("countElements",lines(snapshot!!.remainingCountElements))
                    .put("counts",JSONObject(counts.mapKeys { it.key.name }.mapValues { it.value.count }))
                    .put("unmaskedDiagnosticControl",JSONObject(TencentRemainingCountSource.read(bitmap,sourceTime,snapshot,emptyList())
                        .mapKeys { it.key.name }.mapValues { it.value.count }))
                    .put("note","unmaskedDiagnosticControl is offline comparison only; never passed to the production observer")
                    .toString(2))
                assertEquals("Unobscured badge $name",sample.getInt("expectedPreviousCount"),counts[Seat.PREVIOUS]?.count)
                if(sample.isNull("expectedQualifiedOppositeCount"))
                    assertNull("The cropped opposite badge must not become quantity evidence: $name",counts[Seat.OPPOSITE])
                else assertEquals("Qualified opposite badge $name",sample.getInt("expectedQualifiedOppositeCount"),counts[Seat.OPPOSITE]?.count)
                val record=JSONObject().put("file",name).put("sha256",sha).put("sourceElapsedMs",sourceTime)
                    .put("previousCount",counts.getValue(Seat.PREVIOUS).count)
                    .put("qualifiedOppositeCount",counts[Seat.OPPOSITE]?.count ?: JSONObject.NULL)
                if(sample.has("expectedSuit")) {
                    val done=CompletableFuture<Suit>()
                    VisionRuntime.handler.post {
                        try {
                            LocalCardDetector(instrument.targetContext,"CPU").use { detector ->
                                val glyphs=detector.detect(bitmap).map { p -> PlayGlyph(p.card,p.confidence,GlyphBox(p.box.left,p.box.top,p.box.right,p.box.bottom)) }
                                val group=assignPlayGroups(partitionGuandanHand(glyphs).table,emptySet(),emptyList()).seats.getValue(Seat.OPPOSITE)
                                assertEquals(listOf(Card(Rank.A,Suit.BLACK)),group.map { it.card })
                                val reading=SuitPixelReader().read(bitmap,group.single(),SuitPixelReader.Placement.TABLE,masks)
                                val observed=ObservedPlay(group.map { it.card },group.minOf { it.confidence },group)
                                val identity=SingleCardIdentityReader().read(bitmap,observed,sourceTime,masks)
                                assertNotNull("Production single-card ROI did not qualify $name",identity)
                                assertEquals(reading.suit,identity!!.card.suit)
                                assertEquals(sourceTime,identity.sourceTimeMs)
                                assertNull("An obscured suit was accepted",SingleCardIdentityReader().read(bitmap,observed,sourceTime,
                                    listOf(GlyphBox(0f,0f,1f,1f))))
                                done.complete(checkNotNull(reading.suit))
                            }
                        } catch(error: Throwable) { done.completeExceptionally(error) }
                    }
                    val suit=done.get(45,TimeUnit.SECONDS)
                    assertEquals(sample.getString("expectedSuit"),suit.name)
                    record.put("tableSuit",suit.name)
                }
                report.put(record)
            } finally { ocr.close(); bitmap.recycle() }
        }
        File(folder,"result-${System.currentTimeMillis()}.json").writeText(JSONObject()
            .put("scope","offline_OCR_and_model_on_reviewed_source_images_not_original_live_OCR")
            .put("samples",report).toString(2))
    }
}
