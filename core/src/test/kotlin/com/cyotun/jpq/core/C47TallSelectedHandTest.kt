package com.cyotun.jpq.core

import kotlin.test.Test
import kotlin.test.assertEquals

class C47TallSelectedHandTest {
    private fun glyph(rank: Rank,suit: Suit,l: Float,t: Float,r: Float,b: Float)=
        PlayGlyph(Card(rank,suit),.90f,GlyphBox(l,t,r,b))

    @Test fun selectedTopAceRemainsInTheTallOwnHandTower() {
        // C4.7 R4 frame 6792 (elapsed 44216066): the three black aces are one
        // vertical column. The highest selected ace has a shorter detector box
        // than the two cards below it, but its width and rank still match that
        // same-rank hand stack. The on-screen hand retained all six aces.
        val hand=listOf(
            glyph(Rank.A,Suit.BLACK,.4399f,.3095f,.4623f,.3572f),
            glyph(Rank.A,Suit.BLACK,.4398f,.3807f,.4622f,.4399f),
            glyph(Rank.A,Suit.BLACK,.4399f,.4634f,.4624f,.5224f),
            glyph(Rank.A,Suit.RED,.4397f,.5458f,.4626f,.6053f),
            glyph(Rank.A,Suit.RED,.4397f,.6283f,.4627f,.6876f),
            glyph(Rank.A,Suit.RED,.4399f,.7109f,.4629f,.7703f),
            glyph(Rank.Q,Suit.RED,.5004f,.4599f,.5194f,.5253f),
            glyph(Rank.NINE,Suit.BLACK,.5013f,.5453f,.5185f,.6037f),
            glyph(Rank.NINE,Suit.BLACK,.5014f,.6279f,.5189f,.6862f),
            glyph(Rank.NINE,Suit.RED,.5015f,.7107f,.5193f,.7695f))
        val central=listOf(
            glyph(Rank.K,Suit.RED,.4272f,.3239f,.4435f,.3700f),
            glyph(Rank.K,Suit.RED,.4516f,.3240f,.4680f,.3702f),
            glyph(Rank.K,Suit.BLACK,.4761f,.3240f,.4929f,.3708f),
            glyph(Rank.SEVEN,Suit.BLACK,.5022f,.3250f,.5160f,.3703f),
            glyph(Rank.SEVEN,Suit.BLACK,.5264f,.3250f,.5404f,.3698f))

        val result=partitionGuandanHand(hand+central)

        assertEquals(hand.toSet(),result.hand.toSet())
        assertEquals(central.toSet(),result.table.toSet())
    }
}
