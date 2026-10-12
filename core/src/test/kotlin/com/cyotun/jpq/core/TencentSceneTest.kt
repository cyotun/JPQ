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
}
