package com.cyotun.jpq.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskStartupRecoveryPolicyTest {
    @Test fun requiresAContinuousThreeSecondEligibleSuspicion() {
        val policy = TaskStartupRecoveryPolicy()
        assertFalse(policy.observe(100L, eligible = true, suspicious = true))
        assertFalse(policy.observe(3_099L, eligible = true, suspicious = true))
        assertTrue(policy.observe(3_100L, eligible = true, suspicious = true))
        assertEquals(TaskStartupRecoveryPolicy.RequestReason.MOSTLY_BLACK, policy.lastRequestReason)
    }

    @Test fun persistentUnknownStartupTriggersFallbackAtEightSeconds() {
        val policy = TaskStartupRecoveryPolicy()
        assertFalse(policy.observe(1_000L, eligible = true, suspicious = false))
        assertFalse(policy.observe(8_999L, eligible = true, suspicious = false))
        assertTrue(policy.observe(9_000L, eligible = true, suspicious = false))
        assertEquals(TaskStartupRecoveryPolicy.RequestReason.NO_RECOGNIZABLE_STARTUP, policy.lastRequestReason)
    }

    @Test fun healthyEligibleFramesResetBlackIntervalButKeepUnknownInterval() {
        val policy = TaskStartupRecoveryPolicy()
        assertFalse(policy.observe(0L, true, true))
        assertFalse(policy.observe(2_000L, true, false))
        assertFalse(policy.observe(5_000L, true, true))
        assertTrue(policy.observe(8_000L, true, true))
        assertEquals(TaskStartupRecoveryPolicy.RequestReason.MOSTLY_BLACK, policy.lastRequestReason)
    }

    @Test fun ineligibleFrameResetsBothIntervals() {
        val policy = TaskStartupRecoveryPolicy()
        assertFalse(policy.observe(0L, true, false))
        assertFalse(policy.observe(7_999L, true, false))
        assertFalse(policy.observe(8_000L, false, false))
        assertFalse(policy.observe(15_999L, true, false))
        assertFalse(policy.observe(23_998L, true, false))
        assertTrue(policy.observe(23_999L, true, false))
        assertEquals(TaskStartupRecoveryPolicy.RequestReason.NO_RECOGNIZABLE_STARTUP, policy.lastRequestReason)
    }

    @Test fun eachFallbackRequestRestartsPersistenceAndKeepsCooldown() {
        val policy = TaskStartupRecoveryPolicy()
        assertFalse(policy.observe(0L, true, false))
        assertTrue(policy.observe(8_000L, true, false))
        assertFalse(policy.observe(15_999L, true, false)) // fresh streak is one millisecond short
        assertFalse(policy.observe(16_000L, true, false)) // persistence met; cooldown is not
        assertFalse(policy.observe(22_999L, true, false)) // cooldown is one millisecond short
        assertTrue(policy.observe(23_000L, true, false))
        assertEquals(TaskStartupRecoveryPolicy.RequestReason.NO_RECOGNIZABLE_STARTUP, policy.lastRequestReason)
    }

    @Test fun ineligibleOrHealthyFrameBreaksTheStreak() {
        val policy = TaskStartupRecoveryPolicy()
        assertFalse(policy.observe(0L, true, true))
        assertFalse(policy.observe(2_000L, false, true))
        assertFalse(policy.observe(4_999L, true, true))
        assertFalse(policy.observe(5_000L, true, false))
        assertFalse(policy.observe(7_999L, true, true))
        assertFalse(policy.observe(10_998L, true, true))
        assertTrue(policy.observe(10_999L, true, true))
    }

    @Test fun requestsAreAtLeastFifteenSecondsApartAndEachNeedsFreshPersistence() {
        val policy = TaskStartupRecoveryPolicy()
        assertFalse(policy.observe(0L, true, true))
        assertTrue(policy.observe(3_000L, true, true))

        assertFalse(policy.observe(5_999L, true, true))
        assertFalse(policy.observe(6_000L, true, true)) // persistence is met; cooldown is not
        assertFalse(policy.observe(17_999L, true, true)) // cooldown is still one millisecond short
        assertTrue(policy.observe(18_000L, true, true))
    }

    @Test fun healthyFramesDoNotResetTheSessionRequestLimit() {
        val policy = TaskStartupRecoveryPolicy()
        assertFalse(policy.observe(0L, true, true))
        assertTrue(policy.observe(3_000L, true, true))
        assertFalse(policy.observe(3_001L, true, false))
        assertFalse(policy.observe(3_002L, false, false))

        assertFalse(policy.observe(20_000L, true, true))
        assertTrue(policy.observe(23_000L, true, true))
        assertFalse(policy.observe(23_001L, true, false))
        assertFalse(policy.observe(40_000L, true, true))
        assertFalse(policy.observe(80_000L, true, true))
        assertTrue(policy.exhausted)
        assertEquals(TaskStartupRecoveryPolicy.RequestReason.MOSTLY_BLACK, policy.lastRequestReason)
    }

    @Test fun clockRollbackBreaksStreakAndCannotTriggerRequest() {
        val policy = TaskStartupRecoveryPolicy()
        assertFalse(policy.observe(10_000L, true, true))
        assertFalse(policy.observe(12_000L, true, true))
        assertFalse(policy.observe(5_000L, true, true)) // rollback starts a new streak
        assertFalse(policy.observe(7_999L, true, true))
        assertTrue(policy.observe(8_000L, true, true))
    }

    @Test fun clockRollbackAlsoRestartsTheUnknownFallbackInterval() {
        val policy = TaskStartupRecoveryPolicy()
        assertFalse(policy.observe(10_000L, true, false))
        assertFalse(policy.observe(17_999L, true, false))
        assertFalse(policy.observe(5_000L, true, false))
        assertFalse(policy.observe(12_999L, true, false))
        assertTrue(policy.observe(13_000L, true, false))
        assertEquals(TaskStartupRecoveryPolicy.RequestReason.NO_RECOGNIZABLE_STARTUP, policy.lastRequestReason)
    }

    @Test fun blackFractionAcceptsOnlyFiniteValuesFromEightyToNinetyNinePercent() {
        assertFalse(TaskStartupRecoveryPolicy.isSuspiciousBlackFraction(0.79f))
        assertTrue(TaskStartupRecoveryPolicy.isSuspiciousBlackFraction(0.80f))
        assertTrue(TaskStartupRecoveryPolicy.isSuspiciousBlackFraction(0.90f))
        assertTrue(TaskStartupRecoveryPolicy.isSuspiciousBlackFraction(0.99f))
        assertFalse(TaskStartupRecoveryPolicy.isSuspiciousBlackFraction(1.0f))
        assertFalse(TaskStartupRecoveryPolicy.isSuspiciousBlackFraction(Float.NaN))
        assertFalse(TaskStartupRecoveryPolicy.isSuspiciousBlackFraction(Float.POSITIVE_INFINITY))
        assertFalse(TaskStartupRecoveryPolicy.isSuspiciousBlackFraction(Float.NEGATIVE_INFINITY))
    }
}
