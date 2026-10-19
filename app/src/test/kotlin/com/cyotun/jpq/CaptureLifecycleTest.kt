package com.cyotun.jpq

import org.junit.Assert.*
import org.junit.Test

/** Host-side failure injection runs on every full sweep, without a screen-sharing grant. */
class CaptureLifecycleTest {
    @Test fun aStoppedProjectionCallbackCannotStopItsReplacementSession() {
        val lifecycle=CaptureLifecycle()
        val old=lifecycle.begin()
        assertTrue(lifecycle.close(old))
        val replacement=lifecycle.begin()

        // MediaProjection.stop() may dispatch an already queued old callback.
        assertFalse(lifecycle.close(old))
        assertEquals(replacement,lifecycle.generation)
        assertFalse(lifecycle.closed)
        assertTrue(lifecycle.close(replacement))
    }

    @Test fun taskRemovalThenSystemStopOrDestroyReleasesEachOwnerOnlyOnce() {
        val lifecycle=CaptureLifecycle()
        val generation=lifecycle.begin()
        var releases=0
        repeat(3) { if(lifecycle.close(generation)) releases++ }
        assertEquals(1,releases)
        assertTrue(lifecycle.closed)
        assertTrue(lifecycle.generation>generation)
    }

    @Test fun failedPartialStartupCanBeClosedAndThenStartedAgain() {
        val lifecycle=CaptureLifecycle()
        val failed=lifecycle.begin()
        // Permission validation/virtual-display creation may fail before running=true.
        assertTrue(lifecycle.close(failed))
        val retry=lifecycle.begin()
        assertTrue(retry>failed)
        assertFalse(lifecycle.closed)
        assertTrue(lifecycle.close(retry))
    }

    @Test fun aPlatformReleaseFailureStillStopsSharingAndRemovesForegroundState() {
        val failures=mutableListOf<String>()
        val released=mutableListOf<String>()
        val cleanup=CaptureCleanup { name,_ -> failures+=name }
        cleanup.release("callback") { throw IllegalStateException("projection callback already unregistered") }
        cleanup.release("display") { released+="display" }
        cleanup.release("projection") { released+="projection" }
        cleanup.release("foreground") { released+="foreground" }
        cleanup.release("reader") { throw IllegalStateException("reader already closed") }
        cleanup.release("overlay") { released+="overlay" }
        cleanup.release("service") { released+="service" }

        assertEquals(listOf("callback","reader"),failures)
        assertEquals(listOf("display","projection","foreground","overlay","service"),released)
    }

    @Test fun aFailureReporterCannotInterruptRemainingCleanup() {
        val cleanup=CaptureCleanup { _,_ -> throw IllegalStateException("logging failed") }
        var projectionStopped=false
        cleanup.release("display") { throw IllegalStateException("display release failed") }
        cleanup.release("projection") { projectionStopped=true }
        assertTrue(projectionStopped)
    }
}
