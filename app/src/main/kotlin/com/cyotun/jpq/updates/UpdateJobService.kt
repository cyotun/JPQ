package com.cyotun.jpq.updates

import android.app.job.*
import com.cyotun.jpq.AppState
import com.cyotun.jpq.BuildConfig

class UpdateJobService: JobService() {
    private val cancellations=java.util.concurrent.ConcurrentHashMap<Int,java.util.concurrent.atomic.AtomicBoolean>()
    override fun onStartJob(params: JobParameters): Boolean {
        if(BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE) return false
        val canceled=java.util.concurrent.atomic.AtomicBoolean(false); cancellations[params.jobId]=canceled
        Thread {
            var retry=false
            runCatching {
                val updates=UpdateManager(applicationContext)
                if(params.jobId==401) updates.check()
                else if(params.jobId==402) {
                    val done=!AppState.running && updates.download(shouldCancel={ canceled.get() })
                    retry=!done && updates.hasPendingUpdate()
                }
            }
            // A deferred/failed download remains a pending job. onStopJob owns
            // system cancellation; a canceled worker must not finish its job.
            if(!canceled.get()) jobFinished(params,retry)
            cancellations.remove(params.jobId,canceled)
        }.start(); return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        cancellations[params.jobId]?.set(true)
        return !BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE
    }
}
