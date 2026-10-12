package com.cyotun.jpq.core

data class EmptyEvidence(val observed: Boolean,val reason: String,val tableRatio: Float?=null)

/** Shared by Android frames and full-video replay: absence of glyphs needs a visible surface boundary. */
class EmptyRegionEvidence {
    private val calibrationRegion=GlyphBox(.33f,.25f,.67f,.40f)
    // Side players' long groups can reach across the center. These are deliberately
    // broader than an individual detected group, so a hidden tail is never "complete".
    // Default bars end above .12; moving one into these areas removes observation proof.
    private val sideRegion=GlyphBox(.04f,.12f,.94f,.58f)
    private val oppositeRegion=GlyphBox(.26f,.12f,.76f,.35f)
    private val selfRegion=GlyphBox(.26f,.24f,.76f,.58f)
    private var palette: SurfacePalette?=null
    private val regions=mutableMapOf<Seat,GlyphBox>()
    private var previousRound: String?=null

    fun evaluate(pixels: IntArray,width: Int,height: Int,scene: TableScene,handCount: Int,
        groups: Map<Seat,List<PlayGlyph>>,roundId: String?,masks: List<GlyphBox> = emptyList()): Map<Seat,EmptyEvidence> {
        if(scene in setOf(TableScene.LOBBY,TableScene.RESULT,TableScene.TRIBUTE) ||
            (previousRound!=null && roundId!=null && previousRound!=roundId)) {
            regions.clear(); palette=null
        }
        previousRound=roundId
        if(palette==null && handCount==27 && groups.values.all { it.isEmpty() } && scene in setOf(TableScene.TABLE,TableScene.DEALING) &&
            masks.none { intersects(it,calibrationRegion) }) {
            palette=SurfacePalette.learn(pixels,width,height,calibrationRegion)
        }
        return Seat.entries.associateWith { seat ->
            val glyphs=groups[seat].orEmpty()
            val possible=when {
                scene==TableScene.TEAMMATE && seat!=Seat.SELF -> sideRegion
                seat==Seat.PREVIOUS || seat==Seat.NEXT -> sideRegion
                seat==Seat.OPPOSITE -> oppositeRegion
                else -> selfRegion
            }
            val possibleMasked=masks.any { intersects(it,possible) }
            if(glyphs.isNotEmpty()) {
                val rect=GlyphBox(glyphs.minOf { it.box.left }-.005f,glyphs.minOf { it.box.top }-.005f,
                    glyphs.maxOf { it.box.right }+.005f,glyphs.maxOf { it.box.bottom }+.005f)
                val masked=possibleMasked || masks.any { intersects(it,rect) }
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
