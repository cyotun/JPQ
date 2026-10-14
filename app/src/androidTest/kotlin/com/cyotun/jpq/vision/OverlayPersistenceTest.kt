package com.cyotun.jpq.vision

import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.*
import com.cyotun.jpq.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Requires an explicit stopped-capture probe; never enters or plays a game. */
@RunWith(AndroidJUnit4::class)
class OverlayPersistenceTest {
    @Test fun stoppingAndStartingAtADifferentBannerWidthPreservesTheTopLeftAnchor() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("displayInteractionProbe")=="true")
        check(!AppState.running) { "Run overlay probes only after capture and the real match stop" }
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        assumeTrue(Settings.canDrawOverlays(context))
        val store=ConfigStore(context); val original=store.load()
        val initial=Rank.entries.filterNot { it.isJoker }.flatMap { rank ->
            listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } }
        }.take(27)
        var overlay: OverlayController?=null
        try {
            store.save(DisplayConfig(showHistory=false,showReset=false,doubleTapHide=true))
            AppState.ledger.waitForNext(); AppState.ledger.begin("position-stop-start",initial)
            val before=AppState.ledger.snapshot()
            lateinit var view: View
            instrument.runOnMainSync { overlay=OverlayController(context).also { it.show(); view=it.previewView() } }
            waitFor { view.width>0 && AppState.barMask!=null }
            val density=context.resources.displayMetrics.density
            val screenWidth=checkNotNull(AppState.barMask).screenWidth
            val waitingWidth=(minOf(370f,screenWidth/density)*density).roundToInt()
            val dx=minOf(8*density,(screenWidth-waitingWidth).coerceAtLeast(0)/2f)
            val dy=24*density
            val point=screenPoint(instrument,view,view.width/2f,22*density)
            val down=SystemClock.uptimeMillis()
            touch(instrument,view,MotionEvent.ACTION_DOWN,point.x,point.y,down,down)
            touch(instrument,view,MotionEvent.ACTION_MOVE,point.x+dx,point.y+dy,down,down+30)
            touch(instrument,view,MotionEvent.ACTION_UP,point.x+dx,point.y+dy,down,down+60)
            waitFor { AppState.barMask?.rectangle?.top != 0 }
            val stopped=Rect(checkNotNull(AppState.barMask).rectangle)
            instrument.runOnMainSync { overlay!!.close() }
            val saved=store.load()
            assertTrue(saved.positionIsScreenRelative)
            assertEquals(before,AppState.ledger.snapshot())

            // Capture starts in WAITING, whose banner has a different width from
            // the card grid that was showing when the previous capture stopped.
            AppState.ledger.waitForNext()
            instrument.runOnMainSync { overlay=OverlayController(context).also { it.show(); view=it.previewView() } }
            waitFor { view.width==waitingWidth && AppState.barMask?.rectangle?.width()==waitingWidth }
            val waiting=Rect(checkNotNull(AppState.barMask).rectangle)
            assertEquals(stopped.left,waiting.left); assertEquals(stopped.top,waiting.top)
            AppState.ledger.begin("position-stop-start-new-round",initial)
            instrument.runOnMainSync { AppState.notifyChanged() }
            waitFor { view.width!=waitingWidth && AppState.barMask?.rectangle?.width()==view.width }
            val playing=Rect(checkNotNull(AppState.barMask).rectangle)
            assertEquals(stopped.left,playing.left); assertEquals(stopped.top,playing.top)

            // All three supported history modes affect only display geometry.
            val round=AppState.ledger.snapshot()
            store.save(store.load().copy(showHistory=true,showHistoryDetails=false))
            val expectedCountWidth=(HistoryWidth().measure(store.load(),0f,screenWidth/density).total*density).roundToInt()
            instrument.runOnMainSync { overlay!!.refresh() }
            waitFor { view.width==expectedCountWidth && AppState.barMask?.rectangle?.width()==expectedCountWidth }
            val countOnlyWidth=view.width
            store.save(store.load().copy(showHistoryDetails=true))
            instrument.runOnMainSync { overlay!!.refresh() }
            waitFor { view.width>countOnlyWidth && AppState.barMask?.rectangle?.width()==view.width }
            assertEquals(round,AppState.ledger.snapshot())
        } finally {
            instrument.runOnMainSync { overlay?.close() }
            AppState.ledger.waitForNext(); store.save(original)
        }
    }

    private fun waitFor(condition: ()->Boolean) {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val deadline=SystemClock.uptimeMillis()+2000
        while(SystemClock.uptimeMillis()<deadline) {
            var ready=false; instrument.runOnMainSync { ready=condition() }
            if(ready) return
            SystemClock.sleep(16)
        }
        fail("Overlay state did not settle")
    }

    private fun screenPoint(instrument: android.app.Instrumentation,view: View,x: Float,y: Float): PointF {
        val point=PointF()
        instrument.runOnMainSync {
            val location=IntArray(2); view.getLocationOnScreen(location)
            point.set(location[0]+x,location[1]+y)
        }
        return point
    }

    private fun touch(instrument: android.app.Instrumentation,view: View,action: Int,x: Float,y: Float,down: Long,at: Long) {
        instrument.runOnMainSync {
            val location=IntArray(2); view.getLocationOnScreen(location)
            val event=MotionEvent.obtain(down,at,action,x,y,0)
            try {
                event.setLocation(x-location[0],y-location[1]); assertTrue(view.dispatchTouchEvent(event))
            } finally { event.recycle() }
        }
        instrument.waitForIdleSync()
    }
}
