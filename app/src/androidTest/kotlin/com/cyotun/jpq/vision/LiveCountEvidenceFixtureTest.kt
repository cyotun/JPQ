package com.cyotun.jpq.vision

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.AppState
import com.cyotun.jpq.core.Seat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Opt-in stopped-capture OCR checks on hash-bound private source images. */
@RunWith(AndroidJUnit4::class)
class LiveCountEvidenceFixtureTest {
    @Test fun readReviewedPrivateCountBadges() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCountFixtureProbe")=="true")
        check(!AppState.running)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.getSystemService(android.os.PowerManager::class.java).isInteractive)
        val folder=File(context.getExternalFilesDir(null),"live-count-fixtures")
        val manifest=JSONObject(File(folder,"manifest.json").readText())
        val entries=manifest.getJSONArray("images")
        require(entries.length() in 1..12)
        val results=JSONArray()
        var failed=false
        for(i in 0 until entries.length()) {
            val entry=entries.getJSONObject(i)
            val name=entry.getString("file")
            require(name.matches(Regex("[A-Za-z0-9._-]+\\.jpg")))
            val bytes=File(folder,name).readBytes()
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(hash==entry.getString("sha256"))
            val bitmap=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
            val expected=entry.getJSONObject("expected")
            val reader=SceneOcr()
            try {
                val deadline=SystemClock.elapsedRealtime()+30000
                // Fixed-image probe: preserve its one source timestamp while
                // waiting for emulated OCR. This does not measure live latency.
                val sourceTime=SystemClock.elapsedRealtime()
                reader.request(bitmap,sourceTime,captureRemainingCounts=true)
                var text: SceneText?=null
                var actual=emptyMap<Seat,com.cyotun.jpq.core.RemainingHandCount>()
                fun matches()=expected.keys().asSequence().all { key -> actual[Seat.valueOf(key)]?.count==expected.getInt(key) }
                do {
                    text=reader.snapshot(bitmap.width,bitmap.height,sourceTime)
                    actual=TencentRemainingCountSource.read(bitmap,sourceTime,text,emptyList())
                    if(text!=null) break
                    SystemClock.sleep(80)
                } while(SystemClock.elapsedRealtime()<deadline)
                val passed=matches()
                failed=failed || !passed
                results.put(JSONObject().put("file",name).put("passed",passed).put("expected",expected)
                    .put("actual",JSONObject(actual.mapKeys { it.key.name }.mapValues { it.value.count }))
                    .put("snapshotReceived",text!=null)
                    .put("badgeLines",JSONArray(text?.remainingCountElements.orEmpty().map { it.toString() }))
                    .put("sceneLines",JSONArray(text?.lines.orEmpty().map { it.toString() })))
            } finally { reader.close(); bitmap.recycle() }
        }
        File(folder,"results.json").writeText(JSONObject().put("images",results).put("passed",!failed).toString(2))
        assertEquals(results.toString(),false,failed)
    }
}
