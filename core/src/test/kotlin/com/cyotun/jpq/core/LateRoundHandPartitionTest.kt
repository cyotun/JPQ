package com.cyotun.jpq.core

import kotlin.test.*

class LateRoundHandPartitionTest {
    private fun g(rank: Rank,suit: Suit,l: Float,t: Float,r: Float,b: Float)=
        PlayGlyph(Card(rank,suit),.89f,GlyphBox(l,t,r,b))
    @Test fun centralQueensCannotBridgeIntoTheRaisedOwnSix() {
        // Table and two raised rows are anonymized from real frame1785.
        val table=listOf(g(Rank.Q,Suit.BLACK,.428f,.3227f,.4428f,.3718f),
            g(Rank.Q,Suit.BLACK,.4524f,.3227f,.4672f,.3719f),g(Rank.Q,Suit.BLACK,.4771f,.3228f,.4919f,.3718f),
            g(Rank.THREE,Suit.RED,.5025f,.3243f,.5157f,.3696f),g(Rank.THREE,Suit.RED,.5269f,.3244f,.5399f,.3695f))
        val raised=listOf(g(Rank.SIX,Suit.RED,.4422f,.4446f,.46f,.5031f),g(Rank.FOUR,Suit.RED,.4414f,.5108f,.4604f,.5691f))
        val lower=g(Rank.FOUR,Suit.RED,.4414f,.577f,.4604f,.635f) // explicit lower-row seed
        val result=partitionGuandanHand(table+raised+lower)
        assertEquals(raised+lower,result.hand.sortedBy { it.box.top })
        assertEquals(table.toSet(),result.table.toSet())
        assertEquals(5,assignPlayGroups(result.table,emptySet(),emptyList()).seats.getValue(Seat.SELF).size)
    }
    @Test fun centralFivesCannotBridgeIntoTheOwnJacks() {
        // Table and upper own rows are anonymized from real frame2951.
        val table=listOf(g(Rank.FIVE,Suit.RED,.4409f,.3237f,.4545f,.3696f),
            g(Rank.FIVE,Suit.RED,.4656f,.3238f,.4791f,.3695f),g(Rank.FIVE,Suit.BLACK,.4899f,.3238f,.5035f,.3696f),
            g(Rank.FIVE,Suit.BLACK,.5143f,.3236f,.5278f,.3695f))
        val raised=listOf(g(Rank.J,Suit.BLACK,.4717f,.4424f,.4902f,.5034f),g(Rank.J,Suit.RED,.4717f,.5102f,.4897f,.57f))
        val lower=g(Rank.J,Suit.RED,.4717f,.578f,.4897f,.636f)
        val result=partitionGuandanHand(table+raised+lower)
        assertEquals(3,result.hand.size)
        assertEquals(table.toSet(),result.table.toSet())
        assertTrue(assignPlayGroups(result.table,emptySet(),emptyList()).ambiguous.isEmpty())
    }
}
