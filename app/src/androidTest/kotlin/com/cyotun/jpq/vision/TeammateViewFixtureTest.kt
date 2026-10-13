package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.AppState
import com.cyotun.jpq.core.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** User-supplied screenshot, manually enumerated visible cards. It verifies the
 * view/OCR/detection boundary, not an entire live match. No qualification is written. */
@RunWith(AndroidJUnit4::class)
class TeammateViewFixtureTest {
    @Test fun teammateHandIsNotAPlayAndTheOpponentPairKeepsItsOwner() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("teammateFixtureProbe")=="true")
        check(!AppState.running)
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext
        val original=checkNotNull(instrument.context.assets.open("fixtures/teammate-own-finished.jpg").use { BitmapFactory.decodeStream(it) })
        val images=listOf(original,Bitmap.createScaledBitmap(original,1280,588,true))
        val client=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val report=JSONArray()
        val expected=listOf("THREE:BLACK","THREE:BLACK","THREE:RED","THREE:RED","A:BLACK",
            "K:BLACK","K:RED","Q:BLACK","Q:RED","EIGHT:BLACK","EIGHT:BLACK","EIGHT:BLACK","EIGHT:RED",
            "SEVEN:RED","SIX:BLACK","SIX:RED","SIX:RED","FIVE:BLACK","FIVE:BLACK","FIVE:BLACK","FOUR:BLACK","TWO:BLACK").sorted()
        try {
            for(bitmap in images) {
                val text=Tasks.await(client.process(InputImage.fromBitmap(bitmap,0)),15,TimeUnit.SECONDS)
                val hints=text.textBlocks.flatMap { it.lines }.map { line -> SceneHint(line.text.replace(" ",""),line.boundingBox?.let { b ->
                    GlyphBox(b.left.toFloat()/bitmap.width,b.top.toFloat()/bitmap.height,b.right.toFloat()/bitmap.width,b.bottom.toFloat()/bitmap.height)
                }) }
                assertEquals(TableScene.TEAMMATE,tencentScene(hints,true,true))
                // The teammate-hand label is itself positive completion evidence.
                // OCR need not also read the small decorative finish badge.
                val done=CompletableFuture<Unit>()
                check(VisionRuntime.handler.post {
                    try {
                        LocalCardDetector(app,"CPU").use { full -> ReferenceCardDetector(app,full).use { fast ->
                            for(route in listOf("FULL","REFERENCE")) {
                                val found=if(route=="FULL") full.detect(bitmap) else {
                                    var detected=emptyList<CardDetection>(); val now=SystemClock.elapsedRealtime()
                                    repeat(4) { detected=fast.detect(bitmap,now+it*100) }; detected
                                }
                                val glyphs=found.filter { it.box.centerY()>.13f && it.box.centerX() in .10f.. .88f && it.box.width()<.12f && it.box.height()>.02f }
                                    .map { PlayGlyph(it.card,it.confidence,GlyphBox(it.box.left,it.box.top,it.box.right,it.box.bottom)) }
                                val partition=partitionGuandanHand(glyphs)
                                val owned=assignPlayGroups(partition.table,emptySet(),emptyList(),true)
                                report.put(JSONObject().put("width",bitmap.width).put("route",route)
                                    .put("selectedProfile",if(route=="FULL") "FULL" else fast.selectedProfile.name)
                                    .put("hand",JSONArray(partition.hand.map { it.card.colorKey() }.sorted()))
                                    .put("seats",JSONObject(owned.seats.mapKeys { it.key.name }.mapValues { (_,g) -> g.map { it.card.colorKey() } }))
                                    .put("fallback",if(route=="FULL") JSONObject.NULL else fast.fallbackReason ?: JSONObject.NULL))
                                assertEquals("$route width=${bitmap.width} independent revealed hand",expected,partition.hand.map { it.card.colorKey() }.sorted())
                                assertEquals(listOf("A:RED","A:RED"),owned.seats.getValue(Seat.PREVIOUS).map { it.card.colorKey() })
                                assertTrue(owned.seats.filterKeys { it!=Seat.PREVIOUS }.values.all { it.isEmpty() })
                                assertTrue(owned.ambiguous.isEmpty())
                            }
                        } }
                        done.complete(Unit)
                    } catch(error: Throwable) { done.completeExceptionally(error) }
                })
                done.get(30,TimeUnit.SECONDS)
            }
        } finally {
            client.close(); images.forEach { it.recycle() }
            File(app.getExternalFilesDir(null),"phone-preflight/teammate-fixture.json").apply { parentFile!!.mkdirs() }
                .writeText(JSONObject().put("scope","static_user_screenshot_not_live_round").put("cases",report).toString(2))
        }
    }
}
