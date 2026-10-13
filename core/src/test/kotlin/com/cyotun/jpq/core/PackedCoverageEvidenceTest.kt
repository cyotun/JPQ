package com.cyotun.jpq.core

import kotlin.test.*

class PackedCoverageEvidenceTest {
    private val width=378
    private val height=174
    private fun table()=IntArray(width*height) { 0xff148e81.toInt() }
    private fun paper(pixels: IntArray,box: GlyphBox,color: Int=0xffd2d2d2.toInt()) {
        for(y in (box.top*height).toInt() until (box.bottom*height).toInt())
            for(x in (box.left*width).toInt() until (box.right*width).toInt()) pixels[y*width+x]=color
    }
    @Test fun missedGrayJokerPaperRequiresFullFrame() {
        val pixels=table()
        // Right joker position independently read at video PTS528.062. The glyph is
        // black, while its gray surrounding card paper remains positive coverage evidence.
        paper(pixels,GlyphBox(.79f,.275f,.849f,.426f))
        paper(pixels,GlyphBox(.798f,.279f,.81f,.356f),0xff303030.toInt())
        val hand=GlyphBox(.25f,.35f,.75f,.9f)
        val result=PackedCoverageEvidence.evaluate(pixels,width,height,listOf(hand))
        assertTrue(result.uncoveredSurface)
        assertTrue(result.box!!.cx>.78f)
    }
    @Test fun completeTileCoverageDoesNotRequestFull() {
        val pixels=table(); val card=GlyphBox(.79f,.275f,.849f,.426f)
        paper(pixels,card)
        assertFalse(PackedCoverageEvidence.evaluate(pixels,width,height,listOf(GlyphBox(.78f,.26f,.86f,.44f))).uncoveredSurface)
    }
    @Test fun partialTileCannotHideAnUnobservedCardTail() {
        val pixels=table(); paper(pixels,GlyphBox(.79f,.275f,.849f,.426f))
        assertTrue(PackedCoverageEvidence.evaluate(pixels,width,height,listOf(GlyphBox(.78f,.26f,.86f,.32f))).uncoveredSurface)
    }
    @Test fun tableAndAvatarColumnsAreNotCardCoverageEvidence() {
        val pixels=table()
        paper(pixels,GlyphBox(.06f,.25f,.13f,.5f)); paper(pixels,GlyphBox(.87f,.25f,.95f,.5f))
        assertFalse(PackedCoverageEvidence.evaluate(pixels,width,height,emptyList()).uncoveredSurface)
    }
}
