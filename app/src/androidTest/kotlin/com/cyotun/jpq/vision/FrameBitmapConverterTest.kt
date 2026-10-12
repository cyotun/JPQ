package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed byte fixtures independently verify RGBA order, row packing and opaque RGB. */
@RunWith(AndroidJUnit4::class)
class FrameBitmapConverterTest {
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
}
