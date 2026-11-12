package com.cyotun.jpq.vision

import kotlin.test.Test
import kotlin.test.assertEquals

class DetectionPipelinePolicyTest {
    @Test fun candidateAlwaysUsesFullCpuEvenWithCurrentReferenceQualification() {
        assertEquals(DetectionPipelinePolicy.FULL_CPU,DetectionPipelinePolicy.select(
            singleAppCaptureEnabled=true,isCandidate=true,storedFingerprint="device",storedVersion="v1",
            currentFingerprint="device",expectedVersion="v1"))
    }

    @Test fun stableBuildRetainsReferenceQualificationRequirements() {
        assertEquals(DetectionPipelinePolicy.REFERENCE,DetectionPipelinePolicy.select(
            singleAppCaptureEnabled=false,isCandidate=false,storedFingerprint="device",storedVersion="v1",
            currentFingerprint="device",expectedVersion="v1"))
        assertEquals(DetectionPipelinePolicy.FULL_CPU,DetectionPipelinePolicy.select(
            singleAppCaptureEnabled=false,isCandidate=false,storedFingerprint="other-device",storedVersion="v1",
            currentFingerprint="device",expectedVersion="v1"))
        assertEquals(DetectionPipelinePolicy.FULL_CPU,DetectionPipelinePolicy.select(
            singleAppCaptureEnabled=false,isCandidate=false,storedFingerprint="device",storedVersion="old",
            currentFingerprint="device",expectedVersion="v1"))
    }

    @Test fun promotedFormalBuildCannotInheritOldReferenceQualificationEvenWithExactMatch() {
        assertEquals(DetectionPipelinePolicy.FULL_CPU,DetectionPipelinePolicy.select(
            singleAppCaptureEnabled=true,isCandidate=false,storedFingerprint="device",storedVersion="v1",
            currentFingerprint="device",expectedVersion="v1"))
    }

    @Test fun betaIdentityStillCannotUseReferenceWhenFeatureIsAccidentallyDisabled() {
        assertEquals(DetectionPipelinePolicy.FULL_CPU,DetectionPipelinePolicy.select(
            singleAppCaptureEnabled=false,isCandidate=true,storedFingerprint="device",storedVersion="v1",
            currentFingerprint="device",expectedVersion="v1"))
    }
}
