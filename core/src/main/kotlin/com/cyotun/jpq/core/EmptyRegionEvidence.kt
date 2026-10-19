package com.cyotun.jpq.core

data class EmptyEvidence(val observed: Boolean,val reason: String,val tableRatio: Float?=null)

/** Shared by Android frames and full-video replay: absence of glyphs needs a visible surface boundary. */
class EmptyRegionEvidence {
    private val calibrationRegion=GlyphBox(.33f,.25f,.67f,.40f)
    private var palette: SurfacePalette?=null
    private val regions=mutableMapOf<Seat,GlyphBox>()
    private var previousRound: String?=null
    private val ownRegions=PrebaselineOwnRegionReassignment()

    /** Nonempty detections need no felt-color inference, but still need the
     * complete source mask set and the full possible group area unobstructed. */
    fun directlyObserved(scene: TableScene,seat: Seat,glyphs: List<PlayGlyph>,masks: List<GlyphBox>): Boolean =
        glyphs.isNotEmpty() && !overlayOccluded(scene,seat,glyphs,masks)
    fun overlayOccluded(scene: TableScene,seat: Seat,glyphs: List<PlayGlyph>,masks: List<GlyphBox>): Boolean =
        PlayRegionVisibility.occluded(scene,seat,glyphs,masks)
    private fun glyphRegion(glyphs: List<PlayGlyph>)=GlyphBox(glyphs.minOf { it.box.left }-.005f,glyphs.minOf { it.box.top }-.005f,
        glyphs.maxOf { it.box.right }+.005f,glyphs.maxOf { it.box.bottom }+.005f)

    fun evaluate(pixels: IntArray,width: Int,height: Int,scene: TableScene,handCount: Int,
        groups: Map<Seat,List<PlayGlyph>>,roundId: String?,masks: List<GlyphBox> = emptyList(),
        handGlyphs: List<PlayGlyph> = emptyList(),timeMs: Long?=null,ambiguous: Boolean=false): Map<Seat,EmptyEvidence> {
        if(scene in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.TRIBUTE) ||
            (previousRound!=null && roundId!=null && previousRound!=roundId)) {
            regions.clear(); palette=null
        }
        previousRound=roundId
        ownRegions.step(scene,handCount,handGlyphs,groups,roundId,masks,timeMs,ambiguous).forEach { regions.remove(it) }
        if(palette==null && handCount==27 && groups.values.all { it.isEmpty() } && scene in setOf(TableScene.TABLE,TableScene.DEALING) &&
            masks.none { intersects(it,calibrationRegion) }) {
            palette=SurfacePalette.learn(pixels,width,height,calibrationRegion)
        }
        return Seat.entries.associateWith { seat ->
            val glyphs=groups[seat].orEmpty()
            val possibleMasked=overlayOccluded(scene,seat,emptyList(),masks)
            if(glyphs.isNotEmpty()) {
                val rect=glyphRegion(glyphs)
                val masked=!directlyObserved(scene,seat,glyphs,masks)
                if(!masked && glyphs.minOf { it.confidence }>=.65f) regions[seat]=rect
                EmptyEvidence(!masked,if(masked) "overlay_occlusion" else "detected_glyphs")
            } else {
                val rect=regions[seat]
                when {
                    possibleMasked -> EmptyEvidence(false,"overlay_occlusion")
                    rect==null -> EmptyEvidence(true,"no_prior_presentation")
                    masks.any { intersects(it,rect) } -> EmptyEvidence(false,"overlay_occlusion")
                    palette==null -> EmptyEvidence(false,"surface_not_calibrated")
                    else -> {
                        val ratio=palette!!.tableLikeRatio(pixels,width,height,rect)
                        val empty=SurfacePalette.isCleanEmpty(ratio)
                        EmptyEvidence(empty,if(empty) "table_surface_visible" else "foreground_or_animation",ratio)
                    }
                }
            }
        }
    }
    private fun intersects(a: GlyphBox,b: GlyphBox)=a.left<b.right && b.left<a.right && a.top<b.bottom && b.top<a.bottom
}

/** Shared geometry for live evidence, prebaseline ownership correction and replay. */
internal object PlayRegionVisibility {
    // Whole possible groups include hidden tails; glyph footprints cover prints
    // which extend above the recognizer's center boundary.
    private val side=GlyphBox(.04f,.13f,.94f,.58f)
    private val opposite=GlyphBox(.26f,.13f,.76f,.35f)
    private val self=GlyphBox(.26f,.24f,.76f,.58f)
    fun validBox(box: GlyphBox)=listOf(box.left,box.top,box.right,box.bottom).all { it.isFinite() } &&
        box.left>=0 && box.top>=0 && box.right<=1 && box.bottom<=1 && box.width>0 && box.height>0
    fun intersects(a: GlyphBox,b: GlyphBox)=a.left<b.right && b.left<a.right && a.top<b.bottom && b.top<a.bottom
    fun occluded(scene: TableScene,seat: Seat,glyphs: List<PlayGlyph>,masks: List<GlyphBox>): Boolean {
        val possible=when {
            scene==TableScene.TEAMMATE && seat!=Seat.SELF -> side
            seat==Seat.PREVIOUS || seat==Seat.NEXT -> side
            seat==Seat.OPPOSITE -> opposite
            else -> self
        }
        // The padded sampling region is for felt/foreground classification, not
        // evidence that an overlay covered a printed card. Keep the complete
        // possible-group area above, including an undetected hidden tail.
        val footprint=if(glyphs.isEmpty()) null else GlyphBox(glyphs.minOf { it.box.left },
            glyphs.minOf { it.box.top },glyphs.maxOf { it.box.right },glyphs.maxOf { it.box.bottom })
        return masks.any { !validBox(it) || intersects(it,possible) || (footprint!=null && intersects(it,footprint)) }
    }
}
