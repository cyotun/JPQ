package com.cyotun.jpq.core

import kotlin.test.*

class TencentRemainingCountsTest {
    private val left=GlyphBox(.080f,.365f,.140f,.405f)
    private val right=GlyphBox(.860f,.365f,.920f,.405f)
    private val opposite=GlyphBox(.470f,.120f,.550f,.155f)

    @Test fun readsPositionedOpponentBadgesAndPreservesTheirSourceTime() {
        val parsed=tencentRemainingHandCounts(listOf(
            SceneHint("剩8张",left),SceneHint("剩4张",right),SceneHint("剩6张",opposite)
        ),1234L)

        assertEquals(RemainingHandCount(8,1234L),parsed[Seat.PREVIOUS])
        assertEquals(RemainingHandCount(4,1234L),parsed[Seat.NEXT])
        assertEquals(RemainingHandCount(6,1234L),parsed[Seat.OPPOSITE])
        assertFalse(Seat.SELF in parsed)
    }

    @Test fun onlyExactOneOrTwoDigitCountsInRangeAreAccepted() {
        val accepted=listOf("剩1张","剩10张","剩27张").map { SceneHint(it,left) }
        val rejected=listOf("剩0张","剩28张","剩100张","剩4张了","剩余4张","  剩4张", "倒计时4张")
            .map { SceneHint(it,left) }
        for((hint,expected) in accepted.zip(listOf(1,10,27))) {
            assertEquals(RemainingHandCount(expected,55L),tencentRemainingHandCounts(listOf(hint),55L)[Seat.PREVIOUS])
        }
        assertTrue(tencentRemainingHandCounts(rejected,55L).isEmpty())
    }

    @Test fun unpositionedAndWrongSeatPositionsDoNotCreateCounts() {
        val hints=listOf(
            SceneHint("剩8张",null),
            SceneHint("剩4张",GlyphBox(.30f,.35f,.36f,.39f)),
            SceneHint("剩5张",GlyphBox(.46f,.35f,.52f,.39f)),
            SceneHint("剩6张",GlyphBox(.45f,.20f,.51f,.24f)),
            SceneHint("剩7张",GlyphBox(.20f,.12f,.26f,.15f))
        )
        assertTrue(tencentRemainingHandCounts(hints,99L).isEmpty())
    }

    @Test fun maskedCountIsRejectedAndInvalidMaskRejectsAllEvidence() {
        val masked=tencentRemainingHandCounts(listOf(SceneHint("剩8张",left),SceneHint("剩4张",right)),
            77L,listOf(GlyphBox(.11f,.38f,.13f,.40f)))
        assertEquals(mapOf(Seat.NEXT to RemainingHandCount(4,77L)),masked)

        assertTrue(tencentRemainingHandCounts(listOf(SceneHint("剩8张",left)),77L,
            listOf(GlyphBox(Float.NaN,.2f,.3f,.3f))).isEmpty())
    }

    @Test fun duplicateSameCountsCoalesceButConflictingCountsInvalidateThatSeat() {
        val parsed=tencentRemainingHandCounts(listOf(
            SceneHint("剩8张",left),SceneHint("剩8张",left.copy(left=.081f,right=.141f)),
            SceneHint("剩4张",left),SceneHint("剩3张",right)
        ),88L)

        assertFalse(Seat.PREVIOUS in parsed)
        assertEquals(RemainingHandCount(3,88L),parsed[Seat.NEXT])
    }

    @Test fun finishedRankWordsDoNotImplyZeroRemainingCards() {
        val hints=listOf(SceneHint("头游",left),SceneHint("二游",right),SceneHint("三游",opposite))
        assertTrue(tencentRemainingHandCounts(hints,101L).isEmpty())
    }
}
