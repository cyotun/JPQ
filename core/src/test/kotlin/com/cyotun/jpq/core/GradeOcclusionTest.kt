package com.cyotun.jpq.core

import kotlin.test.*

class GradeOcclusionTest {
    private val grade=GradeOcclusionRect(150,0,450,135)
    private val bar=GradeOcclusionRect(0,0,1800,138)

    @Test fun onlyActualOverlapDuringVisibleCaptureShowsTheHint() {
        val session=GradeOcclusionSession()
        assertTrue(session.shouldShow(true,false,false,true,bar,grade))
        assertFalse(session.shouldShow(false,false,false,true,bar,grade))
        assertFalse(session.shouldShow(true,true,false,true,bar,grade))
        assertFalse(session.shouldShow(true,false,true,true,bar,grade))
        assertFalse(session.shouldShow(true,false,false,false,bar,grade))
        assertFalse(session.shouldShow(true,false,false,true,bar,null))
        assertFalse(session.shouldShow(true,false,false,true,GradeOcclusionRect(450,0,2000,138),grade))
        assertFalse(session.shouldShow(true,false,false,true,GradeOcclusionRect(0,135,1800,300),grade))
    }

    @Test fun ignoreSurvivesRoundsRegionsOrientationAndSettingChangesUntilANewSession() {
        val session=GradeOcclusionSession()
        session.ignore()
        for(enabled in listOf(false,true)) {
            assertFalse(session.shouldShow(true,false,false,enabled,bar,grade))
            assertFalse(session.shouldShow(true,false,false,enabled,bar,grade.copy(right=500)))
            assertFalse(session.shouldShow(true,false,false,enabled,GradeOcclusionRect(0,0,1000,160),grade))
        }
        assertTrue(GradeOcclusionSession().shouldShow(true,false,false,true,bar,grade))
    }

    @Test fun oneKeyMoveKeepsTheWholeBarInsideTheTopSafeBand() {
        val point=assertNotNull(GradeOcclusionGeometry.safeTopPosition(2608,1200,1700,138,0,grade,12))
        assertEquals(OverlayPoint(462,0),point)
        val moved=GradeOcclusionRect(point.x,point.y,point.x+1700,point.y+138)
        assertFalse(moved.intersects(grade.padded(12)))
        assertTrue(moved.right<=2608*.84f)
    }

    @Test fun aFullWidthOrTooTallBarMustBeMovedManually() {
        assertNull(GradeOcclusionGeometry.safeTopPosition(2608,1200,2300,138,0,grade,12))
        assertNull(GradeOcclusionGeometry.safeTopPosition(2608,1200,1800,180,0,grade,12))
        assertNull(GradeOcclusionGeometry.safeTopPosition(1200,2608,800,138,0,grade,12))
        assertNull(GradeOcclusionGeometry.safeTopPosition(2608,1200,2800,138,0,grade,12))
    }

    @Test fun nearestSafeTopAnchorCanBeOnEitherSideOfANonstandardGradeRegion() {
        val central=GradeOcclusionRect(1100,0,1450,135)
        assertEquals(OverlayPoint(0,0),GradeOcclusionGeometry.safeTopPosition(2608,1200,800,138,0,central,12))
        assertEquals(OverlayPoint(288,0),GradeOcclusionGeometry.safeTopPosition(2608,1200,800,138,900,central,12))
    }

    @Test fun belowBarHintAvoidsTheCentralAndSidePlayedCardRegions() {
        val hint=assertNotNull(GradeOcclusionGeometry.hintPosition(2608,1200,bar,1050,90,144,12))
        assertEquals(GradeOcclusionRect(0,150,1043,240),hint)
        assertTrue(hint.right<=2608*.40f)
        assertTrue(hint.top>=bar.bottom)
        assertEquals(GradeOcclusionRect(0,98,1043,188),GradeOcclusionGeometry.hintPosition(2608,1200,bar.copy(top=200,bottom=338),1050,90,144,12))
        assertNull(GradeOcclusionGeometry.hintPosition(2608,1200,bar.copy(top=500,bottom=638),1050,90,144,12))
        assertNull(GradeOcclusionGeometry.hintPosition(1200,2608,bar,1050,90,144,12))
    }

    @Test fun paddingAndRectEdgeContactAreConservative() {
        assertFalse(grade.intersects(grade.copy(left=450,right=650)))
        assertTrue(grade.padded(1).intersects(grade.copy(left=450,right=650)))
        assertFailsWith<IllegalArgumentException> { GradeOcclusionGeometry.safeTopPosition(0,1200,100,100,0,grade) }
    }
}
