package com.cyotun.jpq.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TencentRoundGradeTest {
    private fun hud(own: String = "3", other: String = own): List<SceneHint> = listOf(
        SceneHint("我方", GlyphBox(.081f,.008f,.119f,.034f)),
        SceneHint(own, GlyphBox(.091f,.043f,.109f,.089f)),
        SceneHint("对方", GlyphBox(.131f,.008f,.169f,.034f)),
        SceneHint(other, GlyphBox(.141f,.043f,.159f,.089f)),
    )
    private fun announce(text: String, box: GlyphBox? = GlyphBox(.35f,.20f,.65f,.27f)) = SceneHint(text,box)
    private fun reading(rank: Rank?, source: String = "HUD_EQUAL", conflict: Boolean = false) =
        TencentGradeReading(rank?.let { setOf(it) },source,null,conflict)
    private fun confirm(tracker: RoundGradeTracker, rank: Rank = Rank.THREE) {
        for (time in listOf(0L,250L,500L)) tracker.observe(time,time,reading(rank))
    }

    @Test fun sameHudScoresDeclareOneHeartGradeForEveryOrdinaryRank() {
        for (rank in Rank.entries.filterNot { it.isJoker }) {
            val result=tencentRoundGradeReading(hud(rank.label))
            assertEquals(setOf(rank),result.ranks)
            assertEquals("HUD_EQUAL",result.source)
            assertFalse(result.conflict)
            assertTrue(result.hudRegion!!.top <= .008f)
            assertTrue(result.hudRegion.bottom >= .089f)
        }
    }
    @Test fun userProvidedHudLayoutHasAValidPositionedThreeGrade() {
        // Manual bounds from user screenshot a078f695 (2089 x 1094).
        // This validates the parser's region constraints, not ML Kit's OCR output.
        fun box(l: Int,t: Int,r: Int,b: Int)=GlyphBox(l/2089f,t/1094f,r/2089f,b/1094f)
        val source=listOf(SceneHint("我方",box(159,5,247,43)),SceneHint("3",box(185,55,222,101)),
            SceneHint("对方",box(259,5,347,43)),SceneHint("3",box(289,55,328,101)))
        val result=tencentRoundGradeReading(source)
        assertEquals(setOf(Rank.THREE),result.ranks)
        assertTrue(result.hudRegion!!.left <= 159/2089f)
        assertTrue(result.hudRegion.right >= 347/2089f)
        assertTrue(result.hudRegion.bottom >= 101/1094f)
    }
    @Test fun differentHudTeamLevelsAloneDoNotDeclareTwoWildRanks() {
        for (mode in WildcardMode.entries) {
            val result=tencentRoundGradeReading(hud("3","7"),mode=mode)
            assertNull(result.ranks)
            assertFalse(result.conflict)
        }
    }
    @Test fun differentHudLevelsRequireCardCornerAndExplicitModeAgreement() {
        assertEquals(setOf(Rank.SEVEN),tencentRoundGradeReading(hud("3","7"),
            setOf(Rank.SEVEN),WildcardMode.SINGLE).ranks)
        assertEquals(setOf(Rank.THREE,Rank.SEVEN),tencentRoundGradeReading(hud("3","7"),
            setOf(Rank.THREE,Rank.SEVEN),WildcardMode.DOUBLE).ranks)
        assertNull(tencentRoundGradeReading(hud("3","7"),setOf(Rank.THREE,Rank.SEVEN),WildcardMode.UNKNOWN).ranks)
        assertEquals(setOf(Rank.THREE,Rank.FIVE),tencentRoundGradeReading(hud("3","7"),
            setOf(Rank.THREE,Rank.FIVE),WildcardMode.DOUBLE).ranks)
        assertNull(tencentRoundGradeReading(hud("3","7"),setOf(Rank.THREE),WildcardMode.DOUBLE).ranks)
    }
    @Test fun aQualifiedSingleCornerCanEstablishGradeWhenHudIsHidden() {
        assertEquals(setOf(Rank.K),tencentRoundGradeReading(emptyList(),setOf(Rank.K),WildcardMode.SINGLE).ranks)
        assertNull(tencentRoundGradeReading(emptyList(),setOf(Rank.K),WildcardMode.UNKNOWN).ranks)
        assertNull(tencentRoundGradeReading(emptyList(),setOf(Rank.K),WildcardMode.DOUBLE).ranks)
    }
    @Test fun equalDualLevelsHaveOnlyOneDistinctPhysicalHeartGrade() {
        assertEquals(setOf(Rank.A),tencentRoundGradeReading(hud("A"),setOf(Rank.A),WildcardMode.DOUBLE).ranks)
    }
    @Test fun currentDealAnnouncementOverridesUpgradeScoresInRandomGradeMode() {
        for (text in listOf("本局级牌:7","本局随机级牌为7","本局打7","本轮级牌是7","本局随机打7")) {
            val result=tencentRoundGradeReading(hud()+announce(text))
            assertEquals(setOf(Rank.SEVEN),result.ranks,text)
            assertEquals("ANNOUNCEMENT",result.source)
        }
        assertEquals(setOf(Rank.TEN),tencentRoundGradeReading(listOf(announce("本 局 级 牌：10"))).ranks)
        assertEquals(setOf(Rank.K),tencentRoundGradeReading(listOf(announce("本局打k"))).ranks)
    }
    @Test fun explicitTwoPointAnnouncementsNeedNoGuessFromTeamLevels() {
        for (text in listOf("本局级牌3、7","本局级牌3/7","本局级牌3和7"))
            assertEquals(setOf(Rank.THREE,Rank.SEVEN),tencentRoundGradeReading(listOf(announce(text))).ranks)
    }
    @Test fun contradictoryAnnouncementsOrActualCornersWithdrawTheRule() {
        assertTrue(tencentRoundGradeReading(listOf(announce("本局级牌3"),announce("本局级牌7"))).conflict)
        assertTrue(tencentRoundGradeReading(listOf(announce("本局级牌3")),setOf(Rank.SEVEN)).conflict)
        assertTrue(tencentRoundGradeReading(hud(),setOf(Rank.SEVEN)).conflict)
        assertEquals(setOf(Rank.SEVEN),tencentRoundGradeReading(hud(),setOf(Rank.SEVEN),WildcardMode.SINGLE).ranks)
    }
    @Test fun missingLabelsNicknamesArbitraryDigitsAndIllegalPointsAreRejected() {
        for (text in listOf("3","本局级牌1","本局级牌大","本局级牌33","本局级牌3分","本局打3的高手","级牌3"))
            assertNull(tencentRoundGradeReading(listOf(announce(text))).ranks,text)
        assertNull(tencentRoundGradeReading(hud().filter { it.text != "我方" }).ranks)
        assertNull(tencentRoundGradeReading(hud("0")).ranks)
        assertNull(tencentRoundGradeReading(hud("11")).ranks)
    }
    @Test fun upperLeftCompactMergedHudTokensAreAllowedButExtraTextIsNot() {
        val tokens=listOf(SceneHint("我方10",GlyphBox(.08f,.008f,.12f,.10f)),
            SceneHint("对方10",GlyphBox(.13f,.008f,.17f,.10f)))
        assertEquals(setOf(Rank.TEN),tencentRoundGradeReading(tokens).ranks)
        assertNull(tencentRoundGradeReading(tokens.map { it.copy(text=it.text+"号") }).ranks)
        val mergedLabels=SceneHint("我方对方",GlyphBox(.081f,.008f,.169f,.034f))
        assertEquals(setOf(Rank.THREE),tencentRoundGradeReading(listOf(mergedLabels)+hud().filter {
            it.text == "3"
        }).ranks)
    }
    @Test fun qualifiedPhysicalCornerCanBeARandomPointDifferentFromBothTeamLevels() {
        assertEquals(setOf(Rank.K),tencentRoundGradeReading(hud("3","7"),setOf(Rank.K),WildcardMode.SINGLE).ranks)
    }
    @Test fun ambiguousDigitAssignmentsDoNotSelectOneGrade() {
        val hints=hud()+SceneHint("7",GlyphBox(.09f,.045f,.11f,.088f))
        assertNull(tencentRoundGradeReading(hints).ranks)
        assertEquals(setOf(Rank.THREE),tencentRoundGradeReading(hud()+hud()).ranks)
    }
    @Test fun wrongPositionsAndInvalidBoxesDoNotDeclareGrades() {
        val shifted=hud().map { hint -> val b=hint.box!!; hint.copy(box=GlyphBox(b.left+.3f,b.top+.4f,b.right+.3f,b.bottom+.4f)) }
        assertNull(tencentRoundGradeReading(shifted).ranks)
        for (box in listOf(null,GlyphBox(.1f,.02f,.2f,.08f),GlyphBox(.35f,.8f,.65f,.86f),
            GlyphBox(Float.NaN,.20f,.65f,.27f),GlyphBox(.65f,.20f,.35f,.27f),GlyphBox(-.1f,.20f,.65f,.27f)))
            assertNull(tencentRoundGradeReading(listOf(announce("本局级牌3",box))).ranks)
    }
    @Test fun oneCachedOcrCannotProvideThreeVotes() {
        val tracker=RoundGradeTracker()
        tracker.observe(0,0,reading(Rank.THREE))
        tracker.observe(250,0,reading(Rank.THREE)); tracker.observe(500,0,reading(Rank.THREE))
        assertFalse(tracker.current().confirmed)
        tracker.observe(500,250,reading(Rank.THREE)); tracker.observe(750,500,reading(Rank.THREE))
        assertEquals(setOf(Rank.THREE),tracker.current().ranks)
    }
    @Test fun threeRapidDistinctResultsStillNeedHalfASecondSpan() {
        val tracker=RoundGradeTracker()
        for (time in listOf(0L,100L,200L)) tracker.observe(time,time,reading(Rank.THREE))
        assertFalse(tracker.current().confirmed)
        tracker.observe(500,500,reading(Rank.THREE))
        assertTrue(tracker.current().confirmed)
    }
    @Test fun staleFutureAndNegativeSourceTimesCannotEstablishOrRevokeGrade() {
        val tracker=RoundGradeTracker(); confirm(tracker)
        for ((now,source) in listOf(2000L to 2001L,5000L to 600L,-1L to 1L,5000L to -1L))
            tracker.observe(now,source,reading(Rank.K,conflict=true))
        assertEquals(setOf(Rank.THREE),tracker.current().ranks)
    }
    @Test fun hidingOverlayEmptyOcrAndLongGapsPreserveConfirmedRoundGrade() {
        val tracker=RoundGradeTracker(); confirm(tracker)
        tracker.observe(5000,5000,reading(null)); tracker.observe(6000,null,reading(null))
        tracker.observe(7000,7000,reading(Rank.THREE))
        assertEquals(setOf(Rank.THREE),tracker.current().ranks)
    }
    @Test fun aFreshDifferentPointWithdrawsBeforeRequalification() {
        val tracker=RoundGradeTracker(); confirm(tracker)
        tracker.observe(750,750,reading(Rank.K)); assertFalse(tracker.current().confirmed)
        tracker.observe(1000,1000,reading(Rank.K)); tracker.observe(1250,1250,reading(Rank.K))
        assertEquals(setOf(Rank.K),tracker.current().ranks)
    }
    @Test fun aFreshConflictWithdrawsAndRequiresThreeNewVotes() {
        val tracker=RoundGradeTracker(); confirm(tracker)
        tracker.observe(750,750,reading(null,conflict=true)); assertFalse(tracker.current().confirmed)
        tracker.observe(1000,1000,reading(Rank.THREE)); tracker.observe(1250,1250,reading(Rank.THREE))
        assertFalse(tracker.current().confirmed)
        tracker.observe(1500,1500,reading(Rank.THREE)); assertTrue(tracker.current().confirmed)
    }
    @Test fun incompleteReadingsAndLongGapsBreakUnconfirmedVotes() {
        val tracker=RoundGradeTracker()
        tracker.observe(0,0,reading(Rank.THREE)); tracker.observe(250,250,reading(null))
        tracker.observe(500,500,reading(Rank.THREE)); tracker.observe(750,750,reading(Rank.THREE))
        assertFalse(tracker.current().confirmed)
        tracker.observe(2500,2500,reading(Rank.THREE)); assertFalse(tracker.current().confirmed)
        tracker.observe(2750,2750,reading(Rank.THREE)); tracker.observe(3000,3000,reading(Rank.THREE))
        assertTrue(tracker.current().confirmed)
    }
    @Test fun resetDoesNotReuseAnyPriorDealOrSessionGrade() {
        val tracker=RoundGradeTracker(); confirm(tracker)
        tracker.reset(); assertFalse(tracker.current().confirmed)
        tracker.observe(0,0,reading(Rank.A)); assertFalse(tracker.current().confirmed)
        tracker.observe(250,250,reading(Rank.A)); tracker.observe(500,500,reading(Rank.A))
        assertEquals(setOf(Rank.A),tracker.current().ranks)
    }
    @Test fun randomDealAnnouncementSurvivesEqualProgressHudAndEmptyReadings() {
        val tracker=RoundGradeTracker()
        for (time in listOf(0L,250L,500L)) tracker.observe(time,time,reading(Rank.J,"ANNOUNCEMENT"))
        for (time in listOf(750L,1000L,1250L)) tracker.observe(time,time,reading(Rank.THREE))
        assertEquals(setOf(Rank.J),tracker.current().ranks)
        tracker.observe(1500,1500,reading(null))
        for (time in listOf(1750L,2000L,2250L)) tracker.observe(time,time,reading(Rank.THREE))
        assertEquals(setOf(Rank.J),tracker.current().ranks)
    }
    @Test fun pendingStrongSourceCannotBeOverwrittenByWeakerHudVotes() {
        val tracker=RoundGradeTracker()
        tracker.observe(0,0,reading(Rank.J,"ANNOUNCEMENT")); tracker.observe(250,250,reading(Rank.J,"ANNOUNCEMENT"))
        for (time in listOf(500L,750L,1000L)) tracker.observe(time,time,reading(Rank.THREE))
        assertFalse(tracker.current().confirmed)
        tracker.observe(1250,1250,reading(Rank.J,"ANNOUNCEMENT"))
        assertEquals(setOf(Rank.J),tracker.current().ranks)
    }
    @Test fun physicalConflictWithdrawsAnnouncementWithoutFallingBackToTeamProgress() {
        val tracker=RoundGradeTracker()
        for (time in listOf(0L,250L,500L)) tracker.observe(time,time,reading(Rank.J,"ANNOUNCEMENT"))
        tracker.observe(750,750,reading(null,"HUD_CORNER_CONFLICT",true))
        assertFalse(tracker.current().confirmed)
        for (time in listOf(1000L,1250L,1500L)) tracker.observe(time,time,reading(Rank.THREE))
        assertFalse(tracker.current().confirmed)
        for (time in listOf(1750L,2000L,2250L)) tracker.observe(time,time,reading(Rank.J,"ANNOUNCEMENT"))
        assertEquals(setOf(Rank.J),tracker.current().ranks)
    }
    @Test fun contradictingStrongAnnouncementRequiresFreshStrongRequalification() {
        val tracker=RoundGradeTracker()
        for (time in listOf(0L,250L,500L)) tracker.observe(time,time,reading(Rank.J,"ANNOUNCEMENT"))
        tracker.observe(750,750,reading(Rank.K,"ANNOUNCEMENT")); assertFalse(tracker.current().confirmed)
        tracker.observe(1000,1000,reading(Rank.K,"ANNOUNCEMENT")); tracker.observe(1250,1250,reading(Rank.K,"ANNOUNCEMENT"))
        assertEquals(setOf(Rank.K),tracker.current().ranks)
    }
    @Test fun visibilityChangeClearsOnlyPendingEvidence() {
        val tracker=RoundGradeTracker(); confirm(tracker)
        tracker.clearCandidateVotes(); assertEquals(setOf(Rank.THREE),tracker.current().ranks)
        tracker.observe(750,750,reading(Rank.K)); tracker.clearCandidateVotes()
        tracker.observe(1000,1000,reading(Rank.K)); tracker.observe(1250,1250,reading(Rank.K))
        assertFalse(tracker.current().confirmed)
        tracker.observe(1500,1500,reading(Rank.K)); assertEquals(setOf(Rank.K),tracker.current().ranks)
    }
    @Test fun emptyStrongCandidateStillPreventsProgressFromDeclaringARandomGrade() {
        val tracker=RoundGradeTracker()
        tracker.observe(0,0,reading(Rank.J,"ANNOUNCEMENT")); tracker.observe(250,250,reading(null))
        for (time in listOf(500L,750L,1000L)) tracker.observe(time,time,reading(Rank.THREE))
        assertFalse(tracker.current().confirmed)
        for (time in listOf(1250L,1500L,1750L)) tracker.observe(time,time,reading(Rank.J,"ANNOUNCEMENT"))
        assertEquals(setOf(Rank.J),tracker.current().ranks)
    }
    @Test fun qualifiedActualCornerCanContradictAnAnnouncementButCannotReplaceItWithProgress() {
        val tracker=RoundGradeTracker()
        for (time in listOf(0L,250L,500L)) tracker.observe(time,time,reading(Rank.J,"ANNOUNCEMENT"))
        tracker.observe(750,750,reading(Rank.K,"CORNER_SINGLE")); assertFalse(tracker.current().confirmed)
        for (time in listOf(1000L,1250L,1500L)) tracker.observe(time,time,reading(Rank.THREE))
        assertFalse(tracker.current().confirmed)
    }
    @Test fun aCorrectRandomPhysicalCornerDoesNotLetProgressHudWithdrawItsAnnouncement() {
        val tracker=RoundGradeTracker()
        for (time in listOf(0L,250L,500L)) tracker.observe(time,time,reading(Rank.J,"ANNOUNCEMENT"))
        val later=tencentRoundGradeReading(hud(),setOf(Rank.J),WildcardMode.UNKNOWN)
        assertTrue(later.conflict)
        tracker.observe(750,750,later)
        assertEquals(setOf(Rank.J),tracker.current().ranks)
        tracker.observe(1000,1000,tencentRoundGradeReading(hud(),setOf(Rank.K),WildcardMode.UNKNOWN))
        assertFalse(tracker.current().confirmed)
    }
}
