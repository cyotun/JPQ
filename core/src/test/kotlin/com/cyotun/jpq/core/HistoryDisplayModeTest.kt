package com.cyotun.jpq.core

import kotlin.test.*

class HistoryDisplayModeTest {
    @Test fun countOnlyContainsOnlyTheSeatAndYellowCountCells() {
        val size=HistoryWidth().measure(DisplayConfig(showHistoryDetails=false,showStraightFlush=false),500f,1000f)
        assertEquals(32f,size.history)
        assertEquals(235.577047f,size.grid,.001f)
        assertEquals(size.grid+35f,size.content)
        assertEquals(size.content+96f,size.total)
    }

    @Test fun countModeDiscardsExpandedDetailedTextAndDoesNotKeepItsGutter() {
        val widths=HistoryWidth(); val config=DisplayConfig(showStraightFlush=false)
        val expanded=widths.measure(config,466.806f,1000f)
        val counts=widths.measure(config.copy(showHistoryDetails=false),466.806f,1000f)
        assertTrue(counts.total<expanded.total)
        assertEquals(counts,widths.measure(config.copy(showHistoryDetails=false),1200f,1000f))
        val restored=widths.measure(config,10f,1000f)
        assertTrue(restored.history<expanded.history)
    }

    @Test fun countOnlyWidthUsesTheCompactWidthSliderAndHonorsBothControls() {
        val config=DisplayConfig(showHistoryDetails=false,countWidthScale=1.25f,widthScale=.8f,
            doubleTapHide=true,showReset=false,showStraightFlush=false)
        val counts=HistoryWidth().measure(config,1000f,1000f)
        assertEquals(235.577047f*1.25f,counts.grid,.001f)
        assertEquals(counts.content,counts.total)
        assertEquals(counts,HistoryWidth().measure(config.copy(widthScale=1.35f),0f,1000f))
        val hidden=HistoryWidth().measure(config.copy(showHistory=false),1000f,1000f)
        assertEquals(0f,hidden.history)
        assertEquals(hidden.grid+2f,hidden.content)
        assertEquals(hidden.grid,counts.grid)
    }

    @Test fun theDefaultStillShowsDetailsAndLegacyHiddenModeStillHidesEverything() {
        assertTrue(DisplayConfig().showHistoryDetails)
        assertTrue(DisplayConfig().showStraightFlush)
        val config=DisplayConfig(showHistory=false)
        assertEquals(HistoryWidth().measure(config,0f,1000f),
            HistoryWidth().measure(config.copy(showHistoryDetails=false),1200f,1000f))
    }

    @Test fun straightFlushSlotAddsSixteenDpInEveryHistoryModeWithoutChangingCardOrCountWidths() {
        listOf(false to false,true to false,true to true).forEach { (history,details) ->
            val off=DisplayConfig(showHistory=history,showHistoryDetails=details,showStraightFlush=false)
            val original=HistoryWidth().measure(off,120f,1000f)
            val indicator=HistoryWidth().measure(off.copy(showStraightFlush=true),120f,1000f)
            assertEquals(original.grid,indicator.grid)
            assertEquals(original.history,indicator.history)
            assertEquals(original.content+16f,indicator.content,.001f)
            assertEquals(original.total+16f,indicator.total,.001f)
        }
    }

    @Test fun narrowScreenReservesCompleteCountsAndSpadeSlotBeforeShrinkingTheCardGrid() {
        listOf(false,true).forEach { details ->
            val config=DisplayConfig(showHistoryDetails=details,showStraightFlush=true,countWidthScale=1.35f,widthScale=1.35f)
            val result=HistoryWidth().measure(config,500f,280f)
            assertEquals(32f,result.history)
            assertEquals(280f,result.total,.001f)
            assertTrue(result.grid>0f)
            assertEquals(result.grid+16f+32f+3f,result.content,.001f)
        }
    }
}
