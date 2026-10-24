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

/** Hash-bound private images; no game, projection, ledger or preferences are changed. */
@RunWith(AndroidJUnit4::class)
class PassBoundaryFixtureTest {
    @Test fun actualPassBubblesRemainSourceBoundAndNeverBecomeEmptyPixelClaims() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("passBoundaryFixtureProbe")=="true")
        check(!AppState.running)
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(app.getExternalFilesDir(null),"pass-boundary-fixtures")
        val entries=JSONObject(File(folder,"manifest.json").readText()).getJSONArray("images")
        require(entries.length() in 1..12)
        val results=JSONArray()
        var allPassed=true
        for(i in 0 until entries.length()) {
            val entry=entries.getJSONObject(i); val name=entry.getString("file")
            require(name.matches(Regex("[A-Za-z0-9._-]+\\.png")))
            val bytes=File(folder,name).readBytes()
            assertEquals(entry.getString("sha256"),MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) })
            val image=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
            val ocr=SceneOcr()
            try {
                val sourceTime=SystemClock.elapsedRealtime()
                ocr.request(image,sourceTime,visibilityEpoch=77L)
                val deadline=SystemClock.elapsedRealtime()+20000
                var text: SceneText?
                do { text=ocr.snapshot(image.width,image.height,sourceTime,77L)
                    if(text==null) SystemClock.sleep(40)
                } while(text==null && SystemClock.elapsedRealtime()<deadline)
                assertNotNull("OCR $name",text)
                val done=CompletableFuture<Map<Seat,List<PlayGlyph>>>()
                check(VisionRuntime.handler.post {
                    try { LocalCardDetector(app,"CPU",DetectorProfile.FULL).use { detector ->
                        val glyphs=detector.detect(image).filter { it.box.centerY()>.13f && it.box.centerX() in .10f.. .88f &&
                            it.box.width()<.12f && it.box.height()>.02f }.map {
                            PlayGlyph(it.card,it.confidence,GlyphBox(it.box.left,it.box.top,it.box.right,it.box.bottom)) }
                        val partition=partitionGuandanHand(glyphs)
                        done.complete(assignPlayGroups(partition.table,emptySet(),emptyList()).seats)
                    } } catch(error: Throwable) { done.completeExceptionally(error) }
                })
                val groups=done.get(30,TimeUnit.SECONDS)
                val actual=TencentFrameVisibility.observedPasses(text,sourceTime,image.width,image.height,77L,
                    TableScene.TABLE,groups,emptyList())
                val expected=entry.getJSONArray("expectedPassSeats").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                val passed=expected==actual.keys.map { it.name }.toSet()
                allPassed=allPassed && passed
                assertTrue(actual.values.all { it==sourceTime })
                assertTrue(TencentFrameVisibility.observedPasses(text,sourceTime+1001,image.width,image.height,77L,
                    TableScene.TABLE,groups,emptyList()).isEmpty())
                results.put(JSONObject().put("file",name).put("passes",JSONObject(actual.mapKeys { it.key.name }))
                    .put("passed",passed).put("expected",JSONArray(expected.toList()))
                    .put("lines",JSONArray(text?.lines.orEmpty().map { it.toString() }))
                    .put("groups",JSONObject(groups.mapKeys { it.key.name }.mapValues { (_,g) -> g.map { it.card.colorKey() } })))
            } finally { ocr.close(); image.recycle() }
        }
        File(folder,"results.json").writeText(JSONObject().put("images",results).put("passed",allPassed).toString(2))
        assertTrue(results.toString(),allPassed)
    }
}
