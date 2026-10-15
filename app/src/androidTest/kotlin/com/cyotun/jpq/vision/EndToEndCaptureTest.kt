package com.cyotun.jpq.vision

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.cyotun.jpq.AppState
import com.cyotun.jpq.CaptureService
import com.cyotun.jpq.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit authorized full-display test. Only our own app and blank test host are opened.
 * No screenrecord, screenshot or second projection is used alongside production capture.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndCaptureTest {
    @Test(timeout=150000) fun actualConsentCaptureRotationAndRestartUseFreshFrames() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("endToEndCaptureProbe")=="true")
        check(!AppState.running) { "Never run during a real match" }
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        val prefs=context.getSharedPreferences("jpq-diagnostics",Context.MODE_PRIVATE)
        val had=prefs.contains("enabled"); val original=prefs.getBoolean("enabled",false)
        val sessions=File(context.filesDir,"sessions")
        val results=JSONArray()
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            instrument.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        fun await(label: String,timeout: Long=12000,condition: ()->Boolean) {
            val deadline=SystemClock.uptimeMillis()+timeout
            while(SystemClock.uptimeMillis()<deadline) { if(condition()) return; SystemClock.sleep(50) }
            error("Timed out: $label")
        }
        fun descendants(view: View): List<View> = listOf(view)+if(view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun consent(node: AccessibilityNodeInfo?): Boolean {
            if(node==null) return false
            val pkg=node.packageName?.toString().orEmpty()
            if(pkg in setOf("com.android.systemui","com.miui.securitycenter") &&
                node.text?.toString() in setOf("共享屏幕","立即开始","开始录制","Start now","Share screen") && node.isEnabled) {
                if(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            return (0 until node.childCount).any { consent(node.getChild(it)) }
        }
        fun landscape() {
            val result=shell("am start -W -n com.cyotun.jpq.tests/com.cyotun.jpq.vision.LandscapeProbeActivity -f 0x10008000")
            check(!result.contains("Error:"))
        }
        fun health(existing: Set<String>,after: Long): JSONObject? {
            val folder=sessions.listFiles().orEmpty().filter { it.name !in existing && it.name.startsWith("capture-") }
                .maxByOrNull { it.name } ?: return null
            val line=runCatching { folder.resolve("capture-health.jsonl").readLines().lastOrNull() }.getOrNull() ?: return null
            return runCatching { JSONObject(line) }.getOrNull()?.takeIf {
                it.optLong("lastDetectEndMs")>after && it.optInt("sourceWidth")>it.optInt("sourceHeight") &&
                    !it.optBoolean("supplyGap") && !it.optBoolean("consumeGap") && it.optLong("acquireFailures")==0L
            }
        }
        prefs.edit().putBoolean("enabled",true).commit()
        try {
            repeat(2) { cycle ->
                val existing=sessions.listFiles().orEmpty().map { it.name }.toSet()
                launchStoppedJpqPage(instrument,"home")
                await("own home") {
                    var clicked=false
                    instrument.runOnMainSync {
                        val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<MainActivity>().firstOrNull()
                        val button=activity?.let { descendants(it.window.decorView).filterIsInstance<Button>()
                            .singleOrNull { b -> b.text.toString()=="开始记牌" } }
                        if(button!=null) { button.performClick(); clicked=true }
                    }
                    clicked
                }
                await("system full-screen consent",18000) {
                    if(!AppState.running) consent(instrument.uiAutomation.rootInActiveWindow)
                    AppState.running && AppState.recognitionReady
                }
                landscape()
                val started=SystemClock.elapsedRealtime()
                var first: JSONObject?=null
                await("landscape bitmap inference") { first=health(existing,started); first!=null }
                val frames=AppState.capturedFrames
                val portraitAt=SystemClock.elapsedRealtime()
                shell("am start -W -n ${context.packageName}/com.cyotun.jpq.MainActivity -f 0x10008000 --es page home")
                await("portrait source") { AppState.sourceHeight>AppState.sourceWidth }
                landscape()
                var resumed: JSONObject?=null
                await("fresh inference after rotation") { resumed=health(existing,portraitAt); resumed!=null && AppState.capturedFrames>frames }
                assertTrue(AppState.running); assertNotNull(AppState.barMask)
                assertEquals(com.cyotun.jpq.core.RoundPhase.WAITING,AppState.ledger.snapshot().phase)
                results.put(JSONObject().put("cycle",cycle+1).put("initial",first).put("resumed",resumed))
                context.startService(Intent(context,CaptureService::class.java).setAction("STOP"))
                await("capture stop") { !AppState.running && !AppState.recognitionReady && AppState.barMask==null }
            }
        } finally {
            if(AppState.running) {
                context.startService(Intent(context,CaptureService::class.java).setAction("STOP"))
                await("final capture cleanup") { !AppState.running }
            }
            prefs.edit().apply { if(had) putBoolean("enabled",original) else remove("enabled") }.commit()
            val folder=File(context.getExternalFilesDir(null),"end-to-end-capture-check").apply { mkdirs() }
            File(folder,"result.json").writeText(JSONObject().put("testOnlyScreens",true)
                .put("completedCycles",results.length()).put("cycles",results).toString(2))
        }
    }
}
