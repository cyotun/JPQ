package com.cyotun.jpq.core
import kotlin.test.*

class PlayOwnershipTest {
    @Test fun centralRunIsOneGroupAndNeedsOwnerEvidence() {
        val glyphs=listOf(Rank.NINE,Rank.TEN,Rank.J,Rank.Q,Rank.K).mapIndexed { i,rank ->
            PlayGlyph(Card(rank,Suit.BLACK),.9f,GlyphBox(.415f+i*.025f,.32f,.44f+i*.025f,.39f))
        }
        val owned=assignPlayGroups(glyphs,setOf(Seat.PREVIOUS,Seat.OPPOSITE),emptyList())
        assertEquals(5,owned.seats[Seat.NEXT]!!.size); assertTrue(owned.seats[Seat.PREVIOUS]!!.isEmpty())
        assertEquals(5,assignPlayGroups(glyphs,emptySet(),emptyList()).ambiguous.size)
    }
    @Test fun ownVisiblePlayRequiresMatchingHandDifference() {
        val card=Card(Rank.A,Suit.RED); val glyph=PlayGlyph(card,.95f,GlyphBox(.49f,.44f,.52f,.51f))
        assertEquals(listOf(glyph),assignPlayGroups(listOf(glyph),emptySet(),listOf(card)).seats[Seat.SELF])
        assertEquals(1,assignPlayGroups(listOf(glyph),emptySet(),listOf(Card(Rank.K,Suit.RED))).ambiguous.size)
    }
}
