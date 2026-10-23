package com.cyotun.jpq.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class InstallCoordinatorTest {
    @Test fun slowVerificationDoesNotBlockRequestAndDuplicateRequestsAreRejected() {
        val executor=Executors.newSingleThreadExecutor()
        val finishVerification=CountDownLatch(1)
        try {
            val verificationStarted=CountDownLatch(1)
            val main=LinkedBlockingQueue<() -> Unit>()
            val launchCount=AtomicInteger()
            val coordinator=coordinator(
                executor=executor,
                main=main,
                verify={ verificationStarted.countDown(); finishVerification.await(); true },
                launch={ launchCount.incrementAndGet() },
            )

            val requestReturned=CountDownLatch(1)
            val accepted=AtomicBoolean(false)
            Thread { accepted.set(coordinator.request()); requestReturned.countDown() }.start()
            assertTrue("request must return while verification is still blocked",requestReturned.await(500,TimeUnit.MILLISECONDS))
            assertTrue(accepted.get())
            assertTrue(verificationStarted.await(1,TimeUnit.SECONDS))
            assertFalse(coordinator.request())

            finishVerification.countDown()
            runNext(main)
            runNext(main) // completion callback dispatch
            assertEquals(1,launchCount.get())
        } finally {
            finishVerification.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun holdsUpdateLockDuringVerificationThenReleasesItBeforeUiDispatch() {
        val executor=Executors.newSingleThreadExecutor()
        val finishVerify=CountDownLatch(1)
        try {
            val main=LinkedBlockingQueue<() -> Unit>()
            val lock=Any()
            val verifyStarted=CountDownLatch(1)
            val coordinator=coordinator(executor=executor,main=main,lock=lock,verify={
                verifyStarted.countDown()
                finishVerify.await()
                true
            })
            assertTrue(coordinator.request())
            assertTrue(verifyStarted.await(1,TimeUnit.SECONDS))

            val acquired=CountDownLatch(1)
            val contender=Thread { synchronized(lock) { acquired.countDown() } }
            contender.start()
            assertFalse(acquired.await(100,TimeUnit.MILLISECONDS))

            finishVerify.countDown()
            val installAction=takeNext(main)
            assertTrue("file lock should be released after creating the verified handoff",acquired.await(1,TimeUnit.SECONDS))
            installAction()
            contender.join(1000)
            runNext(main) // completion callback dispatch
        } finally {
            finishVerify.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun installerHandoffUsesSnapshotAfterVerifiedApkChanges() {
        val executor=Executors.newSingleThreadExecutor()
        val directory=java.nio.file.Files.createTempDirectory("jpq-install-snapshot").toFile()
        try {
            val source=java.io.File(directory,"verified.apk").apply { writeText("verified bytes") }
            val snapshot=java.util.concurrent.atomic.AtomicReference<java.io.File>()
            val main=LinkedBlockingQueue<() -> Unit>()
            val coordinator=coordinator(
                executor=executor,
                main=main,
                verify={ snapshot.set(InstallFileSnapshot.create(source,directory)); true },
                launch={ assertEquals("verified bytes",snapshot.get().readText()) },
            )
            assertTrue(coordinator.request())
            val installerAction=takeNext(main)
            source.writeText("replacement from a later update")
            installerAction()
            runNext(main) // completion callback dispatch
            assertEquals("verified bytes",snapshot.get().readText())
        } finally {
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test fun skipsInstallWhenActivityIsDestroyedOrRecordingRestarts() {
        for (scenario in listOf("activity destroyed","recording restarted")) {
            val executor=Executors.newSingleThreadExecutor()
            try {
                val main=LinkedBlockingQueue<() -> Unit>()
                val launchCount=AtomicInteger()
                val failureCount=AtomicInteger()
                val activityDestroyed=AtomicBoolean(false)
                val recording=AtomicBoolean(false)
                val coordinator=coordinator(
                    executor=executor,
                    main=main,
                    verify={
                        if(scenario=="activity destroyed") activityDestroyed.set(true)
                        if(scenario=="recording restarted") recording.set(true)
                        true
                    },
                    canInstall={ !activityDestroyed.get() && !recording.get() },
                    launch={ launchCount.incrementAndGet() },
                    onFailure={ failureCount.incrementAndGet() },
                )
                assertTrue(coordinator.request())
                runNext(main)
                runNext(main) // completion callback dispatch
                assertEquals(0,launchCount.get())
                assertEquals(0,failureCount.get())
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test fun failedVerificationReleasesTheGateForRetry() {
        val executor=Executors.newSingleThreadExecutor()
        try {
            val main=LinkedBlockingQueue<() -> Unit>()
            val failureCount=AtomicInteger()
            val valid=AtomicBoolean(false)
            val coordinator=coordinator(
                executor=executor,
                main=main,
                verify={ valid.get() },
                onFailure={ failureCount.incrementAndGet() },
            )

            assertTrue(coordinator.request())
            runNext(main) // first attempt completion
            assertEquals(1,failureCount.get())
            valid.set(true)
            assertTrue(coordinator.request())
            runNext(main)
            runNext(main) // retry completion
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun timeoutCancelsALateMainThreadRunnable() {
        val executor=Executors.newSingleThreadExecutor()
        try {
            val main=LinkedBlockingQueue<() -> Unit>()
            val launchCount=AtomicInteger()
            val failureCount=AtomicInteger()
            val lock=Any()
            val coordinator=coordinator(
                executor=executor,
                main=main,
                lock=lock,
                launch={ launchCount.incrementAndGet() },
                onFailure={ failureCount.incrementAndGet() },
                timeoutMs=25,
            )

            assertTrue(coordinator.request())
            val lateInstaller=takeNext(main)
            val completion=takeNext(main)
            val lockReleased=CountDownLatch(1)
            Thread { synchronized(lock) { lockReleased.countDown() } }.start()
            assertTrue(lockReleased.await(1,TimeUnit.SECONDS))
            lateInstaller()
            completion()
            assertEquals(0,launchCount.get())
            assertEquals(1,failureCount.get())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun coordinator(
        executor: ExecutorService,
        main: BlockingQueue<() -> Unit>,
        lock: Any=Any(),
        verify: () -> Boolean={ true },
        canInstall: () -> Boolean={ true },
        launch: () -> Unit={},
        onFailure: () -> Unit={},
        timeoutMs: Long=1000,
    )=InstallCoordinator(
        executor=executor,
        updateLock=lock,
        dispatchMain={ action -> main.put(action) },
        verify=verify,
        canInstall=canInstall,
        launch=launch,
        onFailure=onFailure,
        dispatchTimeoutMs=timeoutMs,
    )

    private fun runNext(queue: BlockingQueue<() -> Unit>)=takeNext(queue).invoke()

    private fun takeNext(queue: BlockingQueue<() -> Unit>)=
        queue.poll(2,TimeUnit.SECONDS) ?: throw AssertionError("Timed out waiting for dispatched UI action")
}
