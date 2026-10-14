package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.cyotun.jpq.core.GlyphBox
import com.cyotun.jpq.core.PlayGlyph
import com.cyotun.jpq.core.Suit
import com.cyotun.jpq.core.SuitSymbolClassifier
import com.cyotun.jpq.core.SuitSymbolObservation
import com.cyotun.jpq.core.SuitSymbolPixels
import kotlin.math.ceil
import kotlin.math.floor

/** Extract only on the capture owner thread, then classify the owned pixels elsewhere. */
class SuitPixelReader {
    enum class Placement { HAND, TABLE }

    fun extract(bitmap: Bitmap, glyph: PlayGlyph, placement: Placement,
                masks: List<GlyphBox> = emptyList()): SuitSymbolPixels {
        val color = when (glyph.card.suit) {
            Suit.RED, Suit.HEART, Suit.DIAMOND -> Suit.RED
            Suit.BLACK, Suit.SPADE, Suit.CLUB -> Suit.BLACK
            Suit.NONE -> Suit.NONE
        }
        fun unavailable(reason: String) = SuitSymbolPixels(0, 0, IntArray(0), color, false, reason)
        if (glyph.card.rank.isJoker) return unavailable("joker_has_no_suit")
        if (bitmap.isRecycled || glyph.confidence < .65f) return unavailable("rank_unconfirmed")
        val box = glyph.box
        if (listOf(box.left, box.top, box.right, box.bottom).any { !it.isFinite() }) return unavailable("rank_geometry")
        val left = box.left * bitmap.width; val right = box.right * bitmap.width
        val top = box.top * bitmap.height; val bottom = box.bottom * bitmap.height
        val height = bottom - top
        if (!height.isFinite() || height < 12f || height > 110f || right <= left) return unavailable("rank_geometry")
        // In Tencent's stacked hand the small suit is beside the rank. On the
        // table it is below the rank. Rank width is deliberately not used as a
        // unit: the two-digit 10 must have the same-sized suit as a single digit.
        val crop = when (placement) {
            Placement.HAND -> Rect(floor(right - height * .04f).toInt(), floor(top + height * .06f).toInt(),
                ceil(right + height * 1.05f).toInt(), ceil(bottom + height * .09f).toInt())
            Placement.TABLE -> Rect(floor(left - height * .12f).toInt(), floor(bottom + height * .02f).toInt(),
                ceil(left + height * 1.05f).toInt(), ceil(bottom + height * 1.10f).toInt())
        }
        if (crop.left < 0 || crop.top < 0 || crop.right > bitmap.width || crop.bottom > bitmap.height ||
            crop.width() !in 8..128 || crop.height() !in 8..128) return unavailable("roi_clipped")
        val normalized = GlyphBox(crop.left.toFloat() / bitmap.width, crop.top.toFloat() / bitmap.height,
            crop.right.toFloat() / bitmap.width, crop.bottom.toFloat() / bitmap.height)
        if (masks.any { m -> normalized.left < m.right && normalized.right > m.left &&
                normalized.top < m.bottom && normalized.bottom > m.top }) return unavailable("overlay_masks_suit")
        val copy = IntArray(crop.width() * crop.height())
        return try {
            bitmap.getPixels(copy, 0, crop.width(), crop.left, crop.top, crop.width(), crop.height())
            SuitSymbolPixels(crop.width(), crop.height(), copy, color)
        } catch (_: RuntimeException) { unavailable("roi_unavailable") }
    }

    fun classify(pixels: SuitSymbolPixels): SuitSymbolObservation = SuitSymbolClassifier.classify(pixels)

    /** Convenience for offline/fixture callers. Production uses extract + async classify. */
    fun read(bitmap: Bitmap, glyph: PlayGlyph, placement: Placement,
             masks: List<GlyphBox> = emptyList()): SuitSymbolObservation = classify(extract(bitmap, glyph, placement, masks))
}
