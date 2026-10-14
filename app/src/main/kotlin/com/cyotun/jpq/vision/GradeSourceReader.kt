package com.cyotun.jpq.vision

import android.graphics.Bitmap
import com.cyotun.jpq.core.GlyphBox
import com.cyotun.jpq.core.Rank
import com.cyotun.jpq.core.SceneHint
import com.cyotun.jpq.core.TableScene
import com.cyotun.jpq.core.TencentGradeReading
import com.cyotun.jpq.core.WildcardMode
import com.cyotun.jpq.core.tencentRoundGradeReading

data class GradeSourceReading(
    val reading: TencentGradeReading,
    val sourceTime: Long?,
    val hudRegion: GlyphBox?,
    val obscured: Boolean,
    val hints: List<SceneHint> = emptyList(),
    val ocrTime: Long? = null,
)

/** Uses existing OCR snapshots and copied metadata; it never starts a blocking OCR request. */
class GradeSourceReader {
    private var cachedHudRegion: GlyphBox? = null
    private var aspectRatio = 0f
    /** Called on the owning vision thread for a new round or capture session. */
    fun reset() { cachedHudRegion = null; aspectRatio = 0f }

    fun read(bitmap: Bitmap, time: Long, text: SceneText?, masks: List<GlyphBox>,
        scene: TableScene, cornerRanks: Set<Rank>, mode: WildcardMode): GradeSourceReading {
        val aspect = bitmap.width.toFloat()/bitmap.height
        if (aspectRatio > 0f && kotlin.math.abs(aspect - aspectRatio) > .01f) cachedHudRegion = null
        aspectRatio = aspect
        val actualTable = scene in setOf(TableScene.TABLE, TableScene.DEALING, TableScene.TEAMMATE, TableScene.TRIBUTE)
        if (!actualTable || bitmap.width <= bitmap.height)
            return GradeSourceReading(TencentGradeReading(null,"NOT_TABLE",null), null, null, false)
        val fresh = text?.takeIf {
            it.width == bitmap.width && it.height == bitmap.height && time - it.timeMs in 0L..1500L
        }
        fun normalized(line: OcrLine): SceneHint? {
            val box = line.box ?: return null
            if (box.left < 0 || box.top < 0 || box.right > bitmap.width || box.bottom > bitmap.height ||
                box.width() <= 0 || box.height() <= 0) return null
            val bounds = GlyphBox(box.left.toFloat()/bitmap.width,box.top.toFloat()/bitmap.height,
                box.right.toFloat()/bitmap.width,box.bottom.toFloat()/bitmap.height)
            if (masks.any { overlap(bounds,it) > 0f }) return null
            return SceneHint(line.text,bounds)
        }
        val hints = (fresh?.lines.orEmpty() + fresh?.metadataElements.orEmpty()).mapNotNull(::normalized)
        val reading = tencentRoundGradeReading(hints, cornerRanks, mode)
        fun hasLabel(label: String) = hints.any { hint ->
            val b=hint.box!!; val compact=hint.text.filterNot(Char::isWhitespace)
            b.cx in .055f.. .23f && b.cy <= .13f && (compact == label || compact == "我方对方" ||
                (compact.startsWith(label) && Rank.entries.any {
                    !it.isJoker && it.label == compact.removePrefix(label).uppercase()
                } && b.height in .045f.. .13f))
        }
        // A partially visible panel cannot shrink the obstruction target to the
        // still-visible panel. Cache only complete unmasked geometry from both.
        if (hasLabel("我方") && hasLabel("对方") && reading.hudRegion != null)
            cachedHudRegion = reading.hudRegion
        // The HUD is present on the actual table even if the overlay hides every
        // label. This fallback is a screen-normalized UI region, not a grade value.
        val region = cachedHudRegion ?: GlyphBox(.06f, 0f, .22f, .13f)
        val obscured = masks.sumOf { overlap(region,it).toDouble() } >= region.width * region.height * .04f
        val sourceTime = when {
            reading.source in setOf("CORNER_SINGLE","CORNER_DOUBLE","HUD_CORNER_DOUBLE") -> time
            else -> fresh?.timeMs
        }
        return GradeSourceReading(reading,sourceTime,region,obscured,hints.toList(),fresh?.timeMs)
    }

    private fun overlap(a: GlyphBox,b: GlyphBox): Float {
        if (!listOf(b.left,b.top,b.right,b.bottom).all { it.isFinite() } || b.width <= 0 || b.height <= 0) return 0f
        return (minOf(a.right,b.right)-maxOf(a.left,b.left)).coerceAtLeast(0f) *
            (minOf(a.bottom,b.bottom)-maxOf(a.top,b.top)).coerceAtLeast(0f)
    }
}
