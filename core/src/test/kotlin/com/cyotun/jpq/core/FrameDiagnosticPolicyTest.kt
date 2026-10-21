package com.cyotun.jpq.core

import kotlin.test.*

class FrameDiagnosticPolicyTest {
    @Test fun failedTaskStartupRetainsSparsePixelsBeforeAnyRoundExists() {
        val plan=FrameDiagnosticPolicy.decide(false,true,CaptureScope.APP_TASK,TableScene.UNKNOWN)
        assertTrue(plan.eligible)
        assertEquals(15000L,plan.minimumIntervalMs)
    }
    @Test fun stableDisplayAndOtherScenesKeepTheirPreviousEligibility() {
        for(candidate in listOf(false,true)) for(scene in TableScene.entries) {
            val plan=FrameDiagnosticPolicy.decide(false,candidate,CaptureScope.DISPLAY,scene)
            assertFalse(plan.eligible)
        }
        for(scene in TableScene.entries.filter { it!=TableScene.UNKNOWN }) {
            assertFalse(FrameDiagnosticPolicy.decide(false,true,CaptureScope.APP_TASK,scene).eligible)
        }
        assertFalse(FrameDiagnosticPolicy.decide(false,false,CaptureScope.APP_TASK,TableScene.UNKNOWN).eligible)
    }
    @Test fun existingRoundAndRecognisedSceneEvidenceKeepTheFasterCadence() {
        for(scope in CaptureScope.entries) for(candidate in listOf(false,true)) for(scene in TableScene.entries) {
            assertEquals(FrameDiagnosticDecision(true,2000L),FrameDiagnosticPolicy.decide(true,candidate,scope,scene))
        }
    }
}
