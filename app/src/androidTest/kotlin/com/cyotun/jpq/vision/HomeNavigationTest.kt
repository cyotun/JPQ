package com.cyotun.jpq.vision

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.cyotun.jpq.AppState
import com.cyotun.jpq.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit app-only UI checks. They never request screen sharing or enter a game. */
@RunWith(AndroidJUnit4::class)
class HomeNavigationTest {
    @Test fun homeIsOnePageAndTheSameButtonReflectsTheCaptureState() = withStoppedHome { activity ->
        val instrument=InstrumentationRegistry.getInstrumentation()
        lateinit var button: Button
        instrument.runOnMainSync {
            val views=descendants(activity.window.decorView)
            assertTrue(views.none { it is ScrollView })
            assertTrue(views.filterIsInstance<TextView>().none { it.text.toString()=="权限" })
            button=views.filterIsInstance<Button>().single { it.text.toString()=="开始记牌" }
            // A real service publishes this flag through the same listener.
            AppState.running=true; AppState.notifyChanged()
        }
        instrument.waitForIdleSync()
        instrument.runOnMainSync {
            assertEquals("停止记牌",button.text.toString())
            assertSame(button,descendants(activity.window.decorView).filterIsInstance<Button>().single())
            assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().none { it.text.toString()=="返回记牌" })
            AppState.running=false; AppState.notifyChanged()
        }
        instrument.waitForIdleSync()
        instrument.runOnMainSync { assertEquals("开始记牌",button.text.toString()) }
    }

    @Test fun diagnosticToolsAreSecondaryAndPermissionsReturnToTheirSourceAfterRecreation() = withStoppedHome { initial ->
        val instrument=InstrumentationRegistry.getInstrumentation()
        var activity=initial
        fun click(label: String) {
            instrument.runOnMainSync {
                val view=descendants(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString()==label }
                assertTrue(if(view.isClickable) view.performClick() else (view.parent as View).performClick())
            }
            instrument.waitForIdleSync()
        }
        fun has(label: String): Boolean {
            var result=false
            instrument.runOnMainSync { result=descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString()==label } }
            return result
        }
        click("设置")
        assertFalse(has("最近牌局")); assertFalse(has("导出完整测试日志")); assertTrue(has("牌局与诊断"))
        click("牌局与诊断")
        assertTrue(has("最近牌局")); assertTrue(has("导出完整测试日志"))
        instrument.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
        click("权限设置")
        instrument.runOnMainSync { activity.recreate() }
        activity=waitForActivity(initial)
        instrument.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue(has("显示设置"))
        instrument.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue(has("开始记牌")); assertFalse(has("权限设置"))
    }

    private fun descendants(view: View): List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun waitForActivity(previous: MainActivity?=null): MainActivity {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val deadline=SystemClock.uptimeMillis()+4000
        while(SystemClock.uptimeMillis()<deadline) {
            var found: MainActivity?=null
            instrument.runOnMainSync {
                found=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull { it!==previous }
            }
            if(found!=null) return found!!
            SystemClock.sleep(20)
        }
        error("MainActivity did not resume")
    }

    private fun withStoppedHome(action: (MainActivity)->Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("appUiProbe")=="true")
        check(!AppState.running) { "Do not run UI probes during capture or a real match" }
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        context.startActivity(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("page","home"))
        val activity=waitForActivity()
        try { action(activity) }
        finally {
            instrument.runOnMainSync {
                AppState.running=false; AppState.notifyChanged()
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().forEach { it.finish() }
            }
        }
    }
}
