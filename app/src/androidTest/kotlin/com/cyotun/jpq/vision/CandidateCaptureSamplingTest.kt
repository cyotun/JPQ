package com.cyotun.jpq.vision

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.AppState
import com.cyotun.jpq.BuildConfig
import com.cyotun.jpq.CaptureService
import com.cyotun.jpq.DiagnosticArchive
import com.cyotun.jpq.core.CaptureScope
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit non-game lab run. The host completes normal system consent for the game lobby.
 * No second projection, screenshot, game input, or stable-package operation is performed here.
 */
@RunWith(AndroidJUnit4::class)
class CandidateCaptureSamplingTest {
    @Test(timeout=170000) fun sampleStableTaskContentWhileOverlaysRemainVisible() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("candidateLobbySampling")=="true")
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        check(BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE && context.packageName=="com.cyotun.jpq.candidate")
        check(!AppState.running) { "Start this lab only with capture stopped and no match in progress" }
        val prefs=context.getSharedPreferences("jpq-diagnostics",0)
        check(prefs.edit().putBoolean("enabled",true).putInt("captureProbeRemaining",0).commit())
        val probes=File(context.filesDir,"capture-probes")
        val existing=probes.listFiles().orEmpty().map { it.name }.toSet()
        val record=JSONObject().put("targetPackage",context.packageName)
            .put("scope","non_game_real_lobby_pixel_isolation_only")
        fun await(label: String,timeout: Long,condition: ()->Boolean) {
            val deadline=SystemClock.elapsedRealtime()+timeout
            while(SystemClock.elapsedRealtime()<deadline) {
                if(condition()) return
                SystemClock.sleep(50)
            }
            error("Timed out: $label")
        }
        fun stop() {
            if(AppState.running) instrument.runOnMainSync {
                context.startService(Intent(context,CaptureService::class.java).setAction("STOP"))
            }
        }
        try {
            await("host authorized real game task",95000) {
                AppState.running && AppState.recognitionReady && AppState.captureScope==CaptureScope.APP_TASK &&
                    AppState.capturedWidth>AppState.capturedHeight && AppState.capturedFrames>=10
            }
            // Exclude task-resume composition animations; do not spend the three raw probes on them.
            SystemClock.sleep(5000)
            check(AppState.running && AppState.captureScope==CaptureScope.APP_TASK)
            val started=SystemClock.elapsedRealtime()
            val frames=AppState.capturedFrames
            val mask=checkNotNull(AppState.barMask) { "A real visible counter overlay is required" }
            record.put("sampleStartedElapsedMs",started).put("sampleStartedWallMs",System.currentTimeMillis())
                .put("counterScreenWidth",mask.screenWidth).put("counterScreenHeight",mask.screenHeight)
                .put("counterBounds",JSONArray(listOf(mask.rectangle.left,mask.rectangle.top,mask.rectangle.right,mask.rectangle.bottom)))
            check(prefs.edit().putInt("captureProbeRemaining",3).commit())
            await("three fresh raw task samples",15000) {
                probes.listFiles().orEmpty().count { it.name !in existing && File(it,"probe.json").isFile }==3
            }
            record.put("probeIds",JSONArray(probes.listFiles().orEmpty().filter { it.name !in existing }.map { it.name }.sorted()))
            // Keep both real overlays on screen long enough for the host's independent physical comparison.
            await("read-only physical comparison interval",32000) {
                SystemClock.elapsedRealtime()-started>=30000
            }
            check(AppState.running && AppState.capturedFrames>frames+10)
            record.put("sampleEndedElapsedMs",SystemClock.elapsedRealtime()).put("observedFrames",AppState.capturedFrames-frames)
            stop()
            await("normal service stop",8000) { !AppState.running && !AppState.recognitionReady }
            val drained=CountDownLatch(1)
            VisionRuntime.handler.post { drained.countDown() }
            check(drained.await(8,TimeUnit.SECONDS))
            val output=File(context.getExternalFilesDir(null),"phone-preflight").apply { mkdirs() }
            File(output,"candidate-isolation-sampling.json").writeText(record.toString(2))
            File(output,"candidate-isolation-sampling.zip").outputStream().use { DiagnosticArchive.write(context,it) }
        } finally {
            stop()
            prefs.edit().putInt("captureProbeRemaining",0).commit()
        }
    }
}
