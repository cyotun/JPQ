package com.cyotun.jpq.vision

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.AppState
import com.cyotun.jpq.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Reviewed images from the failed live round. No game, capture or preferences are touched. */
@RunWith(AndroidJUnit4::class)
class TencentPhysicalCardFixtureTest {
    @Test fun actualBlackGradeTensAreNaturalEvenBeforeTheirSuitVotesFinish() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gradeOcrFixtureProbe")=="true")
        check(!AppState.running)
        val instrument=InstrumentationRegistry.getInstrumentation(); val done=CompletableFuture<Unit>()
        VisionRuntime.handler.post {
            try {
                val bitmap=instrument.context.assets.open("fixtures/live-0218-two-question-marks.jpg").use { checkNotNull(BitmapFactory.decodeStream(it)) }
                try {
                    LocalCardDetector(instrument.targetContext,"CPU").use { detector ->
                        val glyphs=detector.detect(bitmap).map { found -> PlayGlyph(found.card,found.confidence,
                            GlyphBox(found.box.left,found.box.top,found.box.right,found.box.bottom)) }
                        val partition=partitionGuandanHand(glyphs)
                        val group=assignPlayGroups(partition.table,emptySet(),emptyList()).seats.getValue(Seat.OPPOSITE)
                        assertEquals(2,group.size); assertTrue(group.all { it.card==Card(Rank.TEN,Suit.BLACK) })
                        val reader=SuitPixelReader(); val rule=WildcardRuleEvidence(setOf(Rank.TEN),true)
                        val suits=group.map { glyph ->
                            val pixels=reader.extract(bitmap,glyph,SuitPixelReader.Placement.TABLE)
                            val observation=reader.classify(pixels)
                            assertEquals(false,tencentPhysicalWildcardIdentity(glyph.card,pixels,observation,rule))
                            observation.suit
                        }
                        assertEquals(setOf(Suit.SPADE,Suit.CLUB),suits.toSet())
                    }
                } finally { bitmap.recycle() }
                done.complete(Unit)
            } catch(error: Throwable) { done.completeExceptionally(error) }
        }
        done.get(45,TimeUnit.SECONDS)
    }
    @Test fun actualNaturalPairAndGoldHeartBombKeepSeparatePhysicalCounts() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gradeOcrFixtureProbe")=="true")
        check(!AppState.running)
        val instrument=InstrumentationRegistry.getInstrumentation()
        val done=CompletableFuture<Unit>()
        VisionRuntime.handler.post {
            try {
                val rule=WildcardRuleEvidence(setOf(Rank.TWO),true)
                val reader=SuitPixelReader(); val ledger=RoundLedger(); val evidence=StraightFlushEvidenceTracker()
                LocalCardDetector(instrument.targetContext,"CPU").use { detector ->
                    for((index,file) in listOf("live-0218-natural-pair.jpg","live-0218-gold-bomb.jpg").withIndex()) {
                        val bitmap=instrument.context.assets.open("fixtures/$file").use { checkNotNull(BitmapFactory.decodeStream(it)) }
                        try {
                            val all=detector.detect(bitmap).map { found ->
                                PlayGlyph(found.card,found.confidence,GlyphBox(found.box.left,found.box.top,found.box.right,found.box.bottom)) }
                            val partition=partitionGuandanHand(all)
                            if(index==0) {
                                assertEquals(27,partition.hand.size)
                                ledger.begin("live-physical-proof",partition.hand.map { it.card })
                            }
                            val group=assignPlayGroups(partition.table,emptySet(),emptyList()).seats.getValue(Seat.OPPOSITE)
                            assertEquals(if(index==0) 2 else 4,group.size)
                            val cards=mutableListOf<Card>(); val flags=mutableListOf<Boolean?>()
                            group.sortedBy { it.box.cx }.forEach { glyph ->
                                val pixels=reader.extract(bitmap,glyph,SuitPixelReader.Placement.TABLE)
                                val reading=reader.classify(pixels)
                                val identity=tencentPhysicalWildcardIdentity(glyph.card,pixels,reading,rule)
                                assertNotNull("$file ${glyph.card} $reading",identity)
                                cards+=Card(glyph.card.rank,reading.suit ?: glyph.card.suit); flags+=identity
                            }
                            assertEquals(if(index==0) 0 else 1,flags.count { it==true })
                            if(index==0) assertTrue(cards.all { it==Card(Rank.FIVE,Suit.HEART) })
                            else assertTrue(cards.indices.single { flags[it]==true }.let { cards[it]==Card(Rank.TWO,Suit.HEART) })
                            val event=PlayEvent("live-physical-proof","event-$index",index.toLong()+1,Seat.OPPOSITE,group.map { it.card })
                            ledger.commit(event)
                            val suited=SuitedGroup(cards,group.sortedBy { it.box.cx }.map { it.box },flags)
                            repeat(3) { vote -> evidence.update(1000L+index*2000L+vote*300L,ledger.snapshot(),ledger.initialHand(),null,mapOf(Seat.OPPOSITE to suited),rule) }
                            val state=GradeCardCounter.evaluate(ledger.snapshot(),ledger.initialHand(),evidence.physicalEventCards,rule)
                            assertEquals(if(index==0) 2 else 1,state.heartCount?.exact)
                            assertEquals(6,state.ordinaryCounts[Rank.TWO]?.exact)
                        } finally { bitmap.recycle() }
                    }
                }
                done.complete(Unit)
            } catch(error: Throwable) { done.completeExceptionally(error) }
        }
        done.get(45,TimeUnit.SECONDS)
    }
}
