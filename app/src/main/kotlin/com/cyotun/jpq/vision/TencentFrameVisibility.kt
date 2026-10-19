package com.cyotun.jpq.vision

import com.cyotun.jpq.core.*

/** Preserve independent evidence without treating an obscured region as complete. */
internal object TencentFrameVisibility {
    private fun validMask(box: GlyphBox)=
        listOf(box.left,box.top,box.right,box.bottom).all { it.isFinite() } &&
            box.left>=0f && box.top>=0f && box.right<=1f && box.bottom<=1f && box.width>0f && box.height>0f
    private fun intersects(a: GlyphBox,b: GlyphBox)=
        a.left<b.right && b.left<a.right && a.top<b.bottom && b.top<a.bottom

    fun handObserved(glyphs: List<PlayGlyph>,masks: List<GlyphBox>): Boolean = guandanHandRegionObserved(glyphs,masks)

    fun qualify(frame: TableFrame,handGlyphs: List<PlayGlyph>,evidence: Map<Seat,EmptyEvidence>,
        masks: List<GlyphBox>): TableFrame {
        val handVisible=handObserved(handGlyphs,masks)
        val maskedSeats=evidence.filterValues { it.reason=="overlay_occlusion" }.keys.toSet()
        val overlayOccluded=maskedSeats.isNotEmpty() &&
            frame.scene !in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.TRIBUTE)
        return frame.copy(
            hand=frame.hand.takeIf { handVisible },
            teammateHand=frame.teammateHand.takeIf { handVisible },
            plays=frame.plays.mapValues { (seat,play) ->
                play?.copy(regionObserved=play.regionObserved && evidence[seat]?.observed==true)
            },
            localizedOverlaySeats=maskedSeats.takeIf { overlayOccluded && handVisible &&
                frame.incompleteReason==null && !frame.captureGap && !frame.ambiguousPresentation }.orEmpty(),
            incompleteReason=frame.incompleteReason ?: when {
                overlayOccluded -> "悬浮条遮住出牌区，请移回顶部，正在重新核对"
                !handVisible -> "悬浮条遮住手牌区，请移回顶部，正在重新核对"
                else -> null
            })
    }

    /** The timestamp belongs to the captured OCR input, never the completion or delivery frame. */
    fun openingChoiceSourceTimeMs(text: SceneText?,timeMs: Long,width: Int,height: Int,epoch: Long,
        masks: List<GlyphBox>): Long? {
        if(text==null || text.width!=width || text.height!=height || text.visibilityEpoch!=epoch ||
            timeMs-text.timeMs !in 0L..1000L) return null
        val allMasks=text.sourceMasks+masks
        if(allMasks.any { !validMask(it) }) return null
        val hints=text.lines.mapNotNull { line ->
            val b=line.box ?: return@mapNotNull null
            val box=GlyphBox(b.left.toFloat()/width,b.top.toFloat()/height,
                b.right.toFloat()/width,b.bottom.toFloat()/height)
            if(allMasks.any { intersects(it,box) }) null else SceneHint(line.text,box)
        }
        return text.timeMs.takeIf { tencentScene(hints,false,false)==TableScene.DEALING }
    }
}
