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
import com.cyotun.jpq.core.Card
import com.cyotun.jpq.core.GlyphBox
import com.cyotun.jpq.core.PlayGlyph
import com.cyotun.jpq.core.Rank
import com.cyotun.jpq.core.Suit
import com.cyotun.jpq.core.Seat
import com.cyotun.jpq.core.partitionGuandanHand
import com.cyotun.jpq.core.assignPlayGroups
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
    @Test(timeout=240000) fun sampleStableTaskContentWhileOverlaysRemainVisible() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("candidateLobbySampling")=="true")
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        check(BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE && context.packageName=="com.cyotun.jpq.candidate")
        check(!AppState.running) { "Start this lab only with capture stopped and no match in progress" }
        val diagnosticsArgument=InstrumentationRegistry.getArguments().getString("candidateDiagnostics") ?: "true"
        require(diagnosticsArgument in setOf("true","false"))
        val diagnosticsEnabled=diagnosticsArgument=="true"
        val fixtureArgument=InstrumentationRegistry.getArguments().getString("expectedFixtureCase")
        val fixtureCase=fixtureArgument?.toIntOrNull()
        val samplingRunNonce=InstrumentationRegistry.getArguments().getString("samplingRunNonce")
        if(fixtureCase!=null) require(samplingRunNonce!=null &&
            runCatching { java.util.UUID.fromString(samplingRunNonce).toString()==samplingRunNonce }.getOrDefault(false))
        val refreshTaskSurface=InstrumentationRegistry.getArguments().getString("refreshTaskSurface")=="true"
        require(!refreshTaskSurface || fixtureCase!=null)
        val expectStartupRecovery=InstrumentationRegistry.getArguments().getString("expectStartupRecovery")=="true"
        require(!expectStartupRecovery || (fixtureCase!=null && !refreshTaskSurface))
        if(fixtureArgument!=null) {
            require(fixtureCase in 1..4)
            check(diagnosticsEnabled)
            check(listOf(android.os.Build.FINGERPRINT,android.os.Build.MODEL,android.os.Build.PRODUCT)
                .any { it.contains("emulator",true) || it.contains("sdk_gphone",true) || it.contains("generic",true) }) {
                "Deterministic fixture capture assertions are emulator-only; never run them during a real match"
            }
        }
        val holdMs=if(fixtureCase!=null) 10000L else 30000L
        val prefs=context.getSharedPreferences("jpq-diagnostics",0)
        val originalDiagnosticsPresent=prefs.contains("enabled")
        val originalDiagnosticsEnabled=prefs.getBoolean("enabled",false)
        check(prefs.edit().putBoolean("enabled",diagnosticsEnabled).putInt("captureProbeRemaining",0).commit())
        val probes=File(context.filesDir,"capture-probes")
        val existing=probes.listFiles().orEmpty().map { it.name }.toSet()
        val sessions=File(context.filesDir,"sessions")
        val existingSessions=sessions.listFiles().orEmpty().map { it.name }.toSet()
        val record=JSONObject().put("targetPackage",context.packageName)
            .put("scope",if(fixtureCase!=null) "emulator_card_fixture_capture_to_model" else "non_game_real_lobby_pixel_isolation_only")
            .put("diagnosticsEnabled",diagnosticsEnabled)
        val output=File(context.getExternalFilesDir(null),"phone-preflight").apply { mkdirs() }
        fun exportEvidence() {
            val target=File(output,"candidate-isolation-sampling.zip")
            if(fixtureCase==null) {
                target.outputStream().use { DiagnosticArchive.write(context,it) }
                return
            }
            // Fixture loops must not repeatedly zip all historical private rounds
            // and old PNGs. Export only closed files belonging to this new run.
            val files=mutableListOf<Pair<String,File>>()
            val freshSessions=sessions.listFiles().orEmpty().filter { it.name !in existingSessions }
            for(folder in freshSessions) for(file in folder.walkTopDown().filter { it.isFile })
                files+="sessions/${folder.name}/${file.relativeTo(folder).invariantSeparatorsPath}" to file
            for(folder in probes.listFiles().orEmpty().filter { it.name !in existing && File(it,"probe.json").isFile }) {
                val modelComplete=File(folder,"model-detections.json").isFile
                for(name in listOf("probe.json","legacy.png","converted.png")+
                    if(modelComplete) listOf("model-detections.json","model-input.png","masked.png") else emptyList()) {
                    File(folder,name).takeIf { it.isFile }?.let { files+="capture-probes/${folder.name}/$name" to it }
                }
            }
            val freshIds=freshSessions.map { it.name }.toSet()
            val diagnostic=File(context.filesDir,"diagnostic")
            for(sidecar in diagnostic.listFiles().orEmpty().filter { it.extension=="json" }) {
                val metadata=runCatching { JSONObject(sidecar.readText()) }.getOrNull() ?: continue
                if(metadata.optString("sessionId") !in freshIds) continue
                val image=File(diagnostic,metadata.getString("imageFile"))
                if(image.isFile && image.canonicalFile.parentFile==diagnostic.canonicalFile) {
                    files+="diagnostic/${sidecar.name}" to sidecar
                    files+="diagnostic/${image.name}" to image
                }
            }
            check(files.sumOf { it.second.length() }<=128L*1024*1024) { "Fixture evidence exceeded its bounded archive" }
            java.util.zip.ZipOutputStream(target.outputStream().buffered()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("summary.json"));zip.write(record.toString(2).toByteArray());zip.closeEntry()
                for((name,file) in files) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name));file.inputStream().use { it.copyTo(zip) };zip.closeEntry()
                }
            }
        }
        File(output,"candidate-sampling-ready.json").writeText(JSONObject()
            .put("runId",java.util.UUID.randomUUID().toString()).put("readyWallMs",System.currentTimeMillis())
            .put("samplingRunNonce",samplingRunNonce ?: JSONObject.NULL)
            .put("expectedFixtureCase",fixtureCase ?: JSONObject.NULL).toString())
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
            if(expectStartupRecovery) {
                val trace=sessions.listFiles().orEmpty().filter { it.name !in existingSessions }
                    .map { File(it,"trace.jsonl") }.single { it.isFile }
                val settledSince=SystemClock.elapsedRealtime()
                var lines=emptyList<JSONObject>()
                await("fresh flushed landscape model frame after task settling",20000) {
                    lines=trace.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                    lines.lastOrNull { it.optString("type")=="frame" && it.optString("detectorProfile")=="FULL" }
                        ?.optLong("elapsedMs",0L)?.let { it>=settledSince }==true
                }
                if(lines.none { it.optString("type")=="task_startup_input_recovery" }) {
                    val last=lines.last { it.optString("type")=="frame" }
                    val healthy=last.optJSONArray("hand")?.length()==listOf(27,25,26,25)[fixtureCase!!-1]
                    record.put("faultTriggerStatus",if(healthy) "not_reproduced_healthy" else "unrecovered_startup_input")
                    check(healthy) { "Startup input remained broken without an automatic recovery request" }
                    error("No corrupted startup input occurred; automatic recovery was not exercised")
                }
            }
            if(refreshTaskSurface) {
                val trace=sessions.listFiles().orEmpty().filter { it.name !in existingSessions }
                    .map { File(it,"trace.jsonl") }.single { it.isFile }
                val prior=trace.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                    .last { it.optString("type")=="frame" }
                check(prior.optJSONArray("hand")?.length()==0) { "Recovery experiment requires reproduced missing hand pixels" }
                record.put("preRefreshHandCards",0).put("preRefreshScene",prior.getString("scene"))
                    .put("taskSurfaceRefreshExperiment",true)
                val before=AppState.capturedFrames
                instrument.runOnMainSync {
                    context.startService(Intent(context,CaptureService::class.java).setAction("LAB_REFRESH_TASK_SURFACE"))
                }
                await("new processed frames after same-display surface reattachment",20000) {
                    AppState.running && AppState.capturedFrames>=before+10
                }
                SystemClock.sleep(3000)
            }
            check(AppState.running && AppState.captureScope==CaptureScope.APP_TASK)
            val started=SystemClock.elapsedRealtime()
            val frames=AppState.capturedFrames
            val mask=checkNotNull(AppState.barMask) { "A real visible counter overlay is required" }
            record.put("sampleStartedElapsedMs",started).put("sampleStartedWallMs",System.currentTimeMillis())
                .put("counterScreenWidth",mask.screenWidth).put("counterScreenHeight",mask.screenHeight)
                .put("counterBounds",JSONArray(listOf(mask.rectangle.left,mask.rectangle.top,mask.rectangle.right,mask.rectangle.bottom)))
            if(diagnosticsEnabled) {
                check(prefs.edit().putInt("captureProbeRemaining",3).commit())
                await("three fresh raw task samples",if(fixtureCase!=null) 60000L else 15000L) {
                    probes.listFiles().orEmpty().count { it.name !in existing && File(it,"probe.json").isFile }==3
                }
            }
            record.put("probeIds",JSONArray(probes.listFiles().orEmpty().filter { it.name !in existing }.map { it.name }.sorted()))
            // Keep both real overlays on screen long enough for the host's independent physical comparison.
            await("read-only physical comparison interval",if(fixtureCase!=null) 45000L else 32000L) {
                SystemClock.elapsedRealtime()-started>=holdMs && AppState.capturedFrames>frames+10
            }
            check(AppState.running && AppState.capturedFrames>frames+10)
            record.put("sampleEndedElapsedMs",SystemClock.elapsedRealtime()).put("observedFrames",AppState.capturedFrames-frames)
            stop()
            await("normal service stop",8000) { !AppState.running && !AppState.recognitionReady }
            val drained=CountDownLatch(1)
            VisionRuntime.handler.post { drained.countDown() }
            check(drained.await(8,TimeUnit.SECONDS))
            // Capture proof and model proof must belong to the same Image timestamp.
            // A picture-rich PNG or a running callback counter is not recognition proof.
            var fixtureFailure: Throwable?=null
            if(fixtureCase!=null) try {
                val checked=JSONArray()
                val folders=probes.listFiles().orEmpty().filter { it.name !in existing }
                // LatestFrameDispatcher can retire a raw probe before model processing.
                // Require a paired raw/model image and independently verify every
                // production frame in the settled interval, rather than requiring
                // the dispatcher to retain a particular number of sampled frames.
                await("a complete same-frame model diagnostic file",10000) {
                    folders.count { folder -> runCatching {
                        val raw=JSONObject(File(folder,"probe.json").readText())
                        val model=JSONObject(File(folder,"model-detections.json").readText())
                        raw.getLong("imageTimestampNs")==model.getLong("imageTimestampNs")
                    }.getOrDefault(false) }>=1
                }
                for(folder in folders) {
                    val model=File(folder,"model-detections.json")
                    if(!model.isFile) continue // LatestFrameDispatcher can retire a sampled frame.
                    val raw=JSONObject(File(folder,"probe.json").readText())
                    val predicted=JSONObject(model.readText())
                    check(raw.getLong("imageTimestampNs")==predicted.getLong("imageTimestampNs"))
                    check(predicted.getString("detectorProfile")=="FULL")
                    val detections=predicted.getJSONArray("detections")
                    val glyphs=(0 until detections.length()).map { index ->
                        val item=detections.getJSONObject(index); val key=item.getString("card").split(":")
                        val box=item.getJSONArray("box")
                        PlayGlyph(Card(Rank.valueOf(key[0]),if(key.size==1) Suit.NONE else Suit.valueOf(key[1])),item.getDouble("score").toFloat(),
                            GlyphBox(box.getDouble(0).toFloat(),box.getDouble(1).toFloat(),box.getDouble(2).toFloat(),box.getDouble(3).toFloat()))
                    }.filter { it.box.cy>.13f && it.box.cx in .10f.. .88f &&
                        it.box.right-it.box.left<.12f && it.box.bottom-it.box.top>.02f }
                    val partition=partitionGuandanHand(glyphs)
                    check(partition.hand.size==listOf(27,25,26,25)[fixtureCase-1]) {
                        "Actual captured fixture $fixtureCase had ${partition.hand.size} hand cards, not its golden count"
                    }
                    val ownership=assignPlayGroups(partition.table,emptySet(),emptyList())
                    check(ownership.ambiguous.isEmpty())
                    when(fixtureCase) {
                        1 -> check(ownership.seats[Seat.NEXT]!!.map { it.card }==listOf(Card(Rank.FOUR,Suit.RED)))
                        2,4 -> { check(ownership.seats[Seat.PREVIOUS]!!.size==4);check(ownership.seats[Seat.OPPOSITE]!!.isEmpty()) }
                        3 -> {
                            check(ownership.seats[Seat.SELF]!!.map { it.card }==listOf(Card(Rank.K,Suit.RED)))
                            check(ownership.seats[Seat.OPPOSITE]!!.map { it.card }==listOf(Card(Rank.A,Suit.RED)))
                        }
                    }
                    checked.put(JSONObject().put("probeId",folder.name).put("imageTimestampNs",raw.getLong("imageTimestampNs"))
                        .put("handCards",partition.hand.size).put("rawDetectionCount",detections.length()))
                }
                record.put("expectedFixtureCase",fixtureCase).put("sameFrameGoldenChecks",checked)
                check(checked.length()>=1) { "At least one paired raw/model result is required" }
                val traces=sessions.listFiles().orEmpty().filter { it.name !in existingSessions }
                    .map { File(it,"trace.jsonl") }.filter { it.isFile }
                check(traces.size==1) { "Exactly one new capture session is required" }
                val productionChecks=JSONArray()
                val pairedTimestamps=(0 until checked.length()).map { checked.getJSONObject(it).getLong("imageTimestampNs") }.toSet()
                val pairedProductionChecks=mutableSetOf<Long>()
                val recoveryEvents=JSONArray()
                val allRecords=mutableListOf<JSONObject>()
                val capturedFrames=mutableMapOf<Long,JSONObject>()
                val boundRounds=mutableListOf<JSONObject>()
                traces.single().forEachLine { line ->
                    val frame=JSONObject(line)
                    allRecords+=frame
                    if(frame.optString("type")=="frame" && frame.has("imageTimestampNs"))
                        capturedFrames[frame.getLong("imageTimestampNs")]=frame
                    if(frame.optString("type")=="ledger" && !frame.isNull("roundId")) boundRounds+=frame
                    if(frame.optString("type")=="task_startup_input_recovery") {
                        check(frame.getString("captureScope")=="APP_TASK" && frame.getString("phase")=="WAITING")
                        check(frame.getInt("handCount")==0)
                        recoveryEvents.put(frame)
                    }
                    val insideWindow=frame.optLong("elapsedMs")>=started &&
                        frame.optLong("elapsedMs")<=record.getLong("sampleEndedElapsedMs")
                    val paired=frame.optLong("imageTimestampNs",0L) in pairedTimestamps
                    // Copying a current Image can straddle the instant probes are
                    // enabled. Validate that exact paired production frame too;
                    // never count it toward the ten-frame settled-window minimum.
                    if(frame.optString("type")=="frame" && (insideWindow || paired)) {
                        check(frame.getString("detectorProfile")=="FULL")
                        check(frame.getJSONArray("hand").length()==listOf(27,25,26,25)[fixtureCase-1])
                        check(frame.getInt("ambiguous")==0)
                        val groups=frame.getJSONObject("groups")
                        fun cards(seat: String)=groups.getJSONArray(seat).let { array ->
                            (0 until array.length()).map { array.getString(it) }
                        }
                        when(fixtureCase) {
                            1 -> check(cards("NEXT")==listOf("FOUR:RED"))
                            2,4 -> { check(cards("PREVIOUS").size==4); check(cards("OPPOSITE").isEmpty()) }
                            3 -> { check(cards("SELF")==listOf("K:RED")); check(cards("OPPOSITE")==listOf("A:RED")) }
                        }
                        if(insideWindow) productionChecks.put(frame.getLong("imageTimestampNs"))
                        if(paired) pairedProductionChecks+=frame.getLong("imageTimestampNs")
                    }
                }
                check(productionChecks.length()>=10) { "Ten actual production recognizer frames must match the golden fixture" }
                check(pairedProductionChecks.isNotEmpty()) { "A same-timestamp raw/model pair must also pass the production-frame golden checks" }
                record.put("productionGoldenFrames",productionChecks.length())
                record.put("pairedProductionGoldenFrames",pairedProductionChecks.size)
                record.put("startupInputRecoveryEvents",recoveryEvents)
                check(recoveryEvents.length()<=2)
                for(index in 0 until recoveryEvents.length()) {
                    val event=recoveryEvents.getJSONObject(index)
                    val captured=checkNotNull(capturedFrames[event.getLong("imageTimestampNs")])
                    check(captured.getString("scene")=="UNKNOWN" && captured.getJSONArray("hand").length()==0)
                    check(boundRounds.none { it.getLong("elapsedMs")<=event.getLong("elapsedMs") })
                    if(index>0) check(event.getLong("elapsedMs")-recoveryEvents.getJSONObject(index-1).getLong("elapsedMs")>=15000L)
                }
                if(expectStartupRecovery) check(recoveryEvents.length()>0) { "No corrupted startup input occurred; automatic recovery was not exercised" }
                if(!refreshTaskSurface) record.put("startupRecoveryServiceCycles",verifyStartupRecoverySequence(allRecords,expectStartupRecovery))
            } catch(error: Throwable) {
                fixtureFailure=error; record.put("fixtureFailure",error.toString())
            }
            File(output,"candidate-isolation-sampling.json").writeText(record.toString(2))
            exportEvidence()
            fixtureFailure?.let { throw it }
        } catch(error: Throwable) {
            record.put("testFailure",error.toString())
            stop()
            await("stop after failed sampling",8000) { !AppState.running && !AppState.recognitionReady }
            val drained=CountDownLatch(1)
            VisionRuntime.handler.post { drained.countDown() }
            check(drained.await(8,TimeUnit.SECONDS))
            File(output,"candidate-isolation-sampling.json").writeText(record.toString(2))
            exportEvidence()
            throw error
        } finally {
            stop()
            val restore=prefs.edit().putInt("captureProbeRemaining",0)
            if(originalDiagnosticsPresent) restore.putBoolean("enabled",originalDiagnosticsEnabled) else restore.remove("enabled")
            check(restore.commit())
        }
    }
}
