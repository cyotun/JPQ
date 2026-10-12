package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Verify exact input reuse on a real interpreter, including changed pixels and source geometry. */
@RunWith(AndroidJUnit4::class)
class DetectorInputCacheTest {
    @Test fun onlyIdenticalPixelsAndSourceGeometryReuseTheInference() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val first=Bitmap.createBitmap(16,8,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20,150,140)) }
        val identical=checkNotNull(first.copy(Bitmap.Config.ARGB_8888,false))
        val changed=checkNotNull(first.copy(Bitmap.Config.ARGB_8888,true)).apply { setPixel(8,4,Color.WHITE) }
        val differentSize=Bitmap.createScaledBitmap(first,32,16,false)
        try {
            LocalCardDetector(context,forceBackend="CPU").use { detector ->
                detector.detect(first); assertFalse(detector.cacheHit); assertEquals(1L,detector.inferenceCount)
                detector.detect(identical); assertTrue(detector.cacheHit); assertEquals(1L,detector.inferenceCount)
                detector.detect(changed); assertFalse(detector.cacheHit); assertEquals(2L,detector.inferenceCount)
                detector.detect(changed); assertTrue(detector.cacheHit); assertEquals(2L,detector.inferenceCount)
                detector.detect(first); assertFalse(detector.cacheHit); assertEquals(3L,detector.inferenceCount)
                detector.detect(differentSize); assertFalse(detector.cacheHit); assertEquals(4L,detector.inferenceCount)
            }
        } finally { first.recycle(); identical.recycle(); changed.recycle(); differentSize.recycle() }
    }
}
