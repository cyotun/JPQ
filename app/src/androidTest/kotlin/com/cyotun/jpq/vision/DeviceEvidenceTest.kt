package com.cyotun.jpq.vision

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.AppState
import com.cyotun.jpq.ConfigStore
import com.cyotun.jpq.DiagnosticArchive
import com.cyotun.jpq.core.DisplayConfig
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Signed local development utility; never run during a real game.
 * This exports evidence and optionally restores test defaults, not an accuracy check.
 */
@RunWith(AndroidJUnit4::class)
class DeviceEvidenceTest {
    @Test fun exportStoppedCaptureEvidence() {
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("exportEvidence")=="true" || arguments.getString("armCaptureProbe")=="true")
        check(!AppState.running) { "Stop capture after the user confirms the game ended" }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val output=File(context.getExternalFilesDir(null),"phone-preflight").apply { mkdirs() }
        val store=ConfigStore(context)
        File(output,"display-before.json").writeText(store.export())
        if(arguments.getString("exportEvidence")=="true")
            File(output,"diagnostics.zip").outputStream().use { DiagnosticArchive.write(context,it) }
        if(arguments.getString("resetTestDisplay")=="true") {
            store.save(DisplayConfig())
            check(context.getSharedPreferences("jpq-display",0).edit().putString("config",store.draftJson(DisplayConfig())).commit())
            context.getSharedPreferences("jpq-diagnostics",0).edit().putBoolean("enabled",true).commit()
            context.getSharedPreferences("jpq-runtime",0).edit().remove("gpuFingerprint").remove("gpuRuntime").commit()
        }
        if(arguments.getString("armCaptureProbe")=="true")
            check(context.getSharedPreferences("jpq-diagnostics",0).edit().putBoolean("enabled",true).putInt("captureProbeRemaining",3).commit())
        arguments.getString("captureProfile")?.let { profile ->
            require(profile in setOf("reference","adaptive_scaled"))
            check(context.getSharedPreferences("jpq-runtime",0).edit().putString("captureProfile",profile).commit())
        }
        File(output,"display-after.json").writeText(store.export())
        android.util.Log.i("JPQEvidence","completed_evidence_exported; resetTestDisplay=${arguments.getString("resetTestDisplay")}")
    }
}
