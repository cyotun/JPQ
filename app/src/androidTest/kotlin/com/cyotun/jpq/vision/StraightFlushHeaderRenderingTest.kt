package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.*
import com.cyotun.jpq.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Renders only an unattached view: no overlay permission, game or projection. */
@RunWith(AndroidJUnit4::class)
class StraightFlushHeaderRenderingTest {
    @Test fun candidateGoldChangesHeadersOnlyAndNeverRecolorsJokersOrQuantities() {
        check(!AppState.running)
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        val previous=StraightFlushResult(AppState.straightFlushState,candidateRanks=AppState.straightFlushCandidateRanks)
        val wildcards=AppState.straightFlushIncludesWildcards
        val counters=AppState.counterState
        val initial=Rank.entries.filterNot { it.isJoker }.flatMap { rank ->
            listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } }
        }.take(27)
        AppState.ledger.waitForNext(); AppState.resetRemainingPool()
        AppState.ledger.begin("header-render-only",initial)
        val config=DisplayConfig(showHistory=false,showReset=false,doubleTapHide=true,showStraightFlush=true)
        var overlay: OverlayController?=null
        fun render(ranks: Set<Rank>): Bitmap {
            lateinit var result: Bitmap
            instrument.runOnMainSync {
                AppState.publishStraightFlush(StraightFlushResult(StraightFlushState.POSSIBLE,candidateRanks=ranks),true)
                val owner=overlay ?: OverlayController(context,configSource={config}).also { overlay=it }
                val view=owner.previewView()
                val width=context.resources.displayMetrics.widthPixels
                val height=(60*context.resources.displayMetrics.density).toInt()
                view.layout(0,0,width,height)
                result=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(result))
            }
            return result
        }
        var plain: Bitmap?=null; var gold: Bitmap?=null; var jokers: Bitmap?=null
        try {
            plain=render(emptySet()); gold=render(setOf(Rank.A,Rank.TWO)); jokers=render(setOf(Rank.A,Rank.TWO,Rank.BIG,Rank.SMALL))
            val a=IntArray(plain.width*plain.height); val b=IntArray(a.size); val c=IntArray(a.size)
            plain.getPixels(a,0,plain.width,0,0,plain.width,plain.height)
            gold.getPixels(b,0,gold.width,0,0,gold.width,gold.height)
            jokers.getPixels(c,0,jokers.width,0,0,jokers.width,jokers.height)
            val headerBottom=(23*config.gridHeight*context.resources.displayMetrics.density).toInt()
            var changed=0
            for(i in a.indices) if(a[i]!=b[i]) {
                changed++
                assertTrue("Candidate styling changed quantity row",i/plain.width<headerBottom)
            }
            assertTrue("Gold rank glyphs were not rendered",changed>10)
            assertTrue("Jokers must never be highlighted",b.contentEquals(c))
            assertTrue(b.any { it==OverlayController.CANDIDATE_GOLD })
            assertEquals(initial,AppState.ledger.initialHand())
            if(InstrumentationRegistry.getArguments().getString("saveSyntheticRender")=="true") {
                val folder=java.io.File(context.getExternalFilesDir(null),"synthetic-header-check").apply { mkdirs() }
                java.io.File(folder,"gold-candidates.png").outputStream().use {
                    assertTrue(gold.compress(Bitmap.CompressFormat.PNG,100,it))
                }
            }
        } finally {
            plain?.recycle(); gold?.recycle(); jokers?.recycle()
            instrument.runOnMainSync { overlay?.close() }
            AppState.ledger.waitForNext(); AppState.resetRemainingPool()
            AppState.publishCounterState(counters); AppState.publishStraightFlush(previous,wildcards)
        }
    }
}
