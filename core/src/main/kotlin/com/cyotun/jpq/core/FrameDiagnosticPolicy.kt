package com.cyotun.jpq.core

data class FrameDiagnosticDecision(val eligible: Boolean,val minimumIntervalMs: Long)

/** An unrecognised task input must not erase the only evidence of its bad layout. */
object FrameDiagnosticPolicy {
    fun decide(baseEligible: Boolean,candidate: Boolean,scope: CaptureScope,scene: TableScene): FrameDiagnosticDecision {
        if(baseEligible) return FrameDiagnosticDecision(true,2000L)
        if(candidate && scope==CaptureScope.APP_TASK && scene==TableScene.UNKNOWN)
            return FrameDiagnosticDecision(true,15000L)
        return FrameDiagnosticDecision(false,2000L)
    }
}
