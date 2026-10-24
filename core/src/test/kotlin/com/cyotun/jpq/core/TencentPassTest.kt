package com.cyotun.jpq.core

import kotlin.test.*

class TencentPassTest {
    private fun hint(x: Float,y: Float,text: String="不出")=SceneHint(text,GlyphBox(x-.035f,y-.03f,x+.035f,y+.03f))
    @Test fun bubblesHaveSeatIdentityButTheCentralActionButtonDoesNot() {
        assertEquals(setOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT),tencentPassSeats(listOf(
            hint(.18f,.35f),hint(.50f,.18f),hint(.81f,.35f,"不要"))))
        assertEquals(setOf(Seat.OPPOSITE),tencentPassSeats(listOf(hint(.50f,.37f))))
        assertEquals(setOf(Seat.OPPOSITE),tencentPassSeats(listOf(hint(.50f,.18f,"不础"))))
        assertTrue(tencentPassSeats(listOf(hint(.34f,.28f),hint(.18f,.50f),hint(.50f,.85f))).isEmpty())
    }
    @Test fun arbitraryWordsUnpositionedAndMalformedBoxesCannotCloseAnAction() {
        assertTrue(tencentPassSeats(listOf(hint(.50f,.18f,"不要急"),SceneHint("不出",null),
            SceneHint("不出",GlyphBox(Float.NaN,.1f,.5f,.2f)),
            SceneHint("不出",GlyphBox(.45f,.1f,.55f,.105f)))).isEmpty())
    }
}
