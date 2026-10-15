package com.cyotun.jpq.vision;

/** Hue evidence for the known overlay, including the OEM's measured 80% composition. */
final class ScopeProbeColors {
    static boolean magenta(int p) {
        int a=p>>>24&255,r=p>>>16&255,g=p>>>8&255,b=p&255;
        return a>=220 && r>=180 && b>=180 && Math.abs(r-b)<=55 && r-g>=70 && b-g>=70;
    }
}
