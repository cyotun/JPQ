package com.cyotun.jpq.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class InstallFileSnapshotTest {
    @Test fun keepsTheCopiedApkStableAndPrunesOnlyExpiredSnapshots() {
        val directory=Files.createTempDirectory("jpq-install-snapshot").toFile()
        try {
            val now=System.currentTimeMillis()
            val source=File(directory,"verified.apk").apply { writeText("verified package") }
            val expired=File(directory,"install-expired.apk").apply {
                writeText("old snapshot")
                setLastModified(now-60L*24*60*60*1000)
            }
            val recent=File(directory,"install-recent.apk").apply {
                writeText("recent snapshot")
                setLastModified(now-2L*24*60*60*1000)
            }

            val snapshot=InstallFileSnapshot.create(source,directory,now)
            source.writeText("replaced by another update")

            assertEquals("verified package",snapshot.readText())
            assertTrue(recent.exists())
            assertFalse(expired.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
