package com.cyotun.jpq

import android.content.Context
import java.io.File

/** Call while capture is stopped and all evidence writers have drained. */
object DiagnosticStorage {
    const val SESSION_BYTES = 64L*1024*1024
    const val PROBE_BYTES = 256L*1024*1024
    fun prune(context: Context) {
        pruneDirectories(File(context.filesDir,"sessions"),SESSION_BYTES)
        pruneDirectories(File(context.filesDir,"capture-probes"),PROBE_BYTES)
        RoundRepository(context).use { it.pruneOrphanEvidence() }
    }
    internal fun pruneDirectories(base: File,maximumBytes: Long) {
        val entries = base.listFiles().orEmpty().sortedBy { it.lastModified() }
        fun size(f: File) = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        var bytes = entries.sumOf { size(it) }
        for (entry in entries) {
            if (bytes <= maximumBytes) break
            val removed = size(entry)
            check(entry.deleteRecursively()) { "Cannot remove old diagnostic files" }
            bytes -= removed
        }
    }
    fun clear(context: Context) = EvidenceWork.maintain {
        RoundRepository(context).use { it.clear() }
        for (name in listOf("sessions","diagnostic","capture-probes")) {
            val folder = File(context.filesDir,name)
            check(!folder.exists() || folder.deleteRecursively()) { "Cannot clear $name" }
        }
    }
}
