package com.cyotun.jpq.vision

/** Promoted capture sessions use the full CPU path, including DISPLAY on older Android. */
internal object DetectionPipelinePolicy {
    const val FULL_CPU = "FULL_CPU"
    const val REFERENCE = "REFERENCE"

    fun select(
        singleAppCaptureEnabled: Boolean,
        isCandidate: Boolean,
        storedFingerprint: String?,
        storedVersion: String?,
        currentFingerprint: String,
        expectedVersion: String
    ): String = if (!singleAppCaptureEnabled && !isCandidate &&
        storedFingerprint == currentFingerprint && storedVersion == expectedVersion)
        REFERENCE else FULL_CPU
}
