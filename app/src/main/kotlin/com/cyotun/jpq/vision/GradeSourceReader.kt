package com.cyotun.jpq.vision

import android.graphics.Bitmap
import com.cyotun.jpq.core.GlyphBox
import com.cyotun.jpq.core.Rank
import com.cyotun.jpq.core.SceneHint
import com.cyotun.jpq.core.TableScene
import com.cyotun.jpq.core.TencentGradeReading
import com.cyotun.jpq.core.TencentGradeTeam
import com.cyotun.jpq.core.TencentGradeHeaderTone
import com.cyotun.jpq.core.WildcardMode
import com.cyotun.jpq.core.tencentRoundGradeReading
import com.cyotun.jpq.core.tencentGradeHeaderTone
import com.cyotun.jpq.core.tencentGradeHeaderPixelColour

data class GradeSourceReading(
    val reading: TencentGradeReading,
    val sourceTime: Long?,
    val hudRegion: GlyphBox?,
    val obscured: Boolean,
    val hints: List<SceneHint> = emptyList(),
    val ocrTime: Long? = null,
    val activeHudTeams: Set<TencentGradeTeam> = emptySet(),
)

/** Uses existing OCR snapshots and copied metadata; it never starts a blocking OCR request. */
class GradeSourceReader {
    private var cachedHudRegion: GlyphBox? = null
    private var cachedHudTeams=emptySet<TencentGradeTeam>()
    private var aspectRatio = 0f
    /** Called on the owning vision thread for a new round or capture session. */
    fun reset() { cachedHudRegion = null; cachedHudTeams=emptySet(); aspectRatio = 0f }

    fun read(bitmap: Bitmap, time: Long, text: SceneText?, masks: List<GlyphBox>,
        scene: TableScene, cornerRanks: Set<Rank>, mode: WildcardMode): GradeSourceReading {
        val aspect = bitmap.width.toFloat()/bitmap.height
        if (aspectRatio > 0f && kotlin.math.abs(aspect - aspectRatio) > .01f) { cachedHudRegion = null; cachedHudTeams=emptySet() }
        aspectRatio = aspect
        val actualTable = scene in setOf(TableScene.TABLE, TableScene.DEALING, TableScene.TEAMMATE, TableScene.TRIBUTE)
        if (!actualTable || bitmap.width <= bitmap.height) {
            if(scene in setOf(TableScene.LOBBY,TableScene.RESULT) || bitmap.width<=bitmap.height) { cachedHudRegion=null; cachedHudTeams=emptySet() }
            return GradeSourceReading(TencentGradeReading(null,"NOT_TABLE",null), null, null, false)
        }
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
        val headerBoxes=headerBoxes(hints)
        val tones=headerBoxes.groupBy { it.first }.mapValues { (_,boxes) ->
            boxes.map { headerTone(bitmap,it.second,masks) }.toSet().singleOrNull() ?: TencentGradeHeaderTone.UNKNOWN
        }
        val active=tones.filterValues { it==TencentGradeHeaderTone.ACTIVE }.keys
        val reading = tencentRoundGradeReading(hints, cornerRanks, mode,active)
        val measured=headerBoxes.filter { it.first in active }.mapNotNull { measuredPanel(bitmap,it.second,masks) }
        val observed=measured.takeIf { it.isNotEmpty() }?.let { regions ->
            GlyphBox(regions.minOf { it.left },regions.minOf { it.top },regions.maxOf { it.right },regions.maxOf { it.bottom })
        }
        // Only actual coloured header geometry can create a warning target.
        // A fully hidden HUD may retain a previously seen same-deal target;
        // a predicted fixed region must never declare itself obstructed.
        if(observed!=null) {
            // Masking must not shrink a fully seen same-panel target to its
            // still-visible fragment. Unmasked frames may refresh geometry.
            if(cachedHudRegion==null || cachedHudTeams!=active || masks.none { overlap(cachedHudRegion!!,it)>0 })
                cachedHudRegion=observed
            cachedHudTeams=active.toSet()
        } else if(tones.keys.containsAll(TencentGradeTeam.entries) && tones.values.all { it==TencentGradeHeaderTone.INACTIVE }) {
            cachedHudRegion=null; cachedHudTeams=emptySet()
        }
        val region = cachedHudRegion
        val obscured = region!=null && masks.sumOf { overlap(region,it).toDouble() } >= region.width * region.height * .04f
        val sourceTime = when {
            reading.source in setOf("CORNER_SINGLE","CORNER_DOUBLE","HUD_CORNER_DOUBLE") -> time
            else -> fresh?.timeMs
        }
        return GradeSourceReading(reading,sourceTime,region,obscured,hints.toList(),fresh?.timeMs,active.toSet())
    }

    private fun headerBoxes(hints: List<SceneHint>): List<Pair<TencentGradeTeam,GlyphBox>> = hints.flatMap { hint ->
        val b=hint.box ?: return@flatMap emptyList()
        if(b.cx !in .055f.. .23f || b.cy>.13f) return@flatMap emptyList()
        val compact=hint.text.filterNot(Char::isWhitespace)
        if(compact=="我方对方") return@flatMap listOf(
            TencentGradeTeam.OWN to GlyphBox(b.left,b.top,b.cx,b.bottom),
            TencentGradeTeam.OTHER to GlyphBox(b.cx,b.top,b.right,b.bottom))
        TencentGradeTeam.entries.mapNotNull { team ->
            when {
                compact==team.label -> team to b
                compact.startsWith(team.label) && b.height in .045f.. .13f && Rank.entries.any {
                    !it.isJoker && it.label==compact.removePrefix(team.label).uppercase()
                } -> team to GlyphBox(b.left,b.top,b.right,b.top+b.height*.35f)
                else -> null
            }
        }
    }.distinct()

    private fun headerTone(bitmap: Bitmap,box: GlyphBox,masks: List<GlyphBox>): TencentGradeHeaderTone {
        val area=GlyphBox((box.left-.002f).coerceAtLeast(0f),(box.top-.001f).coerceAtLeast(0f),
            (box.right+.002f).coerceAtMost(1f),(box.bottom+.001f).coerceAtMost(1f))
        if(masks.any { overlap(area,it)>0 }) return TencentGradeHeaderTone.UNKNOWN
        val left=(area.left*bitmap.width).toInt(); val top=(area.top*bitmap.height).toInt()
        val right=kotlin.math.ceil(area.right*bitmap.width).toInt(); val bottom=kotlin.math.ceil(area.bottom*bitmap.height).toInt()
        val width=right-left; val height=bottom-top
        if(width !in 4..260 || height !in 3..180) return TencentGradeHeaderTone.UNKNOWN
        val pixels=IntArray(width*height)
        bitmap.getPixels(pixels,0,width,left,top,width,height)
        return tencentGradeHeaderTone(width,height,pixels)
    }

    private fun measuredPanel(bitmap: Bitmap,box: GlyphBox,masks: List<GlyphBox>): GlyphBox? {
        val left=((box.left-.015f).coerceAtLeast(0f)*bitmap.width).toInt()
        val top=((box.top-.004f).coerceAtLeast(0f)*bitmap.height).toInt()
        val right=kotlin.math.ceil((box.right+.015f).coerceAtMost(1f)*bitmap.width).toInt()
        val bottom=kotlin.math.ceil(minOf(box.bottom+.006f,.065f)*bitmap.height).toInt()
        val width=right-left; val height=bottom-top
        if(width !in 4..340 || height !in 3..180) return null
        val pixels=IntArray(width*height); bitmap.getPixels(pixels,0,width,left,top,width,height)
        val seedLeft=((box.left*bitmap.width).toInt()-left).coerceIn(0,width-1)
        val seedRight=(kotlin.math.ceil(box.right*bitmap.width).toInt()-left).coerceIn(seedLeft+1,width)
        var blue=0; var red=0
        for(y in 0 until height) for(x in seedLeft until seedRight) {
            when(tencentGradeHeaderPixelColour(pixels[y*width+x])) { 1 -> blue++; 2 -> red++ }
        }
        val colour=if(blue>=red) 1 else 2
        if(maxOf(blue,red)<16) return null
        val columns=IntArray(width); val rows=IntArray(height)
        for(y in 0 until height) for(x in 0 until width) {
            val nx=(left+x+.5f)/bitmap.width; val ny=(top+y+.5f)/bitmap.height
            if(masks.any { nx>=it.left && nx<it.right && ny>=it.top && ny<it.bottom }) continue
            if(tencentGradeHeaderPixelColour(pixels[y*width+x])==colour) { columns[x]++; rows[y]++ }
        }
        val validColumns=columns.indices.filter { columns[it]>=maxOf(3f,height*.18f) }
        val validRows=rows.indices.filter { rows[it]>=maxOf(3f,width*.18f) }
        if(validColumns.isEmpty() || validRows.isEmpty()) return null
        val l=(left+validColumns.first()).toFloat()/bitmap.width
        val r=(left+validColumns.last()+1).toFloat()/bitmap.width
        val t=(top+validRows.first()).toFloat()/bitmap.height
        val b=(top+validRows.last()+1).toFloat()/bitmap.height
        return GlyphBox(l,t,r,minOf(.15f,b+.07f))
    }

    private fun overlap(a: GlyphBox,b: GlyphBox): Float {
        if (!listOf(b.left,b.top,b.right,b.bottom).all { it.isFinite() } || b.width <= 0 || b.height <= 0) return 0f
        return (minOf(a.right,b.right)-maxOf(a.left,b.left)).coerceAtLeast(0f) *
            (minOf(a.bottom,b.bottom)-maxOf(a.top,b.top)).coerceAtLeast(0f)
    }
}
