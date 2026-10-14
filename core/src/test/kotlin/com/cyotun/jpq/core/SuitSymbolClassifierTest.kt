package com.cyotun.jpq.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuitSymbolClassifierTest {
    private val heart = listOf(
        "..###....###..", ".#####..#####.", "##############", "##############",
        "##############", ".############.", "..##########..", "...########...",
        "....######....", ".....####.....", "......##......",
    )
    private val diamond = listOf(
        "......##......", ".....####.....", "....######....", "...########...",
        "..##########..", ".############.", "##############", ".############.",
        "..##########..", "...########...", "....######....", ".....####.....", "......##......",
    )
    private fun raster(rows: List<String>, ink: Int, paper: Int = 0xffececec.toInt(), scale: Int = 3,
                       padding: Int = 6, color: Suit = Suit.RED): SuitSymbolPixels {
        val w = rows.first().length * scale + padding * 2; val h = rows.size * scale + padding * 2
        val pixels = IntArray(w * h) { paper }
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, char -> if (char == '#') {
            for (dy in 0 until scale) for (dx in 0 until scale)
                pixels[(padding + y * scale + dy) * w + padding + x * scale + dx] = ink
        } } }
        return SuitSymbolPixels(w, h, pixels, color)
    }

    @Test fun heartAndDiamondUseShapeInsteadOfRedColorAlone() {
        val h = SuitSymbolClassifier.classify(raster(heart, 0xffb31313.toInt()))
        val d = SuitSymbolClassifier.classify(raster(diamond, 0xffb31313.toInt()))
        assertEquals(Suit.HEART, h.suit); assertEquals(Suit.DIAMOND, d.suit)
        assertTrue(h.margin >= .10f && d.margin >= .10f)
    }

    @Test fun YellowLevelHeartRemainsHeartWithIndependentFlag() {
        val result = SuitSymbolClassifier.classify(raster(heart, 0xfff1ce59.toInt()))
        assertEquals(Suit.HEART, result.suit); assertTrue(result.goldHeart)
    }

    @Test fun ClippedMarkCannotDeclareAPhysicalSuit() {
        assertNull(SuitSymbolClassifier.classify(raster(heart, 0xffb31313.toInt(), padding = 0)).suit)
    }

    @Test fun DarkOverlayAndTealTableAreNotCardPaper() {
        assertNull(SuitSymbolClassifier.classify(raster(heart, 0xffb31313.toInt(), 0xff242424.toInt())).suit)
        assertNull(SuitSymbolClassifier.classify(raster(diamond, 0xffb31313.toInt(), 0xff008c82.toInt())).suit)
    }

    @Test fun UnobservedOrTransparentPixelsRemainUnknown() {
        assertNull(SuitSymbolClassifier.classify(SuitSymbolPixels(0, 0, IntArray(0), Suit.RED, false, "masked")).suit)
        val transparent = raster(heart, 0x00b31313)
        assertNull(SuitSymbolClassifier.classify(transparent).suit)
    }

    @Test fun BlackRankCannotBecomeARedSuit() {
        assertNull(SuitSymbolClassifier.classify(raster(heart, 0xffb31313.toInt(), color = Suit.BLACK)).suit)
    }

    @Test fun TwoSeparateMarksAreRejectedInsteadOfChoosingOne() {
        val a = raster(heart, 0xffb31313.toInt(), scale = 2)
        val gap = 6; val width = a.width * 2 + gap
        val raw = IntArray(width * a.height) { 0xffececec.toInt() }
        for (y in 0 until a.height) for (x in 0 until a.width) {
            raw[y * width + x] = a.argb[y * a.width + x]
            raw[y * width + a.width + gap + x] = a.argb[y * a.width + x]
        }
        assertNull(SuitSymbolClassifier.classify(SuitSymbolPixels(width, a.height, raw, Suit.RED)).suit)
    }
}
