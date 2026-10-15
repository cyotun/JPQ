package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.core.GlyphBox
import com.cyotun.jpq.core.Rank
import com.cyotun.jpq.core.TableScene
import com.cyotun.jpq.core.WildcardMode
import com.cyotun.jpq.core.TencentGradeTeam
import com.cyotun.jpq.core.GradeOcclusionRect
import com.cyotun.jpq.core.GradeOcclusionSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Adapter/mask checks. These do not claim live OCR or recognition accuracy. */
@RunWith(AndroidJUnit4::class)
class GradeSourceReaderTest {
    private fun rect(left: Float,top: Float,right: Float,bottom: Float,width: Int,height: Int) =
        Rect((left*width).toInt(),(top*height).toInt(),(right*width).toInt(),(bottom*height).toInt())
    private fun hudBitmap(width: Int,height: Int,ownActive: Boolean=true,otherActive: Boolean=false,
        bodies: Boolean=true): Bitmap {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap); val paint=Paint()
        bitmap.eraseColor(Color.rgb(9,108,97))
        paint.color=if(ownActive) Color.rgb(36,132,166) else Color.rgb(146,154,149)
        canvas.drawRect(rect(.075f,.004f,.125f,.04f,width,height),paint)
        paint.color=if(otherActive) Color.rgb(194,52,45) else Color.rgb(146,154,149)
        canvas.drawRect(rect(.125f,.004f,.175f,.04f,width,height),paint)
        if(bodies) {
            // Separate physical white panels, including their real bottom edge.
            // OCR metadata alone must not stand in for these source pixels.
            paint.color=Color.rgb(247,247,239)
            canvas.drawRect(rect(.076f,.042f,.124f,.10f,width,height),paint)
            canvas.drawRect(rect(.126f,.042f,.174f,.10f,width,height),paint)
        }
        return bitmap
    }
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
        val bitmap=hudBitmap(1280,588)
        try {
            val result=GradeSourceReader().read(bitmap,1000,text(bitmap),emptyList(),TableScene.TABLE,
                emptySet(),WildcardMode.UNKNOWN)
            assertEquals(setOf(Rank.THREE),result.reading.ranks)
            assertEquals("HUD_ACTIVE",result.reading.source)
            assertEquals(1000L,result.sourceTime)
            assertNotNull(result.hudRegion)
            assertFalse(result.obscured)
        } finally { bitmap.recycle() }
    }
    @Test fun overlayMasksCannotSupplyGradeEvenWhenCachedOcrStillContainsTheDigits() {
        val bitmap=hudBitmap(1280,588)
        try {
            val result=GradeSourceReader().read(bitmap,1000,text(bitmap),listOf(GlyphBox(.06f,0f,.22f,.13f)),
                TableScene.TABLE,emptySet(),WildcardMode.UNKNOWN)
            assertNull(result.reading.ranks)
            assertFalse(result.obscured)
            assertNull(result.hudRegion)
        } finally { bitmap.recycle() }
    }
    @Test fun tableAppearanceAloneCannotInventAVisibleGradeRegion() {
        val bitmap=hudBitmap(1280,588)
        try {
            val reader=GradeSourceReader()
            for (scene in listOf(TableScene.TABLE,TableScene.DEALING,TableScene.TEAMMATE,TableScene.TRIBUTE))
                assertNull(reader.read(bitmap,1000,null,emptyList(),scene,emptySet(),WildcardMode.UNKNOWN).hudRegion)
            for (scene in listOf(TableScene.LOBBY,TableScene.UNKNOWN,TableScene.RESULT)) {
                val result=reader.read(bitmap,1000,text(bitmap),listOf(GlyphBox(0f,0f,1f,.2f)),scene,
                    emptySet(),WildcardMode.UNKNOWN)
                assertNull(result.hudRegion); assertFalse(result.obscured); assertNull(result.reading.ranks)
            }
        } finally { bitmap.recycle() }
    }
    @Test fun portraitAppScreenCannotBeAHiddenGameGradeRegion() {
        val bitmap=hudBitmap(588,1280)
        try {
            val result=GradeSourceReader().read(bitmap,1000,text(bitmap),listOf(GlyphBox(0f,0f,1f,.2f)),
                TableScene.TABLE,emptySet(),WildcardMode.UNKNOWN)
            assertNull(result.hudRegion); assertFalse(result.obscured)
        } finally { bitmap.recycle() }
    }
    @Test fun staleFutureAndDifferentDimensionOcrCannotDeclareGrade() {
        val bitmap=hudBitmap(1280,588)
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
        val bitmap=hudBitmap(1280,588)
        try {
            val result=GradeSourceReader().read(bitmap,1000,null,listOf(GlyphBox(.06f,0f,.22f,.13f)),
                TableScene.TABLE,setOf(Rank.J),WildcardMode.SINGLE)
            assertEquals(setOf(Rank.J),result.reading.ranks)
            assertEquals("CORNER_SINGLE",result.reading.source)
            assertEquals(1000L,result.sourceTime)
            assertFalse(result.obscured)
            assertNull(result.hudRegion)
        } finally { bitmap.recycle() }
    }
    @Test fun anUnrelatedOverlayOrAnInvalidMaskDoesNotTriggerGradeWarning() {
        val bitmap=hudBitmap(1280,588)
        try {
            val result=GradeSourceReader().read(bitmap,1000,text(bitmap),listOf(GlyphBox(.3f,0f,.8f,.15f),
                GlyphBox(Float.NaN,0f,.22f,.13f),GlyphBox(.2f,.1f,.1f,0f)),TableScene.TABLE,
                emptySet(),WildcardMode.UNKNOWN)
            assertEquals(setOf(Rank.THREE),result.reading.ranks)
            assertFalse(result.obscured)
        } finally { bitmap.recycle() }
    }
    @Test fun partiallyMaskedLabelsDoNotReadAnyCachedGradeDigitsThroughOverlay() {
        val bitmap=hudBitmap(1280,588)
        try {
            val result=GradeSourceReader().read(bitmap,1000,text(bitmap),listOf(GlyphBox(.08f,.004f,.12f,.035f)),
                TableScene.TABLE,emptySet(),WildcardMode.UNKNOWN)
            assertNull(result.reading.ranks)
            assertFalse(result.obscured)
        } finally { bitmap.recycle() }
    }
    @Test fun partialRightAndLeftMasksKeepTheWholePreviouslyObservedHudRegion() {
        val bitmap=hudBitmap(1280,588)
        try {
            val reader=GradeSourceReader()
            val complete=reader.read(bitmap,1000,text(bitmap),emptyList(),TableScene.TABLE,
                emptySet(),WildcardMode.UNKNOWN)
            val maskedActive=reader.read(bitmap,1250,text(bitmap,1250),listOf(GlyphBox(.08f,.004f,.12f,.035f)),
                TableScene.TABLE,emptySet(),WildcardMode.UNKNOWN)
            assertEquals(complete.hudRegion,maskedActive.hudRegion)
            assertTrue(maskedActive.obscured); assertNull(maskedActive.reading.ranks)
            val maskedGrey=reader.read(bitmap,1500,text(bitmap,1500),listOf(GlyphBox(.13f,.004f,.17f,.035f)),
                TableScene.TABLE,emptySet(),WildcardMode.UNKNOWN)
            assertEquals(complete.hudRegion,maskedGrey.hudRegion)
            assertFalse(maskedGrey.obscured); assertEquals(setOf(Rank.THREE),maskedGrey.reading.ranks)
            reader.reset()
            val reset=reader.read(bitmap,1500,null,emptyList(),TableScene.TABLE,emptySet(),WildcardMode.UNKNOWN)
            assertNull(reset.hudRegion)
        } finally { bitmap.recycle() }
    }
    @Test fun aspectChangeInvalidatesPriorCompleteGeometry() {
        val first=hudBitmap(1280,588)
        val next=hudBitmap(1280,720)
        try {
            val reader=GradeSourceReader()
            reader.read(first,1000,text(first),emptyList(),TableScene.TABLE,emptySet(),WildcardMode.UNKNOWN)
            assertNull(reader.read(next,1250,null,emptyList(),TableScene.TABLE,
                emptySet(),WildcardMode.UNKNOWN).hudRegion)
        } finally { first.recycle(); next.recycle() }
    }

    @Test fun greyOwnFiveAndRedOtherQueenReadOnlyQueenAndOnlyTheActualPanel() {
        val bitmap=hudBitmap(1280,588,ownActive=false,otherActive=true)
        try {
            val original=text(bitmap)
            val edited=original.copy(metadataElements=original.metadataElements.map { line ->
                if(line.text=="3") line.copy(text=if(line.box!!.centerX()<bitmap.width*.125f) "5" else "Q") else line
            })
            val result=GradeSourceReader().read(bitmap,1000,edited,emptyList(),TableScene.TABLE,emptySet(),WildcardMode.SINGLE)
            assertEquals(setOf(Rank.Q),result.reading.ranks)
            assertEquals(setOf(TencentGradeTeam.OTHER),result.activeHudTeams)
            assertTrue(result.hudRegion!!.left>.119f)
            assertFalse(result.obscured)
        } finally { bitmap.recycle() }
    }

    @Test fun twoGreyProgressPanelsCannotEstablishAnyCurrentGradeOrWarningRegion() {
        val bitmap=hudBitmap(1280,588,ownActive=false,otherActive=false)
        try {
            val result=GradeSourceReader().read(bitmap,1000,text(bitmap),emptyList(),TableScene.TABLE,emptySet(),WildcardMode.UNKNOWN)
            assertNull(result.reading.ranks)
            assertTrue(result.activeHudTeams.isEmpty())
            assertNull(result.hudRegion); assertFalse(result.obscured)
        } finally { bitmap.recycle() }
    }

    @Test fun observedRegionCannotCarryAcrossLobbyIntoANewTableWithoutEvidence() {
        val bitmap=hudBitmap(1280,588)
        try {
            val reader=GradeSourceReader()
            assertNotNull(reader.read(bitmap,1000,text(bitmap),emptyList(),TableScene.TABLE,emptySet(),WildcardMode.UNKNOWN).hudRegion)
            assertNull(reader.read(bitmap,1250,text(bitmap,1250),emptyList(),TableScene.LOBBY,emptySet(),WildcardMode.UNKNOWN).hudRegion)
            assertNull(reader.read(bitmap,1500,null,emptyList(),TableScene.TABLE,emptySet(),WildcardMode.UNKNOWN).hudRegion)
        } finally { bitmap.recycle() }
    }

    @Test fun measuredBluePanelDoesNotGrowAcrossAGreyNeighbourOrAnUnrelatedBar() {
        val bitmap=hudBitmap(1280,588)
        try {
            val bar=GlyphBox(.1798f,.0042f,.8411f,.1192f)
            val result=GradeSourceReader().read(bitmap,1000,text(bitmap),listOf(bar),TableScene.TABLE,emptySet(),WildcardMode.SINGLE)
            assertEquals(setOf(Rank.THREE),result.reading.ranks)
            val target=checkNotNull(result.hudRegion)
            assertEquals(.075f,target.left,.002f)
            assertEquals(.125f,target.right,.002f)
            assertFalse(result.obscured)
            assertTrue(target.right<bar.left)
        } finally { bitmap.recycle() }
    }

    @Test fun measuredRedPanelEndsAtItsActualBackgroundWithoutFixedRightPadding() {
        val bitmap=hudBitmap(1280,588,ownActive=false,otherActive=true)
        try {
            val bar=GlyphBox(.1798f,.0042f,.8411f,.1192f)
            val result=GradeSourceReader().read(bitmap,1000,text(bitmap),listOf(bar),TableScene.TABLE,emptySet(),WildcardMode.SINGLE)
            val target=checkNotNull(result.hudRegion)
            assertEquals(.125f,target.left,.002f)
            assertEquals(.175f,target.right,.002f)
            assertFalse(result.obscured)
            assertTrue(target.right<bar.left)
        } finally { bitmap.recycle() }
    }

    @Test fun reportedRedTwoHeaderRemainsVisibleWhenOnlyItsSamplingPaddingTouchesTheBar() {
        val bitmap=InstrumentationRegistry.getInstrumentation().context.assets
            .open("fixtures/grade-reported-red-two.jpg").use { checkNotNull(BitmapFactory.decodeStream(it)) }
        try {
            // Actual production OCR boxes from the reported question-mark
            // screenshot. The wider duplicate OTHER title crosses the bar;
            // the 368..466 title and both overlapping 2 readings remain clear.
            val snapshot=SceneText(emptyList(),1000,bitmap.width,bitmap.height,metadataElements=listOf(
                OcrLine("我方",Rect(252,10,374,60)),
                OcrLine("2",Rect(281,69,329,128)),
                OcrLine("2",Rect(291,69,374,125)),
                OcrLine("对方",Rect(368,11,466,55)),
                OcrLine("对方",Rect(372,10,475,60)),
                OcrLine("2",Rect(372,69,435,128)),
                OcrLine("2",Rect(407,69,461,125)),
            ))
            val reader=GradeSourceReader()
            val result=reader.read(bitmap,1000,snapshot,listOf(GlyphBox(.1798f,.0042f,.8409f,.1175f)),
                TableScene.TEAMMATE,emptySet(),WildcardMode.UNKNOWN)
            assertEquals(setOf(TencentGradeTeam.OTHER),result.activeHudTeams)
            assertEquals(setOf(Rank.TWO),result.reading.ranks)
            assertEquals("HUD_ACTIVE",result.reading.source)
            assertTrue(checkNotNull(result.hudRegion).right<.1798f)
            assertFalse(result.obscured)
            // Covering the actual remaining title must still withdraw its
            // evidence rather than reading the cached digit through the mask.
            val covered=reader.read(bitmap,1250,snapshot,listOf(GlyphBox(465/2608f,0f,.8409f,.1175f)),
                TableScene.TEAMMATE,emptySet(),WildcardMode.UNKNOWN)
            assertTrue(covered.activeHudTeams.isEmpty())
            assertNull(covered.reading.ranks)
        } finally { bitmap.recycle() }
    }

    @Test fun barBelowActualPanelButInsideOldExtrapolatedRegionDoesNotWarn() {
        val bitmap=hudBitmap(1280,588)
        try {
            val bar=GlyphBox(.075f,.102f,.125f,.109f)
            val result=GradeSourceReader().read(bitmap,1000,text(bitmap),listOf(bar),TableScene.TABLE,
                emptySet(),WildcardMode.SINGLE)
            val target=checkNotNull(result.hudRegion)
            assertEquals(58f/588f,target.bottom,.00001f)
            assertTrue(target.bottom<bar.top)
            assertFalse(result.obscured)
            val physicalTarget=GradeOcclusionRect((target.left*1280).toInt(),(target.top*588).toInt(),
                kotlin.math.ceil(target.right*1280).toInt(),kotlin.math.ceil(target.bottom*588).toInt())
            assertFalse(GradeOcclusionSession().shouldShow(true,false,false,true,
                GradeOcclusionRect(96,60,160,64),physicalTarget))
        } finally { bitmap.recycle() }
    }

    @Test fun colouredHeadersBeforeNumberPanelsAppearCannotCreateWarningTarget() {
        val bitmap=hudBitmap(1280,588,bodies=false)
        val complete=hudBitmap(1280,588)
        try {
            val reader=GradeSourceReader()
            // Even plausible cached OCR digits cannot replace a missing panel.
            val early=reader.read(bitmap,1000,text(bitmap),emptyList(),TableScene.DEALING,
                emptySet(),WildcardMode.SINGLE)
            assertEquals(setOf(TencentGradeTeam.OWN),early.activeHudTeams)
            assertNull(early.hudRegion)
            assertFalse(early.obscured)
            assertNotNull(reader.read(complete,1250,text(complete,1250),emptyList(),TableScene.DEALING,
                emptySet(),WildcardMode.SINGLE).hudRegion)
        } finally { bitmap.recycle(); complete.recycle() }
    }

    @Test fun coveringOnlyGreyPanelCannotTriggerWarningForVisibleRedGrade() {
        val bitmap=hudBitmap(1280,588,ownActive=false,otherActive=true)
        try {
            val reader=GradeSourceReader()
            val initial=reader.read(bitmap,1000,text(bitmap),emptyList(),TableScene.TABLE,
                emptySet(),WildcardMode.SINGLE)
            val target=checkNotNull(initial.hudRegion)
            assertEquals(.125f,target.left,.00001f)
            val covered=reader.read(bitmap,1250,text(bitmap,1250),listOf(GlyphBox(.075f,0f,.124f,.105f)),
                TableScene.TABLE,emptySet(),WildcardMode.SINGLE)
            assertEquals(target,covered.hudRegion)
            assertFalse(covered.obscured)
            assertEquals(setOf(TencentGradeTeam.OTHER),covered.activeHudTeams)
        } finally { bitmap.recycle() }
    }

    @Test fun maskedNumberBodyCanOnlyRetainATargetSeenEarlierInThisDeal() {
        val bitmap=hudBitmap(1280,588)
        try {
            val mask=listOf(GlyphBox(.075f,.042f,.125f,.10f))
            val firstCovered=GradeSourceReader().read(bitmap,1000,text(bitmap),mask,TableScene.TABLE,
                emptySet(),WildcardMode.SINGLE)
            assertNull(firstCovered.hudRegion)
            assertFalse(firstCovered.obscured)
            val reader=GradeSourceReader()
            val initial=reader.read(bitmap,1000,text(bitmap),emptyList(),TableScene.TABLE,
                emptySet(),WildcardMode.SINGLE)
            assertNotNull(initial.hudRegion)
            val subsequentlyCovered=reader.read(bitmap,1250,text(bitmap,1250),mask,TableScene.TABLE,
                emptySet(),WildcardMode.SINGLE)
            assertEquals(initial.hudRegion,subsequentlyCovered.hudRegion)
            assertTrue(subsequentlyCovered.obscured)
            reader.reset()
            assertNull(reader.read(bitmap,1500,text(bitmap,1500),mask,TableScene.TABLE,
                emptySet(),WildcardMode.SINGLE).hudRegion)
        } finally { bitmap.recycle() }
    }
}
