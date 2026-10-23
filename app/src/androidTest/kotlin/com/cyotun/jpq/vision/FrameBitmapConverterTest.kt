package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed byte fixtures independently verify RGBA order, row packing and opaque RGB. */
@RunWith(AndroidJUnit4::class)
class FrameBitmapConverterTest {
    private companion object {
        const val BENCHMARK_WIDTH=2608
        const val BENCHMARK_HEIGHT=1200
        const val BENCHMARK_ROW_PADDING=64
        const val BENCHMARK_WARMUPS=3
        const val BENCHMARK_SAMPLES=7
        const val BENCHMARK_TAG="FrameCopyBenchmark"
    }

    private fun bytes(vararg values: Int)=ByteBuffer.allocateDirect(values.size).apply {
        values.forEach { put(it.toByte()) }; flip()
    }
    private fun colors(bitmap: Bitmap,vararg expected: Int) {
        assertEquals(bitmap.width*bitmap.height,expected.size)
        expected.forEachIndexed { index,color -> assertEquals("pixel=$index",color,bitmap.getPixel(index%bitmap.width,index/bitmap.width)) }
    }

    @Test fun tightRowsKeepRedGreenBlueAndReuseBitmap() {
        FrameBitmapConverter().use { converter ->
            val first=converter.convert(bytes(255,0,0,255, 0,255,0,255, 0,0,255,255),3,1,12,4)
            colors(first,Color.RED,Color.GREEN,Color.BLUE)
            val second=converter.convert(bytes(0,0,0,255, 255,255,255,255, 255,0,0,255),3,1,12,4)
            assertSame(first,second)
            colors(second,Color.BLACK,Color.WHITE,Color.RED)
        }
    }

    @Test fun rowPaddingAndNonzeroBufferPositionDoNotBecomePixels() {
        // Prefix is not image data; the final row intentionally omits its four padding bytes.
        val source=bytes(11,22,33, 255,0,0,255, 0,255,0,255, 91,92,93,94, 0,0,255,255, 255,255,0,255)
        source.position(3); source.order(ByteOrder.BIG_ENDIAN)
        val originalPosition=source.position(); val originalLimit=source.limit()
        FrameBitmapConverter().use { converter ->
            colors(converter.convert(source,2,2,12,4),Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW)
            assertEquals(originalPosition,source.position()); assertEquals(originalLimit,source.limit())
            assertEquals(ByteOrder.BIG_ENDIAN,source.order())
        }
    }

    @Test fun interPixelPaddingIsSkippedWithoutChangingRgbaChannels() {
        val source=bytes(
            255,0,0,255, 91,92,93,94, 0,255,0,255, 81,82,83,84, 71,72,73,74,
            0,0,255,255, 61,62,63,64, 255,255,0,255
        )
        FrameBitmapConverter().use { converter ->
            colors(converter.convert(source,2,2,20,8),Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW)
        }
    }

    @Test fun captureAlphaDoesNotDimOrHideRgbInPixelsOrCanvas() {
        FrameBitmapConverter().use { converter ->
            val output=converter.convert(bytes(255,0,0,0, 0,255,0,128, 0,0,255,255),3,1,12,4)
            assertFalse(output.hasAlpha())
            colors(output,Color.RED,Color.GREEN,Color.BLUE)
            val drawn=Bitmap.createBitmap(3,1,Bitmap.Config.ARGB_8888)
            try {
                Canvas(drawn).apply { drawColor(Color.BLACK); drawBitmap(output,0f,0f,null) }
                colors(drawn,Color.RED,Color.GREEN,Color.BLUE)
            } finally { drawn.recycle() }
        }
    }

    @Test fun cropOffsetHonorsRowsPixelsAndSourcePosition() {
        val source=bytes(11,22,33,
            0,0,0,255, 255,0,0,255, 0,255,0,255, 91,92,93,94,
            255,255,255,255, 0,0,255,0, 255,255,0,128)
        source.position(3)
        FrameBitmapConverter().use { converter ->
            colors(converter.convert(source,2,1,16,4,1,1),Color.BLUE,Color.YELLOW)
            assertEquals(3,source.position())
            colors(converter.convert(source,2,2,16,4,1,0),Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW)
        }
    }

    @Test fun cropTopOnTightlyPackedRowsUsesTheOffsetOnTheFastPath() {
        val source=bytes(255,0,0,255, 0,255,0,255, 0,0,255,0, 255,255,0,128)
        FrameBitmapConverter().use { converter ->
            colors(converter.convert(source,2,1,8,4,0,1),Color.BLUE,Color.YELLOW)
        }
    }

    @Test fun directFullFrameWritesIntoAndReusesCallerOwnedBitmap() {
        val destination=Bitmap.createBitmap(3,1,Bitmap.Config.ARGB_8888)
        try {
            FrameBitmapConverter().use { converter ->
                converter.convertInto(bytes(255,0,0,0, 0,255,0,128, 0,0,255,255),
                    3,1,12,4,0,0,destination,0,0,clearOutside=false)
                assertFalse(destination.hasAlpha())
                colors(destination,Color.RED,Color.GREEN,Color.BLUE)

                // The producer-owned destination is overwritten on the next frame too.
                converter.convertInto(bytes(255,255,0,0, 255,0,255,128, 0,255,255,255),
                    3,1,12,4,0,0,destination,0,0,clearOutside=false)
                colors(destination,Color.YELLOW,Color.MAGENTA,Color.CYAN)
            }
            // Converter shutdown must never recycle the producer's pooled bitmap.
            assertFalse(destination.isRecycled)
        } finally { destination.recycle() }
    }

    @Test fun directBulkFrameMatchesIndependentBytewiseReference() {
        val source=bytes(17,18,19,
            13,201,77,0, 252,8,139,64, 44,165,230,255,
            193,51,17,127, 0,254,129,1, 91,37,208,220)
        source.position(3); source.order(ByteOrder.BIG_ENDIAN)
        val position=source.position(); val limit=source.limit()
        val destination=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888)
        try {
            val expected=IntArray(6)
            for(y in 0 until 2) for(x in 0 until 3) {
                val offset=position+y*12+x*4
                expected[y*3+x]=Color.argb(255,
                    source.get(offset).toInt() and 255,
                    source.get(offset+1).toInt() and 255,
                    source.get(offset+2).toInt() and 255)
            }
            FrameBitmapConverter().use { converter ->
                converter.convertInto(source,3,2,12,4,0,0,destination,0,0,clearOutside=false)
            }
            val actual=IntArray(6)
            destination.getPixels(actual,0,3,0,0,3,2)
            assertArrayEquals(expected,actual)
            assertEquals(position,source.position()); assertEquals(limit,source.limit())
            assertEquals(ByteOrder.BIG_ENDIAN,source.order())
        } finally { destination.recycle() }
    }

    @Test fun directCropClearsStalePixelsAndKeepsPaddedRowsAndOpaqueRgb() {
        // Prefix tests nonzero source position. Each row has a padding word.
        val source=bytes(77,
            255,255,255,255, 1,2,3,4, 5,6,7,8, 9,10,11,12, 90,91,92,93,
            255,255,0,255, 255,0,0,0, 0,255,0,128, 0,0,255,255, 80,81,82,83,
            255,255,0,0, 255,0,255,0, 255,255,0,128, 255,255,255,255, 70,71,72,73)
        source.position(1)
        val destination=Bitmap.createBitmap(4,3,Bitmap.Config.ARGB_8888)
        try {
            Canvas(destination).drawColor(Color.MAGENTA)
            FrameBitmapConverter().use { converter ->
                converter.convertInto(source,2,2,20,4,1,1,destination,1,1,clearOutside=true)
            }
            colors(destination,
                Color.BLACK,Color.BLACK,Color.BLACK,Color.BLACK,
                Color.BLACK,Color.RED,Color.GREEN,Color.BLACK,
                Color.BLACK,Color.MAGENTA,Color.YELLOW,Color.BLACK)
            assertFalse(destination.hasAlpha())
            assertEquals(1,source.position())
        } finally { destination.recycle() }
    }

    @Test fun resizedFrameRecyclesOldBitmapAndCloseIsIdempotent() {
        val converter=FrameBitmapConverter()
        val first=converter.convert(bytes(255,0,0,255),1,1,4,4)
        val second=converter.convert(bytes(0,255,0,255, 0,0,255,255),2,1,8,4)
        assertTrue(first.isRecycled); colors(second,Color.GREEN,Color.BLUE)
        converter.close(); converter.close(); assertTrue(second.isRecycled)
    }

    @Test fun truncatedRowsAreRejectedBeforeCopying() {
        FrameBitmapConverter().use { converter ->
            val error=runCatching { converter.convert(bytes(255,0,0,255, 0,255,0),2,1,8,4) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        }
    }

    @Test fun closedConverterRetainsIllegalStateFailureContract() {
        val converter=FrameBitmapConverter()
        converter.close()
        val error=runCatching { converter.convert(bytes(255,0,0,255),1,1,4,4) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    @Test fun benchmarkFullPaddedFrameLegacyVersusDirect() {
        benchmarkPaddedFrame(cropLeft=0,cropTop=0,cropWidth=BENCHMARK_WIDTH,cropHeight=BENCHMARK_HEIGHT,
            label="full")
    }

    @Test fun benchmarkCroppedPaddedFrameLegacyVersusDirect() {
        benchmarkPaddedFrame(cropLeft=24,cropTop=16,cropWidth=BENCHMARK_WIDTH-48,
            cropHeight=BENCHMARK_HEIGHT-32,label="crop")
    }

    /**
     * Compares the old crop-bitmap + Canvas copy against direct conversion into its
     * eventual full-display bitmap. Timings are diagnostics only; pixel equality is enforced.
     */
    private fun benchmarkPaddedFrame(cropLeft: Int,cropTop: Int,cropWidth: Int,cropHeight: Int,label: String) {
        val rowStride=BENCHMARK_WIDTH*4+BENCHMARK_ROW_PADDING
        val source=ByteBuffer.allocateDirect(rowStride*BENCHMARK_HEIGHT).order(ByteOrder.LITTLE_ENDIAN)
        for(y in 0 until BENCHMARK_HEIGHT) {
            for(x in 0 until BENCHMARK_WIDTH) {
                source.put(((x*31+y*17) and 255).toByte())
                source.put(((x*7+y*43+19) and 255).toByte())
                source.put(((x*53+y*3+101) and 255).toByte())
                source.put(((x+y*5) and 255).toByte())
            }
            while(source.position()<(y+1)*rowStride) source.put(0x5a.toByte())
        }
        source.flip()

        val legacyOutput=Bitmap.createBitmap(BENCHMARK_WIDTH,BENCHMARK_HEIGHT,Bitmap.Config.ARGB_8888)
        val directOutput=Bitmap.createBitmap(BENCHMARK_WIDTH,BENCHMARK_HEIGHT,Bitmap.Config.ARGB_8888)
        val legacyConverter=FrameBitmapConverter()
        val directConverter=FrameBitmapConverter()
        val legacyCanvas=Canvas(legacyOutput)
        val legacyTimes=LongArray(BENCHMARK_SAMPLES)
        val directTimes=LongArray(BENCHMARK_SAMPLES)
        try {
            fun runLegacy() {
                val converted=legacyConverter.convert(source.duplicate(),cropWidth,cropHeight,rowStride,4,cropLeft,cropTop)
                legacyCanvas.drawColor(Color.BLACK)
                legacyCanvas.drawBitmap(converted,cropLeft.toFloat(),cropTop.toFloat(),null)
            }
            fun runDirect() {
                directConverter.convertInto(source.duplicate(),cropWidth,cropHeight,rowStride,4,
                    cropLeft,cropTop,directOutput,cropLeft,cropTop,
                    clearOutside=cropLeft!=0 || cropTop!=0 || cropWidth!=BENCHMARK_WIDTH || cropHeight!=BENCHMARK_HEIGHT)
            }

            for(iteration in 0 until BENCHMARK_WARMUPS+BENCHMARK_SAMPLES) {
                val sampleIndex=iteration-BENCHMARK_WARMUPS
                fun timed(action: () -> Unit): Long {
                    val start=SystemClock.elapsedRealtimeNanos()
                    action()
                    return SystemClock.elapsedRealtimeNanos()-start
                }
                // Alternate which implementation runs first to reduce order and thermal bias.
                if(iteration%2==0) {
                    val legacyDuration=timed(::runLegacy)
                    val directDuration=timed(::runDirect)
                    if(sampleIndex>=0) { legacyTimes[sampleIndex]=legacyDuration; directTimes[sampleIndex]=directDuration }
                } else {
                    val directDuration=timed(::runDirect)
                    val legacyDuration=timed(::runLegacy)
                    if(sampleIndex>=0) { legacyTimes[sampleIndex]=legacyDuration; directTimes[sampleIndex]=directDuration }
                }
            }

            // Compare every output pixel after all timed runs; no timing threshold is used.
            val legacyRow=IntArray(BENCHMARK_WIDTH)
            val directRow=IntArray(BENCHMARK_WIDTH)
            for(y in 0 until BENCHMARK_HEIGHT) {
                legacyOutput.getPixels(legacyRow,0,BENCHMARK_WIDTH,0,y,BENCHMARK_WIDTH,1)
                directOutput.getPixels(directRow,0,BENCHMARK_WIDTH,0,y,BENCHMARK_WIDTH,1)
                assertArrayEquals("$label output differs on row $y",legacyRow,directRow)
            }
            Log.i(BENCHMARK_TAG,"$label ${BENCHMARK_WIDTH}x${BENCHMARK_HEIGHT} padded RGBA rowStride=$rowStride " +
                "legacyMedianNs=${median(legacyTimes)} directMedianNs=${median(directTimes)} " +
                "warmups=$BENCHMARK_WARMUPS samples=$BENCHMARK_SAMPLES")
        } finally {
            legacyConverter.close(); directConverter.close()
            legacyOutput.recycle(); directOutput.recycle()
        }
    }

    private fun median(samples: LongArray): Long = samples.sorted()[samples.size/2]
}
