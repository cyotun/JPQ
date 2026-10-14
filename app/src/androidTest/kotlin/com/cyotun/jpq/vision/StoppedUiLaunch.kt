package com.cyotun.jpq.vision

import android.app.Instrumentation
import android.app.KeyguardManager
import android.os.ParcelFileDescriptor
import com.cyotun.jpq.AppState

/** Explicit app-only preflight. OEM background-start rules also apply to tests. */
internal fun launchStoppedJpqPage(instrument: Instrumentation, page: String) {
    check(!AppState.running) { "Do not launch UI tests during capture or a real match" }
    val context=instrument.targetContext
    check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
        "Unlock the test phone before running UI checks"
    }
    val packageName=context.packageName
    require(packageName in setOf("com.cyotun.jpq","com.cyotun.jpq.dev"))
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
    require(packageName=="com.cyotun.jpq.tests")
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
