package com.cyotun.jpq.vision;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ScopeProbeEvidenceTest {
    private ScopeProbeEvidence baseline() {
        ScopeProbeEvidence evidence=new ScopeProbeEvidence();
        assertTrue(evidence.baseline(1,100)); assertTrue(evidence.baseline(2,200));
        return evidence;
    }
    @Test public void independentPresentFramesEstablishPositiveControl() {
        ScopeProbeEvidence evidence=baseline();
        for(int i=3;i<=5;i++) evidence.overlay(i,i*100,"landscape-position1",true,false);
        assertEquals("OVERLAY_PIXELS_PRESENT",evidence.classification());
    }
    @Test public void independentGreenFramesEstablishOnlyPixelAbsence() {
        ScopeProbeEvidence evidence=baseline();
        for(int i=3;i<=5;i++) evidence.overlay(i,i*100,"landscape-position1",false,true);
        assertEquals("OVERLAY_PIXELS_NOT_OBSERVED",evidence.classification());
    }
    @Test public void sameTimestampCannotCountAsSeveralCapturedFrames() {
        ScopeProbeEvidence evidence=baseline();
        assertTrue(evidence.overlay(3,300,"one",false,true));
        assertFalse(evidence.overlay(4,300,"one",false,true));
        assertFalse(evidence.overlay(5,250,"one",false,true));
        assertEquals(1,evidence.absentFrames());
        assertEquals("INCONCLUSIVE",evidence.classification());
    }
    @Test public void dynamicFramesElsewhereDoNotQualifyAFrozenGreenRegion() {
        ScopeProbeEvidence evidence=baseline();
        evidence.overlay(3,300,"one",false,false);
        for(int i=4;i<=6;i++) evidence.overlay(4,i*100,"one",false,true);
        assertEquals(3,evidence.absentFrames());
        assertEquals(1,evidence.absentDistinctCounters());
        assertEquals("INCONCLUSIVE",evidence.classification());
    }
    @Test public void separateReaderSizesOrPositionsCannotCombineTheirVotes() {
        ScopeProbeEvidence evidence=baseline();
        evidence.overlay(3,300,"landscape",false,true);
        evidence.overlay(4,400,"landscape",false,true);
        evidence.overlay(5,500,"portrait",false,true);
        assertEquals(1,evidence.absentFrames());
        assertEquals("INCONCLUSIVE",evidence.classification());
        evidence.overlay(6,600,"portrait",false,true);
        evidence.overlay(7,700,"portrait",false,true);
        assertEquals("OVERLAY_PIXELS_NOT_OBSERVED",evidence.classification());
    }
    @Test public void mixedVisibleAndGreenPixelsRemainInconclusive() {
        ScopeProbeEvidence evidence=baseline();
        for(int i=3;i<=5;i++) evidence.overlay(i,i*100,"one",false,true);
        evidence.overlay(6,600,"one",true,false);
        assertEquals("INCONCLUSIVE",evidence.classification());
    }
    @Test public void movingAnOverlayInvalidatesPriorConclusionWithoutNeedingANewFrame() {
        ScopeProbeEvidence evidence=baseline();
        for(int i=3;i<=5;i++) evidence.overlay(i,i*100,"one",false,true);
        evidence.invalidateOverlay();
        assertNull(evidence.geometry()); assertEquals(0,evidence.absentFrames());
        assertEquals("INCONCLUSIVE",evidence.classification());
    }
    @Test public void baselineRequiresIndependentDynamicCounters() {
        ScopeProbeEvidence evidence=new ScopeProbeEvidence();
        evidence.baseline(1,100); evidence.baseline(1,200); assertFalse(evidence.hasBaseline());
        evidence.baseline(2,300); assertTrue(evidence.hasBaseline());
    }
    @Test public void absentBaselineAndInvalidFramesCannotDeclareScope() {
        ScopeProbeEvidence evidence=new ScopeProbeEvidence();
        assertFalse(evidence.baseline(0,10)); assertFalse(evidence.baseline(65536,20));
        assertFalse(evidence.baseline(1,0));
        assertFalse(evidence.overlay(1,50,null,false,true));
        for(int i=3;i<=5;i++) evidence.overlay(i,i*100,"one",false,true);
        assertEquals("INCONCLUSIVE",evidence.classification());
    }
    @Test public void unexpectedTerminationCannotDeclareASuccessfulRun() {
        ScopeProbeEvidence evidence=baseline();
        for(int i=3;i<=5;i++) evidence.overlay(i,i*100,"one",false,true);
        for(String reason:new String[]{"projection_stopped","external_overlay_error","frame_error",
            "fixture_frames_stalled_or_unrecognized","fixture_left_foreground","resize_error"})
            assertEquals(reason,"INCONCLUSIVE",evidence.classificationForStop(reason));
        assertEquals("OVERLAY_PIXELS_NOT_OBSERVED",evidence.classification());
    }
    @Test public void normalTimeoutAndExplicitStopKeepQualifiedPixelEvidence() {
        ScopeProbeEvidence evidence=baseline();
        for(int i=3;i<=5;i++) evidence.overlay(i,i*100,"one",true,false);
        for(String reason:new String[]{"timeout","notification_stop","service_destroyed"})
            assertEquals("OVERLAY_PIXELS_PRESENT",evidence.classificationForStop(reason));
    }
}
