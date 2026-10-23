package com.cyotun.jpq.updates

import java.io.File
import java.util.UUID

/** Makes a stable, private copy for the package installer to read after its intent is launched. */
internal object InstallFileSnapshot {
    private const val PREFIX="install-"
    private const val SUFFIX=".apk"
    private const val RETAIN_FOR_MS=30L*24*60*60*1000

    fun create(source: File,directory: File,nowMs: Long=System.currentTimeMillis()): File {
        require(source.isFile)
        directory.mkdirs()
        pruneExpired(directory,nowMs)
        val snapshot=File(directory,"$PREFIX${UUID.randomUUID()}$SUFFIX")
        try {
            source.copyTo(snapshot,overwrite=false)
            check(snapshot.length()==source.length()) { "Incomplete installer snapshot" }
            return snapshot
        } catch (error: Exception) {
            snapshot.delete()
            throw error
        }
    }

    private fun pruneExpired(directory: File,nowMs: Long) {
        val cutoff=nowMs-RETAIN_FOR_MS
        directory.listFiles()?.filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
            ?.filter { it.lastModified()<cutoff }
            ?.forEach { it.delete() }
    }
}
