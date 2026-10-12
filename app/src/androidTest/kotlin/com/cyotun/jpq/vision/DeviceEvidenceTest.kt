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
        assumeTrue(arguments.getString("exportEvidence")=="true")
        check(!AppState.running) { "Stop capture after the user confirms the game ended" }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val output=File(context.getExternalFilesDir(null),"phone-preflight").apply { mkdirs() }
        val store=ConfigStore(context)
        File(output,"display-before.json").writeText(store.export())
        File(output,"diagnostics.zip").outputStream().use { DiagnosticArchive.write(context,it) }
        if(arguments.getString("resetTestDisplay")=="true") {
            store.save(DisplayConfig())
            context.getSharedPreferences("jpq-diagnostics",0).edit().putBoolean("enabled",true).commit()
            context.getSharedPreferences("jpq-runtime",0).edit().remove("gpuFingerprint").remove("gpuRuntime").commit()
        }
        File(output,"display-after.json").writeText(store.export())
        android.util.Log.i("JPQEvidence","completed_evidence_exported; resetTestDisplay=${arguments.getString("resetTestDisplay")}")
    }
}
