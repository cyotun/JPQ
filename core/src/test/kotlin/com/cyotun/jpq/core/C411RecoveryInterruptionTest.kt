package com.cyotun.jpq.core

import kotlin.test.*

class C411RecoveryInterruptionTest {
    private val hand=List(2) { Card(Rank.A,Suit.BLACK) }
    private val card=Card(Rank.Q,Suit.RED)
    private val glyph=PlayGlyph(card,.95f,GlyphBox(.72f,.28f,.74f,.32f))
    private fun recovery()=RoundRecovery(100L,hand,false,
        mapOf(Seat.NEXT to RoundRecovery.Anchor(listOf(card),listOf(glyph),"old",0L)),false,emptyMap())
    private fun frame(time: Long)=TableFrame(time,TableScene.TABLE,hand,
        Seat.entries.associateWith { if(it==Seat.NEXT) ObservedPlay(listOf(card),.95f,listOf(glyph))
            else ObservedPlay(emptyList(),1f) })

    @Test fun aSecondCaptureBreakCannotDonateItsThreeEarlierPhysicalVotesToTheReturn() {
        val proof=recovery()
        for(time in listOf(200L,300L,400L)) assertNull(proof.reconcile(frame(time)))
        proof.observationInterrupted(450L)
        for(time in listOf(500L,600L,700L)) assertNull(proof.reconcile(frame(time)))
        assertEquals(emptyMap(),proof.reconcile(frame(800L)))
    }

    @Test fun repeatedGapsNeverExtendTheOriginalPhysicalRecoveryDeadline() {
        val proof=recovery()
        for(time in listOf(200L,300L,400L)) assertNull(proof.reconcile(frame(time)))
        proof.observationInterrupted(4000L)
        for(time in listOf(4101L,4201L,4301L,4401L)) assertNull(proof.reconcile(frame(time)))
    }
}
