package com.cyotun.jpq.vision

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
import com.cyotun.jpq.HomeGameCatalog
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
            if(activity.resources.configuration.screenHeightDp>=800 && activity.resources.configuration.fontScale<=1.3f)
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

    @Test fun gameSelectionCannotStartAnUnsupportedAdapterAndUpdatesRemainReachable() = withStoppedHome { activity ->
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            assertTrue(HomeGameCatalog.canStart(HomeGameCatalog.DEFAULT_PLATFORM))
            assertFalse(HomeGameCatalog.canStart("tencent-doudizhu"))
            assertFalse(HomeGameCatalog.canStart(""))
            assertEquals(listOf(HomeGameCatalog.DEFAULT_PLATFORM),HomeGameCatalog.search("大掼蛋").map { it.id })
            val views=descendants(activity.window.decorView)
            assertTrue(views.filterIsInstance<TextView>().single { it.text.toString()=="斗地主" }.isEnabled.not())
            val cards=views.single { it.contentDescription?.toString()=="初始总牌数 108 张" } as ViewGroup
            assertEquals(15,cards.childCount)
            val ranks=(0 until cards.childCount).map { descendants(cards.getChildAt(it)).filterIsInstance<TextView>() }
            assertEquals(108,ranks.sumOf { it.last().text.toString().toInt() })
            val ten=ranks.single { it.first().text.toString()=="10" }.first()
            val nine=ranks.single { it.first().text.toString()=="9" }.first()
            assertEquals(nine.textSize,ten.textSize,.1f)
            if(views.none { it is ScrollView }) {
                val bounds=android.graphics.Rect(); cards.getGlobalVisibleRect(bounds)
                assertEquals(cards.height,bounds.height())
                val primary=views.filterIsInstance<Button>().single()
                assertTrue(primary.top>=0 && primary.height>0)
                val sample=views.single { it.contentDescription?.toString()=="悬浮记牌条样式示例" }
                assertTrue(sample.getGlobalVisibleRect(bounds)); assertEquals(sample.height,bounds.height())
            }
            val settings=views.filterIsInstance<TextView>().single { it.text.toString()=="设置" }
            assertTrue((settings.parent as View).performClick())
        }
        instrument.waitForIdleSync()
        instrument.runOnMainSync {
            val row=activity.window.decorView.findViewWithTag<ViewGroup>("application-update-row")
            assertNotNull("Update action must be in the first-level settings",row)
            assertFalse("The row must not open a secondary page",row.isClickable)
            val buttons=descendants(row).filterIsInstance<Button>()
            assertEquals(1,buttons.size)
            assertEquals("application-update-action",buttons.single().tag)
            row.requestRectangleOnScreen(android.graphics.Rect(0,0,row.width,row.height),true)
            assertEquals("Action must align with the row's right padding",row.width-row.paddingRight,buttons.single().right)
            val bounds=android.graphics.Rect()
            assertTrue("Inline action must be visible after scrolling to the row",buttons.single().getGlobalVisibleRect(bounds))
            assertEquals(buttons.single().height,bounds.height())
            assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString()=="显示设置" })
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(descendants(activity.window.decorView).filterIsInstance<Button>().any { it.text.toString()=="开始记牌" })
        }
    }

    @Test fun anOldUpdateNotificationOpensTheFirstLevelSettingsAfterRecreation() = withStoppedPage("update") { original ->
        val instrument=InstrumentationRegistry.getInstrumentation()
        fun verify(activity: MainActivity) = instrument.runOnMainSync {
            assertNotNull(activity.window.decorView.findViewWithTag<View>("application-update-row"))
            assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString()=="显示设置" })
            assertFalse(descendants(activity.window.decorView).filterIsInstance<Button>().any { it.text.toString()=="安装已下载的更新" })
        }
        verify(original)
        instrument.runOnMainSync { original.recreate() }
        verify(waitForActivity(original))
    }

    @Test fun anUpdateIntentDeliveredToTheExistingHomeAlsoOpensSettings() = withStoppedHome { activity ->
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            activity.startActivity(android.content.Intent(activity,MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("page","update"))
        }
        instrument.waitForIdleSync()
        instrument.runOnMainSync {
            assertNotNull(activity.window.decorView.findViewWithTag<View>("application-update-row"))
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(descendants(activity.window.decorView).filterIsInstance<Button>().any { it.text.toString()=="开始记牌" })
        }
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

    private fun withStoppedHome(action: (MainActivity)->Unit) = withStoppedPage("home",action)
    private fun withStoppedPage(page: String,action: (MainActivity)->Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("appUiProbe")=="true")
        check(!AppState.running) { "Do not run UI probes during capture or a real match" }
        val instrument=InstrumentationRegistry.getInstrumentation()
        launchStoppedJpqPage(instrument,page)
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
