package com.cyotun.jpq.vision

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.AppState
import com.cyotun.jpq.BuildConfig
import com.cyotun.jpq.CaptureService
import com.cyotun.jpq.core.CaptureScope
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.BufferedOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Verifies that the candidate keeps sparse pixels for an unrecognised APP_TASK input. */
@RunWith(AndroidJUnit4::class)
class CandidateUnknownDiagnosticTest {
    @Test(timeout=210000) fun savesTwoSparseUnknownFramesFromOneNewTaskSession() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e candidateUnknownDiagnostic true to run this emulator-only test",
            args.getString("candidateUnknownDiagnostic")=="true")
        assumeTrue("This diagnostic test is emulator-only",isEmulator())

        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        check(BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE && context.packageName=="com.cyotun.jpq.candidate") {
            "Run only in the single-app candidate package"
        }
        check(Build.VERSION.SDK_INT>=37) { "APP_TASK capture requires API 37 or later" }
        check(!AppState.running) { "Candidate capture must be stopped before this test" }

        val prefs=context.getSharedPreferences("jpq-diagnostics",0)
        val previousEnabledPresent=prefs.contains("enabled")
        val previousEnabled=prefs.getBoolean("enabled",false)
        val previousProbeRemaining=prefs.getInt("captureProbeRemaining",0)
        val sessions=File(context.filesDir,"sessions")
        val oldSessionIds=sessions.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSet()
        val probes=File(context.filesDir,"capture-probes")
        val output=File(checkNotNull(context.getExternalFilesDir(null)),"phone-preflight").apply {
            check(isDirectory || mkdirs())
        }
        val runId=UUID.randomUUID().toString()
        val readyWallMs=System.currentTimeMillis()
        val report=JSONObject().put("schemaVersion",1).put("runId",runId)
            .put("test","CandidateUnknownDiagnosticTest")
            .put("targetPackage",context.packageName).put("captureTargetPackage","com.cyotun.jpq.tests")
            .put("captureScope","APP_TASK").put("sdkInt",Build.VERSION.SDK_INT)
            .put("buildFingerprint",Build.FINGERPRINT).put("buildModel",Build.MODEL)
            .put("readyWallMs",readyWallMs).put("diagnosticsEnabled",true)
            .put("scope","emulator_scope_probe_unknown_diagnostic_only")
        var failure: Throwable?=null
        var cleanupStopped=false

        fun saveReportAndArchive() {
            val reportFile=File(output,"candidate-unknown-diagnostic.json")
            reportFile.writeText(report.toString(2),Charsets.UTF_8)
            val sessionDirs=sessions.listFiles().orEmpty().filter {
                it.isDirectory && it.name !in oldSessionIds
            }
            val sessionIds=sessionDirs.map { it.name }.toSet()
            val evidence=mutableListOf<Pair<String,File>>()
            sessionDirs.forEach { directory ->
                listOf("trace.jsonl","capture-health.jsonl","capture-health.previous.jsonl").forEach { name ->
                    File(directory,name).takeIf { it.isFile }?.let { evidence += "sessions/${directory.name}/$name" to it }
                }
            }
            val diagnosticDir=File(context.filesDir,"diagnostic")
            diagnosticDir.listFiles().orEmpty().filter { it.isFile && it.extension=="json" }.forEach { sidecar ->
                val metadata=runCatching { JSONObject(sidecar.readText(Charsets.UTF_8)) }.getOrNull() ?: return@forEach
                val sessionId=metadata.optString("sessionId")
                if(sessionId !in sessionIds) return@forEach
                val imageName=metadata.optString("imageFile")
                val image=File(diagnosticDir,imageName)
                if(image.isFile && image.canonicalFile.parentFile==diagnosticDir.canonicalFile) {
                    evidence += "diagnostic/$imageName" to image
                    evidence += "diagnostic/${sidecar.name}" to sidecar
                }
            }
            val maxArchiveBytes=128L*1024*1024
            var copied=0L
            val included=JSONArray()
            val omitted=JSONArray()
            ZipOutputStream(BufferedOutputStream(FileOutputStream(File(output,"candidate-unknown-diagnostic.zip")))).use { zip ->
                fun addBytes(name: String,bytes: ByteArray) {
                    if(copied+bytes.size>maxArchiveBytes) { omitted.put(name); return }
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                    copied+=bytes.size; included.put(name)
                }
                fun addSnapshot(name: String,file: File) {
                    val snapshotLength=file.length().coerceAtLeast(0L)
                    if(copied+snapshotLength>maxArchiveBytes) { omitted.put(name); return }
                    zip.putNextEntry(ZipEntry(name))
                    var remaining=snapshotLength
                    FileInputStream(file).use { input ->
                        val buffer=ByteArray(32*1024)
                        while(remaining>0) {
                            val count=input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt())
                            if(count<0) break
                            zip.write(buffer,0,count); remaining-=count; copied+=count
                        }
                    }
                    zip.closeEntry(); included.put(name)
                }
                addBytes("summary.json",report.toString(2).toByteArray(Charsets.UTF_8))
                evidence.forEach { (name,file) -> addSnapshot(name,file) }
                addBytes("archive-manifest.json",JSONObject().put("runId",runId)
                    .put("maxBytes",maxArchiveBytes).put("bytesWritten",copied)
                    .put("included",included).put("omittedForSize",omitted).toString(2).toByteArray(Charsets.UTF_8))
            }
            report.put("archiveBytes",copied).put("archiveOmittedForSize",omitted)
            reportFile.writeText(report.toString(2),Charsets.UTF_8)
        }
        fun stopCapture() {
            if(AppState.running) instrument.runOnMainSync {
                context.startService(Intent(context,CaptureService::class.java).setAction("STOP"))
            }
        }
        fun waitStopped(timeoutMs: Long): Boolean {
            val deadline=SystemClock.elapsedRealtime()+timeoutMs
            while(SystemClock.elapsedRealtime()<deadline) {
                if(!AppState.running && !AppState.recognitionReady) return true
                SystemClock.sleep(100)
            }
            return !AppState.running && !AppState.recognitionReady
        }
        fun drainVisionWorker() {
            val drained=java.util.concurrent.CountDownLatch(1)
            VisionRuntime.handler.post { drained.countDown() }
            check(drained.await(8,java.util.concurrent.TimeUnit.SECONDS)) { "Vision worker barrier timed out" }
        }
        fun await(label: String,timeoutMs: Long,condition: ()->Boolean) {
            val deadline=SystemClock.elapsedRealtime()+timeoutMs
            while(SystemClock.elapsedRealtime()<deadline) {
                if(condition()) return
                SystemClock.sleep(100)
            }
            error("Timed out: $label")
        }

        try {
            // Reset only candidate-private raw probe state so no stale probe can be
            // mistaken for evidence from this session.
            check(prefs.edit().putBoolean("enabled",true).putInt("captureProbeRemaining",0).commit())
            if(probes.exists()) check(probes.deleteRecursively()) { "Could not clear candidate raw probes" }
            check(probes.isDirectory || probes.mkdirs())

            File(output,"candidate-unknown-diagnostic-ready.json").writeText(JSONObject()
                .put("runId",runId).put("readyWallMs",readyWallMs)
                .put("expectedCaptureTargetPackage","com.cyotun.jpq.tests")
                .put("expectedCaptureScope","APP_TASK").toString(),Charsets.UTF_8)

            await("new visible APP_TASK landscape capture with ten processed frames",65_000) {
                AppState.running && AppState.recognitionReady && AppState.captureScope==CaptureScope.APP_TASK &&
                    AppState.capturedWidth>AppState.capturedHeight && AppState.capturedFrames>=10
            }
            val captureReadyElapsed=SystemClock.elapsedRealtime()
            val dimensions=JSONObject().put("width",AppState.capturedWidth).put("height",AppState.capturedHeight)
            report.put("captureReadyElapsedMs",captureReadyElapsed).put("captureDimensions",dimensions)
                .put("initialProcessedFrames",AppState.capturedFrames)

            val diagnosticDir=File(context.filesDir,"diagnostic")
            val found=awaitUnknownDiagnostics(
                diagnosticDir=diagnosticDir,
                sessionsDir=sessions,
                oldSessionIds=oldSessionIds,
                timeoutMs=65_000,
                await=::await)
            report.put("diagnosticFrames",JSONObject()
                .put("frames",JSONArray(found.map { it.metadata }))
                .put("newSessionId",found.first().sessionId)
                .put("verifiedCount",found.size)
                .put("waitEndedElapsedMs",SystemClock.elapsedRealtime()))
                .put("verifiedDiagnosticCount",found.size)
            check(AppState.running && AppState.captureScope==CaptureScope.APP_TASK)
            stopCapture()
            cleanupStopped=waitStopped(8_000)
            check(cleanupStopped) { "Candidate service did not stop normally" }
            drainVisionWorker()
            awaitDiagnosticWritesSettled(File(context.filesDir,"diagnostic"),5_000)
            val trace=File(sessions,"${found.first().sessionId}/trace.jsonl")
            val records=trace.readLines().map { JSONObject(it) }
            report.put("startupRecoveryServiceCycles",verifyStartupRecoverySequence(records,requireRequest=true))
            // The helper deliberately stays UNKNOWN. This proves the bounded automatic
            // service path and fresh model delivery, not repair of a real geometry fault.
            report.put("startupRecoveryScope","long_unrecognised_input_service_smoke_not_geometry_repair")
            report.put("appStoppedNormally",true).put("success",true)
            saveReportAndArchive()
        } catch(error: Throwable) {
            failure=error
            report.put("success",false).put("failure",error.toString())
            runCatching {
                stopCapture()
                cleanupStopped=waitStopped(8_000)
                report.put("appStoppedNormally",cleanupStopped)
                if(cleanupStopped) drainVisionWorker()
            }.onFailure { report.put("cleanupFailure",it.toString()) }
            runCatching { saveReportAndArchive() }
                .onFailure { report.put("exportFailure",it.toString()); runCatching {
                    File(output,"candidate-unknown-diagnostic.json").writeText(report.toString(2),Charsets.UTF_8)
                } }
        } finally {
            runCatching { stopCapture() }
            val restore=prefs.edit().putInt("captureProbeRemaining",0)
            if(previousEnabledPresent) restore.putBoolean("enabled",previousEnabled) else restore.remove("enabled")
            check(restore.commit()) { "Could not restore diagnostics preference" }
            // The previous probe count is recorded for audit, but stale raw probe
            // evidence is intentionally cleared as part of this isolated test.
            report.put("previousCaptureProbeRemaining",previousProbeRemaining).put("rawProbesCleared",true)
            runCatching { File(output,"candidate-unknown-diagnostic.json").writeText(report.toString(2),Charsets.UTF_8) }
        }
        failure?.let { throw it }
    }

    private data class VerifiedDiagnostic(val sessionId: String,val metadata: JSONObject)

    private fun awaitUnknownDiagnostics(
        diagnosticDir: File,
        sessionsDir: File,
        oldSessionIds: Set<String>,
        timeoutMs: Long,
        await: (String,Long,()->Boolean)->Unit
    ): List<VerifiedDiagnostic> {
        var result=emptyList<VerifiedDiagnostic>()
        var cachedTracePath:String?=null
        var cachedTraceLength=-1L
        var cachedTraceModified=-1L
        var cachedTraceFrames:Map<Long,JSONObject>?=null
        val verifiedCache=mutableMapOf<String,Pair<String,VerifiedDiagnostic>>()
        await("two complete UNKNOWN diagnostic JPEG/metadata pairs from the new session",timeoutMs) {
            val newSessionDirs=sessionsDir.listFiles().orEmpty().filter {
                it.isDirectory && it.name !in oldSessionIds && File(it,"trace.jsonl").isFile
            }
            if(newSessionDirs.size>1) error("More than one new capture session appeared")
            if(newSessionDirs.size!=1) return@await false
            val sessionDir=newSessionDirs.single()
            val traceFile=File(sessionDir,"trace.jsonl")
            val traceLength=traceFile.length(); val traceModified=traceFile.lastModified()
            if(cachedTracePath!=traceFile.absolutePath || cachedTraceLength!=traceLength || cachedTraceModified!=traceModified) {
                cachedTracePath=traceFile.absolutePath; cachedTraceLength=traceLength; cachedTraceModified=traceModified
                cachedTraceFrames=readUnknownFrames(traceFile,sessionDir.name)
            }
            val traceFrames=cachedTraceFrames ?: return@await false
            val metadataFiles=diagnosticDir.listFiles().orEmpty().filter { it.isFile && it.extension=="json" }
            val valid=metadataFiles.mapNotNull { sidecar ->
                runCatching {
                    val metadata=JSONObject(sidecar.readText(Charsets.UTF_8))
                    if(metadata.optString("sessionId")!=sessionDir.name || metadata.optString("scene")!="UNKNOWN" ||
                        metadata.optString("captureScope")!="APP_TASK" || metadata.optLong("minimumIntervalMs")!=15_000L) return@runCatching null
                    val imageName=metadata.getString("imageFile")
                    if(imageName!=sidecar.nameWithoutExtension+".jpg") return@runCatching null
                    val image=File(diagnosticDir,imageName)
                    if(!image.isFile || image.canonicalFile.parentFile!=diagnosticDir.canonicalFile) return@runCatching null
                    val signature="${sidecar.length()}:${sidecar.lastModified()}:${image.length()}:${image.lastModified()}"
                    verifiedCache[sidecar.name]?.takeIf { it.first==signature }?.second?.let { return@runCatching it }
                    val sourceElapsed=metadata.getLong("sourceElapsedMs")
                    val timestamp=metadata.getLong("imageTimestampNs")
                    if(sourceElapsed<=0L || timestamp<=0L || metadata.getInt("width")<=0 || metadata.getInt("height")<=0) return@runCatching null
                    if(sha256(image)!=metadata.getString("imageSha256")) return@runCatching null
                    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                    BitmapFactory.decodeFile(image.absolutePath,bounds)
                    if(bounds.outWidth!=metadata.getInt("width") || bounds.outHeight!=metadata.getInt("height")) return@runCatching null
                    val frame=traceFrames[timestamp] ?: return@runCatching null
                    if(frame.optLong("elapsedMs")!=sourceElapsed || frame.optString("scene")!="UNKNOWN" ||
                        frame.optString("detectorProfile")!="FULL" || frame.optInt("rawDetectionCount",-1)<0 ||
                        metadata.optInt("rawDetectionCount",-2)!=frame.optInt("rawDetectionCount",-1) ||
                        metadata.optInt("handCount",-2)!=(frame.optJSONArray("hand")?.length() ?: -3) ||
                        metadata.optInt("width",-1)!=frame.optInt("width",-2) ||
                        metadata.optInt("height",-1)!=frame.optInt("height",-2) ||
                        !frame.has("inferenceMs") || frame.optDouble("inferenceMs",-1.0)<0.0) return@runCatching null
                    VerifiedDiagnostic(sessionDir.name,metadata).also { verifiedCache[sidecar.name]=signature to it }
                }.getOrNull()
            }.sortedBy { it.metadata.getLong("sourceElapsedMs") }
            if(valid.size<2) return@await false
            if(valid.zipWithNext().any { (a,b) ->
                    b.metadata.getLong("sourceElapsedMs")<=a.metadata.getLong("sourceElapsedMs") ||
                        b.metadata.getLong("imageTimestampNs")<=a.metadata.getLong("imageTimestampNs")
                }) error("UNKNOWN diagnostic source timestamps are not strictly increasing")
            result=valid
            true
        }
        return result
    }

    private fun readUnknownFrames(trace: File,expectedSessionId: String): Map<Long,JSONObject>? {
        val frames=linkedMapOf<Long,JSONObject>()
        var sessionSeen=false
        return try {
            for(line in trace.readLines(Charsets.UTF_8)) {
                val item=JSONObject(line)
                when(item.optString("type")) {
                    "session" -> {
                        if(item.optString("captureScope")!="APP_TASK" ||
                            item.optString("applicationId")!="com.cyotun.jpq.candidate" ||
                            item.optString("sessionId")!=expectedSessionId) return null
                        sessionSeen=true
                    }
                    "frame" -> if(item.optString("scene")=="UNKNOWN") {
                        // Portrait/partial frames have no model or matching JPEG.
                        // They must not poison later complete landscape evidence.
                        if(!item.has("detectorProfile")) continue
                        val timestamp=item.optLong("imageTimestampNs",0L)
                        if(timestamp<=0L || item.optString("detectorProfile")!="FULL") return null
                        frames[timestamp]=item
                    }
                }
            }
            if(sessionSeen) frames else null
        } catch(_: Exception) { null }
    }

    private fun sha256(file: File): String {
        val digest=MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer=ByteArray(32*1024)
            while(true) {
                val count=input.read(buffer)
                if(count<0) break
                digest.update(buffer,0,count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun awaitDiagnosticWritesSettled(directory: File,timeoutMs: Long) {
        val deadline=SystemClock.elapsedRealtime()+timeoutMs
        var prior=directory.listFiles().orEmpty().filter { it.isFile }.associate { it.name to (it.length() to it.lastModified()) }
        var stableSince=SystemClock.elapsedRealtime()
        while(SystemClock.elapsedRealtime()<deadline) {
            SystemClock.sleep(100)
            val current=directory.listFiles().orEmpty().filter { it.isFile }.associate { it.name to (it.length() to it.lastModified()) }
            if(current==prior) {
                if(SystemClock.elapsedRealtime()-stableSince>=600L) return
            } else {
                prior=current
                stableSince=SystemClock.elapsedRealtime()
            }
        }
        error("Diagnostic writes did not settle before export")
    }

    private fun isEmulator(): Boolean = listOf(Build.FINGERPRINT,Build.MODEL,Build.PRODUCT,Build.DEVICE)
        .any { value -> value.contains("generic",true) || value.contains("emulator",true) || value.contains("sdk_gphone",true) }
}
