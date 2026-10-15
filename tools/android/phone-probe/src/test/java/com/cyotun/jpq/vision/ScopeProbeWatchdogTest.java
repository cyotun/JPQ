package com.cyotun.jpq.vision;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ScopeProbeWatchdogTest {
    @Test public void noImageCallbacksStillStopAfterFiveSeconds() {
        ScopeProbeWatchdog watchdog=new ScopeProbeWatchdog(1000);
        assertNull(watchdog.stopReason(5999,true));
        assertEquals("fixture_frames_stalled_or_unrecognized",watchdog.stopReason(6000,true));
    }
    @Test public void leavingFixtureStopsEvenWhenImageProducerIsSilent() {
        ScopeProbeWatchdog watchdog=new ScopeProbeWatchdog(1000);
        assertNull(watchdog.stopReason(1200,false));
        assertNull(watchdog.stopReason(2699,false));
        assertEquals("fixture_left_foreground",watchdog.stopReason(2700,false));
    }
    @Test public void briefFocusChangeDoesNotEndAnOtherwiseLiveExperiment() {
        ScopeProbeWatchdog watchdog=new ScopeProbeWatchdog(1000);
        assertNull(watchdog.stopReason(1200,false));
        watchdog.knownPattern(2400,2,200);
        assertNull(watchdog.stopReason(2500,true));
        assertNull(watchdog.stopReason(3500,false));
        assertEquals("fixture_left_foreground",watchdog.stopReason(5000,false));
    }
    @Test public void dynamicFramesResetTheStallDeadline() {
        ScopeProbeWatchdog watchdog=new ScopeProbeWatchdog(1000);
        watchdog.knownPattern(2000,1,100); watchdog.knownPattern(6000,2,200);
        assertNull(watchdog.stopReason(10999,true));
        assertEquals("fixture_frames_stalled_or_unrecognized",watchdog.stopReason(11000,true));
    }
    @Test public void repeatedTimestampAndFrozenCountersCannotKeepSessionAlive() {
        ScopeProbeWatchdog watchdog=new ScopeProbeWatchdog(1000);
        watchdog.knownPattern(2000,1,100);
        watchdog.knownPattern(4000,2,100);
        watchdog.knownPattern(5000,1,200);
        assertEquals("fixture_frames_stalled_or_unrecognized",watchdog.stopReason(7000,true));
    }
    @Test public void counterWrapStillShowsProgressAndInvalidCountersDoNot() {
        ScopeProbeWatchdog watchdog=new ScopeProbeWatchdog(1000);
        watchdog.knownPattern(2000,65535,100); watchdog.knownPattern(3000,1,200);
        watchdog.knownPattern(6000,0,300);
        assertNull(watchdog.stopReason(7999,true));
        assertEquals("fixture_frames_stalled_or_unrecognized",watchdog.stopReason(8000,true));
    }
}
