package com.cyotun.jpq.vision

import android.app.Activity
import android.app.job.JobScheduler
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.*
import com.cyotun.jpq.core.CaptureScope
import com.cyotun.jpq.updates.UpdateManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Non-interactive contracts only: never starts projection, launches the game or touches stable data. */
@RunWith(AndroidJUnit4::class)
class CandidateIsolationContractTest {
    private fun context()=InstrumentationRegistry.getInstrumentation().targetContext.also {
        assumeTrue(BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE)
        check(it.packageName=="com.cyotun.jpq.candidate")
        check(!AppState.running)
    }

    @Test fun candidateHasAnIndependentPackageAndLabel() {
        val c=context()
        assertEquals("com.cyotun.jpq.candidate.tests",InstrumentationRegistry.getInstrumentation().context.packageName)
        assertEquals("记牌器BETA",c.applicationInfo.loadLabel(c.packageManager).toString())
        assertEquals(CaptureScope.APP_TASK,ProjectionConsentRequest.scope)
    }

    @Test fun publicAppOnlyConfigExcludesDisplayAndContentSources() {
        val c=context()
        assumeTrue(Build.VERSION.SDK_INT>=37)
        val config=ProjectionConsentRequest.appOnlyConfig()
        val api=MediaProjectionConfig::class.java
        val app=api.getField("PROJECTION_SOURCE_APP").getInt(null)
        assertEquals(app,api.getMethod("getProjectionSources").invoke(config))
        assertEquals(app,api.getMethod("getInitiallySelectedSource").invoke(config))
        assertNotNull(ProjectionConsentRequest.create(c.getSystemService(MediaProjectionManager::class.java)))
        assertFalse(AppState.running)
    }

    @Test fun candidateCannotCheckDownloadInstallOrScheduleStableUpdates() {
        val c=context()
        val prefs=c.getSharedPreferences("jpq-updates",0)
        val before=prefs.all.toMap()
        val jobs=c.getSystemService(JobScheduler::class.java)
        val jobIds=jobs.allPendingJobs.map { it.id }.sorted()
        val manager=UpdateManager(c)
        assertFalse(manager.check(true))
        assertFalse(manager.download(true))
        InstrumentationRegistry.getInstrumentation().runOnMainSync { manager.install(Activity()) }
        UpdateManager.schedule(c)
        UpdateManager.scheduleDownload(c)
        assertEquals(jobIds,jobs.allPendingJobs.map { it.id }.sorted())
        assertEquals(before,prefs.all)
        assertTrue(manager.status().contains("BETA"))
        assertFalse(AppState.running)
    }
}
