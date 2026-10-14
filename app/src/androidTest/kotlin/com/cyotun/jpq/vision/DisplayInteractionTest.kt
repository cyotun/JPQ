package com.cyotun.jpq.vision

import android.content.Intent
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.inspector.WindowInspector
import android.widget.Switch
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.*
import com.cyotun.jpq.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Explicit, stopped-capture UI check. Synthetic ledger data never starts a game. */
@RunWith(AndroidJUnit4::class)
class DisplayInteractionTest {
    @Test fun settingsAndHideGesturesPreserveTheRound() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("displayInteractionProbe")=="true")
        check(!AppState.running) { "Do not run UI probes during capture or a real match" }
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        assumeTrue(Settings.canDrawOverlays(context))
        val store=ConfigStore(context)
        val original=store.load()
        var overlay: OverlayController?=null
        var activity: MainActivity?=null
        try {
            store.save(DisplayConfig())
            AppState.ledger.waitForNext()
            val initial=Rank.entries.filterNot { it.isJoker }.flatMap { rank ->
                listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } }
            }.take(27)
            AppState.ledger.begin("display-test",initial)
            val before=AppState.ledger.snapshot()
            context.startActivity(Intent(context,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("page","settings"))
            waitFor(instrument) {
                activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
                activity!=null
            }
            fun descendants(view: View): List<View> = listOf(view)+if(view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            fun selectHistory(which: Int) {
                instrument.runOnMainSync {
                    val label=descendants(activity!!.window.decorView).filterIsInstance<TextView>().single { it.text.toString()=="三家出牌显示" }
                    assertTrue((label.parent as View).performClick())
                }
                instrument.waitForIdleSync()
                instrument.runOnMainSync {
                    val label=listOf("隐藏","仅数量","数量和详情")[which]
                    val choice=WindowInspector.getGlobalWindowViews().flatMap { descendants(it) }.filterIsInstance<TextView>()
                        .first { it.text.toString()==label && it.parent is android.widget.ListView }
                    val list=choice.parent as android.widget.ListView
                    assertTrue(list.performItemClick(choice,which,list.adapter.getItemId(which)))
                }
                instrument.waitForIdleSync()
            }
            fun toggleSwitches() {
                instrument.runOnMainSync {
                    val switches=descendants(activity!!.window.decorView).filterIsInstance<Switch>()
                        .filter { it.text.toString() in setOf("显示重置按钮","双击记牌条隐藏") }
                    assertEquals(2,switches.size); switches.forEach { it.performClick() }
                }
            }
            fun toggleStraightFlush() {
                instrument.runOnMainSync {
                    val setting=descendants(activity!!.window.decorView).filterIsInstance<Switch>()
                        .single { it.text.toString()=="显示同花顺标识" }
                    assertTrue(setting.performClick())
                }
                instrument.waitForIdleSync()
            }
            assertTrue(store.load().showStraightFlush)
            toggleStraightFlush()
            assertFalse(store.load().showStraightFlush); assertEquals(before,AppState.ledger.snapshot())
            toggleStraightFlush()
            assertTrue(store.load().showStraightFlush); assertEquals(before,AppState.ledger.snapshot())
            selectHistory(0)
            // Exercise the actual screen listeners and the exact combination reported by the user.
            toggleSwitches()
            assertFalse(store.load().showHistory); assertFalse(store.load().showReset)
            assertTrue(store.load().doubleTapHide); assertEquals(before,AppState.ledger.snapshot())
            selectHistory(1)
            assertTrue(store.load().showHistory); assertFalse(store.load().showHistoryDetails)
            assertEquals(before,AppState.ledger.snapshot())
            selectHistory(2); toggleSwitches()
            assertTrue(store.load().showHistory); assertTrue(store.load().showReset)
            assertTrue(store.load().showHistoryDetails)
            assertFalse(store.load().doubleTapHide); assertEquals(before,AppState.ledger.snapshot())
            instrument.runOnMainSync { overlay=OverlayController(context).also { it.show() } }
            instrument.waitForIdleSync()
            val density=context.resources.displayMetrics.density
            lateinit var view: View
            instrument.runOnMainSync { view=overlay!!.previewView() }
            instrument.waitForIdleSync()
            val expandedWidth=view.width
            assertTrue(expandedWidth>44*density)
            assertNotNull(AppState.barMask)
            fun tap(x: Float,y: Float,time: Long) {
                instrument.runOnMainSync {
                    listOf(MotionEvent.ACTION_DOWN to time,MotionEvent.ACTION_UP to time+20).forEach { (action,at) ->
                        val event=MotionEvent.obtain(time,at,action,x,y,0)
                        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
                    }
                }
                instrument.waitForIdleSync()
            }
            tap(22*density,22*density,SystemClock.uptimeMillis())
            assertEquals((44*density).roundToInt(),view.width)
            assertEquals(view.width,AppState.barMask!!.rectangle.width())
            tap(22*density,22*density,SystemClock.uptimeMillis()+50)
            assertEquals(expandedWidth,view.width)
            assertEquals(before,AppState.ledger.snapshot())
            store.save(store.load().copy(showHistory=false,showReset=false,doubleTapHide=true))
            instrument.runOnMainSync { overlay!!.refresh() }; instrument.waitForIdleSync()
            val compactWidth=view.width
            assertTrue(compactWidth<expandedWidth)
            val now=SystemClock.uptimeMillis()+100
            tap(100*density,22*density,now)
            tap(100*density,22*density,now+100)
            assertEquals((44*density).roundToInt(),view.width)
            tap(22*density,22*density,now+200)
            assertEquals(compactWidth,view.width)
            assertEquals(before,AppState.ledger.snapshot())
            android.util.Log.i("JPQUIProbe","settings_restore_and_both_hide_modes_passed; synthetic_round_only")
        } finally {
            instrument.runOnMainSync { overlay?.close(); activity?.finish() }
            AppState.ledger.waitForNext()
            store.save(original)
        }
    }

    @Test fun hiddenShowButtonDragRemainsHiddenThroughRefreshAndRestoresAtTheMovedAnchor() {
        withStoppedSyntheticOverlay(DisplayConfig(showHistory=false,showReset=false,doubleTapHide=false,x=0f,y=0f)) {
                instrument,context,store,overlay,view,before ->
            val density=context.resources.displayMetrics.density
            val circle=(44*density).roundToInt()
            val expandedWidth=view.width
            val initial=Rect(checkNotNull(AppState.barMask).rectangle)
            // Body dragging is still available before either hide mode is used.
            drag(instrument,view,72*density,22*density,0f,24*density)
            waitFor(instrument) { AppState.barMask?.rectangle?.top != initial.top }
            assertEquals(expandedWidth,view.width)
            tapReal(instrument,view,22*density,22*density)
            waitFor(instrument) { view.width == circle && AppState.barMask?.rectangle?.width() == circle }
            val hiddenBefore=Rect(checkNotNull(AppState.barMask).rectangle)
            val mask=checkNotNull(AppState.barMask)
            val availableX=(mask.screenWidth-expandedWidth-hiddenBefore.left).coerceAtLeast(0)
            val availableY=(mask.screenHeight-view.height-hiddenBefore.top-8*density).coerceAtLeast(0f)
            val dx=minOf(40*density,availableX/2f)
            val dy=minOf(72*density,availableY/2f)
            assertTrue("The synthetic view needs room for a real drag",dy > ViewConfiguration.get(context).scaledTouchSlop)
            drag(instrument,view,22*density,22*density,dx,dy)
            waitFor(instrument) { AppState.barMask?.rectangle != hiddenBefore }
            val moved=Rect(checkNotNull(AppState.barMask).rectangle)
            assertEquals(circle,view.width); assertEquals(circle,view.height)
            assertEquals(circle,moved.width()); assertEquals(circle,moved.height())
            assertEquals(before,AppState.ledger.snapshot())
            val saved=store.load()
            assertTrue(saved.x != 0f || saved.y != 0f)
            instrument.runOnMainSync { overlay.refresh(); AppState.notifyChanged() }
            instrument.waitForIdleSync()
            waitFor(instrument) { AppState.barMask?.rectangle == moved }
            assertEquals(circle,view.width)
            // The drag's UP must not restore the bar or seed a double tap.
            tapReal(instrument,view,22*density,22*density)
            waitFor(instrument) { view.width == expandedWidth && AppState.barMask?.rectangle?.width() == expandedWidth }
            val restored=Rect(checkNotNull(AppState.barMask).rectangle)
            assertEquals(moved.left,restored.left); assertEquals(moved.top,restored.top)
            assertEquals(before,AppState.ledger.snapshot())

            // Repeat using the other supported hide mode, then drag the same circle.
            store.save(store.load().copy(doubleTapHide=true))
            instrument.runOnMainSync { overlay.refresh() }
            instrument.waitForIdleSync()
            val bodyWidth=view.width
            val bodyX=bodyWidth/2f
            tapReal(instrument,view,bodyX,22*density)
            SystemClock.sleep(80)
            tapReal(instrument,view,bodyX,22*density)
            waitFor(instrument) { view.width == circle && AppState.barMask?.rectangle?.width() == circle }
            val secondStart=Rect(checkNotNull(AppState.barMask).rectangle)
            drag(instrument,view,22*density,22*density,0f,24*density)
            waitFor(instrument) { AppState.barMask?.rectangle?.top != secondStart.top }
            val secondMoved=Rect(checkNotNull(AppState.barMask).rectangle)
            assertEquals(circle,view.width)
            instrument.runOnMainSync { overlay.refresh() }
            instrument.waitForIdleSync()
            assertEquals(secondMoved,AppState.barMask!!.rectangle)
            tapReal(instrument,view,22*density,22*density)
            waitFor(instrument) { view.width == bodyWidth && AppState.barMask?.rectangle?.width() == bodyWidth }
            assertEquals(secondMoved.top,AppState.barMask!!.rectangle.top)
            assertEquals(before,AppState.ledger.snapshot())
            // A drag immediately following a body tap must not finish a double tap.
            val restoredBeforeBodyDrag=Rect(AppState.barMask!!.rectangle)
            tapReal(instrument,view,bodyWidth/2f,22*density)
            SystemClock.sleep(80)
            drag(instrument,view,bodyWidth/2f,22*density,0f,24*density)
            waitFor(instrument) { AppState.barMask?.rectangle?.top != restoredBeforeBodyDrag.top }
            assertEquals(bodyWidth,view.width)
            assertEquals(before,AppState.ledger.snapshot())
        }
    }

    @Test fun aRealBodyLongPressCannotOpenToolsOrStartAdjustment() {
        withStoppedSyntheticOverlay(DisplayConfig(showHistory=false,showReset=false,doubleTapHide=false,x=0f,y=0f)) {
                instrument,context,store,overlay,view,before ->
            assumeTrue(Build.VERSION.SDK_INT >= 29)
            val density=context.resources.displayMetrics.density
            for(doubleTapMode in listOf(false,true)) {
                store.save(store.load().copy(doubleTapHide=doubleTapMode))
                instrument.runOnMainSync { overlay.refresh() }
                instrument.waitForIdleSync()
                val initialMask=Rect(checkNotNull(AppState.barMask).rectangle)
                val width=view.width
                val windows=globalWindows(instrument)
                assertFalse(AppState.adjusting); assertNull(AppState.adjustmentMask)
                val localX=if(doubleTapMode) view.width/2f else minOf(72*density,view.width-4*density)
                val point=screenPoint(instrument,view,localX,22*density)
                val down=SystemClock.uptimeMillis()
                touch(instrument,view,MotionEvent.ACTION_DOWN,point.x,point.y,down,down)
                // Let GestureDetector's real handler deadlines elapse; fabricated UP
                // timestamps alone would not exercise the removed long-press callback.
                SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong()+150)
                instrument.waitForIdleSync()
                assertFalse(AppState.adjusting); assertNull(AppState.adjustmentMask)
                assertEquals(windows,globalWindows(instrument))
                touch(instrument,view,MotionEvent.ACTION_UP,point.x,point.y,down,SystemClock.uptimeMillis())
                instrument.waitForIdleSync()
                assertEquals(width,view.width)
                assertEquals(initialMask,AppState.barMask!!.rectangle)
                assertEquals(windows,globalWindows(instrument))
                assertFalse(AppState.adjusting); assertNull(AppState.adjustmentMask)
                assertEquals(before,AppState.ledger.snapshot())
            }
        }
    }

    @Test fun straightFlushDefaultsToGoldAndOnlyProvedAbsenceDarkensTheSameSmallSlot() {
        withStoppedSyntheticOverlay(DisplayConfig(showHistory=false,showReset=false,doubleTapHide=true,showStraightFlush=true)) {
                instrument,context,store,overlay,view,before ->
            val original=AppState.straightFlushState
            val density=context.resources.displayMetrics.density
            val config=store.load()
            val width=HistoryWidth().measure(config,0f,context.resources.displayMetrics.widthPixels/density)
            val centerX=((width.grid+10f)*density).roundToInt()
            val centerY=((22*config.gridHeight+1.5f)*density).roundToInt()
            val shownWidth=view.width
            fun centerColor(state: StraightFlushState): Int {
                var color=0
                instrument.runOnMainSync {
                    AppState.straightFlushState=state; overlay.refresh()
                    val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                    try { view.draw(Canvas(bitmap)); color=bitmap.getPixel(centerX,centerY) }
                    finally { bitmap.recycle() }
                }
                assertEquals(shownWidth,view.width)
                assertEquals(before,AppState.ledger.snapshot())
                return color
            }
            try {
                assertEquals(OverlayController.GOLD,centerColor(StraightFlushState.UNKNOWN))
                assertEquals(OverlayController.GOLD,centerColor(StraightFlushState.POSSIBLE))
                assertEquals(Color.rgb(98,98,98),centerColor(StraightFlushState.IMPOSSIBLE))
                store.save(config.copy(showStraightFlush=false))
                instrument.runOnMainSync { overlay.refresh() }
                instrument.waitForIdleSync()
                assertEquals((16*density).roundToInt(),shownWidth-view.width)
                assertEquals(before,AppState.ledger.snapshot())
            } finally { AppState.straightFlushState=original }
        }
    }

    private fun withStoppedSyntheticOverlay(config: DisplayConfig,
        block: (android.app.Instrumentation,android.content.Context,ConfigStore,OverlayController,View,RoundSnapshot)->Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("displayInteractionProbe") == "true")
        check(!AppState.running) { "Do not run UI probes during capture or a real match" }
        assumeTrue(!AppState.adjusting && AppState.adjustmentMask == null)
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        assumeTrue(Settings.canDrawOverlays(context))
        val store=ConfigStore(context); val original=store.load()
        var owned: OverlayController?=null
        try {
            store.save(config)
            AppState.ledger.waitForNext()
            val initial=Rank.entries.filterNot { it.isJoker }.flatMap { rank ->
                listOf(Suit.RED,Suit.BLACK).flatMap { suit -> List(4) { Card(rank,suit) } }
            }.take(27)
            AppState.ledger.begin("display-gesture-${SystemClock.uptimeMillis()}",initial)
            val before=AppState.ledger.snapshot()
            lateinit var overlay: OverlayController
            lateinit var view: View
            instrument.runOnMainSync {
                overlay=OverlayController(context); owned=overlay
                overlay.show(); view=overlay.previewView()
            }
            waitFor(instrument) { view.width > 0 && AppState.barMask != null }
            block(instrument,context,store,overlay,view,before)
        } finally {
            instrument.runOnMainSync { owned?.close() }
            AppState.ledger.waitForNext()
            store.save(original)
        }
    }

    private fun waitFor(instrument: android.app.Instrumentation,condition: ()->Boolean) {
        val deadline=SystemClock.uptimeMillis()+2000
        while(SystemClock.uptimeMillis() < deadline) {
            var ready=false
            instrument.runOnMainSync { ready=condition() }
            if(ready) return
            SystemClock.sleep(16)
        }
        fail("View/mask state did not settle")
    }

    private fun screenPoint(instrument: android.app.Instrumentation,view: View,x: Float,y: Float): PointF {
        val result=PointF()
        instrument.runOnMainSync {
            val location=IntArray(2); view.getLocationOnScreen(location)
            result.set(location[0]+x,location[1]+y)
        }
        return result
    }

    private fun touch(instrument: android.app.Instrumentation,view: View,action: Int,rawX: Float,rawY: Float,down: Long,at: Long) {
        instrument.runOnMainSync {
            val location=IntArray(2); view.getLocationOnScreen(location)
            val event=MotionEvent.obtain(down,at,action,rawX,rawY,0)
            try {
                event.setLocation(rawX-location[0],rawY-location[1])
                assertEquals(rawX,event.rawX,.01f); assertEquals(rawY,event.rawY,.01f)
                assertTrue(view.dispatchTouchEvent(event))
            } finally { event.recycle() }
        }
        instrument.waitForIdleSync()
    }

    private fun tapReal(instrument: android.app.Instrumentation,view: View,x: Float,y: Float) {
        val point=screenPoint(instrument,view,x,y)
        val down=SystemClock.uptimeMillis()
        touch(instrument,view,MotionEvent.ACTION_DOWN,point.x,point.y,down,down)
        touch(instrument,view,MotionEvent.ACTION_UP,point.x,point.y,down,down+20)
    }

    private fun drag(instrument: android.app.Instrumentation,view: View,x: Float,y: Float,dx: Float,dy: Float) {
        val start=screenPoint(instrument,view,x,y)
        val down=SystemClock.uptimeMillis()
        touch(instrument,view,MotionEvent.ACTION_DOWN,start.x,start.y,down,down)
        touch(instrument,view,MotionEvent.ACTION_MOVE,start.x+dx/2,start.y+dy/2,down,down+25)
        touch(instrument,view,MotionEvent.ACTION_MOVE,start.x+dx,start.y+dy,down,down+50)
        touch(instrument,view,MotionEvent.ACTION_UP,start.x+dx,start.y+dy,down,down+75)
    }

    private fun globalWindows(instrument: android.app.Instrumentation): Set<View> {
        var windows=emptySet<View>()
        instrument.runOnMainSync { windows=WindowInspector.getGlobalWindowViews().toSet() }
        return windows
    }
}
