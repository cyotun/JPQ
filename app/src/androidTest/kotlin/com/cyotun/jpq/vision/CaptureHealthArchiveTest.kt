package com.cyotun.jpq.vision

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.AppState
import com.cyotun.jpq.DiagnosticArchive
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/** Explicit stopped-capture probe; no screen sharing, network or game interaction. */
@RunWith(AndroidJUnit4::class)
class CaptureHealthArchiveTest {
    @Test fun localArchiveRetainsPipelineProgressWithoutAConnectedComputer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureHealthProbe")=="true")
        check(!AppState.running)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("jpq-diagnostics",Context.MODE_PRIVATE)
        val had=prefs.contains("enabled"); val original=prefs.getBoolean("enabled",false)
        val sessions=File(context.filesDir,"sessions")
        val existing=sessions.listFiles().orEmpty().map { it.name }.toSet()
        var generated: File?=null
        val archive=File.createTempFile("capture-health-probe-",".zip",context.cacheDir)
        prefs.edit().putBoolean("enabled",true).commit()
        try {
            val health=CapturePipelineHealth(context,1234)
            health.imageCallback(); health.imageAcquired(true); health.offered()
            health.consumeStarted(); health.visionStage("detect_start"); health.visionStage("detect_end"); health.consumeEnded()
            health.report(2608,1200,1304,600,true,true,true,1,null,null)
            health.close()
            val deadline=SystemClock.uptimeMillis()+3000
            while(SystemClock.uptimeMillis()<deadline) {
                generated=sessions.listFiles().orEmpty().singleOrNull { it.name !in existing && it.name.startsWith("capture-") }
                if(generated?.resolve("capture-health.jsonl")?.length()?.let { it>0 }==true) break
                SystemClock.sleep(20)
            }
            val folder=checkNotNull(generated)
            val file=folder.resolve("capture-health.jsonl")
            val entry="sessions/${folder.name}/capture-health.jsonl"
            val data=JSONObject(file.readText().trim())
            assertEquals(1,data.getLong("callbacks")); assertEquals(1,data.getLong("acquired"))
            assertEquals(1,data.getLong("consumeEnds")); assertEquals("detect_end",data.getString("lastWorkStage"))
            assertFalse(data.getBoolean("supplyGap")); assertTrue(data.getLong("wallTimeMs")>0)
            archive.outputStream().use { DiagnosticArchive.write(context,it) }
            val exported=ZipFile(archive).use { zip ->
                zip.getEntry(entry)?.let { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
            }
            assertEquals(file.readText(),exported)
        } finally {
            archive.delete()
            generated?.deleteRecursively()
            prefs.edit().apply { if(had) putBoolean("enabled",original) else remove("enabled") }.commit()
        }
    }
}
