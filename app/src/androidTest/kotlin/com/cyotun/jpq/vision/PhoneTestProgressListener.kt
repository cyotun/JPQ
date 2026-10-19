package com.cyotun.jpq.vision

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.RunListener
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Opt-in instrumentation listener. Captures only this test process's threads;
 * no second projection, device privileges or unrelated application data. */
class PhoneTestProgressListener : RunListener() {
    private val generation=AtomicLong()
    private val watchdog=Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable,"jpq-test-watchdog").apply { isDaemon=true }
    }
    override fun testStarted(description: Description) {
        val current=generation.incrementAndGet()
        val started=SystemClock.elapsedRealtime()
        for(delay in listOf(20L,60L)) watchdog.schedule({
            if(generation.get()!=current) return@schedule
            // This marker precedes both storage and VM stack enumeration: an
            // absent dump must not be mistaken for an unscheduled timer.
            val marker="test=$description elapsedMs=${SystemClock.elapsedRealtime()-started} phase=before_stack_enumeration"
            Log.w("JPQTestWatch",marker)
            runCatching {
                val context=InstrumentationRegistry.getInstrumentation().targetContext
                val directory=File(context.getExternalFilesDir(null),"phone-test-stacks").apply { mkdirs() }
                val output=File(directory,"${started}-${current}-${delay}s.txt")
                output.writeText(marker+"\n")
                val text=buildString {
                    Thread.getAllStackTraces().entries.sortedBy { it.key.name }.forEach { (thread,stack) ->
                        appendLine("\n${thread.name} id=${thread.id} state=${thread.state}")
                        stack.forEach { appendLine("    at $it") }
                    }
                }
                output.appendText(text)
                Log.w("JPQTestWatch",text)
            }.onFailure { Log.w("JPQTestWatch","Could not save own-process test stacks",it) }
        },delay,TimeUnit.SECONDS)
    }
    override fun testFinished(description: Description) { generation.incrementAndGet() }
    override fun testRunFinished(result: Result) { generation.incrementAndGet(); watchdog.shutdownNow() }
}
