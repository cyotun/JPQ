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

    @Test fun approvedReminderFitsBelowDefaultBarAndFallsBackAboveOnlyAtTheScreenEdge() {
        val shortBar=bar.copy(bottom=60)
        val hint=assertNotNull(GradeOcclusionGeometry.hintPosition(2608,1200,shortBar,1050,80,144,12))
        assertEquals(GradeOcclusionRect(144,72,1194,152),hint)
        assertEquals(shortBar.left+144,hint.left)
        assertTrue(hint.top>=shortBar.bottom)
        assertTrue(hint.bottom<=1200*.13f)
        assertEquals(GradeOcclusionRect(144,150,1194,240),GradeOcclusionGeometry.hintPosition(2608,1200,bar,1050,90,144,12))
        assertEquals(GradeOcclusionRect(144,310,1194,400),GradeOcclusionGeometry.hintPosition(2608,1200,bar.copy(top=160,bottom=298),1050,90,144,12))
        assertEquals(GradeOcclusionRect(144,650,1194,740),GradeOcclusionGeometry.hintPosition(2608,1200,bar.copy(top=500,bottom=638),1050,90,144,12))
        assertEquals(GradeOcclusionRect(144,998,1194,1088),GradeOcclusionGeometry.hintPosition(2608,1200,bar.copy(top=1100,bottom=1190),1050,90,144,12))
        assertNull(GradeOcclusionGeometry.hintPosition(1200,2608,bar,1050,90,144,12))
    }
    @Test fun reminderTracksTheBodyAndNeverMovesSeparatelyToTheLeftEdge() {
        val original=GradeOcclusionRect(300,0,2100,60)
        val hint=assertNotNull(GradeOcclusionGeometry.hintPosition(2608,1200,original,1050,80,144,12,144))
        assertEquals(444,hint.left)
        val moved=assertNotNull(GradeOcclusionGeometry.hintPosition(2608,1200,original.copy(left=450,right=2250),1050,80,144,12,144))
        assertEquals(150,moved.left-hint.left)
        val narrow=assertNotNull(GradeOcclusionGeometry.hintPosition(2608,1200,GradeOcclusionRect(450,0,1050,60),1050,80,144,12,144))
        assertEquals(312,narrow.width)
        assertEquals(594,narrow.left)
    }

    @Test fun visibleReminderDoesNotOverrideTheMaskProtectingActualPlayedCards() {
        val reminder=assertNotNull(GradeOcclusionGeometry.hintPosition(2608,1200,
            GradeOcclusionRect(468,6,2040,138),1043,96,0,10))
        assertEquals(468,reminder.left); assertEquals(148,reminder.top)
        val blocked=EmptyRegionEvidence().evaluate(IntArray(10*10),10,10,TableScene.TABLE,0,
            Seat.entries.associateWith { emptyList() },"actual-reminder",
            listOf(GlyphBox(reminder.left/2608f,reminder.top/1200f,reminder.right/2608f,reminder.bottom/1200f)))
        assertTrue(listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).all { blocked.getValue(it).reason=="overlay_occlusion" })
        val safe=assertNotNull(GradeOcclusionGeometry.hintPosition(2608,1200,
            GradeOcclusionRect(0,0,1800,60),1043,80,0,10))
        val evidence=EmptyRegionEvidence().evaluate(IntArray(10*10),10,10,TableScene.TABLE,27,
            Seat.entries.associateWith { emptyList() },"safe-tip",
            listOf(GlyphBox(safe.left/2608f,safe.top/1200f,safe.right/2608f,safe.bottom/1200f)))
        assertTrue(evidence.values.none { it.reason=="overlay_occlusion" })
    }

    @Test fun paddingAndRectEdgeContactAreConservative() {
        assertFalse(grade.intersects(grade.copy(left=450,right=650)))
        assertTrue(grade.padded(1).intersects(grade.copy(left=450,right=650)))
        assertFailsWith<IllegalArgumentException> { GradeOcclusionGeometry.safeTopPosition(0,1200,100,100,0,grade) }
    }

    @Test fun dismissingTheApprovedReminderCanResumeAnUnchangedRoundWithoutRestarting() {
        val hand=Rank.entries.filterNot { it.isJoker }.flatMap { listOf(Card(it,Suit.RED),Card(it,Suit.BLACK)) }+Card(Rank.SMALL,Suit.NONE)
        val ledger=RoundLedger(); val observer=RoundObserver(ledger)
        val clear=Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }
        observer.observe(TableFrame(0,TableScene.LOBBY,null))
        for(t in 60L..240L step 60) observer.observe(TableFrame(t,TableScene.TABLE,hand,clear))
        fun play(rank: Rank,x: Float)=Card(rank,Suit.RED).let { card ->
            ObservedPlay(listOf(card),.99f,listOf(PlayGlyph(card,.99f,GlyphBox(x,.30f,x+.02f,.35f))))
        }
        val groups=clear+mapOf(Seat.PREVIOUS to play(Rank.K,.20f),Seat.OPPOSITE to play(Rank.Q,.48f),Seat.NEXT to play(Rank.A,.75f))
        for(t in 300L..480L step 60) observer.observe(TableFrame(t,TableScene.TABLE,hand,groups))
        val before=ledger.snapshot()
        val tip=assertNotNull(GradeOcclusionGeometry.hintPosition(2608,1200,bar,1050,96,144,12))
        val mask=GlyphBox(tip.left/2608f,tip.top/1200f,tip.right/2608f,tip.bottom/1200f)
        val evidence=EmptyRegionEvidence().evaluate(IntArray(10*10),10,10,TableScene.TABLE,27,
            groups.mapValues { it.value.glyphs },"reminder",listOf(mask))
        assertTrue(listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).all { !evidence.getValue(it).observed })
        for(t in 600L..1200L step 120) observer.observe(TableFrame(t,TableScene.UNKNOWN,null,incompleteReason="reminder occludes card region"))
        assertEquals(RoundPhase.RECOVERING,ledger.snapshot().phase)
        assertNull(ledger.snapshot().remaining)
        assertFalse(observer.diagnosticStatus().locked)
        for(t in 1260L..1440L step 60) observer.observe(TableFrame(t,TableScene.TABLE,hand,groups))
        assertEquals(before,ledger.snapshot())
        assertFalse(observer.diagnosticStatus().recovering)
    }
}
