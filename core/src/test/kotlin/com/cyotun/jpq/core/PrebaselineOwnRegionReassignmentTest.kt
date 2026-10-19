package com.cyotun.jpq.core

import kotlin.test.*

class PrebaselineOwnRegionReassignmentTest {
    private val top=PlayGlyph(Card(Rank.A,Suit.BLACK),.86f,GlyphBox(.2903f,.4433f,.3133f,.5017f))
    private val hand=listOf(top,
        top.copy(box=GlyphBox(.2903f,.5108f,.3133f,.5683f)),
        top.copy(card=Card(Rank.A,Suit.RED),box=GlyphBox(.2903f,.5792f,.3133f,.635f)))+
        Rank.entries.filter { !it.isJoker && it!=Rank.A }.flatMap { rank ->
            listOf(Card(rank,Suit.RED),Card(rank,Suit.BLACK))
        }.mapIndexed { i,card -> PlayGlyph(card,.90f,
            GlyphBox(.40f+i%6*.07f,.60f+i/6*.07f,.42f+i%6*.07f,.655f+i/6*.07f)) }
    private val empty=Seat.entries.associateWith { emptyList<PlayGlyph>() }
    private fun seed(helper: PrebaselineOwnRegionReassignment,at: Long=100,round: String?=null,
        count: Int=14,source: PlayGlyph=top) {
        helper.step(TableScene.TABLE,count,hand.drop(1).take(count),empty+(Seat.PREVIOUS to listOf(source)),round,emptyList(),at)
    }
    private fun clear(helper: PrebaselineOwnRegionReassignment,at: Long,own: List<PlayGlyph> = hand,
        round: String?=null,masks: List<GlyphBox> = emptyList(),ambiguous: Boolean=false,
        scene: TableScene=TableScene.TABLE,time: Long?=at)=
        helper.step(scene,own.size,own,empty,round,masks,time,ambiguous)
    private fun settle(helper: PrebaselineOwnRegionReassignment,own: List<PlayGlyph> = hand,
        from: Long=300,round: String?=null): Set<Seat> {
        clear(helper,from,own,round); clear(helper,from+50,own,round)
        return clear(helper,from+110,own,round)
    }

    @Test fun aDealAnimationPrintReassignedToTheSameCompleteHandRetiresOnlyThatSeat() {
        assertEquals(27,hand.size)
        val helper=PrebaselineOwnRegionReassignment(); seed(helper)
        assertEquals(setOf(Seat.PREVIOUS),settle(helper))
        assertEquals(setOf(Seat.PREVIOUS),clear(helper,500,round="new-baseline"))
        assertEquals(emptySet(),helper.step(TableScene.TABLE,27,hand,
            empty+(Seat.PREVIOUS to listOf(top.copy(box=GlyphBox(.15f,.28f,.17f,.33f)))),
            "new-baseline",emptyList(),600))
        assertEquals(emptySet(),clear(helper,700,round="new-baseline"))
    }
    @Test fun missingMetadataAndAnAlreadyTrackedRoundCannotRetractAnyPresentation() {
        val missing=PrebaselineOwnRegionReassignment()
        missing.step(TableScene.TABLE,14,emptyList(),empty+(Seat.PREVIOUS to listOf(top)),null,emptyList(),100)
        assertEquals(emptySet(),settle(missing))
        val untimed=PrebaselineOwnRegionReassignment(); seed(untimed)
        for(t in listOf(300L,350L,410L)) assertEquals(emptySet(),clear(untimed,t,time=null))
        val tracked=PrebaselineOwnRegionReassignment(); seed(tracked,round="existing")
        assertEquals(emptySet(),settle(tracked,round="existing"))
        assertEquals(emptySet(),settle(tracked,from=600))
        val complete=PrebaselineOwnRegionReassignment(); seed(complete,count=27)
        assertEquals(emptySet(),settle(complete))
    }
    @Test fun theSameRankNearbyOrATableSizedPrintCannotBeBorrowedFromAnotherOwnA() {
        val shifted=PrebaselineOwnRegionReassignment(); seed(shifted)
        assertEquals(emptySet(),settle(shifted,hand.map { if(it==top) it.copy(box=it.box.copy(left=.32f,right=.343f)) else it }))
        val solitary=PrebaselineOwnRegionReassignment(); seed(solitary)
        val differentColumns=hand.mapIndexed { i,p -> if(i in 1..2) p.copy(box=p.box.copy(left=.20f,right=.223f)) else p }
        assertEquals(emptySet(),settle(solitary,differentColumns))
        val table=top.copy(box=GlyphBox(.2903f,.4533f,.3133f,.4983f))
        val small=PrebaselineOwnRegionReassignment(); seed(small,source=table)
        assertEquals(emptySet(),settle(small,listOf(table)+hand.drop(1)))
    }
    @Test fun sourceAndVotesExpireRatherThanUsingElapsedTimeAsOwnershipProof() {
        val old=PrebaselineOwnRegionReassignment(); seed(old)
        assertEquals(emptySet(),settle(old,from=2101))
        val gap=PrebaselineOwnRegionReassignment(); seed(gap)
        clear(gap,300); clear(gap,350)
        assertEquals(emptySet(),clear(gap,1000))
        assertEquals(emptySet(),clear(gap,1050))
        assertEquals(setOf(Seat.PREVIOUS),clear(gap,1110))
    }
    @Test fun ambiguousMaskedInvalidOrNonTableFramesCannotSupplyConfirmationVotes() {
        for(kind in listOf("ambiguous","mask","invalid","scene")) {
            val helper=PrebaselineOwnRegionReassignment(); seed(helper)
            clear(helper,300); clear(helper,350)
            clear(helper,410,ambiguous=kind=="ambiguous",
                masks=when(kind) {
                    "mask" -> listOf(GlyphBox(.28f,.44f,.32f,.51f))
                    "invalid" -> listOf(GlyphBox(Float.NaN,0f,.1f,.1f))
                    else -> emptyList()
                },scene=if(kind=="scene") TableScene.UNKNOWN else TableScene.TABLE)
            assertEquals(emptySet(),clear(helper,500))
            assertEquals(emptySet(),clear(helper,550))
            assertEquals(setOf(Seat.PREVIOUS),clear(helper,610))
        }
    }
    @Test fun lowConfidenceContradictoryOrIncompleteHandCannotProveOwnership() {
        for(own in listOf(hand.drop(1),hand.map { it.copy(confidence=.64f) },
            hand.map { if(it==top) it.copy(confidence=.79f) else it })) {
            val helper=PrebaselineOwnRegionReassignment(); seed(helper)
            assertEquals(emptySet(),settle(helper,own))
        }
        val weak=PrebaselineOwnRegionReassignment(); seed(weak,source=top.copy(confidence=.79f))
        assertEquals(emptySet(),settle(weak))
    }
    @Test fun explicitBoundaryRetiresNoSourceFromThePreviousTable() {
        val helper=PrebaselineOwnRegionReassignment(); seed(helper)
        assertEquals(setOf(Seat.PREVIOUS),settle(helper))
        assertEquals(emptySet(),helper.step(TableScene.RESULT,0,emptyList(),empty,null,emptyList(),500))
        assertEquals(emptySet(),settle(helper,from=600))
    }
    @Test fun aDirectNextDealWithoutAResultSceneCanCorrectItsOwnTemporaryPrint() {
        val helper=PrebaselineOwnRegionReassignment()
        clear(helper,100,round="previous-round")
        seed(helper,at=200)
        assertEquals(setOf(Seat.PREVIOUS),settle(helper,from=300))
        clear(helper,500,round="next-round")
        assertEquals(emptySet(),helper.step(TableScene.TABLE,27,hand,
            empty+(Seat.PREVIOUS to listOf(top)),"next-round",emptyList(),600))
        // A missing result scene never permits a new midround reassignment.
        seed(helper,at=700,round="next-round")
        assertEquals(emptySet(),settle(helper,from=800,round="next-round"))
    }
    @Test fun anotherUnownedDealGlyphDoesNotEraseAClearProvisionalSourceOrConfirmItsRetirement() {
        val helper=PrebaselineOwnRegionReassignment()
        // Actual source frame219: the A was assigned PREVIOUS while a separate
        // black TWO at x=.64 was ambiguous during the growing hand animation.
        assertEquals(emptySet(),helper.step(TableScene.TABLE,14,hand.drop(1).take(14),
            empty+(Seat.PREVIOUS to listOf(top)),null,emptyList(),100,ambiguous=true))
        for(t in listOf(200L,250L,310L)) assertEquals(emptySet(),clear(helper,t,ambiguous=true))
        assertEquals(setOf(Seat.PREVIOUS),settle(helper,from=400))
        val masked=PrebaselineOwnRegionReassignment()
        masked.step(TableScene.TABLE,14,hand.drop(1).take(14),empty+(Seat.PREVIOUS to listOf(top)),null,
            listOf(GlyphBox(.28f,.44f,.32f,.51f)),100,ambiguous=true)
        assertEquals(emptySet(),settle(masked,from=400))
    }
    @Test fun pixelEvidenceDeletesOnlyThePhysicallyReassignedTemporaryFootprint() {
        val evidence=EmptyRegionEvidence(); val pixels=IntArray(256*128) { 0xff008277.toInt() }
        for(y in 55..65) for(x in 72..82) pixels[y*256+x]=0xffffffff.toInt()
        evidence.evaluate(pixels,256,128,TableScene.TABLE,14,empty+(Seat.PREVIOUS to listOf(top)),null,
            handGlyphs=hand.drop(1).take(14),timeMs=100)
        for(t in listOf(300L,350L)) {
            val blocked=evidence.evaluate(pixels,256,128,TableScene.TABLE,27,empty,null,handGlyphs=hand,timeMs=t)
                .getValue(Seat.PREVIOUS)
            assertFalse(blocked.observed); assertEquals("foreground_or_animation",blocked.reason)
        }
        val corrected=evidence.evaluate(pixels,256,128,TableScene.TABLE,27,empty,null,handGlyphs=hand,timeMs=410)
        assertEquals("no_prior_presentation",corrected.getValue(Seat.PREVIOUS).reason)
        assertTrue(corrected.getValue(Seat.PREVIOUS).observed)
    }
    @Test fun replayAndLiveUseTheSameMaskPolicyIncludingEmptyPossibleAreasAndHighGlyphPrints() {
        val evidence=EmptyRegionEvidence()
        assertTrue(evidence.overlayOccluded(TableScene.TABLE,Seat.PREVIOUS,emptyList(),listOf(GlyphBox(.82f,.30f,.90f,.36f))))
        assertTrue(evidence.overlayOccluded(TableScene.TABLE,Seat.OPPOSITE,
            listOf(top.copy(box=GlyphBox(.4f,.12f,.44f,.17f))),listOf(GlyphBox(0f,.01f,.48f,.125f))))
        assertFalse(evidence.overlayOccluded(TableScene.TABLE,Seat.OPPOSITE,emptyList(),listOf(GlyphBox(0f,0f,.8f,.115f))))
        assertTrue(evidence.overlayOccluded(TableScene.TABLE,Seat.OPPOSITE,emptyList(),listOf(GlyphBox(Float.NaN,0f,.8f,.115f))))
    }
}
