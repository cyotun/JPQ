package com.cyotun.jpq.core
import kotlin.test.*

class PlayOwnershipTest {
    @Test fun centralRunIsOneGroupAndNeedsOwnerEvidence() {
        val glyphs=listOf(Rank.NINE,Rank.TEN,Rank.J,Rank.Q,Rank.K).mapIndexed { i,rank ->
            PlayGlyph(Card(rank,Suit.BLACK),.9f,GlyphBox(.415f+i*.025f,.32f,.44f+i*.025f,.39f))
        }
        val owned=assignPlayGroups(glyphs,setOf(Seat.PREVIOUS,Seat.OPPOSITE),emptyList(),true)
        assertEquals(5,owned.seats[Seat.NEXT]!!.size); assertTrue(owned.seats[Seat.PREVIOUS]!!.isEmpty())
        assertEquals(5,assignPlayGroups(glyphs,emptySet(),emptyList(),true).ambiguous.size)
    }
    @Test fun ownVisiblePlayRequiresMatchingHandDifference() {
        val card=Card(Rank.A,Suit.RED); val glyph=PlayGlyph(card,.95f,GlyphBox(.49f,.44f,.52f,.51f))
        assertEquals(listOf(glyph),assignPlayGroups(listOf(glyph),emptySet(),listOf(card)).seats[Seat.SELF])
        assertEquals(1,assignPlayGroups(listOf(glyph),emptySet(),listOf(Card(Rank.K,Suit.RED)),true).ambiguous.size)
    }
    @Test fun highLeftBombIsNotTheOppositeSeat() {
        val cards=List(4) { i -> PlayGlyph(Card(Rank.THREE,if(i<2) Suit.RED else Suit.BLACK),.95f,GlyphBox(.15f+i*.025f,.265f,.175f+i*.025f,.32f)) }
        assertEquals(4,assignPlayGroups(cards,emptySet(),emptyList()).seats[Seat.PREVIOUS]!!.size)
    }
    @Test fun centralSelfCardBelowOppositeIsNotAmbiguous() {
        val opposite=PlayGlyph(Card(Rank.A,Suit.RED),.96f,GlyphBox(.48f,.16f,.51f,.22f))
        val self=PlayGlyph(Card(Rank.K,Suit.RED),.96f,GlyphBox(.48f,.32f,.51f,.38f))
        val result=assignPlayGroups(listOf(opposite,self),setOf(Seat.NEXT),emptyList())
        assertEquals(listOf(opposite),result.seats[Seat.OPPOSITE]); assertEquals(listOf(self),result.seats[Seat.SELF]); assertTrue(result.ambiguous.isEmpty())
    }
    @Test fun twoSeparateGroupsAtTheSameSeatAreNotCombinedIntoOnePlay() {
        val old=PlayGlyph(Card(Rank.A,Suit.RED),.98f,GlyphBox(.14f,.27f,.165f,.33f))
        val incoming=PlayGlyph(Card(Rank.K,Suit.BLACK),.98f,GlyphBox(.24f,.27f,.265f,.33f))
        val owned=assignPlayGroups(listOf(old,incoming),emptySet(),emptyList())
        assertTrue(owned.seats[Seat.PREVIOUS]!!.isEmpty()); assertEquals(2,owned.ambiguous.size)
    }
    @Test fun tallJokerAndOrdinaryGlyphsInOneRowStayOneGroup() {
        val joker=PlayGlyph(Card(Rank.SMALL,Suit.NONE),.98f,GlyphBox(.14f,.27f,.165f,.40f))
        val pair=PlayGlyph(Card(Rank.A,Suit.RED),.98f,GlyphBox(.168f,.27f,.193f,.33f))
        val owned=assignPlayGroups(listOf(joker,pair),emptySet(),emptyList())
        assertEquals(2,owned.seats[Seat.PREVIOUS]!!.size); assertTrue(owned.ambiguous.isEmpty())
    }
    @Test fun closeDifferentRowsFromDifferentSeatsDoNotJoinAtTheirCenters() {
        val left=listOf(Rank.FOUR,Rank.FIVE,Rank.SIX,Rank.SEVEN,Rank.EIGHT).mapIndexed { i,rank ->
            PlayGlyph(Card(rank,Suit.BLACK),.98f,GlyphBox(.28f+i*.026f,.285f,.305f+i*.026f,.345f))
        }
        val opposite=PlayGlyph(Card(Rank.A,Suit.RED),.98f,GlyphBox(.475f,.24f,.50f,.30f))
        val owned=assignPlayGroups(left+opposite,emptySet(),emptyList())
        assertEquals(5,owned.seats[Seat.PREVIOUS]!!.size); assertEquals(listOf(opposite),owned.seats[Seat.OPPOSITE]); assertTrue(owned.ambiguous.isEmpty())
    }
}
