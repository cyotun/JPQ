package com.cyotun.jpq.core

import kotlin.test.*

class TencentSceneTest {
    private fun hint(text: String,x: Float=.5f,y: Float=.3f)=SceneHint(text,GlyphBox(x-.04f,y-.02f,x+.04f,y+.02f))
    @Test fun lastPlaceRevealIsResultEvenWhenFiveCardsAreVisible() {
        // First phone game: right player's QQ Q 33 are a five-card reveal, not a play.
        assertEquals(TableScene.RESULT,tencentScene(listOf(hint("末游",.77f,.34f),hint("三游",.5f,.70f)),true,false))
    }
    @Test fun earlierFinishesDoNotEndAnOngoingGame() {
        assertEquals(TableScene.TABLE,tencentScene(listOf(hint("头游",.24f,.34f),hint("二游",.5f,.22f)),true,true))
    }
    @Test fun remainingCountAloneDoesNotMeanResult() {
        assertEquals(TableScene.TABLE,tencentScene(listOf(hint("剩5张",.9f,.4f)),true,true))
    }
    @Test fun persistentVerticalAvatarDoubleTagsDoNotBecomeDealing() {
        // Persistent side-player captions from the real midgame capture.
        val avatarTags=listOf(
            SceneHint("加倍",GlyphBox(.1319f,.27f,.1453f,.3575f)),
            SceneHint("加倍",GlyphBox(.8528f,.2767f,.8658f,.3558f))
        )
        assertEquals(TableScene.TABLE,tencentScene(avatarTags,true,true))
    }
    @Test fun twoActualCentralDoublingChoicesBecomeDealing() {
        // Observed central choice presentation in diagnostics: a top-center
        // choice and the broad central "超级加倍" button.
        val choices=listOf(
            SceneHint("不加倍",GlyphBox(.4594f,.1558f,.5406f,.215f)),
            SceneHint("超级加倍",GlyphBox(.3746f,.365f,.625f,.5108f))
        )
        assertEquals(TableScene.DEALING,tencentScene(choices,true,true))
    }
    @Test fun oneCentralDoublingChoiceIsNotEnough() {
        assertEquals(TableScene.TABLE,tencentScene(listOf(
            SceneHint("加倍",GlyphBox(.4732f,.1575f,.5268f,.2167f))
        ),true,true))
    }
    @Test fun unpositionedAndMalformedDoublingLabelsDoNotCompleteThePair() {
        val oneValid=SceneHint("加倍",GlyphBox(.4732f,.1575f,.5268f,.2167f))
        assertEquals(TableScene.TABLE,tencentScene(listOf(
            oneValid,
            SceneHint("不加倍",null),
            SceneHint("超级加倍",GlyphBox(Float.NaN,.36f,.62f,.51f))
        ),true,true))
    }
    @Test fun teammateAndTerminalScenesKeepPriorityOverCentralDoublingChoices() {
        val choices=listOf(
            SceneHint("不加倍",GlyphBox(.4594f,.1558f,.5406f,.215f)),
            SceneHint("超级加倍",GlyphBox(.3746f,.365f,.625f,.5108f))
        )
        assertEquals(TableScene.TEAMMATE,tencentScene(choices+hint("队友手牌",.5f,.78f),true,true))
        assertEquals(TableScene.RESULT,tencentScene(choices+hint("末游",.77f,.34f),true,true))
        assertEquals(TableScene.RESULT,tencentScene(choices+hint("再来一局",.5f,.8f),true,true))
    }
    @Test fun duplicateOcrOrTwoUpperAvatarLabelsAreNotTwoChoiceControls() {
        val button=SceneHint("加倍",GlyphBox(.4f,.36f,.52f,.43f))
        assertEquals(TableScene.TABLE,tencentScene(listOf(button,button),true,true))
        assertEquals(TableScene.TABLE,tencentScene(listOf(
            SceneHint("加倍",GlyphBox(.4732f,.1575f,.5268f,.2167f)),
            SceneHint("不加倍",GlyphBox(.4594f,.1558f,.5406f,.215f))
        ),true,true))
    }
    @Test fun actualMultiplierButtonLabelsAreCentralDealEvidence() {
        // The reviewed red-Q fixture has these three large controls.
        assertEquals(TableScene.DEALING,tencentScene(listOf(
            SceneHint("加倍×2",GlyphBox(.26f,.34f,.37f,.42f)),
            SceneHint("超级加倍x4",GlyphBox(.42f,.34f,.57f,.42f)),
            SceneHint("不加倍",GlyphBox(.66f,.34f,.78f,.42f))
        ),true,true))
    }
    @Test fun nicknameOutsideFinishRegionIsNotATerminalBadge() {
        assertEquals(TableScene.TABLE,tencentScene(listOf(hint("末游",.90f,.52f)),true,true))
    }
    @Test fun selfLastPlaceBadgeAlsoEndsTheRound() {
        assertEquals(TableScene.RESULT,tencentScene(listOf(hint("末游",.1f,.80f)),true,true))
    }
    @Test fun smallNicknameInsideUpperBandIsNotABanner() {
        assertEquals(TableScene.TABLE,tencentScene(listOf(SceneHint("末游",GlyphBox(.53f,.14f,.57f,.16f))),true,true))
    }
    @Test fun finishingSomeoneElseIsNotSelfFinished() {
        assertFalse(tencentSelfFinished(listOf(hint("头游",.24f,.34f),hint("二游",.5f,.22f))))
        assertTrue(tencentSelfFinished(listOf(hint("三游",.5f,.70f))))
    }
    private val actualHead=SceneHint("头游",GlyphBox(.1625f,.30045f,.28229f,.40930f))
    private val actualSecond=SceneHint("二游",GlyphBox(.47031f,.18934f,.52917f,.25170f))
    private val actualThird=SceneHint("三游",GlyphBox(.42292f,.67574f,.53073f,.74490f))
    private val actualThirdBadge=SceneHint("三游",GlyphBox(.07656f,.78571f,.12031f,.8220f))
    private val actualHeadBadge=SceneHint("头游",GlyphBox(.08333f,.21655f,.11615f,.25283f))

    @Test fun actualThreeFinishSlotsEndTheRevealWhenLastPlaceOcrIsMissing() {
        // Actual OCR on the first real game's final image: the blue last-place label
        // was missed completely, but these three positioned finishers were recognized.
        assertEquals(TableScene.RESULT,tencentScene(listOf(actualHead,actualSecond,actualThird,actualThirdBadge,actualHeadBadge),true,false))
    }
    @Test fun anOwnFinishBadgeCanSupplyTheThirdProofWithoutItsLargeBanner() {
        assertEquals(TableScene.RESULT,tencentScene(listOf(actualHeadBadge,actualSecond,actualThirdBadge),true,false))
    }
    @Test fun aBannerAndBadgeAreNotTwoDifferentCompletedPlayers() {
        assertEquals(TableScene.TABLE,tencentScene(listOf(actualHead,actualHeadBadge,actualSecond),true,false))
    }
    @Test fun threeRankWordsOnTheSamePlayerDoNotProveThreeFinishers() {
        assertEquals(TableScene.TABLE,tencentScene(listOf(actualHead,actualHead.copy(text="二游"),actualHead.copy(text="三游")),true,false))
    }
    @Test fun aRankAssignedToTwoPlayersIsAmbiguousRatherThanATerminalProof() {
        assertEquals(TableScene.TABLE,tencentScene(listOf(actualHead,actualSecond,actualThirdBadge,actualThird.copy(text="头游")),true,false))
    }
    @Test fun aThirdPlaceNicknameOrUnpositionedWordCannotSupplyTheMissingFinisher() {
        for(third in listOf(hint("三游",.90f,.52f),hint("三游",.1f,.90f),SceneHint("三游",null))) {
            assertEquals(TableScene.TABLE,tencentScene(listOf(actualHead,actualSecond,third),true,false))
        }
    }
    @Test fun aCombinedTextLineIsNotThreePositionedFinishers() {
        assertEquals(TableScene.TABLE,tencentScene(listOf(actualHead.copy(text="头游 二游 三游")),true,false))
    }
    @Test fun firstPlaceTeammateViewIsStillAnOngoingGame() {
        assertEquals(TableScene.TEAMMATE,tencentScene(listOf(actualThird.copy(text="头游"),actualThirdBadge.copy(text="头游"),actualSecond,hint("队友手牌",.5f,.78f)),true,true))
    }
    @Test fun otherThreePlayersCanFinishWhileSelfIsLast() {
        assertEquals(TableScene.RESULT,tencentScene(listOf(actualHead,actualSecond,hint("三游",.75f,.34f)),true,true))
    }
    @Test fun rankWordsOutsideTheUiSlotsDoNotBecomeTerminalProofs() {
        assertEquals(TableScene.TABLE,tencentScene(listOf(actualHead,actualSecond,SceneHint("三游",GlyphBox(.48f,.13f,.52f,.15f))),true,false))
    }
}
