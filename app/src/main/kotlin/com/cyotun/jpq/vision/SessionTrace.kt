package com.cyotun.jpq.vision

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.cyotun.jpq.BuildConfig
import org.json.JSONObject
import java.io.File

/** Append-only local test evidence. No network, secret values, or other-app identifiers. */
class SessionTrace(context: Context): AutoCloseable {
    val id="${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}"
    private val enabled=context.getSharedPreferences("jpq-diagnostics",Context.MODE_PRIVATE).getBoolean("enabled",false)
    private val folder=File(context.filesDir,"sessions/$id").apply { if(enabled) mkdirs() }
    private var writer=if(enabled) runCatching { File(folder,"trace.jsonl").bufferedWriter() }.getOrNull() else null
    private var flushed=0L
    init {
        write(JSONObject().put("type","session").put("schemaVersion",1).put("sessionId",id)
            .put("versionCode",BuildConfig.VERSION_CODE).put("versionName",BuildConfig.VERSION_NAME)
            .put("modelSha256",LocalCardDetector.MODEL_SHA).put("device",Build.MODEL).put("sdk",Build.VERSION.SDK_INT)
            .put("runtimeVersion",LocalCardDetector.RUNTIME_VERSION).put("osBuild",Build.DISPLAY)
            .put("captureProfile",context.getSharedPreferences("jpq-runtime",Context.MODE_PRIVATE).getString("captureProfile","reference"))
            .put("gameVersion",runCatching { @Suppress("DEPRECATION") context.packageManager.getPackageInfo("com.tencent.guandansy",0).versionName }.getOrNull())
            .put("densityDpi",context.resources.displayMetrics.densityDpi)
            .put("displayConfig",JSONObject(com.cyotun.jpq.ConfigStore(context).export()).getJSONObject("display"))
            .put("wallTimeMs",System.currentTimeMillis()).put("elapsedMs",SystemClock.elapsedRealtime()))
    }
    @Synchronized fun write(record: JSONObject) {
        if(!enabled) return
        val encoded=record.put("sessionId",id).toString()
        // ADB streams compact records during live testing; full evidence stays in the private file.
        val compact=if(encoded.length<=3600) encoded else JSONObject(record.toString()).apply { remove("detections"); remove("events"); remove("glyphs") }.toString()
        if(compact.length<=3600) android.util.Log.i("JPQTrace",compact)
        val sink=writer ?: return
        runCatching {
            sink.write(encoded); sink.newLine()
            val now=SystemClock.elapsedRealtime(); if(now-flushed>=1000) { sink.flush(); flushed=now }
        }.onFailure { runCatching { sink.close() }; writer=null; android.util.Log.w("JPQTrace","local_trace_unavailable") }
    }
    @Synchronized override fun close() { runCatching { writer?.flush() }; runCatching { writer?.close() }; writer=null }
}
