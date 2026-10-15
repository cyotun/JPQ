package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.core.TencentGradeTeam
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Pixel geometry checks only. These never declare a rank or an effective game mode. */
@RunWith(AndroidJUnit4::class)
class TencentHudPanelsTest {
    private val blue=Color.rgb(36,132,166)
    private val red=Color.rgb(194,52,45)
    private val gray=Color.rgb(146,154,149)
    private val table=Color.rgb(9,108,97)
    private fun drawPlate(canvas: Canvas,left: Int,tone: Int,top: Int=5,width: Int=47,
        body: Boolean=true,bodyGap: Int=1) {
        val paint=Paint()
        paint.color=tone; canvas.drawRect(Rect(left,top,left+width,top+23),paint)
        if(body) {
            paint.color=Color.rgb(247,247,239)
            canvas.drawRect(Rect(left,top+23+bodyGap,left+width,top+23+bodyGap+32),paint)
        }
    }
    private fun source(own: Int=blue,other: Int=gray,otherTop: Int=5,otherWidth: Int=47,
        ownBody: Boolean=true,otherBody: Boolean=true,otherBodyGap: Int=1): Bitmap =
        Bitmap.createBitmap(1280,588,Bitmap.Config.ARGB_8888).apply {
            eraseColor(table)
            val canvas=Canvas(this)
            drawPlate(canvas,128,own,body=ownBody)
            drawPlate(canvas,180,other,top=otherTop,width=otherWidth,body=otherBody,bodyGap=otherBodyGap)
        }
    private inline fun withSource(bitmap: Bitmap,check: (Bitmap)->Unit) {
        try { check(bitmap) } finally { bitmap.recycle() }
    }
    private fun fixture(name: String): Bitmap {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gradeOcrFixtureProbe")=="true")
        val stream=runCatching {
            InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/$name")
        }.getOrNull()
        assumeNotNull(stream)
        return stream!!.use { checkNotNull(BitmapFactory.decodeStream(it)) }
    }
    private fun near(expected: Rect,actual: Rect,tolerance: Int=2) {
        assertTrue("Expected $expected near $actual",listOf(
            expected.left-actual.left,expected.top-actual.top,
            expected.right-actual.right,expected.bottom-actual.bottom).all { abs(it)<=tolerance })
    }

    @Test fun actualQueenFrameMeasuresTheRedHeaderAndItsWhiteBodyWithoutAnyOcr() =
        withSource(fixture("grade-active-red-queen-clear.png")) { bitmap ->
            val panels=TencentHudPanels.detect(bitmap)
            assertEquals(2,panels.size)
            assertEquals(TencentGradeTeam.OWN,panels[0].team)
            assertFalse(panels[0].active)
            assertEquals(TencentGradeTeam.OTHER,panels[1].team)
            assertTrue(panels[1].active)
            near(Rect(262,10,358,57),panels[0].header)
            near(Rect(263,59,358,125),panels[0].body)
            near(Rect(369,10,464,57),panels[1].header)
            near(Rect(369,59,464,125),panels[1].body)
        }

    @Test fun allowedBlueGrayGrayRedAndBlueRedPairsKeepTheirMeasuredBounds() {
        for((own,other) in listOf(blue to gray,gray to red,blue to red)) withSource(source(own,other)) { bitmap ->
            val panels=TencentHudPanels.detect(bitmap)
            assertEquals(2,panels.size)
            assertEquals(listOf(TencentGradeTeam.OWN,TencentGradeTeam.OTHER),panels.map { it.team })
            assertEquals(listOf(own==blue,other==red),panels.map { it.active })
            assertEquals(Rect(128,5,175,28),panels[0].header)
            assertEquals(Rect(180,29,227,61),panels[1].body)
        }
    }

    @Test fun greyPanelsAloneNeverCreateAnActiveGradeTarget() = withSource(source(gray,gray)) {
        assertTrue(TencentHudPanels.detect(it).isEmpty())
    }

    @Test fun sameColoredPanelsAndReversedTeamColorsAreRejected() {
        for((own,other) in listOf(red to red,blue to blue,red to gray,gray to blue,red to blue))
            withSource(source(own,other)) { assertTrue(TencentHudPanels.detect(it).isEmpty()) }
    }

    @Test fun coloredButtonsWithoutWhiteLevelBodiesDoNotCreatePanels() =
        withSource(source(blue,red,ownBody=false,otherBody=false)) {
            assertTrue(TencentHudPanels.detect(it).isEmpty())
        }

    @Test fun anIsolatedRedLetterCannotPretendToBeAnActiveHeader() {
        val bitmap=Bitmap.createBitmap(1280,588,Bitmap.Config.ARGB_8888).apply {
            eraseColor(table)
            Canvas(this).drawText("Q",180f,58f,Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=red; textSize=48f
            })
        }
        withSource(bitmap) { assertTrue(TencentHudPanels.detect(it).isEmpty()) }
    }

    @Test fun eitherMissingWhiteBodyRejectsTheWholePair() {
        withSource(source(ownBody=false)) { assertTrue(TencentHudPanels.detect(it).isEmpty()) }
        withSource(source(otherBody=false)) { assertTrue(TencentHudPanels.detect(it).isEmpty()) }
    }

    @Test fun whiteBodyMustImmediatelyAdjoinAndAlignWithItsActualHeader() {
        withSource(source(otherBodyGap=12)) { assertTrue(TencentHudPanels.detect(it).isEmpty()) }
        withSource(source()) { bitmap ->
            val canvas=Canvas(bitmap); val paint=Paint().apply { color=table }
            canvas.drawRect(Rect(180,29,227,61),paint)
            paint.color=Color.WHITE; canvas.drawRect(Rect(189,29,236,61),paint)
            assertTrue(TencentHudPanels.detect(bitmap).isEmpty())
        }
    }

    @Test fun twoRealPlatesStillNeedTheSameBaselinesAndNearEqualWidths() {
        withSource(source(otherTop=15)) { assertTrue(TencentHudPanels.detect(it).isEmpty()) }
        withSource(source(otherWidth=70)) { assertTrue(TencentHudPanels.detect(it).isEmpty()) }
    }

    @Test fun multiplePossiblePairsAreAmbiguousRatherThanSelectingTheNearestOne() = withSource(source()) { bitmap ->
        drawPlate(Canvas(bitmap),232,red)
        assertTrue(TencentHudPanels.detect(bitmap).isEmpty())
    }

    @Test fun aUniformBackgroundAndAnEmptyExpectedHudPositionCannotInventGeometry() {
        for(color in listOf(table,blue,red,gray,Color.WHITE)) {
            val bitmap=Bitmap.createBitmap(1280,588,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            withSource(bitmap) { assertTrue(TencentHudPanels.detect(it).isEmpty()) }
        }
    }

    @Test fun portraitFramesAreNotTencentLandscapeHudEvidence() {
        val bitmap=Bitmap.createBitmap(588,1280,Bitmap.Config.ARGB_8888).apply { eraseColor(table) }
        withSource(bitmap) { assertTrue(TencentHudPanels.detect(it).isEmpty()) }
    }
}
