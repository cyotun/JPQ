package com.cyotun.jpq

import com.cyotun.jpq.core.CaptureInputPolicy
import com.cyotun.jpq.core.CaptureScope
import com.cyotun.jpq.updates.UpdateRowAction
import com.cyotun.jpq.updates.UpdateRowPolicy
import kotlin.test.*

/** Checks the generated build identity as well as the shared policy. No Android or network calls. */
class CapturePromotionContractTest {
    @Test fun formalAndBetaBuildsRetainSeparateIdentitiesWithThePromotedCapability() {
        when(BuildConfig.APPLICATION_ID) {
            "com.cyotun.jpq" -> {
                assertFalse(BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE)
                assertTrue(BuildConfig.SINGLE_APP_CAPTURE_ENABLED)
            }
            "com.cyotun.jpq.candidate" -> {
                assertTrue(BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE)
                assertTrue(BuildConfig.SINGLE_APP_CAPTURE_ENABLED)
            }
        }
    }

    @Test fun enabledTaskCaptureDoesNotDisableFormalUpdatesOrEnableBetaUpdates() {
        val row=UpdateRowPolicy.state(BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE,
            currentVersion=BuildConfig.VERSION_CODE.toLong(),
            availableVersion=BuildConfig.VERSION_CODE.toLong()+1,
            readyVersion=BuildConfig.VERSION_CODE.toLong()+1,
            needsCheck=false,recording=false,operation=null,progress=-1)
        if(BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE) {
            assertEquals(UpdateRowAction.NONE,row.action)
            assertFalse(row.enabled)
        } else {
            assertEquals(UpdateRowAction.INSTALL,row.action)
            assertTrue(row.enabled)
        }
    }

    @Test fun generatedFlagsChooseTheCorrectScopeAcrossTheSystemBoundary() {
        val enabled=BuildConfig.SINGLE_APP_CAPTURE_ENABLED
        val beta=BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE
        assertEquals(if(enabled) CaptureScope.APP_TASK else CaptureScope.DISPLAY,
            CaptureInputPolicy.requestedScope(enabled,beta,37))
        if(beta) assertFailsWith<IllegalArgumentException> { CaptureInputPolicy.requestedScope(enabled,beta,36) }
        else assertEquals(CaptureScope.DISPLAY,CaptureInputPolicy.requestedScope(enabled,beta,36))
    }
}
