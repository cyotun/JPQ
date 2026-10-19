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
    @Test fun backupDisplayConfigForUiTestRun() {
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("displayConfigAction")=="backup")
        check(!AppState.running) { "Back up configuration only after capture and the real match stop" }
        val runId=displayConfigRunId(arguments.getString("displayConfigRunId"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("jpq-display",0)
        val diagnostics=context.getSharedPreferences("jpq-diagnostics",0)
        val output=displayConfigRunDirectory(context,runId)
        check(output.mkdirs()) { "Display config backup already exists for run $runId" }
        val present=prefs.contains("config")
        val raw=if(present) prefs.getString("config",null) else null
        check(!present || raw!=null) { "Stored display config is not a string" }
        val record=org.json.JSONObject()
            .put("schemaVersion",2).put("runId",runId)
            .put("createdAtUtc",java.time.Instant.now().toString())
            .put("targetPackage",context.packageName)
            .put("configPresent",present)
            .put("rawConfig",raw ?: org.json.JSONObject.NULL)
            .put("diagnosticsEnabledPresent",diagnostics.contains("enabled"))
            .put("diagnosticsEnabled",diagnostics.getBoolean("enabled",false))
            .put("displayExport",ConfigStore(context).export())
        writeDurably(File(output,"backup.json"),record.toString(2))
        android.util.Log.i("JPQEvidence","display_config_backup_ready runId=$runId")
    }

    @Test fun restoreDisplayConfigAfterUiTestRun() {
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("displayConfigAction")=="restore")
        val runId=displayConfigRunId(arguments.getString("displayConfigRunId"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val output=displayConfigRunDirectory(context,runId)
        val backup=org.json.JSONObject(File(output,"backup.json").readText())
        val schemaVersion=backup.getInt("schemaVersion")
        check(schemaVersion in 1..2 && backup.getString("runId")==runId)
        check(backup.getString("targetPackage")==context.packageName)
        val prefs=context.getSharedPreferences("jpq-display",0)
        val diagnostics=context.getSharedPreferences("jpq-diagnostics",0)
        val store=ConfigStore(context)
        val attempt="${System.currentTimeMillis()}-${System.nanoTime()}"
        val before=org.json.JSONObject().put("runId",runId).put("capturedAtUtc",java.time.Instant.now().toString())
            .put("configPresent",prefs.contains("config"))
            .put("rawConfig",prefs.getString("config",null) ?: org.json.JSONObject.NULL)
            .put("diagnosticsEnabledPresent",diagnostics.contains("enabled"))
            .put("diagnosticsEnabled",diagnostics.getBoolean("enabled",false))
            .put("displayExport",store.export())
        writeDurably(File(output,"before-restore-$attempt.json"),before.toString(2))
        val edit=prefs.edit()
        if(backup.getBoolean("configPresent")) edit.putString("config",backup.getString("rawConfig"))
        else edit.remove("config")
        check(edit.commit()) { "Could not durably restore display config for run $runId" }
        if(schemaVersion>=2) {
            val diagnosticsEdit=diagnostics.edit()
            if(backup.getBoolean("diagnosticsEnabledPresent"))
                diagnosticsEdit.putBoolean("enabled",backup.getBoolean("diagnosticsEnabled"))
            else diagnosticsEdit.remove("enabled")
            check(diagnosticsEdit.commit()) { "Could not durably restore diagnostics setting for run $runId" }
            check(diagnostics.contains("enabled")==backup.getBoolean("diagnosticsEnabledPresent"))
            check(diagnostics.getBoolean("enabled",false)==backup.getBoolean("diagnosticsEnabled"))
        }
        val restored=prefs.contains("config").let { present ->
            val raw=if(present) prefs.getString("config",null) else null
            check(present==backup.getBoolean("configPresent"))
            check(raw==(if(present) backup.getString("rawConfig") else null))
            org.json.JSONObject().put("runId",runId).put("restoredAtUtc",java.time.Instant.now().toString())
                .put("configPresent",present).put("rawConfig",raw ?: org.json.JSONObject.NULL)
                .put("schemaVersion",schemaVersion)
                .put("diagnosticsEnabledPresent",diagnostics.contains("enabled"))
                .put("diagnosticsEnabled",diagnostics.getBoolean("enabled",false))
                .put("displayExport",store.export())
        }
        writeDurably(File(output,"after-restore-$attempt.json"),restored.toString(2))
        android.util.Log.i("JPQEvidence","display_config_restored runId=$runId")
    }

    @Test fun exportStoppedCaptureEvidence() {
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("exportEvidence")=="true" || arguments.getString("armCaptureProbe")=="true")
        check(!AppState.running) { "Stop capture after the user confirms the game ended" }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        // Optional only: AndroidX may wait for activity idle inside startActivity
        // on OEM task transitions. A batch evidence export must not depend on it.
        if(arguments.getString("exportEvidence")=="true" && arguments.getString("launchExportUi")=="true") {
            val intent=android.content.Intent(context,com.cyotun.jpq.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("page","home")
            // Export needs no UI result. Waiting for AndroidX's activity-idle
            // monitor can time out on HyperOS task transitions before any IO starts.
            context.startActivity(intent)
        }
        val output=File(context.getExternalFilesDir(null),"phone-preflight").apply { mkdirs() }
        val store=ConfigStore(context)
        File(output,"display-before.json").writeText(store.export())
        val diagnostics=context.getSharedPreferences("jpq-diagnostics",0)
        File(output,"runtime-before.json").writeText(org.json.JSONObject()
            .put("diagnosticsEnabled",diagnostics.getBoolean("enabled",false))
            .put("captureProfile",context.getSharedPreferences("jpq-runtime",0).getString("captureProfile","reference"))
            .toString())
        if(arguments.getString("exportEvidence")=="true") {
            android.util.Log.i("JPQEvidence","archive_write_start")
            val pending=File(output,"diagnostics.pending.zip")
            pending.outputStream().use { DiagnosticArchive.write(context,it) }
            java.util.zip.ZipFile(pending).use { check(it.size()>0) }
            check(pending.renameTo(File(output,"diagnostics.zip")))
            android.util.Log.i("JPQEvidence","archive_write_end")
        }
        if(arguments.getString("resetTestDisplay")=="true") {
            store.save(DisplayConfig())
            check(context.getSharedPreferences("jpq-display",0).edit().putString("config",store.draftJson(DisplayConfig())).commit())
            context.getSharedPreferences("jpq-diagnostics",0).edit().putBoolean("enabled",true).commit()
            context.getSharedPreferences("jpq-runtime",0).edit().remove("gpuFingerprint").remove("gpuRuntime").commit()
        }
        if(arguments.getString("armCaptureProbe")=="true")
            check(context.getSharedPreferences("jpq-diagnostics",0).edit().putBoolean("enabled",true).putInt("captureProbeRemaining",3).commit())
        arguments.getString("diagnosticsEnabled")?.let { value ->
            require(value in setOf("true","false"))
            check(diagnostics.edit().putBoolean("enabled",value=="true").commit())
        }
        // Explicit local test selection only. The operator must first review the
        // fixture and continuous replay reports; this exporter is not certification.
        arguments.getString("enableReferencePipeline")?.let { enabled ->
            require(enabled in setOf("true","false"))
            val edit=context.getSharedPreferences("jpq-runtime",0).edit()
            if(enabled=="true") edit.putString("referencePipelineFingerprint",android.os.Build.FINGERPRINT)
                .putString("referencePipelineVersion",ReferenceCardDetector.PIPELINE_VERSION)
            else edit.remove("referencePipelineFingerprint").remove("referencePipelineVersion")
            check(edit.commit())
        }
        arguments.getString("captureProfile")?.let { profile ->
            require(profile in setOf("reference","adaptive_scaled"))
            check(context.getSharedPreferences("jpq-runtime",0).edit().putString("captureProfile",profile).commit())
        }
        File(output,"display-after.json").writeText(store.export())
        android.util.Log.i("JPQEvidence","completed_evidence_exported; resetTestDisplay=${arguments.getString("resetTestDisplay")}")
    }

    private fun displayConfigRunId(value: String?): String {
        require(!value.isNullOrBlank() && value.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        return value
    }

    private fun displayConfigRunDirectory(context: android.content.Context,runId: String): File {
        val external=context.getExternalFilesDir(null) ?: error("External evidence storage is unavailable")
        return File(external,"phone-preflight/ui-config-$runId")
    }

    private fun writeDurably(file: File,text: String) {
        val temporary=File(file.parentFile,".${file.name}.pending")
        temporary.outputStream().use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8)); stream.flush(); stream.fd.sync()
        }
        check(temporary.renameTo(file)) { "Could not finalize evidence file ${file.name}" }
    }
}
