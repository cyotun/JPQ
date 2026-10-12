package com.cyotun.jpq.core

import kotlin.test.*

class SurfacePaletteTest {
    private val whole=GlyphBox(0f,0f,1f,1f)
    private fun color(r: Int,g: Int,b: Int)=(255 shl 24) or (r shl 16) or (g shl 8) or b
    private val felt=color(1,130,119)
    private val white=color(255,255,255)
    private val gold=color(255,224,138)
    private fun filled(value: Int,width: Int=128,height: Int=128)=IntArray(width*height) { value }
    private fun palette()=assertNotNull(SurfacePalette.learn(filled(felt),128,128))

    @Test fun aDominantTexturedSurfaceCalibratesAcrossChromaBins() {
        val pixels=IntArray(128*128) { i -> if(i%5==0) white else if(i%2==0) color(1,125,125) else felt }
        val palette=assertNotNull(SurfacePalette.learn(pixels,128,128))
        assertTrue(palette.coverage>.79f); assertTrue(palette.samples>=256)
        assertEquals(1f,palette.red+palette.green+palette.blue,.0001f)
        assertTrue(SurfacePalette.isCleanEmpty(palette.tableLikeRatio(pixels,128,128,whole)))
    }
    @Test fun brightnessChangesPreserveSurfaceEvidence() {
        val palette=palette()
        for(color in listOf(color(1,65,60),color(2,195,179),felt)) {
            assertEquals(1f,palette.tableLikeRatio(filled(color),128,128,whole))
        }
    }
    @Test fun whiteCardsAreOccupiedEvenThoughTheyAreBright() {
        val palette=palette()
        assertEquals(0f,palette.tableLikeRatio(filled(white),128,128,whole))
        assertFalse(SurfacePalette.isCleanEmpty(palette.tableLikeRatio(filled(white),128,128,whole)))
        assertNull(SurfacePalette.learn(filled(white),128,128))
    }
    @Test fun goldBombAnimationIsOccludedRatherThanEmpty() {
        val palette=palette()
        val glow=IntArray(128*128) { i -> if(i%4==0) white else gold }
        assertEquals(0f,palette.tableLikeRatio(glow,128,128,whole))
        assertFalse(SurfacePalette.isCleanEmpty(palette.tableLikeRatio(glow,128,128,whole)))
    }
    @Test fun darkPixelsDoNotDisappearFromTheMeasurementDenominator() {
        val palette=palette()
        val pixels=IntArray(128*128) { i -> if(i%2==0) felt else color(0,0,0) }
        assertEquals(.5f,assertNotNull(palette.tableLikeRatio(pixels,128,128,whole)),.0001f)
        assertFalse(SurfacePalette.isCleanEmpty(palette.tableLikeRatio(pixels,128,128,whole)))
    }
    @Test fun mostlyDarkFramesCannotCalibrateFromAFewSurfacePixels() {
        val pixels=IntArray(128*128) { i -> if(i%8==0) felt else color(1,2,3) }
        assertNull(SurfacePalette.learn(pixels,128,128))
    }
    @Test fun mixedUnrelatedColorsHaveNoSupportedDominantSurface() {
        val colors=listOf(color(255,0,0),color(0,255,0),color(0,0,255),white)
        assertNull(SurfacePalette.learn(IntArray(128*128) { colors[it%4] },128,128))
    }
    @Test fun insufficientCalibrationAndMissingMeasurementsRemainUnknown() {
        assertNull(SurfacePalette.learn(filled(felt,8,8),8,8))
        val palette=palette()
        assertNull(palette.tableLikeRatio(filled(felt),128,128,GlyphBox(.1f,.1f,.11f,.11f)))
        assertFalse(SurfacePalette.isCleanEmpty(null)); assertFalse(SurfacePalette.isCleanEmpty(Float.NaN))
    }
    @Test fun invalidOrOffscreenRegionsAreNotEmptyEvidence() {
        val palette=palette()
        assertNull(palette.tableLikeRatio(IntArray(10),128,128,whole))
        assertNull(palette.tableLikeRatio(filled(felt),128,128,GlyphBox(2f,2f,3f,3f)))
        assertNull(palette.tableLikeRatio(filled(felt),128,128,GlyphBox(Float.NaN,0f,1f,1f)))
    }
    @Test fun measurementUsesTheRequestedCardFootprint() {
        val palette=palette()
        val pixels=IntArray(128*128) { i -> if(i%128<64) felt else white }
        assertEquals(1f,palette.tableLikeRatio(pixels,128,128,GlyphBox(0f,0f,.5f,1f)))
        assertEquals(0f,palette.tableLikeRatio(pixels,128,128,GlyphBox(.5f,0f,1f,1f)))
    }
    @Test fun theEmptyThresholdIncludesItsBoundary() {
        assertTrue(SurfacePalette.isCleanEmpty(.70f)); assertTrue(SurfacePalette.isCleanEmpty(.80f))
        assertFalse(SurfacePalette.isCleanEmpty(.699f)); assertFalse(SurfacePalette.isCleanEmpty(Float.POSITIVE_INFINITY))
    }
}
