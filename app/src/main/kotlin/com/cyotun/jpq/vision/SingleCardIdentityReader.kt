package com.cyotun.jpq.vision

import android.graphics.Bitmap
import com.cyotun.jpq.core.*

/** Tiny current-image ROI; no OCR wait, bitmap retention, or network request. */
class SingleCardIdentityReader {
    private val reader=SuitPixelReader()
    fun read(bitmap: Bitmap,play: ObservedPlay,timeMs: Long,masks: List<GlyphBox>): SingleCardIdentity? {
        val glyph=play.glyphs.singleOrNull() ?: return null
        if(!play.regionObserved || play.cards.singleOrNull()!=glyph.card || glyph.card.rank.isJoker ||
            play.confidence<.80f) return null
        val result=reader.read(bitmap,glyph,SuitPixelReader.Placement.TABLE,masks)
        val suit=result.suit ?: return null
        if(result.reason!="shape_matched" || result.confidence<.80f || result.margin<.12f) return null
        val card=Card(glyph.card.rank,suit)
        return SingleCardIdentity(card,result.confidence,result.margin,timeMs)
            .takeIf { it.card.colorKey()==glyph.card.colorKey() }
    }
}
