package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cyotun.jpq.core.GlyphBox
import com.cyotun.jpq.core.Rank
import com.cyotun.jpq.core.TableScene
import com.cyotun.jpq.core.WildcardMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Adapter/mask checks. These do not claim live OCR or recognition accuracy. */
@RunWith(AndroidJUnit4::class)
class GradeSourceReaderTest {
    private fun rect(left: Float,top: Float,right: Float,bottom: Float,width: Int,height: Int) =
        Rect((left*width).toInt(),(top*height).toInt(),(right*width).toInt(),(bottom*height).toInt())
    private fun text(bitmap: Bitmap,time: Long=1000L): SceneText {
        val w=bitmap.width; val h=bitmap.height
        val elements=listOf(
            OcrLine("我方",rect(.081f,.008f,.119f,.034f,w,h)),
            OcrLine("3",rect(.091f,.043f,.109f,.089f,w,h)),
            OcrLine("对方",rect(.131f,.008f,.169f,.034f,w,h)),
            OcrLine("3",rect(.141f,.043f,.159f,.089f,w,h)),
        )
        return SceneText(emptyList(),time,w,h,metadataElements=elements)
    }
    @Test fun retainedPositionedElementsReadEqualHudScoresWithoutExtraOcr() {
        val bitmap=Bitmap.createBitmap(1280,588,Bitmap.Config.ARGB_8888)
        try {
            val result=GradeSourceReader().read(bitmap,1000,text(bitmap),emptyList(),TableScene.TABLE,
                emptySet(),WildcardMode.UNKNOWN)
            assertEquals(setOf(Rank.THREE),result.reading.ranks)
            assertEquals(1000L,result.sourceTime)
            assertNotNull(result.hudRegion)
            assertFalse(result.obscured)
        } finally { bitmap.recycle() }
    }
    @Test fun overlayMasksCannotSupplyGradeEvenWhenCachedOcrStillContainsTheDigits() {
        val bitmap=Bitmap.createBitmap(1280,588,Bitmap.Config.ARGB_8888)
        try {
            val result=GradeSourceReader().read(bitmap,1000,text(bitmap),listOf(GlyphBox(.06f,0f,.22f,.13f)),
                TableScene.TABLE,emptySet(),WildcardMode.UNKNOWN)
            assertNull(result.reading.ranks)
            assertTrue(result.obscured)
            assertEquals(GlyphBox(.06f,0f,.22f,.13f),result.hudRegion)
        } finally { bitmap.recycle() }
    }
    @Test fun fallbackGradeRegionExistsOnlyOnLandscapeTableScenes() {
        val bitmap=Bitmap.createBitmap(1280,588,Bitmap.Config.ARGB_8888)
        try {
            val reader=GradeSourceReader()
            for (scene in listOf(TableScene.TABLE,TableScene.DEALING,TableScene.TEAMMATE,TableScene.TRIBUTE))
                assertNotNull(reader.read(bitmap,1000,null,emptyList(),scene,emptySet(),WildcardMode.UNKNOWN).hudRegion)
            for (scene in listOf(TableScene.LOBBY,TableScene.UNKNOWN,TableScene.RESULT)) {
                val result=reader.read(bitmap,1000,text(bitmap),listOf(GlyphBox(0f,0f,1f,.2f)),scene,
                    emptySet(),WildcardMode.UNKNOWN)
                assertNull(result.hudRegion); assertFalse(result.obscured); assertNull(result.reading.ranks)
            }
        } finally { bitmap.recycle() }
    }
    @Test fun portraitAppScreenCannotBeAHiddenGameGradeRegion() {
        val bitmap=Bitmap.createBitmap(588,1280,Bitmap.Config.ARGB_8888)
        try {
            val result=GradeSourceReader().read(bitmap,1000,text(bitmap),listOf(GlyphBox(0f,0f,1f,.2f)),
                TableScene.TABLE,emptySet(),WildcardMode.UNKNOWN)
            assertNull(result.hudRegion); assertFalse(result.obscured)
        } finally { bitmap.recycle() }
    }
    @Test fun staleFutureAndDifferentDimensionOcrCannotDeclareGrade() {
        val bitmap=Bitmap.createBitmap(1280,588,Bitmap.Config.ARGB_8888)
        try {
            for (snapshot in listOf(text(bitmap,-1000),text(bitmap,1001),text(bitmap).copy(width=2608),
                text(bitmap).copy(height=1200))) {
                val result=GradeSourceReader().read(bitmap,1000,snapshot,emptyList(),TableScene.TABLE,
                    emptySet(),WildcardMode.UNKNOWN)
                assertNull(result.reading.ranks); assertNull(result.sourceTime)
            }
        } finally { bitmap.recycle() }
    }
    @Test fun aQualifiedActualCornerUsesItsFreshFrameTimestampWhileHudIsHidden() {
        val bitmap=Bitmap.createBitmap(1280,588,Bitmap.Config.ARGB_8888)
        try {
            val result=GradeSourceReader().read(bitmap,1000,null,listOf(GlyphBox(.06f,0f,.22f,.13f)),
                TableScene.TABLE,setOf(Rank.J),WildcardMode.SINGLE)
            assertEquals(setOf(Rank.J),result.reading.ranks)
            assertEquals("CORNER_SINGLE",result.reading.source)
            assertEquals(1000L,result.sourceTime)
            assertTrue(result.obscured)
        } finally { bitmap.recycle() }
    }
    @Test fun anUnrelatedOverlayOrAnInvalidMaskDoesNotTriggerGradeWarning() {
        val bitmap=Bitmap.createBitmap(1280,588,Bitmap.Config.ARGB_8888)
        try {
            val result=GradeSourceReader().read(bitmap,1000,text(bitmap),listOf(GlyphBox(.3f,0f,.8f,.15f),
                GlyphBox(Float.NaN,0f,.22f,.13f),GlyphBox(.2f,.1f,.1f,0f)),TableScene.TABLE,
                emptySet(),WildcardMode.UNKNOWN)
            assertEquals(setOf(Rank.THREE),result.reading.ranks)
            assertFalse(result.obscured)
        } finally { bitmap.recycle() }
    }
    @Test fun partiallyMaskedLabelsDoNotReadAnyCachedGradeDigitsThroughOverlay() {
        val bitmap=Bitmap.createBitmap(1280,588,Bitmap.Config.ARGB_8888)
        try {
            val result=GradeSourceReader().read(bitmap,1000,text(bitmap),listOf(GlyphBox(.08f,.004f,.12f,.035f)),
                TableScene.TABLE,emptySet(),WildcardMode.UNKNOWN)
            assertNull(result.reading.ranks)
            assertTrue(result.obscured)
        } finally { bitmap.recycle() }
    }
    @Test fun partialRightAndLeftMasksKeepTheWholePreviouslyObservedHudRegion() {
        val bitmap=Bitmap.createBitmap(1280,588,Bitmap.Config.ARGB_8888)
        try {
            val reader=GradeSourceReader()
            val complete=reader.read(bitmap,1000,text(bitmap),emptyList(),TableScene.TABLE,
                emptySet(),WildcardMode.UNKNOWN)
            for (mask in listOf(GlyphBox(.08f,.004f,.12f,.035f),GlyphBox(.13f,.004f,.17f,.035f))) {
                val partial=reader.read(bitmap,1250,text(bitmap,1250),listOf(mask),TableScene.TABLE,
                    emptySet(),WildcardMode.UNKNOWN)
                assertEquals(complete.hudRegion,partial.hudRegion)
                assertTrue(partial.obscured)
                assertNull(partial.reading.ranks)
            }
            reader.reset()
            val reset=reader.read(bitmap,1500,null,emptyList(),TableScene.TABLE,emptySet(),WildcardMode.UNKNOWN)
            assertEquals(GlyphBox(.06f,0f,.22f,.13f),reset.hudRegion)
        } finally { bitmap.recycle() }
    }
    @Test fun aspectChangeInvalidatesPriorCompleteGeometry() {
        val first=Bitmap.createBitmap(1280,588,Bitmap.Config.ARGB_8888)
        val next=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888)
        try {
            val reader=GradeSourceReader()
            reader.read(first,1000,text(first),emptyList(),TableScene.TABLE,emptySet(),WildcardMode.UNKNOWN)
            assertEquals(GlyphBox(.06f,0f,.22f,.13f),reader.read(next,1250,null,emptyList(),TableScene.TABLE,
                emptySet(),WildcardMode.UNKNOWN).hudRegion)
        } finally { first.recycle(); next.recycle() }
    }
}
