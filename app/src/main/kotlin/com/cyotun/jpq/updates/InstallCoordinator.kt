package com.cyotun.jpq.updates

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Runs file verification off the caller thread and hands the verified file to the UI safely. */
internal class InstallCoordinator(
    private val executor: Executor,
    private val updateLock: Any,
    private val dispatchMain: (() -> Unit) -> Unit,
    private val verify: () -> Boolean,
    private val canInstall: () -> Boolean,
    private val launch: () -> Unit,
    private val onFailure: () -> Unit,
    private val dispatchTimeoutMs: Long = 5000L,
    private val active: AtomicBoolean = AtomicBoolean(false),
) {
    fun request(onStarted: () -> Unit = {}, onFinished: () -> Unit = {}): Boolean {
        if (!active.compareAndSet(false, true)) return false
        try {
            onStarted()
            executor.execute {
                try {
                    check(synchronized(updateLock) { verify() }) { "Install package verification failed" }
                    dispatchAndWait()
                } catch (_: Exception) {
                    onFailure()
                } finally {
                    active.set(false)
                    runCatching { dispatchMain(onFinished) }
                }
            }
        } catch (_: Exception) {
            active.set(false)
            onFailure()
            runCatching { dispatchMain(onFinished) }
        }
        return true
    }

    private fun dispatchAndWait() {
        val dispatched = CountDownLatch(1)
        val state = AtomicInteger(PENDING)
        dispatchMain {
            try {
                if (state.get() != PENDING) return@dispatchMain
                if (canInstall() && state.compareAndSet(PENDING, RUNNING)) launch()
            } catch (_: Exception) {
                onFailure()
            } finally {
                state.compareAndSet(PENDING, FINISHED)
                state.compareAndSet(RUNNING, FINISHED)
                dispatched.countDown()
            }
        }
        if (!dispatched.await(dispatchTimeoutMs, TimeUnit.MILLISECONDS)) {
            if (state.compareAndSet(PENDING, CANCELED)) {
                onFailure()
            }
        }
    }

    private companion object {
        const val PENDING=0
        const val RUNNING=1
        const val CANCELED=2
        const val FINISHED=3
    }
}
