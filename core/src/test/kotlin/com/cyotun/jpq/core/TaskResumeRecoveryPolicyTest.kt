package com.cyotun.jpq.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskResumeRecoveryPolicyTest {
    @Test fun emptyTableIsNotHealthyMerelyBecauseAnOldTeammatePhaseIsLatched() {
        assertEquals(TaskResumeRecoveryPolicy.Content.TABLE_EMPTY,TaskResumeRecoveryPolicy.contentFor(TableScene.TABLE,false))
        assertEquals(TaskResumeRecoveryPolicy.Content.UNKNOWN_EMPTY,TaskResumeRecoveryPolicy.contentFor(TableScene.UNKNOWN,false))
        for(scene in TableScene.entries) assertEquals(TaskResumeRecoveryPolicy.Content.GAME_CONTENT,
            TaskResumeRecoveryPolicy.contentFor(scene,true))
        assertEquals(TaskResumeRecoveryPolicy.Content.GAME_CONTENT,TaskResumeRecoveryPolicy.contentFor(TableScene.TEAMMATE,false))
        for(scene in listOf(TableScene.LOBBY,TableScene.RESULT,TableScene.DEALING,TableScene.TRIBUTE))
            assertEquals(TaskResumeRecoveryPolicy.Content.GAME_CONTENT,TaskResumeRecoveryPolicy.contentFor(scene,false))
    }
    private val oldRound = "old-round"
    private val empty = TaskResumeRecoveryPolicy.Content.UNKNOWN_EMPTY
    private val healthy = TaskResumeRecoveryPolicy.Content.GAME_CONTENT

    private fun TaskResumeRecoveryPolicy.arm(time: Long = 0L, epoch: Long = 1L) =
        onDiscontinuity(time, epoch, RoundPhase.RECOVERING, oldRound)

    private fun TaskResumeRecoveryPolicy.frame(
        time: Long,
        content: TaskResumeRecoveryPolicy.Content = empty,
        epoch: Long = 1L,
        sourceTime: Long = time,
        phase: RoundPhase = RoundPhase.RECOVERING,
        round: String? = oldRound,
    ) = observe(time, sourceTime, epoch, phase, round, content)

    private fun TaskResumeRecoveryPolicy.take(
        request: TaskResumeRecoveryPolicy.Request,
        time: Long = request.issuedAtMs,
        epoch: Long = request.visibilityEpoch,
        phase: RoundPhase = RoundPhase.RECOVERING,
        round: String? = oldRound,
    ) = takeRequest(request, time, epoch, phase, round)

    @Test fun emptyFramesCannotArmAnEpisodeWithoutADiscontinuity() {
        val policy = TaskResumeRecoveryPolicy()
        assertNull(policy.frame(0L))
        assertNull(policy.frame(8_000L))
        assertNull(policy.frame(60_000L))
        assertFalse(policy.exhausted)
    }

    @Test fun onlyAnAlreadyRecoveringExistingRoundCanArm() {
        for (phase in RoundPhase.entries.filter { it != RoundPhase.RECOVERING }) {
            val policy = TaskResumeRecoveryPolicy()
            assertFalse(policy.onDiscontinuity(0L, 1L, phase, oldRound))
            assertNull(policy.frame(0L, phase = phase))
            assertNull(policy.frame(8_000L, phase = phase))
        }
        for (round in listOf(null, "", " ")) {
            val policy = TaskResumeRecoveryPolicy()
            assertFalse(policy.onDiscontinuity(0L, 1L, RoundPhase.RECOVERING, round))
            assertNull(policy.frame(8_000L, round = round))
        }
    }

    @Test fun portraitUnknownAndBareTableShareOnePersistentInputFault() {
        val policy = TaskResumeRecoveryPolicy()
        assertTrue(policy.arm())
        assertNull(policy.frame(0L, TaskResumeRecoveryPolicy.Content.PORTRAIT_OR_NULL))
        assertNull(policy.frame(4_000L, TaskResumeRecoveryPolicy.Content.TABLE_EMPTY))
        assertNull(policy.frame(7_999L))
        val request = assertNotNull(policy.frame(8_000L))
        assertEquals(oldRound, request.roundId)
        assertEquals(1L, request.visibilityEpoch)
        assertTrue(policy.take(request))
        assertFalse(policy.take(request)) // duplicate queued callbacks cannot repeat a repair
    }

    @Test fun eachFaultKindWorksBeforeOrAfterInference() {
        for (content in listOf(
            TaskResumeRecoveryPolicy.Content.PORTRAIT_OR_NULL,
            TaskResumeRecoveryPolicy.Content.UNKNOWN_EMPTY,
            TaskResumeRecoveryPolicy.Content.TABLE_EMPTY,
        )) {
            val policy = TaskResumeRecoveryPolicy()
            policy.arm()
            assertNull(policy.frame(1_000L, content))
            assertNull(policy.frame(8_999L, content))
            assertNotNull(policy.frame(9_000L, content))
        }
    }

    @Test fun firstEmptySourceCannotInheritTheTimeBeforeItWasObserved() {
        val policy = TaskResumeRecoveryPolicy()
        policy.arm()
        assertNull(policy.frame(20_000L))
        assertNull(policy.frame(27_999L))
        assertNotNull(policy.frame(28_000L))
    }

    @Test fun freshGameEvidenceCancelsPendingRepairAndLaterEmptiesCannotRestartIt() {
        val policy = TaskResumeRecoveryPolicy()
        policy.arm()
        policy.frame(0L)
        val request = assertNotNull(policy.frame(8_000L))
        assertNull(policy.frame(8_001L, healthy))
        assertFalse(policy.take(request, 8_002L))
        assertNull(policy.frame(20_000L))
        assertNull(policy.frame(40_000L))
        assertFalse(policy.arm(40_001L, 1L)) // same epoch is not a new discontinuity
    }

    @Test fun partialInputCannotBridgeAnEmptyStreak() {
        val policy = TaskResumeRecoveryPolicy()
        policy.arm()
        policy.frame(0L)
        assertNull(policy.frame(7_999L, TaskResumeRecoveryPolicy.Content.OTHER))
        assertNull(policy.frame(8_000L))
        assertNull(policy.frame(15_999L))
        assertNotNull(policy.frame(16_000L))
    }

    @Test fun normalSceneTransitionsAndAPlayingHandCannotStartAnyRepair() {
        val policy = TaskResumeRecoveryPolicy()
        for (time in 0L..20_000L step 1_000L) {
            assertNull(policy.frame(time, healthy, phase = RoundPhase.ACTIVE))
        }
        val activePolicy = TaskResumeRecoveryPolicy()
        assertFalse(activePolicy.onDiscontinuity(21_000L, 1L, RoundPhase.ACTIVE, oldRound))
        assertNull(activePolicy.frame(21_000L, phase = RoundPhase.ACTIVE))
        assertNull(activePolicy.frame(40_000L, phase = RoundPhase.ACTIVE))
    }

    @Test fun retryRequiresBothFreshPersistenceAndFifteenSecondCooldown() {
        val policy = TaskResumeRecoveryPolicy()
        policy.arm()
        policy.frame(0L)
        assertNotNull(policy.frame(8_000L))
        assertNull(policy.frame(8_001L))
        assertNull(policy.frame(16_000L)) // first fresh streak is one millisecond short
        assertNull(policy.frame(16_001L)) // persistence met, cooldown not met
        assertNull(policy.frame(22_999L))
        assertNotNull(policy.frame(23_000L))
        assertTrue(policy.exhausted)
        assertNull(policy.frame(31_000L))
        assertNull(policy.frame(46_000L))
    }

    @Test fun aNewHealthyEpisodeCannotResetTheSessionBudget() {
        val policy = TaskResumeRecoveryPolicy()
        policy.arm()
        policy.frame(0L)
        assertNotNull(policy.frame(8_000L))
        policy.frame(8_001L, healthy)
        assertTrue(policy.arm(20_000L, 2L))
        policy.frame(20_000L, epoch = 2L)
        assertNotNull(policy.frame(28_000L, epoch = 2L))
        policy.frame(28_001L, healthy, epoch = 2L)
        assertFalse(policy.arm(40_000L, 3L))
        assertNull(policy.frame(40_000L, epoch = 3L))
        assertNull(policy.frame(48_000L, epoch = 3L))
        assertTrue(policy.exhausted)
    }

    @Test fun reattachedEpochRequiresFreshPersistenceAndInvalidatesOldQueuedRequest() {
        val policy = TaskResumeRecoveryPolicy()
        policy.arm()
        policy.frame(0L)
        val old = assertNotNull(policy.frame(8_000L))
        assertTrue(policy.arm(8_001L, 2L))
        assertFalse(policy.take(old, 8_001L, epoch = 2L))
        assertNull(policy.frame(8_002L, epoch = 1L))
        assertNull(policy.frame(8_003L, epoch = 2L, sourceTime = 8_000L))
        assertNull(policy.frame(8_004L, epoch = 2L))
        assertNull(policy.frame(16_004L, epoch = 2L))
        val next = assertNotNull(policy.frame(23_000L, epoch = 2L))
        assertEquals(2L, next.visibilityEpoch)
        assertEquals(2, next.sequence)
    }

    @Test fun repeatedEpochRebindingCannotExtendTheOriginalSixtySecondDeadline() {
        val policy = TaskResumeRecoveryPolicy()
        policy.arm()
        assertTrue(policy.arm(50_000L, 2L))
        policy.frame(51_999L, epoch = 2L)
        assertNull(policy.frame(59_998L, epoch = 2L))
        val request = assertNotNull(policy.frame(59_999L, epoch = 2L))
        assertFalse(policy.take(request, 60_000L))
        assertFalse(policy.arm(60_001L, 3L))
        assertNull(policy.frame(68_002L, epoch = 3L))
    }

    @Test fun callbackStormCannotOpenAnotherExpiredEpisodeWithoutNewHealthyEvidence() {
        val policy = TaskResumeRecoveryPolicy()
        policy.arm()
        assertFalse(policy.arm(60_000L, 2L))
        assertFalse(policy.arm(70_000L, 3L))
        assertFalse(policy.arm(80_000L, 4L))
        assertNull(policy.frame(81_000L, epoch = 4L))
        assertNull(policy.frame(89_000L, epoch = 4L))
    }

    @Test fun wrongRoundOrPhaseCannotRepairOrRetainAPendingCallback() {
        for ((phase, round) in listOf(
            RoundPhase.ACTIVE to oldRound,
            RoundPhase.WAITING to null,
            RoundPhase.RECOVERING to "new-round",
        )) {
            val policy = TaskResumeRecoveryPolicy()
            policy.arm()
            policy.frame(0L)
            val request = assertNotNull(policy.frame(8_000L))
            assertNull(policy.frame(8_001L, phase = phase, round = round))
            assertFalse(policy.take(request, 8_002L))
            assertNull(policy.frame(20_000L))
        }
    }

    @Test fun staleDuplicateFutureOrOldEpochSourcesCannotVoteOrCancel() {
        val policy = TaskResumeRecoveryPolicy()
        policy.arm(10_000L, 2L)
        assertNull(policy.frame(10_000L, epoch = 2L))
        assertNull(policy.frame(17_000L, healthy, epoch = 1L))
        assertNull(policy.frame(17_001L, healthy, epoch = 2L, sourceTime = 10_000L))
        assertNull(policy.frame(17_002L, healthy, epoch = 2L, sourceTime = 17_003L))
        assertNull(policy.frame(17_003L, healthy, epoch = 3L)) // unannounced epoch
        assertNull(policy.frame(17_004L, empty, epoch = 2L, sourceTime = 16_000L))
        assertNotNull(policy.frame(18_000L, epoch = 2L))
    }

    @Test fun genuineContentFromSlowInferenceCancelsButCannotRearmFromStalePixels() {
        val policy = TaskResumeRecoveryPolicy()
        policy.arm()
        policy.frame(0L)
        val request = assertNotNull(policy.frame(8_000L))
        assertNull(policy.frame(10_002L, healthy, sourceTime = 9_000L))
        assertFalse(policy.take(request, 10_003L))
        assertFalse(policy.arm(11_000L, 2L))
        assertNull(policy.frame(11_001L, epoch = 2L))
        assertNull(policy.frame(20_000L, epoch = 2L))
    }

    @Test fun firstNewEpochFrameCanRebindWithItsOriginalSourceFenceWithoutClockRollback() {
        val policy = TaskResumeRecoveryPolicy()
        policy.arm()
        policy.frame(0L)
        assertTrue(policy.onDiscontinuity(500L, 2L, RoundPhase.RECOVERING, oldRound, sourceFenceMs = 400L))
        assertNull(policy.frame(501L, epoch = 2L, sourceTime = 399L))
        assertNull(policy.frame(502L, epoch = 2L, sourceTime = 400L))
        assertNotNull(policy.frame(8_400L, epoch = 2L))
    }

    @Test fun oldSourcesCannotSeedPersistenceAfterADiscontinuity() {
        val policy = TaskResumeRecoveryPolicy()
        policy.arm(10_000L)
        assertNull(policy.frame(10_001L, sourceTime = 9_999L))
        assertNull(policy.frame(17_999L))
        assertNull(policy.frame(18_000L))
        assertNotNull(policy.frame(25_999L))
    }

    @Test fun callbacksAreBoundToCurrentEpochRoundAndTwoSecondValidity() {
        for (case in 0..3) {
            val policy = TaskResumeRecoveryPolicy()
            policy.arm()
            policy.frame(0L)
            val request = assertNotNull(policy.frame(8_000L))
            when (case) {
                0 -> assertFalse(policy.take(request, 8_001L, epoch = 2L))
                1 -> assertFalse(policy.take(request, 8_001L, round = "other-round"))
                2 -> assertFalse(policy.take(request, 8_001L, phase = RoundPhase.ACTIVE))
                3 -> assertFalse(policy.take(request, 10_001L))
            }
        }
        val policy = TaskResumeRecoveryPolicy()
        policy.arm()
        policy.frame(0L)
        assertTrue(policy.take(assertNotNull(policy.frame(8_000L)), 10_000L))
    }

    @Test fun rollbackClosesTheEpisodeRatherThanInventingNewPersistence() {
        val policy = TaskResumeRecoveryPolicy()
        policy.arm(10_000L)
        policy.frame(10_000L)
        assertNull(policy.frame(9_999L))
        assertNull(policy.frame(18_000L))
        assertNull(policy.frame(30_000L))
        assertFalse(policy.arm(31_000L, 2L))
    }
}
