package com.cyotun.jpq.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.Image
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Opt-in, local-only comparison of one still-open capture Image with its converted Bitmap.
 * Call immediately after conversion, before any masks are drawn. No scene/hand gate applies.
 * Arm with jpq-diagnostics.captureProbeRemaining (1..3). Each attempted probe consumes one
 * committed preference count, and at most three attempts are made by this instance.
 */
class CaptureFrameProbe : AutoCloseable {
    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "JPQCaptureProbe")
    }
    private val inFlight = AtomicBoolean(false)
    private var closed = false
    private var attempts = 0
    private var legacyPixels = IntArray(0)
    private var convertedPixels = IntArray(0)

    /**
     * Image bytes are read synchronously; only owned bitmap snapshots reach the IO thread.
     * Returns the accepted probe directory so the caller can add same-frame evidence.
     */
    @Synchronized
    fun capture(context: Context, image: Image, converted: Bitmap): File? {
        if (closed || attempts >= MAX_PROBES || image.width <= image.height) return null
        val preferences = context.getSharedPreferences("jpq-diagnostics", Context.MODE_PRIVATE)
        val remaining = preferences.getInt("captureProbeRemaining", 0).coerceIn(0, MAX_PROBES)
        if (remaining == 0 || !inFlight.compareAndSet(false, true)) return null

        var legacySnapshot: Bitmap? = null
        var convertedSnapshot: Bitmap? = null
        var submitted = false
        var acceptedFolder: File? = null
        try {
            // A failed attempt is consumed too, so malformed frames cannot cause a probe loop.
            if (!preferences.edit().putInt("captureProbeRemaining", remaining - 1).commit()) {
                Log.w(TAG, "preference_commit_failed")
                return null
            }
            attempts++
            val wallTimeMs = System.currentTimeMillis()
            val elapsedMs = SystemClock.elapsedRealtime()
            val probeId = "$wallTimeMs-${UUID.randomUUID().toString().take(8)}"
            require(image.format == PixelFormat.RGBA_8888) { "RGBA_8888 capture required" }
            require(image.width > 0 && image.height > 0)
            require(converted.width == image.width && converted.height == image.height) {
                "Converted bitmap dimensions differ from the Image"
            }

            val plane = image.planes.single()
            val original = plane.buffer
            val bytes = original.duplicate()
            val base = bytes.position()
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            require(pixelStride >= 4)
            val rowPayload = (width - 1L) * pixelStride + 4L
            val required = (height - 1L) * rowStride + rowPayload
            require(rowStride.toLong() >= rowPayload && required <= bytes.remaining().toLong()) {
                "Capture plane is truncated or its row stride is too short"
            }
            val countLong = width.toLong() * height
            require(countLong <= Int.MAX_VALUE)
            val count = countLong.toInt()
            if (legacyPixels.size != count) legacyPixels = IntArray(count)
            if (convertedPixels.size != count) convertedPixels = IntArray(count)

            val rawRgb = RgbStatistics()
            val crop=image.cropRect
            var alphaMin = 255
            var alphaMax = 0
            var alphaSum = 0L
            var zeroAlpha = 0L
            var opaqueAlpha = 0L
            // Independent reference: byte reads + Color.argb + setPixels, without word views
            // or copyPixelsFromBuffer. Source buffer position, limit and order stay untouched.
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val offset = base + y * rowStride + x * pixelStride
                    val r = bytes.get(offset).toInt() and 255
                    val g = bytes.get(offset + 1).toInt() and 255
                    val b = bytes.get(offset + 2).toInt() and 255
                    val a = bytes.get(offset + 3).toInt() and 255
                    legacyPixels[y * width + x] = if(crop.contains(x,y)) Color.argb(255,r,g,b) else Color.BLACK
                    rawRgb.add(r, g, b)
                    alphaMin = minOf(alphaMin, a)
                    alphaMax = maxOf(alphaMax, a)
                    alphaSum += a
                    if (a == 0) zeroAlpha++
                    if (a == 255) opaqueAlpha++
                }
            }
            val legacyOwned = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(legacyPixels, 0, width, 0, 0, width, height)
                setHasAlpha(false)
            }
            legacySnapshot = legacyOwned
            val convertedOwned = checkNotNull(converted.copy(Bitmap.Config.ARGB_8888, false)) {
                "Converted bitmap snapshot unavailable"
            }
            convertedSnapshot = convertedOwned
            converted.getPixels(convertedPixels, 0, width, 0, 0, width, height)
            // Compare rendered colors from both bitmaps, not just the reference input array.
            legacyOwned.getPixels(legacyPixels, 0, width, 0, 0, width, height)

            val legacyRgb = RgbStatistics()
            val newRgb = RgbStatistics()
            var mismatchPixels = 0L
            var maxChannelError = 0
            val absoluteError = LongArray(3)
            for (index in 0 until count) {
                val old = legacyPixels[index]
                val new = convertedPixels[index]
                val oldR = (old shr 16) and 255
                val oldG = (old shr 8) and 255
                val oldB = old and 255
                val newR = (new shr 16) and 255
                val newG = (new shr 8) and 255
                val newB = new and 255
                legacyRgb.add(oldR, oldG, oldB)
                newRgb.add(newR, newG, newB)
                if (((old xor new) and 0x00ffffff) != 0) mismatchPixels++
                val dr = abs(oldR - newR)
                val dg = abs(oldG - newG)
                val db = abs(oldB - newB)
                maxChannelError = maxOf(maxChannelError, maxOf(dr, dg, db))
                absoluteError[0] += dr
                absoluteError[1] += dg
                absoluteError[2] += db
            }

            val record = JSONObject()
                .put("schemaVersion", 1)
                .put("probeId", probeId)
                .put("wallTimeMs", wallTimeMs)
                .put("elapsedMs", elapsedMs)
                .put("imageTimestampNs", image.timestamp)
                .put("stage", "after_conversion_before_masks")
                .put("comparisonMethod", "bytewise_RGBA_crop_to_full_display_canvas")
                .put("attempt", attempts)
                .put("remaining", remaining - 1)
                .put("image", JSONObject().put("format", image.format)
                    .put("width", width).put("height", height)
                    .put("cropRect", JSONArray(listOf(crop.left, crop.top, crop.right, crop.bottom))))
                .put("plane", JSONObject().put("rowStride", rowStride).put("pixelStride", pixelStride)
                    .put("position", original.position()).put("limit", original.limit())
                    .put("remaining", original.remaining()).put("capacity", original.capacity())
                    .put("isDirect", original.isDirect).put("byteOrder", original.order().toString())
                    .put("requiredBytes", required))
                .put("convertedBitmap", JSONObject().put("width", converted.width).put("height", converted.height)
                    .put("rowBytes", converted.rowBytes).put("byteCount", converted.byteCount)
                    .put("hasAlpha", converted.hasAlpha()).put("isPremultiplied", converted.isPremultiplied)
                    .put("config", converted.config?.name ?: JSONObject.NULL))
                .put("rawRgb", rawRgb.toJson())
                .put("rawAlpha", JSONObject().put("min", alphaMin).put("max", alphaMax)
                    .put("mean", alphaSum.toDouble() / count).put("zeroPixels", zeroAlpha)
                    .put("opaquePixels", opaqueAlpha).put("otherPixels", countLong - zeroAlpha - opaqueAlpha)
                    .put("zeroRatio", zeroAlpha.toDouble() / count)
                    .put("opaqueRatio", opaqueAlpha.toDouble() / count))
                .put("legacyRgb", legacyRgb.toJson())
                .put("convertedRgb", newRgb.toJson())
                .put("comparison", JSONObject().put("pixels", count)
                    .put("rgbMismatchPixels", mismatchPixels)
                    .put("rgbMismatchRatio", mismatchPixels.toDouble() / count)
                    .put("maxChannelError", maxChannelError)
                    .put("meanAbsoluteRgbError", JSONArray(absoluteError.map { it.toDouble() / count })))
                .put("files", JSONObject().put("legacy", "legacy.png").put("converted", "converted.png"))

            val folder = File(context.applicationContext.filesDir, "capture-probes/$probeId")
            io.execute {
                try {
                    check(folder.isDirectory || folder.mkdirs() || folder.isDirectory) { "Capture probe folder unavailable" }
                    writePng(legacyOwned, File(folder, "legacy.png"))
                    writePng(convertedOwned, File(folder, "converted.png"))
                    // Written last: probe.json indicates both probe image writes completed.
                    File(folder, "probe.json").writeText(record.toString(2), Charsets.UTF_8)
                    Log.i(TAG, "saved id=$probeId mismatch=$mismatchPixels maxError=$maxChannelError path=${folder.absolutePath}")
                } catch (error: Exception) {
                    Log.w(TAG, "save_failed id=$probeId", error)
                } finally {
                    legacyOwned.recycle()
                    convertedOwned.recycle()
                    inFlight.set(false)
                }
            }
            submitted = true
            acceptedFolder = folder
        } catch (error: Exception) {
            Log.w(TAG, "capture_failed", error)
        } finally {
            if (!submitted) {
                legacySnapshot?.recycle()
                convertedSnapshot?.recycle()
                inFlight.set(false)
            }
            if (remaining <= 1 || attempts >= MAX_PROBES) {
                legacyPixels = IntArray(0)
                convertedPixels = IntArray(0)
            }
        }
        return acceptedFolder
    }

    /** Shutdown drains an already submitted save; it never waits on disk from the caller. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        io.shutdown()
        legacyPixels = IntArray(0)
        convertedPixels = IntArray(0)
    }

    private fun writePng(bitmap: Bitmap, file: File) {
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private class RgbStatistics {
        private var count = 0L
        private val sums = LongArray(3)
        private val minima = IntArray(3) { 255 }
        private val maxima = IntArray(3)
        private var nearBlack = 0L

        fun add(r: Int, g: Int, b: Int) {
            count++
            sums[0] += r; sums[1] += g; sums[2] += b
            minima[0] = minOf(minima[0], r); minima[1] = minOf(minima[1], g); minima[2] = minOf(minima[2], b)
            maxima[0] = maxOf(maxima[0], r); maxima[1] = maxOf(maxima[1], g); maxima[2] = maxOf(maxima[2], b)
            if (r <= NEAR_BLACK_MAX && g <= NEAR_BLACK_MAX && b <= NEAR_BLACK_MAX) nearBlack++
        }

        fun toJson(): JSONObject = JSONObject().put("pixels", count)
            .put("channelOrder", "RGB")
            .put("min", JSONArray(minima.toList())).put("max", JSONArray(maxima.toList()))
            .put("mean", JSONArray(sums.map { it.toDouble() / count }))
            .put("nearBlackMaxChannel", NEAR_BLACK_MAX).put("nearBlackPixels", nearBlack)
            .put("nearBlackRatio", nearBlack.toDouble() / count)
    }

    private companion object {
        const val TAG = "JPQCaptureProbe"
        const val MAX_PROBES = 3
        const val NEAR_BLACK_MAX = 8
    }
}
