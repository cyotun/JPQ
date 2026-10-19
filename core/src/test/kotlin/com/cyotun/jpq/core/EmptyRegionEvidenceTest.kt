package com.cyotun.jpq.core

import kotlin.test.*

class EmptyRegionEvidenceTest {
    private val width=256
    private val height=128
    private val felt=IntArray(width*height) { (255 shl 24) or (1 shl 16) or (130 shl 8) or 119 }
    private val empty=Seat.entries.associateWith { emptyList<PlayGlyph>() }
    private val glyph=PlayGlyph(Card(Rank.A,Suit.RED),.99f,GlyphBox(.20f,.30f,.24f,.35f))
    private fun group(seat: Seat,vararg glyphs: PlayGlyph)=empty+mapOf(seat to glyphs.toList())

    @Test fun defaultTopBarLeavesAllSeatsObservedAndSurfaceCalibrationAvailable() {
        val evidence=EmptyRegionEvidence()
        // On the verified 2608x1200 / density=3 phone, a 46dp banner ends at .115.
        val topBar=listOf(GlyphBox(0f,0f,.82f,.115f))
        val opening=evidence.evaluate(felt,width,height,TableScene.DEALING,27,empty,null,topBar)
        assertTrue(opening.values.all { it.observed })
        evidence.evaluate(felt,width,height,TableScene.TABLE,27,group(Seat.PREVIOUS,glyph),"one",topBar)
        val cleared=evidence.evaluate(felt,width,height,TableScene.TABLE,27,empty,"one",topBar).getValue(Seat.PREVIOUS)
        assertTrue(cleared.observed); assertEquals("table_surface_visible",cleared.reason)
    }

    @Test fun maskedSeatWithNoPreviousGlyphIsUnknownRatherThanAnEmptyRegion() {
        val evidence=EmptyRegionEvidence()
        val result=evidence.evaluate(felt,width,height,TableScene.DEALING,27,empty,null,
            listOf(GlyphBox(.14f,.30f,.19f,.36f)))
        assertFalse(result.getValue(Seat.PREVIOUS).observed)
        assertEquals("overlay_occlusion",result.getValue(Seat.PREVIOUS).reason)
        assertTrue(result.getValue(Seat.OPPOSITE).observed)
    }

    @Test fun observedPhoneInsetStillLeavesTheDefaultBannerAboveTheCardArea() {
        val evidence=EmptyRegionEvidence()
        // Actual default mask on 2608x1200: [0,12,1254,150], not a guessed top edge.
        val result=evidence.evaluate(felt,width,height,TableScene.DEALING,27,empty,null,
            listOf(GlyphBox(0f,.01f,.4808f,.125f)))
        assertTrue(result.values.all { it.observed })
    }

    @Test fun anIndividualGlyphIntersectingTheBannerIsStillNotFullyObserved() {
        val evidence=EmptyRegionEvidence()
        val high=PlayGlyph(Card(Rank.A,Suit.RED),.99f,GlyphBox(.40f,.12f,.44f,.17f))
        val result=evidence.evaluate(felt,width,height,TableScene.TABLE,27,group(Seat.OPPOSITE,high),"one",
            listOf(GlyphBox(0f,.01f,.4808f,.125f))).getValue(Seat.OPPOSITE)
        assertFalse(result.observed); assertEquals("overlay_occlusion",result.reason)
    }

    @Test fun samplingMarginAboveAPrintedCardIsNotAnOcclusion() {
        val evidence=EmptyRegionEvidence()
        // Recorded card/bar geometry: a real 2px gap at 1200px height.
        // The old .005 sampling expansion falsely suspended the entire round.
        val high=PlayGlyph(Card(Rank.A,Suit.RED),.99f,GlyphBox(.4955f,.1150f,.5124f,.1608f))
        val result=evidence.evaluate(felt,width,height,TableScene.TABLE,27,group(Seat.OPPOSITE,high),"one",
            listOf(GlyphBox(.1794f,0f,.5472f,.1133f))).getValue(Seat.OPPOSITE)
        assertTrue(result.observed); assertEquals("detected_glyphs",result.reason)
        assertFalse(evidence.directlyObserved(TableScene.TABLE,Seat.OPPOSITE,listOf(high),
            listOf(GlyphBox(.1794f,0f,.5472f,.116f))))
    }

    @Test fun maskBeyondTheDetectedGlyphStillMakesAPartialGroupUnobserved() {
        val evidence=EmptyRegionEvidence()
        val tailMask=listOf(GlyphBox(.30f,.30f,.35f,.36f))
        val partial=evidence.evaluate(felt,width,height,TableScene.TABLE,27,group(Seat.PREVIOUS,glyph),"one",tailMask)
        assertFalse(partial.getValue(Seat.PREVIOUS).observed)
        assertEquals("overlay_occlusion",partial.getValue(Seat.PREVIOUS).reason)
        // An occluded partial group cannot establish the footprint used to clear later plays.
        val after=evidence.evaluate(felt,width,height,TableScene.TABLE,26,empty,"one").getValue(Seat.PREVIOUS)
        assertEquals("no_prior_presentation",after.reason)
    }

    @Test fun blockedCalibrationWaitsForAClearCompleteOpening() {
        val evidence=EmptyRegionEvidence()
        val centerMask=listOf(GlyphBox(.40f,.27f,.50f,.37f))
        evidence.evaluate(felt,width,height,TableScene.DEALING,27,empty,null,centerMask)
        evidence.evaluate(felt,width,height,TableScene.TABLE,26,group(Seat.PREVIOUS,glyph),"one")
        val unknown=evidence.evaluate(felt,width,height,TableScene.TABLE,26,empty,"one").getValue(Seat.PREVIOUS)
        assertFalse(unknown.observed); assertEquals("surface_not_calibrated",unknown.reason)
        val recovered=evidence.evaluate(felt,width,height,TableScene.DEALING,27,empty,"one").getValue(Seat.PREVIOUS)
        assertTrue(recovered.observed); assertEquals("table_surface_visible",recovered.reason)
    }

    @Test fun aMaskInThePossibleLongGroupAreaCannotClearAnExistingPresentation() {
        val evidence=EmptyRegionEvidence()
        evidence.evaluate(felt,width,height,TableScene.DEALING,27,empty,null)
        evidence.evaluate(felt,width,height,TableScene.TABLE,27,group(Seat.PREVIOUS,glyph),"one")
        val hidden=evidence.evaluate(felt,width,height,TableScene.TABLE,27,empty,"one",
            listOf(GlyphBox(.82f,.30f,.90f,.36f))).getValue(Seat.PREVIOUS)
        assertFalse(hidden.observed); assertEquals("overlay_occlusion",hidden.reason)
    }

    @Test fun teammatePerspectiveTreatsCentralMasksAsPossibleOtherPlayerPlays() {
        val evidence=EmptyRegionEvidence()
        val result=evidence.evaluate(felt,width,height,TableScene.TEAMMATE,0,empty,"one",
            listOf(GlyphBox(.40f,.42f,.50f,.49f)))
        assertTrue(listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).all { !result.getValue(it).observed })
    }
}
