package com.cyotun.jpq.core

import kotlin.test.*

class OwnExitPresentationOwnershipTest {
    private val own=listOf(Suit.BLACK,Suit.BLACK,Suit.BLACK,Suit.RED).mapIndexed { i,suit ->
        PlayGlyph(Card(Rank.EIGHT,suit),.91f,GlyphBox(.44f+i*.025f,.324f,.455f+i*.025f,.371f))
    }
    @Test fun knownOwnFinalPresentationSurvivesTheTeammateView() {
        val result=assignPlayGroups(own,emptySet(),emptyList(),true,own)
        assertEquals(own,result.seats.getValue(Seat.SELF))
        assertTrue(result.ambiguous.isEmpty())
        assertTrue(result.seats.getValue(Seat.OPPOSITE).isEmpty())
    }
    @Test fun teammateCenterWithoutPriorOwnProofRemainsUnassigned() {
        assertEquals(4,assignPlayGroups(own,emptySet(),emptyList(),true).ambiguous.size)
    }
    @Test fun conflictingAnimationColorsCannotBecomeAnOpponentPlay() {
        val recolored=own.map { it.copy(card=Card(Rank.EIGHT,Suit.RED)) }
        val result=assignPlayGroups(recolored,setOf(Seat.PREVIOUS,Seat.NEXT),emptyList(),true,own)
        assertEquals(4,result.ambiguous.size)
        assertTrue(result.seats.values.all { it.isEmpty() })
    }
}
