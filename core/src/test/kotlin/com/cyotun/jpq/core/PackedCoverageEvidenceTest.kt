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
    private fun pattern(pixels: IntArray,left: Int,top: Int,rows: List<String>) {
        rows.forEachIndexed { y,row -> row.forEachIndexed { x,value ->
            if(value=='#') pixels[(top+y)*width+left+x]=0xffd2d2d2.toInt()
        } }
    }
    private fun pixelBox(left: Int,top: Int,right: Int,bottom: Int)=GlyphBox(
        left.toFloat()/width,top.toFloat()/height,right.toFloat()/width,bottom.toFloat()/height)
    private fun outline(width: Int,height: Int)=List(height) { y ->
        if(y==0 || y==height-1) "#".repeat(width) else "#"+".".repeat(width-2)+"#"
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
    @Test fun sampledThinButtonTextDoesNotRequestFull() {
        val pixels=table()
        // 46 neutral points from the real "提示" button at PTS513.010, sampled from
        // its JPEG95 source to 378x173 using NEAREST. Maximum solid square is only 2x2.
        val strokes=listOf(".#.#####.",".#.#...#.",".#.#...#.",".#.#####.","#########",
            "##...#...",".#.#.####",".#.#.#...","###..####")
        assertEquals(46,strokes.sumOf { row -> row.count { it=='#' } })
        pattern(pixels,191,45,strokes)
        assertFalse(PackedCoverageEvidence.evaluate(pixels,width,height,emptyList()).uncoveredSurface)
    }
    @Test fun coveredSolidCoreStillSupportsTheSixteenPointOutsideTail() {
        val pixels=table(); val x=150; val y=35
        // All 3x3 support is covered. A one-pixel connector leads to a hollow 4x6
        // outside tail: exactly 16 points, with no outside solid 3x3 square.
        pattern(pixels,x,y,listOf("#####","#####","#####","#....","#....",
            "####.","#..#.","#..#.","#..#.","#..#.","####."))
        val result=PackedCoverageEvidence.evaluate(pixels,width,height,listOf(pixelBox(x,y,x+5,y+5)))
        assertTrue(result.uncoveredSurface)
        assertEquals(16,result.sampleCount)
        assertEquals(pixelBox(x,y+5,x+4,y+11),result.box)
    }
    @Test fun completelyCoveredCoreAndTailDoNotRequestFull() {
        val pixels=table(); val x=150; val y=35
        pattern(pixels,x,y,listOf("#####","#####","#####","#....","#....",
            "####.","#..#.","#..#.","#..#.","#..#.","####."))
        assertFalse(PackedCoverageEvidence.evaluate(pixels,width,height,
            listOf(pixelBox(x,y,x+5,y+11))).uncoveredSurface)
    }
    @Test fun largeTwelveBySixteenThinContourRemainsPaperEvidence() {
        val pixels=table(); val x=180; val y=40
        // A one-pixel outline has no 3x3 patch and still may be a narrow card edge.
        pattern(pixels,x,y,outline(12,16))
        val result=PackedCoverageEvidence.evaluate(pixels,width,height,emptyList())
        assertTrue(result.uncoveredSurface)
        assertEquals(52,result.sampleCount)
        assertEquals(pixelBox(x,y,x+12,y+16),result.box)
    }
    @Test fun smallerThinOutlineDoesNotMeetTheLargeContourBoundary() {
        for((w,h) in listOf(11 to 16,12 to 15)) {
            val pixels=table(); pattern(pixels,180,40,outline(w,h))
            assertFalse(PackedCoverageEvidence.evaluate(pixels,width,height,emptyList()).uncoveredSurface,
                "outline=${w}x$h")
        }
    }
    @Test fun disconnectedEightPointRemaindersAreNotAddedTogether() {
        val pixels=table(); val x=150; val y=35
        // Original paper has a core, but covering the middle leaves two 4x2 pieces.
        // Neither meets the original 16-point/4x6 remainder test.
        pattern(pixels,x,y,List(12) { "####" })
        val result=PackedCoverageEvidence.evaluate(pixels,width,height,listOf(pixelBox(x,y+2,x+4,y+10)))
        assertFalse(result.uncoveredSurface)
    }
}
