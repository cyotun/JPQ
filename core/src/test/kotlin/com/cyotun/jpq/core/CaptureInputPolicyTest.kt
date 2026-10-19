package com.cyotun.jpq.core

import kotlin.test.*

class CaptureInputPolicyTest {
    @Test fun taskFirstFrameWaitsForRealSizeEvenWhenItEqualsTheScreen() {
        val geometry=CaptureContentGeometry(CaptureScope.APP_TASK)
        assertFalse(geometry.accepts(2608,1200))
        geometry.confirm(2608,1200)
        assertTrue(geometry.accepts(2608,1200))
        geometry.confirm(1200,2608)
        assertFalse(geometry.accepts(2608,1200))
        assertTrue(geometry.accepts(1200,2608))
        assertFalse(CaptureContentGeometry(CaptureScope.APP_TASK).accepts(1200,2608))
    }
    @Test fun invalidCallbackCannotReplaceConfirmedTaskDimensions() {
        val geometry=CaptureContentGeometry(CaptureScope.APP_TASK)
        geometry.confirm(960,540)
        assertFailsWith<IllegalArgumentException> { geometry.confirm(0,0) }
        assertTrue(geometry.accepts(960,540))
        assertTrue(CaptureContentGeometry(CaptureScope.DISPLAY).accepts(2608,1200))
    }
    @Test fun taskCaptureKeepsRealContentOrientationAndResolution() {
        assertEquals(CaptureDimensions(1200,2608),CaptureInputPolicy.dimensions(1200,2608,CaptureScope.APP_TASK,true))
        assertEquals(CaptureDimensions(2608,1200),CaptureInputPolicy.dimensions(2608,1200,CaptureScope.APP_TASK,false))
        assertEquals(CaptureDimensions(960,540),CaptureInputPolicy.dimensions(960,540,CaptureScope.APP_TASK,true))
    }
    @Test fun screenCompatibilityKeepsTheExistingFixedLandscapeCanvas() {
        assertEquals(CaptureDimensions(2608,1200),CaptureInputPolicy.dimensions(1200,2608,CaptureScope.DISPLAY,true))
        assertEquals(CaptureDimensions(1280,588),CaptureInputPolicy.dimensions(2608,1200,CaptureScope.DISPLAY,false))
    }
    @Test fun externalBarMovementCannotEraseTaskPixelsOrPauseTaskEvidence() {
        assertFalse(CaptureInputPolicy.usesScreenOcclusions(CaptureScope.APP_TASK))
        assertFalse(CaptureInputPolicy.pausesForOverlayInteraction(CaptureScope.APP_TASK,true))
        assertTrue(CaptureInputPolicy.usesScreenOcclusions(CaptureScope.DISPLAY))
        assertTrue(CaptureInputPolicy.pausesForOverlayInteraction(CaptureScope.DISPLAY,true))
    }
    @Test fun stableBuildAndOlderDevicesCannotClaimStrictTaskCapture() {
        assertFalse(CaptureInputPolicy.acceptsScope(CaptureScope.APP_TASK,false,37))
        assertFalse(CaptureInputPolicy.acceptsScope(CaptureScope.APP_TASK,true,36))
        assertTrue(CaptureInputPolicy.acceptsScope(CaptureScope.APP_TASK,true,37))
        assertTrue(CaptureInputPolicy.acceptsScope(CaptureScope.DISPLAY,false,28))
    }
    @Test fun invalidDimensionsCannotReachTheReader() {
        assertFailsWith<IllegalArgumentException> { CaptureInputPolicy.dimensions(0,1200,CaptureScope.APP_TASK,true) }
        assertFailsWith<IllegalArgumentException> { CaptureInputPolicy.dimensions(2608,-1,CaptureScope.DISPLAY,true) }
    }
}
