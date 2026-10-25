package com.cyotun.jpq.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TencentEarlyFinishSeatsTest {
    private val previousBadge=GlyphBox(.0763f,.2158f,.1204f,.25f)
    private val nextBadge=GlyphBox(.8796f,.2158f,.9237f,.25f)

    @Test fun exactRankInPositionedAvatarBadgeMapsToItsSeat() {
        assertEquals(mapOf(Seat.PREVIOUS to "二游"),
            tencentEarlyFinishSeats(listOf(SceneHint(" 二 游 ",previousBadge))))
        assertEquals(mapOf(Seat.NEXT to "头游"),
            tencentEarlyFinishSeats(listOf(SceneHint("头游",nextBadge))))
    }

    @Test fun duplicatedRankAcrossSeatsAndConflictingRanksOnOneSeatFailClosed() {
        assertTrue(tencentEarlyFinishSeats(listOf(
            SceneHint("二游",previousBadge),SceneHint("二游",nextBadge))).isEmpty())
        assertTrue(tencentEarlyFinishSeats(listOf(
            SceneHint("头游",previousBadge),SceneHint("二游",previousBadge))).isEmpty())
        // A duplicate OCR copy for the same rank and same player remains one cue.
        assertEquals(mapOf(Seat.PREVIOUS to "二游"),tencentEarlyFinishSeats(listOf(
            SceneHint("二游",previousBadge),SceneHint("二游",previousBadge))))
    }

    @Test fun nicknameTextWrongSlotAndInvalidBoxesCannotBecomeFinishCues() {
        assertTrue(tencentEarlyFinishSeats(listOf(SceneHint("玩家二游",previousBadge))).isEmpty())
        assertTrue(tencentEarlyFinishSeats(listOf(SceneHint("二游",GlyphBox(.20f,.50f,.27f,.55f)))).isEmpty())
        assertTrue(tencentEarlyFinishSeats(listOf(SceneHint("二游",GlyphBox(Float.NaN,.21f,.12f,.25f)))).isEmpty())
        assertTrue(tencentEarlyFinishSeats(listOf(SceneHint("二游",GlyphBox(-.01f,.21f,.12f,.25f)))).isEmpty())
        assertTrue(tencentEarlyFinishSeats(listOf(SceneHint("二游",null))).isEmpty())
    }

    @Test fun distinctFirstAndSecondCuesAtDifferentSeatsAreRetained() {
        assertEquals(mapOf(Seat.PREVIOUS to "二游",Seat.NEXT to "头游"),tencentEarlyFinishSeats(listOf(
            SceneHint("二游",previousBadge),SceneHint("头游",nextBadge))))
    }
}
