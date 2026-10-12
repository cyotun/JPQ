package com.cyotun.jpq.core
import kotlin.test.*

/** Expected hand sizes and seats are independently read from the four user screenshots. */
class FailureScreenshotTest {
    private fun glyphs(case: Int): List<PlayGlyph> = javaClass.getResourceAsStream("/tencent-failure-glyphs.csv")!!.bufferedReader().useLines { lines ->
        lines.map { it.split(',') }.filter { it[0].toInt()==case }.map { p -> PlayGlyph(Card(Rank.valueOf(p[1]),Suit.valueOf(p[2])),p[3].toFloat(),GlyphBox(p[4].toFloat(),p[5].toFloat(),p[6].toFloat(),p[7].toFloat())) }.toList()
    }
    @Test fun initial27AndRightFirstCard() {
        val p=partitionGuandanHand(glyphs(1)); assertEquals(27,p.hand.size)
        val out=assignPlayGroups(p.table,emptySet(),emptyList())
        assertEquals(listOf(Card(Rank.FOUR,Suit.RED)),out.seats[Seat.NEXT]!!.map { it.card }); assertTrue(out.ambiguous.isEmpty())
    }
    @Test fun leftBombAndRaisedOwnHand() {
        for(case in listOf(2,4)) {
            val p=partitionGuandanHand(glyphs(case)); assertEquals(25,p.hand.size)
            val out=assignPlayGroups(p.table,emptySet(),emptyList())
            assertEquals(4,out.seats[Seat.PREVIOUS]!!.size); assertTrue(out.seats[Seat.OPPOSITE]!!.isEmpty()); assertTrue(out.ambiguous.isEmpty())
        }
    }
    @Test fun centralSelfKingAndOppositeAce() {
        val p=partitionGuandanHand(glyphs(3)); assertEquals(26,p.hand.size)
        val out=assignPlayGroups(p.table,setOf(Seat.NEXT),emptyList())
        assertEquals(listOf(Card(Rank.K,Suit.RED)),out.seats[Seat.SELF]!!.map { it.card })
        assertEquals(listOf(Card(Rank.A,Suit.RED)),out.seats[Seat.OPPOSITE]!!.map { it.card }); assertTrue(out.ambiguous.isEmpty())
    }
}
