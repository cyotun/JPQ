package com.cyotun.jpq

import android.content.Context
import java.io.OutputStream
import java.io.File
import java.util.zip.*

object DiagnosticArchive {
    fun write(context: Context,output: OutputStream) {
        check(!AppState.running) { "Stop capture after the match before exporting" }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("rounds.json")); RoundRepository(context).use { zip.write(it.export().toByteArray()) }; zip.closeEntry()
            for(name in listOf("sessions","diagnostic","capture-probes")) {
                val base=File(context.filesDir,name)
                if(base.exists()) base.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relative=file.relativeTo(base).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry("$name/$relative")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
        }
    }
}
