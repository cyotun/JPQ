package com.cyotun.jpq.vision

import android.app.Instrumentation
import android.app.KeyguardManager
import android.os.ParcelFileDescriptor
import com.cyotun.jpq.AppState
import com.cyotun.jpq.BuildConfig

/** Explicit app-only preflight. OEM background-start rules also apply to tests. */
internal fun launchStoppedJpqPage(instrument: Instrumentation, page: String) {
    check(!AppState.running) { "Do not launch UI tests during capture or a real match" }
    val context=instrument.targetContext
    check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
        "Unlock the test phone before running UI checks"
    }
    val packageName=context.packageName
    val candidate=BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE && packageName=="com.cyotun.jpq.candidate" &&
        instrument.context.packageName=="com.cyotun.jpq.candidate.tests"
    require(packageName in setOf("com.cyotun.jpq","com.cyotun.jpq.dev") || candidate)
    require(page in setOf("home","settings"))
    // Launch only our own page as the already-authorized USB test operator.
    // Do not grant the production app a background-launch exemption.
    val output=ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(
        "am start -W -n $packageName/com.cyotun.jpq.MainActivity -f 0x10008000 --es page $page"
    )).bufferedReader().use { it.readText() }
    check(!output.contains("Error:") && !output.contains("Exception")) { "App-only test launch failed: $output" }
    // am start -W can return while the previous Activity is still RESUMED.
    // Assert the requested page has actually appeared before callers inspect it.
    val deadline=android.os.SystemClock.uptimeMillis()+5000
    fun texts(view: android.view.View): List<android.widget.TextView> =
        (if(view is android.widget.TextView) listOf(view) else emptyList()) +
            (if(view is android.view.ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())
    while(android.os.SystemClock.uptimeMillis()<deadline) {
        var ready=false
        instrument.runOnMainSync {
            val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .filterIsInstance<com.cyotun.jpq.MainActivity>().singleOrNull()
            val expected=if(page=="home") "开始记牌" else "三家出牌显示"
            ready=activity?.let { texts(it.window.decorView).any { text -> text.text.toString()==expected && text.width>0 } }==true
        }
        if(ready) return
        android.os.SystemClock.sleep(30)
    }
    error("Requested stopped page did not become visible: $page")
}

internal fun launchStoppedLandscapeHost(instrument: Instrumentation) {
    check(!AppState.running) { "Do not launch UI tests during capture or a real match" }
    val context=instrument.targetContext
    check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
    val packageName=instrument.context.packageName
    val candidate=BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE && context.packageName=="com.cyotun.jpq.candidate" &&
        packageName=="com.cyotun.jpq.candidate.tests"
    require((packageName=="com.cyotun.jpq.tests" && context.packageName in setOf("com.cyotun.jpq","com.cyotun.jpq.dev")) || candidate)
    ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(
        "am start -W -n $packageName/com.cyotun.jpq.vision.LandscapeProbeActivity -f 0x10008000"
    )).bufferedReader().use { it.readText() }
    val deadline=android.os.SystemClock.uptimeMillis()+3000
    while(android.os.SystemClock.uptimeMillis()<deadline) {
        val bounds=context.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        if(bounds.width()>bounds.height()) return
        android.os.SystemClock.sleep(30)
    }
    error("Test-only landscape host did not rotate the display")
}
