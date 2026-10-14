package com.cyotun.jpq.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TencentGradeMetadataTest {
    private val aspect = 2608f / 1200f
    private fun caption(text: String, x: Float = .5f, y: Float = .44f) =
        SceneHint(text, GlyphBox(x - .12f, y - .018f, x + .12f, y + .018f))
    private fun hand(rank: Rank = Rank.J, x: Float = .45f, y: Float = .52f,
        height: Float = 70f / 1200f, ratio: Float = aspect, widthInHeights: Float = .5f,
        suit: Suit = Suit.CLUB, confidence: Float = .95f) =
        PlayGlyph(Card(rank, if (rank.isJoker) Suit.NONE else suit), confidence,
            GlyphBox(x, y, x + height * widthInHeights / ratio, y + height))
    private fun marker(glyph: PlayGlyph, ratio: Float = aspect, dx: Float = 1.9f, dy: Float = .25f,
        width: Float = .45f, height: Float = .45f, text: String = "级"): SceneHint {
        val h = glyph.box.height
        val cx = glyph.box.left + dx * h / ratio; val cy = glyph.box.top + dy * h
        return SceneHint(text, GlyphBox(cx - width * h / ratio / 2, cy - height * h / 2,
            cx + width * h / ratio / 2, cy + height * h / 2))
    }

    @Test fun explicitSingleGradeCaptionOrExactClassicModeLabelProvesSingleMode() {
        for (text in listOf("单级牌初级场", "单级", "经典玩法", "经 典 玩 法"))
            assertEquals(WildcardMode.SINGLE, tencentWildcardMode(listOf(caption(text))))
    }

    @Test fun genericRoomNamesAndTierLabelsDoNotEstablishTheRuleVariant() {
        for (text in listOf("经典初级场 底分1000", "传统中级场", "不洗牌高级场", "炸弹翻倍初级场", "经典玩法初级场"))
            assertEquals(WildcardMode.UNKNOWN, tencentWildcardMode(listOf(caption(text))))
    }

    @Test fun explicitDualGradeOverridesGenericClassicCaption() {
        assertEquals(WildcardMode.DOUBLE, tencentWildcardMode(listOf(caption("经典双级牌场"))))
        assertEquals(WildcardMode.DOUBLE, tencentWildcardMode(listOf(caption("双 级 牌 初 级 场"))))
    }

    @Test fun privateAndCustomCaptionsDoNotProveEitherPreset() {
        for (text in listOf("私人房经典初级场", "双级牌自定义场", "私人双级房"))
            assertEquals(WildcardMode.UNKNOWN, tencentWildcardMode(listOf(caption(text))))
        assertEquals(WildcardMode.UNKNOWN,
            tencentWildcardMode(listOf(caption("双级牌初级场"), caption("自定义房间"))))
    }

    @Test fun missingUnpositionedAndOffCaptionWordsDoNotEstablishMode() {
        for (hints in listOf(emptyList(), listOf(SceneHint("经典初级场", null)),
            listOf(caption("经典")), listOf(caption("初级场")), listOf(caption("未知玩法场")),
            listOf(caption("双级牌初级场", .5f, .08f)), listOf(caption("经典初级场", .12f, .44f))))
            assertEquals(WildcardMode.UNKNOWN, tencentWildcardMode(hints))
    }

    @Test fun invalidCaptionBoxesCannotEstablishMode() {
        for (box in listOf(GlyphBox(.5f, .42f, .4f, .46f), GlyphBox(.4f, .46f, .6f, .42f),
            GlyphBox(-.1f, .42f, 1.1f, .46f), GlyphBox(Float.NaN, .42f, .6f, .46f),
            GlyphBox(.4f, .42f, Float.POSITIVE_INFINITY, .46f)))
            assertEquals(WildcardMode.UNKNOWN, tencentWildcardMode(listOf(SceneHint("双级牌场", box))))
    }

    @Test fun standaloneGradeCharacterMatchesOrdinaryNonHeartHandCards() {
        for (suit in listOf(Suit.SPADE, Suit.CLUB, Suit.DIAMOND, Suit.BLACK, Suit.RED)) {
            val glyph = hand(suit = suit)
            assertEquals(setOf(Rank.J), tencentGradeMarkerRanks(listOf(glyph), listOf(marker(glyph)), aspect))
        }
    }

    @Test fun geometryUsesRankHeightSoTenWidthAndDifferentScreenRatiosDoNotMoveTheCorner() {
        for (ratio in listOf(1.6f, aspect, 2.8f)) for (height in listOf(.028f, 70f / 1200f, .095f)) {
            val ten = hand(Rank.TEN, ratio = ratio, height = height, widthInHeights = 1.05f)
            assertEquals(setOf(Rank.TEN), tencentGradeMarkerRanks(listOf(ten), listOf(marker(ten, ratio)), ratio))
        }
    }

    @Test fun actualRoundRankCanBeAnyOrdinaryPointWithoutUpgradeOrHudInference() {
        for (rank in Rank.entries.filterNot { it.isJoker }) {
            val glyph = hand(rank)
            val hints = listOf(caption("我方3 对方3", .15f, .05f), marker(glyph))
            assertEquals(setOf(rank), tencentGradeMarkerRanks(listOf(glyph), hints, aspect))
        }
        val seven = hand(Rank.SEVEN, x = .25f); val king = hand(Rank.K, x = .62f)
        assertEquals(setOf(Rank.SEVEN, Rank.K),
            tencentGradeMarkerRanks(listOf(seven, king), listOf(marker(seven), marker(king)), aspect))
    }

    @Test fun markerMayAppearBesideTallHandCardsAboveTheMainBottomRow() {
        val glyph = hand(y = .30f)
        assertEquals(setOf(Rank.J), tencentGradeMarkerRanks(listOf(glyph), listOf(marker(glyph)), aspect))
    }

    @Test fun hudTextMergedGradeTextAndAnUnpositionedGradeDoNotDeclareRank() {
        val glyph = hand(); val hud = hand(Rank.THREE, y = .03f)
        assertTrue(tencentGradeMarkerRanks(listOf(hud), listOf(marker(hud)), aspect).isEmpty())
        for (text in listOf("J级", "级牌", "等级", "级级", "级 3", "我方级数"))
            assertTrue(tencentGradeMarkerRanks(listOf(glyph), listOf(marker(glyph, text = text)), aspect).isEmpty())
        assertTrue(tencentGradeMarkerRanks(listOf(glyph), listOf(SceneHint("级", null)), aspect).isEmpty())
        assertEquals(setOf(Rank.J),
            tencentGradeMarkerRanks(listOf(glyph), listOf(marker(glyph, text = " 级 ")), aspect))
    }

    @Test fun largeMergedTinyAndWrongCornerBoxesAreRejected() {
        val glyph = hand()
        for (hint in listOf(marker(glyph, width = 1.2f, height = 1.2f), marker(glyph, width = .08f, height = .08f),
            marker(glyph, width = .90f, height = .20f), marker(glyph, dx = .8f), marker(glyph, dx = 2.7f),
            marker(glyph, dy = -.4f), marker(glyph, dy = .9f)))
            assertTrue(tencentGradeMarkerRanks(listOf(glyph), listOf(hint), aspect).isEmpty())
    }

    @Test fun anAmbiguousMarkerCannotSelectANeighborOrUseEqualRanksToHideAmbiguity() {
        val first = hand(); val second = hand(Rank.TEN, x = first.box.left + first.box.height * .10f / aspect,
            y = first.box.top - first.box.height * .10f)
        assertTrue(tencentGradeMarkerRanks(listOf(first, second), listOf(marker(first)), aspect).isEmpty())
        assertTrue(tencentGradeMarkerRanks(listOf(first, first.copy()), listOf(marker(first)), aspect).isEmpty())
    }

    @Test fun invalidGlyphsInvalidMarkerBoxesAndInvalidAspectRemainUnknown() {
        val glyph = hand()
        for (ratio in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertTrue(tencentGradeMarkerRanks(listOf(glyph), listOf(marker(glyph)), ratio).isEmpty())
        for (bad in listOf(glyph.copy(confidence = Float.NaN), glyph.copy(confidence = .4f),
            glyph.copy(box = GlyphBox(.4f, .5f, .4f, .56f)), glyph.copy(box = GlyphBox(.4f, .6f, .42f, .5f)),
            glyph.copy(box = GlyphBox(Float.NaN, .5f, .42f, .56f)),
            glyph.copy(box = GlyphBox(.4f, .5f, 1.2f, .56f)), hand(widthInHeights = 3f),
            hand(Rank.BIG)))
            assertTrue(tencentGradeMarkerRanks(listOf(bad), listOf(marker(glyph)), aspect).isEmpty())
        for (box in listOf(GlyphBox(.48f, .52f, .46f, .54f), GlyphBox(-.1f, .52f, .51f, .54f),
            GlyphBox(.48f, Float.NaN, .51f, .54f)))
            assertTrue(tencentGradeMarkerRanks(listOf(glyph), listOf(SceneHint("级", box)), aspect).isEmpty())
    }
}
