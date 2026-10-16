package com.cyotun.jpq.vision

import android.graphics.*
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemainingCountSourceTest {
    private val rect=Rect(102,214,179,238)
    private fun source(color: Int)=Bitmap.createBitmap(1280,588,Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.rgb(8,108,96))
        Canvas(this).drawRect(rect,Paint().apply { this.color=color })
    }
    private fun text(bitmap: Bitmap,time: Long=5000)=SceneText(listOf(OcrLine("剩8张",rect)),time,1280,588,
        remainingCountPixels=RemainingCountPixels.capture(bitmap))

    @Test fun positionedGoldBadgeKeepsItsOriginalOcrSourceTime() {
        val bitmap=source(Color.rgb(255,218,125))
        try {
            assertEquals(mapOf(Seat.PREVIOUS to RemainingHandCount(8,5000)),
                TencentRemainingCountSource.read(bitmap,5100,text(bitmap),emptyList()))
        } finally { bitmap.recycle() }
    }
    @Test fun whiteTextSurfaceOrFeltCannotPretendToBeTheGoldBadge() {
        for(color in listOf(Color.WHITE,Color.BLACK,Color.rgb(8,108,96))) {
            val bitmap=source(color)
            try { assertTrue(TencentRemainingCountSource.read(bitmap,5100,text(bitmap),emptyList()).isEmpty()) }
            finally { bitmap.recycle() }
        }
    }
    @Test fun staleFutureMismatchedAndMaskedSnapshotsCannotProvideRecoveryEvidence() {
        val bitmap=source(Color.rgb(255,218,125))
        try {
            for(snapshot in listOf(text(bitmap,3999),text(bitmap,5101),text(bitmap).copy(width=1200)))
                assertTrue(TencentRemainingCountSource.read(bitmap,5100,snapshot,emptyList()).isEmpty())
            assertTrue(TencentRemainingCountSource.read(bitmap,5100,text(bitmap),
                listOf(GlyphBox(.08f,.36f,.14f,.41f))).isEmpty())
        } finally { bitmap.recycle() }
    }
    @Test fun conflictingCountsOnAGoldPlateAreRejected() {
        val bitmap=source(Color.rgb(255,218,125))
        try {
            assertTrue(TencentRemainingCountSource.read(bitmap,5100,
                text(bitmap).copy(lines=listOf(OcrLine("剩8张",rect),OcrLine("剩4张",rect))),emptyList()).isEmpty())
        } finally { bitmap.recycle() }
    }
    @Test fun unchangedGoldBackgroundCannotValidatePixelsFromAnOldDigit() {
        val bitmap=source(Color.rgb(255,218,125))
        try {
            val snapshot=text(bitmap)
            assertEquals(8,TencentRemainingCountSource.read(bitmap,5100,snapshot,emptyList())[Seat.PREVIOUS]?.count)
            // A changed numeral occupies only part of the same gold badge.
            Canvas(bitmap).drawRect(Rect(rect.centerX(),rect.top+4,rect.centerX()+5,rect.bottom-4),Paint().apply { color=Color.rgb(130,50,30) })
            assertTrue(TencentRemainingCountSource.read(bitmap,5100,snapshot,emptyList()).isEmpty())
        } finally { bitmap.recycle() }
    }
    @Test fun unboundSyntheticTextCannotSupplyIndependentCurrentCountEvidence() {
        val bitmap=source(Color.rgb(255,218,125))
        try { assertTrue(TencentRemainingCountSource.read(bitmap,5100,text(bitmap).copy(remainingCountPixels=null),emptyList()).isEmpty()) }
        finally { bitmap.recycle() }
    }
    @Test fun actualTeammateViewReadsTheVisibleLeftEightCardBadge() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gradeOcrFixtureProbe")=="true")
        val source=InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/teammate-own-finished.jpg").use {
            checkNotNull(BitmapFactory.decodeStream(it))
        }
        val ocr=SceneOcr()
        try {
            val deadline=SystemClock.elapsedRealtime()+15000
            var counts=emptyMap<Seat,RemainingHandCount>()
            var snapshot: SceneText?=null
            while(SystemClock.elapsedRealtime()<deadline && counts[Seat.PREVIOUS]?.count!=8) {
                val now=SystemClock.elapsedRealtime(); ocr.request(source,now,captureRemainingCounts=true)
                snapshot=ocr.snapshot(source.width,source.height,now)
                counts=TencentRemainingCountSource.read(source,now,snapshot,emptyList())
                SystemClock.sleep(80)
            }
            assertEquals("source=${source.width}x${source.height}; badges=${snapshot?.remainingCountElements}; OCR=${snapshot?.lines}",8,counts[Seat.PREVIOUS]?.count)
            assertFalse(Seat.NEXT in counts) // The visible right player has no remaining-card badge.
        } finally { ocr.close(); source.recycle() }
    }
}
