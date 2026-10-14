package com.cyotun.jpq.core

import kotlin.test.*

class TallHandCentralPresentationTest {
    private fun g(rank: Rank,suit: Suit,l: Float,t: Float,r: Float,b: Float)=
        PlayGlyph(Card(rank,suit),.89f,GlyphBox(l,t,r,b))
    private val central=listOf(
        g(Rank.SIX,Suit.BLACK,.4287f,.3233f,.4413f,.3700f),
        g(Rank.SEVEN,Suit.RED,.4528f,.3242f,.4663f,.3675f),
        g(Rank.EIGHT,Suit.BLACK,.4770f,.3242f,.4904f,.3683f),
        g(Rank.NINE,Suit.BLACK,.5015f,.3233f,.5153f,.3683f),
        g(Rank.TEN,Suit.RED,.5242f,.3233f,.5426f,.3700f))
    private val tallHand=listOf(
        g(Rank.Q,Suit.BLACK,.5000f,.3750f,.5192f,.4375f),
        g(Rank.Q,Suit.RED,.5004f,.4425f,.5196f,.5042f),
        g(Rank.J,Suit.BLACK,.5012f,.5108f,.5184f,.5683f),
        g(Rank.J,Suit.RED,.5012f,.5775f,.5184f,.6350f),
        g(Rank.TEN,Suit.BLACK,.4981f,.6442f,.5222f,.7025f),
        g(Rank.TEN,Suit.BLACK,.4981f,.7100f,.5222f,.7683f),
        g(Rank.TWO,Suit.BLACK,.4421f,.6450f,.4597f,.7025f),
        g(Rank.TWO,Suit.BLACK,.4421f,.7100f,.4590f,.7675f))
    @Test fun smallerCentralNineCannotBecomeTheSeventhRowOfTeammateHand() {
        // Independently confirmed from the latest game's teammate-view image:
        // the teammate has eight cards, and own final five-card run is central.
        val result=partitionGuandanHand(central+tallHand)
        assertEquals(tallHand.toSet(),result.hand.toSet())
        assertEquals(central.toSet(),result.table.toSet())
        val owned=assignPlayGroups(result.table,emptySet(),emptyList(),false)
        assertEquals(central.toSet(),owned.seats.getValue(Seat.SELF).toSet())
        assertTrue(owned.ambiguous.isEmpty())
    }
    @Test fun finalRunOwnershipRemainsWholeWhenTheTeammateHandAppears() {
        val tracker=OwnExitPresentationTracker()
        val partition=partitionGuandanHand(central+tallHand)
        val own=assignPlayGroups(partition.table,emptySet(),emptyList()).seats.getValue(Seat.SELF)
        tracker.prepare(1000,TableScene.TABLE,"round")
        tracker.observe(1000,RoundPhase.ACTIVE,central.map { it.card },partition.hand.map { it.card },own)
        val proof=tracker.prepare(1120,TableScene.TEAMMATE,"round")
        val teammate=assignPlayGroups(partition.table,emptySet(),emptyList(),true,proof)
        assertEquals(5,teammate.seats.getValue(Seat.SELF).size)
        assertTrue(teammate.ambiguous.isEmpty())
    }
    @Test fun aGenuineHandRowAtTheSameHeightStillJoinsItsStack() {
        val top=g(Rank.NINE,Suit.BLACK,.5010f,.3100f,.5190f,.3690f)
        val result=partitionGuandanHand(tallHand+top)
        assertEquals(9,result.hand.size)
        assertTrue(result.table.isEmpty())
    }
    @Test fun unrelatedCentralGeometryCannotInheritOwnExitOwnership() {
        val shifted=central.map { it.copy(box=it.box.copy(left=it.box.left+.08f,right=it.box.right+.08f)) }
        val result=assignPlayGroups(shifted,emptySet(),emptyList(),true,central)
        assertTrue(result.seats.getValue(Seat.SELF).isEmpty())
        assertEquals(5,result.ambiguous.size)
    }
}
