package com.cyotun.jpq.vision

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aijpq.pokerrecorder.runtime.preprocess.*
import com.cyotun.jpq.AppState
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferenceExitRegionCacheTest {
    private fun region(id: String,state: String,box: IntBox)=ActiveRegion(id,id,box,.8,3,state,500,50,
        "normal_active_region",null,null,1000L,null,null,null)
    @Test fun cachedExitReadsCurrentFrameIdentityAndIsReplacedByNewActivity() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("referenceDetectorProbe")=="true")
        check(!AppState.running)
        val cache=ReferenceExitRegionCache()
        val exit=region("old","exit_probe",IntBox(100,100,200,200))
        assertEquals(1,cache.merge(1000,listOf(exit)).size)
        val retained=cache.merge(1500,emptyList()).single()
        assertEquals(exit.bbox,retained.bbox)
        assertEquals(1500L,retained.observationTimestampMs)
        val fresh=region("new","active",IntBox(100,100,205,205))
        assertEquals(listOf(fresh),cache.merge(1600,listOf(fresh)))
        assertTrue(cache.merge(1700,emptyList()).isEmpty())
    }
    @Test fun noDuplicateCurrentTrackAndResetClearsStoredGeometry() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("referenceDetectorProbe")=="true")
        check(!AppState.running)
        val cache=ReferenceExitRegionCache()
        val exit=region("old","exit_probe",IntBox(100,100,200,200))
        cache.merge(1000,listOf(exit))
        assertEquals(1,cache.merge(1100,listOf(exit)).size)
        cache.clear()
        assertTrue(cache.merge(1200,emptyList()).isEmpty())
    }
}
