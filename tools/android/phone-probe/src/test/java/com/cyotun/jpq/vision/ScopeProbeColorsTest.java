package com.cyotun.jpq.vision;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ScopeProbeColorsTest {
    @Test public void directAndActuallyMeasuredCompositeOverlayRemainPositive() {
        assertTrue(ScopeProbeColors.magenta(0xffff00ff));
        // Real full-display PNG: 80% magenta over green is (204,51,204).
        assertTrue(ScopeProbeColors.magenta(0xffcc33cc));
    }
    @Test public void fixtureColorsGreyAndGoldCannotPretendToBeTheOverlay() {
        for(int color:new int[]{0xff00ff00,0xff0000ff,0xffff9900,0xffffff00,
            0xff000000,0xffffffff,0xffaaaaaa,0xffff0000,0xffffe491})
            assertFalse(Integer.toHexString(color),ScopeProbeColors.magenta(color));
    }
    @Test public void weakOrTransparentUnknownColorsDoNotEstablishOverlayPresence() {
        assertFalse(ScopeProbeColors.magenta(0xff998899));
        assertFalse(ScopeProbeColors.magenta(0x00ff00ff));
        assertFalse(ScopeProbeColors.magenta(0xffcc99cc));
    }
}
