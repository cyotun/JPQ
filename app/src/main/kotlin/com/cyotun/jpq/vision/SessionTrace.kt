package com.cyotun.jpq.vision

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.cyotun.jpq.BuildConfig
import org.json.JSONObject
import java.io.File

/** Captures the session's diagnostics preference once and delays record creation until enabled. */
internal class DiagnosticSnapshotGate<T>(val enabled: Boolean, private val sink: (T) -> Unit) {
    fun write(record: () -> T) {
        if (enabled) sink(record())
    }
}

/** Append-only local test evidence. No network, secret values, or other-app identifiers. */
class SessionTrace(context: Context, private val detectionPipeline: String = DetectionPipelinePolicy.FULL_CPU): AutoCloseable {
    val id="${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}"
    private val snapshotEnabled=context.getSharedPreferences("jpq-diagnostics",Context.MODE_PRIVATE).getBoolean("enabled",false)
    private val gate: DiagnosticSnapshotGate<JSONObject> =DiagnosticSnapshotGate(snapshotEnabled, ::write)
    val enabled get()=gate.enabled
    private val folder=File(context.filesDir,"sessions/$id")
    private var sink: java.io.BufferedWriter?=null
    private var bytes=0L
    private var flushed=0L
    private var closed=false
    private val dropped=java.util.concurrent.atomic.AtomicLong()
    private val lease: AutoCloseable?=if(enabled) com.cyotun.jpq.EvidenceWork.writer() else null
    private val writer=if(enabled) com.cyotun.jpq.core.BoundedWriter<String>("jpq-session-trace",256,
        write={ persist(it) }, dropped={ dropped.incrementAndGet() },
        failed={ android.util.Log.w("JPQTrace","local_trace_unavailable",it) },
        finished={ try { recordDrops(); sink?.close() } finally { sink=null; lease?.close() } }) else null
    init {
        writeIfEnabled { JSONObject().put("type","session").put("schemaVersion",1).put("sessionId",id)
            .put("versionCode",BuildConfig.VERSION_CODE).put("versionName",BuildConfig.VERSION_NAME)
            .put("applicationId",context.packageName).put("captureScope",com.cyotun.jpq.AppState.captureScope.name)
            .put("detectionPipeline",detectionPipeline)
            .put("modelSha256",LocalCardDetector.MODEL_SHA).put("device",Build.MODEL).put("sdk",Build.VERSION.SDK_INT)
            .put("runtimeVersion",LocalCardDetector.RUNTIME_VERSION).put("osBuild",Build.DISPLAY)
            .put("captureProfile",context.getSharedPreferences("jpq-runtime",Context.MODE_PRIVATE).getString("captureProfile","reference"))
            .put("gameVersion",runCatching { @Suppress("DEPRECATION") context.packageManager.getPackageInfo("com.tencent.guandansy",0).versionName }.getOrNull())
            .put("densityDpi",context.resources.displayMetrics.densityDpi)
            .put("displayConfig",JSONObject(com.cyotun.jpq.ConfigStore(context).export()).getJSONObject("display"))
            .put("wallTimeMs",System.currentTimeMillis()).put("elapsedMs",SystemClock.elapsedRealtime()) }
    }
    fun writeIfEnabled(record: () -> JSONObject) = gate.write(record)
    @Synchronized fun write(record: JSONObject) {
        if(!enabled || closed) return
        // Freeze the small record now; encoding/flush of queued records never reads live state.
        writer?.offer(record.put("sessionId",id).toString())
    }
    private fun recordDrops() {
        val count=dropped.getAndSet(0)
        if(count>0) append(JSONObject().put("type","diagnostic_gap").put("sessionId",id)
            .put("droppedRecords",count).put("reason","writer_queue_capacity").toString())
    }
    private fun persist(encoded: String) {
        recordDrops()
        val compact=if(encoded.length<=3600) encoded else JSONObject(encoded).apply {
            remove("detections"); remove("events"); remove("glyphs")
        }.toString()
        if(compact.length<=3600) android.util.Log.i("JPQTrace",compact)
        append(encoded)
    }
    private fun append(encoded: String) {
        folder.mkdirs()
        val file=File(folder,"trace.jsonl")
        val length=encoded.toByteArray(Charsets.UTF_8).size+1
        if(sink!=null && bytes+length>8L*1024*1024) {
            sink?.close(); sink=null
            val previous=File(folder,"trace.previous.jsonl")
            check(!previous.exists() || previous.delete())
            check(file.renameTo(previous)); bytes=0
        }
        if(sink==null) { bytes=file.length(); sink=file.bufferedWriter(Charsets.UTF_8,8192) }
        sink!!.write(encoded); sink!!.newLine(); bytes+=length
        val now=SystemClock.elapsedRealtime()
        if(now-flushed>=1000) { sink!!.flush(); flushed=now }
    }
    @Synchronized override fun close() { if(!closed) { closed=true; writer?.close() } }
    /** Test barrier for large synthetic traces; production maintenance remains time bounded. */
    internal fun awaitClosed(timeoutMs: Long): Boolean = writer?.awaitClosed(timeoutMs) ?: true
}
