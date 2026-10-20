package com.cyotun.jpq.vision

import android.content.Intent
import android.os.Build
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in emulator-only handshake for capturing a selected phone-probe task under real overlays. */
@RunWith(AndroidJUnit4::class)
class EmulatorTaskCaptureTest {
    @Test(timeout=480000) fun captureSelectedEmulatorTaskAcrossOverlayStages() {
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e emulatorTaskCapture true to run this lab test",
            arguments.getString("emulatorTaskCapture")=="true")
        assumeTrue("This test is restricted to an Android emulator", isEmulator())

        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        check(BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE && context.packageName=="com.cyotun.jpq.candidate") {
            "Run only in the single-app candidate package"
        }
        check(Build.VERSION.SDK_INT>=37) { "Single-app task capture requires API 37 or later" }
        check(!AppState.running) { "Start with candidate capture stopped" }

        val external=checkNotNull(context.getExternalFilesDir(null)) { "External files directory unavailable" }
        val root=File(external,"emulator-task-capture").apply { check(isDirectory || mkdirs()) }
        val runId=UUID.randomUUID().toString()
        val runDir=File(root,"run-$runId").apply { check(isDirectory || mkdirs()) }
        val runStartedWall=System.currentTimeMillis()
        val runStartedElapsed=SystemClock.elapsedRealtime()
        val preferences=context.getSharedPreferences("jpq-diagnostics",0)
        val probes=File(context.filesDir,"capture-probes")
        val usedProbeIds=linkedSetOf<String>()
        val stages=JSONArray()
        val summary=JSONObject().put("schemaVersion",1).put("runId",runId)
            .put("test","EmulatorTaskCaptureTest")
            .put("targetPackage",context.packageName)
            .put("captureTargetPackage","com.cyotun.jpq.tests")
            .put("captureScope","APP_TASK")
            .put("sdkInt",Build.VERSION.SDK_INT).put("buildFingerprint",Build.FINGERPRINT)
            .put("buildModel",Build.MODEL).put("runStartedWallMs",runStartedWall)
            .put("runStartedElapsedMs",runStartedElapsed).put("stages",stages)
        File(runDir,"run.json").writeText(summary.toString(2),Charsets.UTF_8)

        fun stopCapture() {
            if(AppState.running) instrument.runOnMainSync {
                context.startService(Intent(context,CaptureService::class.java).setAction("STOP"))
            }
        }

        fun assertCaptureHealthy() {
            check(AppState.running) { "Candidate capture stopped during the test" }
            check(AppState.captureScope==CaptureScope.APP_TASK) { "Capture scope changed from APP_TASK" }
            check(AppState.recognitionReady) { "Recognition stopped being ready" }
            check(AppState.capturedWidth>AppState.capturedHeight) { "Captured task is not landscape" }
        }

        fun awaitInitialCapture() {
            val deadline=SystemClock.elapsedRealtime()+INITIAL_CAPTURE_TIMEOUT_MS
            var trackingReadyFrames=false
            var readyFrames=0L
            var lastFrameCount=-1L
            var lastProgressAt=SystemClock.elapsedRealtime()
            while(SystemClock.elapsedRealtime()<deadline) {
                if(AppState.running && AppState.captureScope!=CaptureScope.APP_TASK) {
                    error("Candidate started capture outside APP_TASK scope")
                }
                val ready=AppState.running && AppState.captureScope==CaptureScope.APP_TASK &&
                    AppState.recognitionReady && AppState.capturedWidth>AppState.capturedHeight
                val now=SystemClock.elapsedRealtime()
                if(ready) {
                    if(!trackingReadyFrames) {
                        trackingReadyFrames=true
                        readyFrames=AppState.capturedFrames
                        lastFrameCount=readyFrames
                        lastProgressAt=now
                    }
                    val frames=AppState.capturedFrames
                    if(frames>lastFrameCount) {
                        lastFrameCount=frames
                        lastProgressAt=now
                    }
                    if(frames-readyFrames>=MIN_STABLE_FRAMES) return
                    if(now-lastProgressAt>NO_FRAME_PROGRESS_TIMEOUT_MS) {
                        error("Candidate capture did not produce continuous frames")
                    }
                } else trackingReadyFrames=false
                SystemClock.sleep(POLL_INTERVAL_MS)
            }
            error("Timed out waiting for authorized APP_TASK recognition and landscape frames")
        }

        fun maskJson()=checkNotNull(AppState.barMask) { "Real candidate counter bar is not present" }.let { mask ->
            JSONObject().put("screenWidth",mask.screenWidth).put("screenHeight",mask.screenHeight)
                .put("rectangle",JSONArray(listOf(mask.rectangle.left,mask.rectangle.top,
                    mask.rectangle.right,mask.rectangle.bottom)))
        }

        fun dimensionsJson()=JSONObject().put("capturedWidth",AppState.capturedWidth)
            .put("capturedHeight",AppState.capturedHeight)
            .put("sourceWidth",AppState.sourceWidth).put("sourceHeight",AppState.sourceHeight)

        fun parseControl(raw: String,token: String): JSONObject? {
            if(raw.trim()==token) return JSONObject().put("token",token)
            return try { JSONObject(raw).takeIf { it.optString("token")==token } }
            catch(_: Exception) { null }
        }

        fun awaitStage(stage: String) {
            check(stage in STAGES)
            assertCaptureHealthy()
            val stageStartedWall=System.currentTimeMillis()
            val stageStartedElapsed=SystemClock.elapsedRealtime()
            val stageDeadline=stageStartedElapsed+STAGE_TIMEOUT_MS
            val token=UUID.randomUUID().toString()
            val control=File(root,"control-$stage")
            val ready=JSONObject().put("runId",runId).put("stage",stage)
                .put("controlPath",control.absolutePath).put("controlToken",token)
                .put("stageStartedWallMs",stageStartedWall)
                .put("barMask",maskJson()).put("dimensions",dimensionsJson())
                .put("capturedFrames",AppState.capturedFrames)
                .put("instruction","Host: verify this visible stage and take the synchronized physical screenshot, then write controlToken to controlPath.")
            File(runDir,"awaiting-$stage.json").writeText(ready.toString(2),Charsets.UTF_8)

            var lastFrames=AppState.capturedFrames
            var lastProgressAt=SystemClock.elapsedRealtime()
            var signal: JSONObject?=null
            while(SystemClock.elapsedRealtime()<stageDeadline) {
                assertCaptureHealthy()
                val now=SystemClock.elapsedRealtime()
                val currentFrames=AppState.capturedFrames
                if(currentFrames>lastFrames) { lastFrames=currentFrames; lastProgressAt=now }
                if(now-lastProgressAt>NO_FRAME_PROGRESS_TIMEOUT_MS) {
                    error("Capture frames stalled while waiting for stage $stage")
                }
                if(control.isFile) {
                    signal=parseControl(control.readText(Charsets.UTF_8),token)
                    if(signal!=null) break
                }
                SystemClock.sleep(POLL_INTERVAL_MS)
            }
            check(signal!=null) { "Timed out waiting for host control-$stage" }

            assertCaptureHealthy()
            // Surface/BufferQueue timestamps use CLOCK_MONOTONIC. Exclude a buffer
            // produced before the host made this stage visible, even if copied later.
            val stageSignalMonotonicNs=System.nanoTime()
            val beforeSettling=AppState.capturedFrames
            while(AppState.capturedFrames<beforeSettling+2 && SystemClock.elapsedRealtime()<stageDeadline) {
                assertCaptureHealthy()
                SystemClock.sleep(POLL_INTERVAL_MS)
            }
            check(AppState.capturedFrames>=beforeSettling+2) { "No fresh frames after stage $stage signal" }
            val armWall=System.currentTimeMillis()
            val armElapsed=SystemClock.elapsedRealtime()
            val priorProbeDirs=probes.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSet()
            check(preferences.edit().putInt("captureProbeRemaining",1).commit()) {
                "Could not arm exactly one capture probe for $stage"
            }
            var completedProbe: File?=null
            var probeRecord: JSONObject?=null
            lastFrames=AppState.capturedFrames
            lastProgressAt=SystemClock.elapsedRealtime()
            while(SystemClock.elapsedRealtime()<stageDeadline) {
                assertCaptureHealthy()
                val now=SystemClock.elapsedRealtime()
                val currentFrames=AppState.capturedFrames
                if(currentFrames>lastFrames) { lastFrames=currentFrames; lastProgressAt=now }
                if(now-lastProgressAt>NO_FRAME_PROGRESS_TIMEOUT_MS) {
                    error("Capture frames stalled while saving stage $stage")
                }
                val fresh=probes.listFiles().orEmpty().filter { folder ->
                    folder.isDirectory && folder.name !in priorProbeDirs && folder.name !in usedProbeIds &&
                        File(folder,"converted.png").isFile && File(folder,"probe.json").isFile
                }
                val matching=fresh.mapNotNull { folder ->
                    val record=try { JSONObject(File(folder,"probe.json").readText(Charsets.UTF_8)) }
                        catch(_: Exception) { null }
                    if(record!=null && record.optLong("wallTimeMs",0L)>=armWall &&
                        record.optLong("imageTimestampNs",0L)>=stageSignalMonotonicNs &&
                        record.optString("probeId")==folder.name) folder to record else null
                }
                if(matching.size>1) error("More than one fresh completed probe appeared for stage $stage")
                if(matching.size==1) {
                    completedProbe=matching.single().first
                    probeRecord=matching.single().second
                    break
                }
                SystemClock.sleep(POLL_INTERVAL_MS)
            }
            val probeDir=checkNotNull(completedProbe) { "Timed out waiting for a new complete probe for stage $stage" }
            val probe=checkNotNull(probeRecord)
            val probeId=probe.getString("probeId")
            check(usedProbeIds.add(probeId)) { "Probe id was reused across stages" }
            check(probe.getJSONObject("files").optString("converted")=="converted.png")
            check(File(probeDir,"converted.png").length()>0L)
            check(preferences.getInt("captureProbeRemaining",-1)==0) {
                "Probe arm was not consumed exactly once for stage $stage"
            }
            assertCaptureHealthy()

            val image=probe.getJSONObject("image")
            val result=JSONObject().put("schemaVersion",1).put("runId",runId).put("stage",stage)
                .put("controlPath",control.absolutePath).put("controlToken",token)
                .put("hostControl",signal).put("stageStartedWallMs",stageStartedWall)
                .put("probeArmedWallMs",armWall).put("probeArmedElapsedMs",armElapsed)
                .put("stageSignalMonotonicNs",stageSignalMonotonicNs)
                .put("probeId",probeId).put("probePath",probeDir.absolutePath)
                .put("probeWallTimeMs",probe.optLong("wallTimeMs"))
                .put("probeElapsedMs",probe.optLong("elapsedMs"))
                .put("imageTimestampNs",probe.optLong("imageTimestampNs"))
                .put("imageDimensions",JSONObject().put("width",image.optInt("width"))
                    .put("height",image.optInt("height")))
                .put("barMask",maskJson()).put("dimensions",dimensionsJson())
                .put("capturedFrames",AppState.capturedFrames)
                .put("recognitionReady",AppState.recognitionReady)
                .put("captureScope",AppState.captureScope.name)
                .put("files",JSONObject().put("converted",File(probeDir,"converted.png").absolutePath)
                    .put("probe",File(probeDir,"probe.json").absolutePath))
            File(runDir,"stage-$stage.json").writeText(result.toString(2),Charsets.UTF_8)
            stages.put(result)
            summary.put("lastStage",stage).put("stageCount",stages.length())
            File(runDir,"run.json").writeText(summary.toString(2),Charsets.UTF_8)
            android.util.Log.i(TAG,"stage_complete run_id=$runId stage=$stage probe_id=$probeId path=${probeDir.absolutePath}")
        }

        try {
            check(preferences.edit().putInt("captureProbeRemaining",0).commit())
            awaitInitialCapture()
            summary.put("captureReadyWallMs",System.currentTimeMillis())
                .put("initialCapturedFrames",AppState.capturedFrames)
            File(runDir,"run.json").writeText(summary.toString(2),Charsets.UTF_8)
            android.util.Log.i(TAG,"run_ready run_id=$runId run_dir=${runDir.absolutePath} control_dir=${root.absolutePath}")

            STAGES.forEach(::awaitStage)

            summary.put("completed",true).put("completedWallMs",System.currentTimeMillis())
                .put("completedElapsedMs",SystemClock.elapsedRealtime())
            stopCapture()
            waitForStopped()
            val drained=CountDownLatch(1)
            VisionRuntime.handler.post { drained.countDown() }
            check(drained.await(STOP_TIMEOUT_MS,TimeUnit.MILLISECONDS)) { "Vision worker barrier timed out" }
            File(runDir,"diagnostics.zip").outputStream().use { DiagnosticArchive.write(context,it) }
            summary.put("appStopped",true).put("diagnosticsZip",File(runDir,"diagnostics.zip").absolutePath)
                .put("completed",true)
            File(runDir,"summary.json").writeText(summary.toString(2),Charsets.UTF_8)
            File(runDir,"run.json").writeText(summary.toString(2),Charsets.UTF_8)
        } finally {
            try {
                stopCapture()
                waitForStopped()
            } finally {
                preferences.edit().putInt("captureProbeRemaining",0).commit()
            }
        }
    }

    private fun waitForStopped() {
        val deadline=SystemClock.elapsedRealtime()+STOP_TIMEOUT_MS
        while(SystemClock.elapsedRealtime()<deadline) {
            if(!AppState.running && !AppState.recognitionReady) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error("Candidate capture did not stop normally")
    }

    private fun isEmulator(): Boolean = listOf(Build.FINGERPRINT,Build.MODEL,Build.PRODUCT,Build.DEVICE)
        .any { value -> value.contains("generic",true) || value.contains("emulator",true) ||
            value.contains("sdk_gphone",true) }

    private companion object {
        const val TAG="JPQEmulatorTaskCapture"
        const val INITIAL_CAPTURE_TIMEOUT_MS=180_000L
        const val STAGE_TIMEOUT_MS=90_000L
        const val STOP_TIMEOUT_MS=8_000L
        const val NO_FRAME_PROGRESS_TIMEOUT_MS=5_000L
        const val POLL_INTERVAL_MS=100L
        const val MIN_STABLE_FRAMES=12L
        val STAGES=listOf("own","other","notification")
    }
}
