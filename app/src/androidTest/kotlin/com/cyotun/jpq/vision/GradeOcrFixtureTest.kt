package com.cyotun.jpq.vision

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.AppState
import com.cyotun.jpq.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Previously reviewed user screenshot: both top-left grades are visibly 3.
 * Runs production asynchronous OCR, not synthetic positioned tokens or a game. */
@RunWith(AndroidJUnit4::class)
class GradeOcrFixtureTest {
    @Test fun actualHudElementsConfirmThreeAndAnOverlayCannotReadThroughItsMask() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("gradeOcrFixtureProbe")=="true")
        check(!AppState.running)
        val source=instrument.context.assets.open("fixtures/teammate-own-finished.jpg").use {
            checkNotNull(BitmapFactory.decodeStream(it))
        }
        val reader=GradeSourceReader()
        val tracker=RoundGradeTracker()
        val ocr=SceneOcr()
        var last: GradeSourceReading?=null
        try {
            val deadline=SystemClock.elapsedRealtime()+15000
            while(SystemClock.elapsedRealtime()<deadline && tracker.current().ranks!=setOf(Rank.THREE)) {
                val now=SystemClock.elapsedRealtime()
                ocr.request(source,now)
                val snapshot=ocr.snapshot(source.width,source.height,now)
                if(snapshot!=null) {
                    last=reader.read(source,now,snapshot,emptyList(),TableScene.TEAMMATE,emptySet(),WildcardMode.UNKNOWN)
                    tracker.observe(now,last.sourceTime,last.reading)
                }
                SystemClock.sleep(80)
            }
            val diagnostic=last?.hints?.filter { it.box!!.cx in .055f.. .23f && it.box!!.cy<=.13f }
            assertEquals("Actual HUD grade-3 OCR: source=${last?.reading?.source}, tokens=$diagnostic",setOf(Rank.THREE),tracker.current().ranks)
            assertEquals("HUD_ACTIVE",last!!.reading.source)
            val now=SystemClock.elapsedRealtime()
            val masked=reader.read(source,now,ocr.snapshot(source.width,source.height,now),
                listOf(GlyphBox(.06f,0f,.22f,.13f)),TableScene.TEAMMATE,emptySet(),WildcardMode.UNKNOWN)
            assertNull(masked.reading.ranks)
            assertTrue(masked.obscured)
        } finally { ocr.close(); source.recycle() }
    }
}
