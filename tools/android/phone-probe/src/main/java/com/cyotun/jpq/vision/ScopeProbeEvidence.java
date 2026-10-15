package com.cyotun.jpq.vision;

import java.util.HashSet;
import java.util.Set;

/** Independent frame evidence for one settled overlay geometry, without Android dependencies. */
final class ScopeProbeEvidence {
    private final Set<Integer> baselineCounters=new HashSet<>();
    private final Set<Integer> presentCounters=new HashSet<>();
    private final Set<Integer> absentCounters=new HashSet<>();
    private long lastTimestamp=-1;
    private String geometry;
    private int baselineFrames,presentFrames,absentFrames;

    boolean baseline(int counter,long timestampNs) {
        if(!fresh(counter,timestampNs)) return false;
        baselineFrames++; baselineCounters.add(counter); return true;
    }
    boolean overlay(int counter,long timestampNs,String geometryKey,boolean present,boolean absent) {
        if(geometryKey==null || !fresh(counter,timestampNs)) return false;
        if(!geometryKey.equals(geometry)) {
            geometry=geometryKey;
            presentFrames=0; absentFrames=0; presentCounters.clear(); absentCounters.clear();
        }
        if(present && !absent) { presentFrames++; presentCounters.add(counter); }
        else if(absent && !present) { absentFrames++; absentCounters.add(counter); }
        return true;
    }
    private boolean fresh(int counter,long timestampNs) {
        if(counter<=0 || counter>65535 || timestampNs<=0 || timestampNs<=lastTimestamp) return false;
        lastTimestamp=timestampNs; return true;
    }
    void invalidateOverlay() {
        geometry=null; presentFrames=0; absentFrames=0; presentCounters.clear(); absentCounters.clear();
    }
    boolean hasBaseline() { return baselineFrames>=2 && baselineCounters.size()>=2; }
    String classification() {
        if(!hasBaseline()) return "INCONCLUSIVE";
        if(presentFrames>=3 && presentCounters.size()>=2 && absentFrames==0) return "OVERLAY_PIXELS_PRESENT";
        if(absentFrames>=3 && absentCounters.size()>=2 && presentFrames==0) return "OVERLAY_PIXELS_NOT_OBSERVED";
        return "INCONCLUSIVE";
    }
    String classificationForStop(String reason) {
        if(!"timeout".equals(reason) && !"notification_stop".equals(reason) && !"service_destroyed".equals(reason))
            return "INCONCLUSIVE";
        return classification();
    }
    int presentFrames() { return presentFrames; }
    int absentFrames() { return absentFrames; }
    int presentDistinctCounters() { return presentCounters.size(); }
    int absentDistinctCounters() { return absentCounters.size(); }
    String geometry() { return geometry; }
}
