package com.cyotun.jpq.vision

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.graphics.Canvas
import com.cyotun.jpq.*
import com.cyotun.jpq.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CounterPresentationIntegrationTest {
    @Test fun backgroundRoundRecoveryKeepsTheActualBarThenRendersConfirmedDeductions() {
        check(!AppState.running && AppState.ledger.snapshot().roundId==null)
        val instrument=InstrumentationRegistry.getInstrumentation()
        val initial=Rank.entries.filterNot { it.isJoker || it==Rank.TWO }.flatMap { rank ->
            listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } }
        }.take(27)
        val originalCounter=AppState.counterState
        var overlay: OverlayController?=null
        val images=mutableListOf<Bitmap>()
        fun render(): Bitmap {
            lateinit var bitmap: Bitmap
            instrument.runOnMainSync {
                AppState.notifyChanged()
                val config=DisplayConfig(showHistory=true,showReset=false,doubleTapHide=true,showStraightFlush=false)
                val view=(overlay ?: OverlayController(instrument.targetContext,configSource={config}).also { overlay=it }).previewView()
                val width=instrument.targetContext.resources.displayMetrics.widthPixels
                view.layout(0,0,width,180)
                bitmap=Bitmap.createBitmap(width,180,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
            }
            images+=bitmap
            return bitmap
        }
        try {
            AppState.resetRemainingPool(); AppState.publishCounterState(GradeCounterState())
            AppState.running=true; AppState.recognitionReady=true
            AppState.ledger.begin("silent-round-recovery",initial)
            val observer=RoundObserver(AppState.ledger) { AppState.notifyChanged() }
            val empty=Seat.entries.associateWith { ObservedPlay(emptyList(),1f) }
            for(time in listOf(1000L,1266L,1532L)) observer.observe(TableFrame(time,TableScene.TABLE,initial,empty))
            val before=render()
            AppState.markCaptureUnobserved()
            observer.incomplete("test capture interruption",1800,immediate=true)
            AppState.captureGapCheckpointed()
            assertNull(AppState.ledger.snapshot().remaining)
            assertTrue("Recovery replaced confirmed cards with a waiting panel",before.sameAs(render()))
            for(time in listOf(2000L,2266L,2532L)) {
                observer.observe(TableFrame(time,TableScene.TABLE,initial,empty,
                    remainingHandCounts=listOf(Seat.PREVIOUS,Seat.OPPOSITE,Seat.NEXT).associateWith { RemainingHandCount(27,time) }))
            }
            assertEquals(RoundPhase.ACTIVE,AppState.ledger.snapshot().phase)
            val card=Card(Rank.TWO,Suit.BLACK)
            val play=ObservedPlay(listOf(card),.95f,listOf(PlayGlyph(card,.95f,GlyphBox(.47f,.16f,.49f,.20f))))
            for(time in listOf(2800L,3066L,3332L,3598L)) observer.observe(TableFrame(time,TableScene.TABLE,initial,
                empty+mapOf(Seat.OPPOSITE to play)))
            assertEquals(7,AppState.ledger.snapshot().remaining!![Rank.TWO])
            assertFalse("A confirmed new deduction did not update the bar",before.sameAs(render()))
        } finally {
            instrument.runOnMainSync { overlay?.close() }
            images.forEach { it.recycle() }
            AppState.running=false; AppState.recognitionReady=false; AppState.ledger.waitForNext()
            AppState.resetRemainingPool(); AppState.captureGapCheckpointed()
            AppState.publishCounterState(GradeCounterState()); AppState.publishCounterState(originalCounter)
        }
    }
    @Test fun rawUncertaintyDoesNotClearTheConfirmedDisplayTransaction() {
        check(!AppState.running)
        val original=AppState.counterState
        try {
            AppState.publishCounterState(GradeCounterState())
            val before=GradeCounterState("display-transaction",setOf(Rank.TEN),GradeCountBounds(1,1),
                mapOf(Rank.TEN to GradeCountBounds(3,3)))
            AppState.publishCounterState(before)
            val pending=before.copy(heartCount=GradeCountBounds(0,1),
                ordinaryCounts=mapOf(Rank.TEN to GradeCountBounds(2,3)),reason="physical_identity_uncertain")
            AppState.publishCounterState(pending)
            assertEquals(pending,AppState.counterState)
            assertEquals(before,AppState.displayCounterState)
            val after=before.copy(ordinaryCounts=mapOf(Rank.TEN to GradeCountBounds(2,2)))
            AppState.publishCounterState(after)
            assertEquals(after,AppState.displayCounterState)
        } finally { AppState.publishCounterState(GradeCounterState()); AppState.publishCounterState(original) }
    }

    @Test fun nextRoundAndStoppedCaptureCannotReuseThePreviousDisplayNumbers() {
        check(!AppState.running)
        val original=AppState.counterState
        try {
            val before=GradeCounterState("old-round",setOf(Rank.TEN),GradeCountBounds(1,1),
                mapOf(Rank.TEN to GradeCountBounds(3,3)))
            AppState.publishCounterState(before)
            val next=GradeCounterState("new-round",setOf(Rank.TEN),reason="awaiting_physical_evidence")
            AppState.publishCounterState(next)
            assertEquals(next,AppState.displayCounterState)
            AppState.publishCounterState(GradeCounterState())
            assertNull(AppState.displayCounterState.roundId)
        } finally { AppState.publishCounterState(GradeCounterState()); AppState.publishCounterState(original) }
    }

    @Test fun pendingSuitProofLeavesTheActualBarPixelsUnchangedUntilItsCountsCommit() {
        check(!AppState.running)
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        val original=AppState.counterState
        val initial=Rank.entries.filterNot { it.isJoker }.flatMap { rank ->
            listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } }
        }.take(27)
        AppState.ledger.waitForNext(); AppState.resetRemainingPool()
        AppState.ledger.begin("counter-render-transaction",initial)
        val config=DisplayConfig(showHistory=false,showReset=false,doubleTapHide=true,showStraightFlush=false)
        var overlay: OverlayController?=null
        val images=mutableListOf<Bitmap>()
        fun render(state: GradeCounterState): Bitmap {
            lateinit var result: Bitmap
            instrument.runOnMainSync {
                AppState.publishCounterState(state)
                val view=(overlay ?: OverlayController(context,configSource={config}).also { overlay=it }).previewView()
                val width=context.resources.displayMetrics.widthPixels
                val height=(60*context.resources.displayMetrics.density).toInt()
                view.layout(0,0,width,height)
                result=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(result))
            }
            images+=result
            return result
        }
        try {
            AppState.publishCounterState(GradeCounterState())
            val before=GradeCounterState("counter-render-transaction",setOf(Rank.TEN),GradeCountBounds(1,1),
                mapOf(Rank.TEN to GradeCountBounds(3,3)))
            val pending=before.copy(heartCount=GradeCountBounds(0,1),
                ordinaryCounts=mapOf(Rank.TEN to GradeCountBounds(2,3)),reason="physical_identity_uncertain")
            val a=render(before); val b=render(pending)
            assertEquals(pending,AppState.counterState)
            assertTrue("Waiting for a suit vote changed the rendered bar",a.sameAs(b))
            val c=render(before.copy(ordinaryCounts=mapOf(Rank.TEN to GradeCountBounds(2,2))))
            assertFalse("Confirmed counts were never rendered",a.sameAs(c))
        } finally {
            images.forEach { it.recycle() }
            instrument.runOnMainSync { overlay?.close() }
            AppState.ledger.waitForNext(); AppState.resetRemainingPool()
            AppState.publishCounterState(GradeCounterState()); AppState.publishCounterState(original)
        }
    }
}
