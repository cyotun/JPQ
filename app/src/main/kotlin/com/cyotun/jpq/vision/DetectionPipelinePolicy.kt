package com.cyotun.jpq.vision

/** Selects the detector path once when a recognition session starts. */
internal object DetectionPipelinePolicy {
    const val FULL_CPU = "FULL_CPU"
    const val REFERENCE = "REFERENCE"

    fun select(
        isCandidate: Boolean,
        storedFingerprint: String?,
        storedVersion: String?,
        currentFingerprint: String,
        expectedVersion: String
    ): String = if (!isCandidate && storedFingerprint == currentFingerprint && storedVersion == expectedVersion)
        REFERENCE else FULL_CPU
}
