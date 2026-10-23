package com.cyotun.jpq

import android.content.Context
import java.io.OutputStream
import java.io.File
import java.util.zip.*

object DiagnosticArchive {
    fun write(context: Context,output: OutputStream) = EvidenceWork.maintain {
        writeZip(context,output,rounds(context))
    }
    /** Open the destination only after capture is stopped and pending writes drain. */
    fun export(context: Context,fullArchive: Boolean,openOutput: ()->OutputStream) = EvidenceWork.maintain {
        val snapshot=rounds(context)
        if(fullArchive) writeZip(context,openOutput(),snapshot)
        else openOutput().use { it.write(snapshot) }
    }
    private fun rounds(context: Context)=RoundRepository(context).use { it.export().toByteArray(Charsets.UTF_8) }
    private fun writeZip(context: Context,output: OutputStream,snapshot: ByteArray) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("rounds.json")); zip.write(snapshot); zip.closeEntry()
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
