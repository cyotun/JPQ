package com.cyotun.jpq.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TencentRoundGradeTest {
    private fun read(hints: List<SceneHint>,corners: Set<Rank> = emptySet(),mode: WildcardMode = WildcardMode.UNKNOWN,
        active: Set<TencentGradeTeam> = TencentGradeTeam.entries.toSet()) =
        tencentRoundGradeReading(hints,corners,mode,active)
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
            val result=read(hud(rank.label))
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
        val result=read(source)
        assertEquals(setOf(Rank.THREE),result.ranks)
        assertTrue(result.hudRegion!!.left <= 159/2089f)
        assertTrue(result.hudRegion.right >= 347/2089f)
        assertTrue(result.hudRegion.bottom >= 101/1094f)
    }
    @Test fun differentHudTeamLevelsWithoutColorEvidenceDoNotDeclareTwoWildRanks() {
        for (mode in WildcardMode.entries) {
            val result=tencentRoundGradeReading(hud("3","7"),mode=mode)
            assertNull(result.ranks)
            assertFalse(result.conflict)
        }
    }
    @Test fun productionMlkitNarrowThreeElementsUnderMergedLabelsReadBothTeams() {
        // Actual production OCR output on the reviewed teammate screenshot.
        val result=read(listOf(
            SceneHint("我方对方",GlyphBox(.09739264f,.011666667f,.1767638f,.043333333f)),
            SceneHint("33",GlyphBox(.125f,.0475f,.1690951f,.104166664f)),
            SceneHint("3",GlyphBox(.125f,.0475f,.13228528f,.104166664f)),
            SceneHint("3",GlyphBox(.16142638f,.0475f,.1690951f,.104166664f)),
        ))
        assertEquals(setOf(Rank.THREE),result.ranks)
        assertEquals("HUD_EQUAL",result.source)
    }
    @Test fun separatorBesideMergedLabelsDoesNotHideTwoActualCharacterBoxes() {
        val result=read(listOf(
            SceneHint("|我方对方",GlyphBox(.09892638f,.011666667f,.17829755f,.045f)),
            SceneHint("JJ",GlyphBox(.110046014f,.045833334f,.1648773f,.104166664f)),
            SceneHint("J",GlyphBox(.110f,.046f,.123f,.104f)),
            SceneHint("J",GlyphBox(.151f,.046f,.165f,.104f)),
        ),active=setOf(TencentGradeTeam.OWN))
        assertEquals(setOf(Rank.J),result.ranks)
        assertEquals("HUD_ACTIVE",result.source)
        assertEquals("|J",tencentGradeHudToken("|J"))
        assertEquals("昵称我方",tencentGradeHudToken("昵称我方"))
    }
    @Test fun mergedTenPointsUseActualAdjacentSymbolBoundsAndKeepTenWhole() {
        val symbols=listOf(
            SceneHint("1",GlyphBox(.11f,.045f,.115f,.10f)),SceneHint("0",GlyphBox(.116f,.045f,.126f,.10f)),
            SceneHint("1",GlyphBox(.15f,.045f,.155f,.10f)),SceneHint("0",GlyphBox(.156f,.045f,.166f,.10f)))
        val parsed=tencentGradeSymbolTokens(symbols)
        assertEquals(listOf("10","10"),parsed.map { it.text })
        assertEquals(GlyphBox(.11f,.045f,.126f,.10f),parsed[0].box)
        val hints=listOf(SceneHint("我方对方",GlyphBox(.10f,.008f,.18f,.035f)))+parsed
        assertEquals(setOf(Rank.TEN),read(hints,active=setOf(TencentGradeTeam.OWN)).ranks)
        assertTrue(tencentGradeSymbolTokens(listOf(symbols[0],symbols[3])).isEmpty())
        assertTrue(tencentGradeSymbolTokens(listOf(symbols[0],symbols[1].copy(box=GlyphBox(.116f,.005f,.126f,.025f)))).isEmpty())
    }
    @Test fun aSingleDigitCenteredBetweenMergedLabelsCannotCountAsBothTeamGrades() {
        val result=read(listOf(
            SceneHint("我方对方",GlyphBox(.09739264f,.011666667f,.1767638f,.043333333f)),
            SceneHint("3",GlyphBox(.133f,.0475f,.14115f,.104166664f)),
        ))
        assertNull(result.ranks)
    }
    @Test fun differentHudLevelsRequireCardCornerAndExplicitModeAgreement() {
        assertEquals(setOf(Rank.SEVEN),read(hud("3","7"),
            setOf(Rank.SEVEN),WildcardMode.SINGLE,setOf(TencentGradeTeam.OTHER)).ranks)
        assertEquals(setOf(Rank.THREE,Rank.SEVEN),read(hud("3","7"),
            setOf(Rank.THREE,Rank.SEVEN),WildcardMode.DOUBLE).ranks)
        assertNull(read(hud("3","7"),setOf(Rank.THREE,Rank.SEVEN),WildcardMode.UNKNOWN).ranks)
        assertTrue(read(hud("3","7"),setOf(Rank.THREE,Rank.FIVE),WildcardMode.DOUBLE).conflict)
        assertNull(read(hud("3","7"),setOf(Rank.THREE),WildcardMode.DOUBLE).ranks)
    }
    @Test fun aQualifiedSingleCornerCanEstablishGradeWhenHudIsHidden() {
        assertEquals(setOf(Rank.K),read(emptyList(),setOf(Rank.K),WildcardMode.SINGLE).ranks)
        assertNull(read(emptyList(),setOf(Rank.K),WildcardMode.UNKNOWN).ranks)
        assertNull(read(emptyList(),setOf(Rank.K),WildcardMode.DOUBLE).ranks)
    }
    @Test fun equalDualLevelsHaveOnlyOneDistinctPhysicalHeartGrade() {
        assertEquals(setOf(Rank.A),read(hud("A"),setOf(Rank.A),WildcardMode.DOUBLE).ranks)
    }
    @Test fun currentDealAnnouncementOverridesUpgradeScoresInRandomGradeMode() {
        for (text in listOf("本局级牌:7","本局随机级牌为7","本局打7","本轮级牌是7","本局随机打7")) {
            val result=read(hud()+announce(text))
            assertEquals(setOf(Rank.SEVEN),result.ranks,text)
            assertEquals("ANNOUNCEMENT",result.source)
        }
        assertEquals(setOf(Rank.TEN),read(listOf(announce("本 局 级 牌：10"))).ranks)
        assertEquals(setOf(Rank.K),read(listOf(announce("本局打k"))).ranks)
    }
    @Test fun explicitTwoPointAnnouncementsNeedNoGuessFromTeamLevels() {
        for (text in listOf("本局级牌3、7","本局级牌3/7","本局级牌3和7"))
            assertEquals(setOf(Rank.THREE,Rank.SEVEN),read(listOf(announce(text))).ranks)
    }
    @Test fun contradictoryAnnouncementsOrActualCornersWithdrawTheRule() {
        assertTrue(read(listOf(announce("本局级牌3"),announce("本局级牌7"))).conflict)
        assertTrue(read(listOf(announce("本局级牌3")),setOf(Rank.SEVEN)).conflict)
        assertTrue(read(hud(),setOf(Rank.SEVEN)).conflict)
        assertTrue(read(hud(),setOf(Rank.SEVEN),WildcardMode.SINGLE).conflict)
    }
    @Test fun missingLabelsNicknamesArbitraryDigitsAndIllegalPointsAreRejected() {
        for (text in listOf("3","本局级牌1","本局级牌大","本局级牌33","本局级牌3分","本局打3的高手","级牌3"))
            assertNull(read(listOf(announce(text))).ranks,text)
        assertNull(read(hud().filter { it.text != "我方" }).ranks)
        assertNull(read(hud("0")).ranks)
        assertNull(read(hud("11")).ranks)
    }
    @Test fun upperLeftCompactMergedHudTokensAreAllowedButExtraTextIsNot() {
        val tokens=listOf(SceneHint("我方10",GlyphBox(.08f,.008f,.12f,.10f)),
            SceneHint("对方10",GlyphBox(.13f,.008f,.17f,.10f)))
        assertEquals(setOf(Rank.TEN),read(tokens).ranks)
        assertNull(read(tokens.map { it.copy(text=it.text+"号") }).ranks)
        val mergedLabels=SceneHint("我方对方",GlyphBox(.081f,.008f,.169f,.034f))
        assertEquals(setOf(Rank.THREE),read(listOf(mergedLabels)+hud().filter {
            it.text == "3"
        }).ranks)
    }
    @Test fun qualifiedPhysicalCornerCanBeARandomPointDifferentFromBothTeamLevels() {
        assertEquals(setOf(Rank.K),read(hud("3","7"),setOf(Rank.K),WildcardMode.SINGLE,emptySet()).ranks)
    }
    @Test fun ambiguousDigitAssignmentsDoNotSelectOneGrade() {
        val hints=hud()+SceneHint("7",GlyphBox(.09f,.045f,.11f,.088f))
        assertNull(read(hints).ranks)
        assertEquals(setOf(Rank.THREE),read(hud()+hud()).ranks)
    }
    @Test fun wrongPositionsAndInvalidBoxesDoNotDeclareGrades() {
        val shifted=hud().map { hint -> val b=hint.box!!; hint.copy(box=GlyphBox(b.left+.3f,b.top+.4f,b.right+.3f,b.bottom+.4f)) }
        assertNull(read(shifted).ranks)
        for (box in listOf(null,GlyphBox(.1f,.02f,.2f,.08f),GlyphBox(.35f,.8f,.65f,.86f),
            GlyphBox(Float.NaN,.20f,.65f,.27f),GlyphBox(.65f,.20f,.35f,.27f),GlyphBox(-.1f,.20f,.65f,.27f)))
            assertNull(read(listOf(announce("本局级牌3",box))).ranks)
    }
    @Test fun userGrayOwnFiveAndRedOtherQueenSelectOnlyTheActualQueenGrade() {
        // 2026-10-15 screenshot 770837d9: grey own 5, red other Q,
        // and a physical gold-heart Q in the teammate's four-card reveal.
        val result=read(hud("5","Q"),active=setOf(TencentGradeTeam.OTHER))
        assertEquals(setOf(Rank.Q),result.ranks)
        assertEquals("HUD_ACTIVE",result.source)
        assertTrue(result.hudRegion!!.left>.119f)
        assertEquals(setOf(Rank.Q),read(hud("5","Q"),setOf(Rank.Q),WildcardMode.SINGLE,
            setOf(TencentGradeTeam.OTHER)).ranks)
    }
    @Test fun blueOwnGradeDoesNotAdoptItsGreyOtherNeighbour() {
        val result=read(hud("J","A"),active=setOf(TencentGradeTeam.OWN))
        assertEquals(setOf(Rank.J),result.ranks)
        assertTrue(result.hudRegion!!.right<.131f)
        assertNull(read(hud("3"),active=emptySet()).ranks)
        assertNull(read(hud("3"),active=emptySet()).hudRegion)
    }
    @Test fun twoActiveDifferentGradesNeedAnExplicitDoubleGradeMode() {
        assertEquals(setOf(Rank.THREE,Rank.SEVEN),read(hud("3","7"),mode=WildcardMode.DOUBLE).ranks)
        assertNull(read(hud("3","7"),mode=WildcardMode.UNKNOWN).ranks)
        assertNull(read(hud("3","7"),mode=WildcardMode.SINGLE).ranks)
        assertNull(read(hud("3","7"),mode=WildcardMode.DOUBLE,active=setOf(TencentGradeTeam.OTHER)).ranks)
    }
    @Test fun greyWhiteAndGreenHeaderPixelsCannotPretendToBeAnActiveTeam() {
        val width=30; val height=16
        fun solid(color: Int)=tencentGradeHeaderTone(width,height,IntArray(width*height) { color })
        assertEquals(TencentGradeHeaderTone.ACTIVE,solid(0xff2484a6.toInt()))
        assertEquals(TencentGradeHeaderTone.ACTIVE,solid(0xffc2342d.toInt()))
        assertEquals(TencentGradeHeaderTone.INACTIVE,solid(0xff929a95.toInt()))
        assertEquals(TencentGradeHeaderTone.UNKNOWN,solid(0xffffffff.toInt()))
        assertEquals(TencentGradeHeaderTone.UNKNOWN,solid(0xff096c61.toInt()))
        val whiteLetters=IntArray(width*height) { if(it%4==0) 0xffffffff.toInt() else 0xff2484a6.toInt() }
        assertEquals(TencentGradeHeaderTone.ACTIVE,tencentGradeHeaderTone(width,height,whiteLetters))
        val blueGlyphOnGrey=IntArray(width*height) { if(it<12) 0xff2484a6.toInt() else 0xff929a95.toInt() }
        assertEquals(TencentGradeHeaderTone.INACTIVE,tencentGradeHeaderTone(width,height,blueGlyphOnGrey))
        assertEquals(TencentGradeHeaderTone.UNKNOWN,tencentGradeHeaderTone(1,1,intArrayOf(0xff2484a6.toInt())))
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
        val later=read(hud(),setOf(Rank.J),WildcardMode.UNKNOWN)
        assertTrue(later.conflict)
        tracker.observe(750,750,later)
        assertEquals(setOf(Rank.J),tracker.current().ranks)
        tracker.observe(1000,1000,read(hud(),setOf(Rank.K),WildcardMode.UNKNOWN))
        assertFalse(tracker.current().confirmed)
    }
    @Test fun freshColoredHudAndExactPhysicalCornerAgreementRecoversAfterTransientConflict() {
        val tracker=RoundGradeTracker()
        val active=setOf(TencentGradeTeam.OTHER)
        val conflict=read(hud("Q","K"),setOf(Rank.Q),active=active)
        assertTrue(conflict.conflict)
        tracker.observe(0,0,conflict)
        // The grey own score has no authority. No SINGLE mode is inferred:
        // these ranks come from the one complete coloured HUD and actual corners.
        val agreement=read(hud("3","Q"),setOf(Rank.Q),active=active)
        assertEquals("HUD_ACTIVE",agreement.source)
        assertEquals(setOf(Rank.Q),agreement.ranks)
        for(time in listOf(250L,500L)) tracker.observe(time,time,agreement)
        assertFalse(tracker.current().confirmed)
        tracker.observe(750,750,agreement)
        assertEquals(setOf(Rank.Q),tracker.current().ranks)
        assertTrue(tracker.current().confirmed)
    }
    @Test fun actualChineseSymbolBoundsRecoverATitleBesideABorderWithoutSplittingItsWord() {
        val own=SceneHint("我",GlyphBox(.102f,.01f,.115f,.043f))
        val end=SceneHint("方",GlyphBox(.115f,.01f,.128f,.043f))
        val rank=SceneHint("Q",GlyphBox(.105f,.06f,.12f,.10f))
        val parsed=tencentGradeSymbolTokens(listOf(SceneHint("「",GlyphBox(.09f,.01f,.102f,.043f)),own,rank,end))
        assertTrue(SceneHint("我方",GlyphBox(.102f,.01f,.128f,.043f)) in parsed)
        assertTrue(rank in parsed)
        assertFalse(parsed.any { it.text.contains("「") })
        assertFalse(tencentGradeSymbolTokens(listOf(own,end.copy(box=GlyphBox(.115f,.06f,.128f,.093f))))
            .any { it.text=="我方" })
        assertFalse(tencentGradeSymbolTokens(listOf(own,end,end.copy(box=GlyphBox(.117f,.01f,.13f,.043f))))
            .any { it.text=="我方" })
    }
    @Test fun aHeaderCharacterBetweenTheActualOneAndZeroDoesNotHideTenOrReuseAZero() {
        val one=SceneHint("1",GlyphBox(.110f,.045f,.115f,.100f))
        val zero=SceneHint("0",GlyphBox(.117f,.045f,.127f,.100f))
        val end=SceneHint("方",GlyphBox(.116f,.010f,.126f,.040f))
        val parsed=tencentGradeSymbolTokens(listOf(one,end,zero))
        assertEquals(listOf(SceneHint("10",GlyphBox(.110f,.045f,.127f,.100f))),parsed)
        assertTrue(tencentGradeSymbolTokens(listOf(one,zero,zero.copy(box=GlyphBox(.118f,.045f,.128f,.100f))))
            .isEmpty())
        assertTrue(tencentGradeSymbolTokens(listOf(one,one.copy(box=GlyphBox(.111f,.045f,.116f,.100f)),zero))
            .isEmpty())
        assertTrue(tencentGradeSymbolTokens(listOf(one,zero.copy(box=GlyphBox(.117f,.010f,.127f,.040f))))
            .isEmpty())
    }
    @Test fun recoveryRequiresIndependentFreshAgreementVotesAndDoesNotUseHudOnly() {
        val tracker=RoundGradeTracker()
        val active=setOf(TencentGradeTeam.OTHER)
        tracker.observe(0,0,read(hud("Q","K"),setOf(Rank.Q),active=active))
        val hudOnly=read(hud("3","Q"),active=active)
        for(time in listOf(250L,500L,750L)) tracker.observe(time,time,hudOnly)
        assertFalse(tracker.current().confirmed)
        val agreement=read(hud("3","Q"),setOf(Rank.Q),active=active)
        tracker.observe(1000,1000,agreement)
        for(time in listOf(1250L,1500L,1750L)) tracker.observe(time,1000,agreement)
        assertFalse(tracker.current().confirmed)
        tracker.observe(2000,1500,agreement)
        assertFalse(tracker.current().confirmed)
        tracker.observe(2250,2000,agreement)
        assertEquals(setOf(Rank.Q),tracker.current().ranks)
    }
    @Test fun renewedConflictOrInterruptedFramesRequireANewFullAgreementSequence() {
        val tracker=RoundGradeTracker()
        val active=setOf(TencentGradeTeam.OTHER)
        val conflict=read(hud("Q","K"),setOf(Rank.Q),active=active)
        val agreement=read(hud("3","Q"),setOf(Rank.Q),active=active)
        tracker.observe(0,0,conflict)
        tracker.observe(250,250,agreement); tracker.observe(500,500,agreement)
        tracker.observe(750,750,conflict)
        tracker.observe(1000,1000,agreement); tracker.observe(1250,1250,agreement)
        assertFalse(tracker.current().confirmed)
        tracker.observe(1500,1500,reading(null,"UNAVAILABLE"))
        tracker.observe(1750,1750,agreement); tracker.observe(2000,2000,agreement)
        assertFalse(tracker.current().confirmed)
        tracker.observe(2250,2250,agreement)
        assertEquals(setOf(Rank.Q),tracker.current().ranks)
    }
    @Test fun exactAgreementDoesNotDowngradeAnAnnouncementFenceOrInventUnknownDoubleMode() {
        val tracker=RoundGradeTracker()
        for(time in listOf(0L,250L,500L)) tracker.observe(time,time,reading(Rank.J,"ANNOUNCEMENT"))
        tracker.observe(750,750,read(hud("3","K"),setOf(Rank.Q),active=setOf(TencentGradeTeam.OTHER)))
        val agreement=read(hud("3","Q"),setOf(Rank.Q),active=setOf(TencentGradeTeam.OTHER))
        for(time in listOf(1000L,1250L,1500L)) tracker.observe(time,time,agreement)
        assertFalse(tracker.current().confirmed)
        assertNull(read(hud("3","Q"),setOf(Rank.Q),WildcardMode.DOUBLE,
            setOf(TencentGradeTeam.OTHER)).ranks)
        assertNull(read(hud("3","Q"),setOf(Rank.THREE,Rank.Q),WildcardMode.UNKNOWN).ranks)
        assertNull(read(emptyList(),setOf(Rank.Q),WildcardMode.UNKNOWN).ranks)
        assertNull(read(hud("3","Q"),setOf(Rank.Q),active=emptySet()).ranks)
    }
}
