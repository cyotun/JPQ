package com.cyotun.jpq.vision;

/** A stalled image producer must not disable foreground or fixture-loss cleanup. */
final class ScopeProbeWatchdog {
    private long lastKnownPatternAt;
    private long lostForegroundAt=-1;
    private long lastTimestamp=-1;
    private int lastCounter=-1;
    ScopeProbeWatchdog(long now) { lastKnownPatternAt=now; }
    void knownPattern(long now,int counter,long timestampNs) {
        if(counter<=0 || timestampNs<=0 || timestampNs<=lastTimestamp) return;
        lastTimestamp=timestampNs;
        if(counter!=lastCounter) { lastKnownPatternAt=now; lastCounter=counter; }
    }
    String stopReason(long now,boolean foreground) {
        if(foreground) lostForegroundAt=-1;
        else if(lostForegroundAt<0) lostForegroundAt=now;
        if(lostForegroundAt>=0 && now-lostForegroundAt>=1500) return "fixture_left_foreground";
        if(now-lastKnownPatternAt>=5000) return "fixture_frames_stalled_or_unrecognized";
        return null;
    }
}
