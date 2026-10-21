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
