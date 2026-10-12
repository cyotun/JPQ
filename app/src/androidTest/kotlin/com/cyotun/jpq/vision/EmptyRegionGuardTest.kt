package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmptyRegionGuardTest {
    private fun image(name: String): Bitmap {
        val original=InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/$name.jpg").use { BitmapFactory.decodeStream(it) }
        return Bitmap.createScaledBitmap(original,1280,588,true).also { if(it!==original) original.recycle() }
    }
    @Test fun bombForegroundCannotClearPreviouslySeenSteelButTheTableCan() {
        val images=listOf("table-empty","steel-visible","bomb-occlusion","truly-empty").map(::image)
        try {
            val guard=EmptyRegionGuard(); val empty=Seat.entries.associateWith { emptyList<PlayGlyph>() }
            guard.evaluate(images[0],TableScene.DEALING,27,empty,null)
            // Box measurements from the independently confirmed six-card presentation.
            val ranks=listOf(Rank.SIX,Rank.SIX,Rank.SIX,Rank.SEVEN,Rank.SEVEN,Rank.SEVEN)
            val colors=listOf(Suit.RED,Suit.BLACK,Suit.BLACK,Suit.RED,Suit.RED,Suit.BLACK)
            val glyphs=ranks.indices.map { i -> PlayGlyph(Card(ranks[i],colors[i]),.9f,
                GlyphBox(.4162f+i*.0245f,.1591f,.4297f+i*.0245f,.2044f)) }
            guard.evaluate(images[1],TableScene.TABLE,24,empty+(Seat.OPPOSITE to glyphs),"fixture")
            val blocked=guard.evaluate(images[2],TableScene.TABLE,24,empty,"fixture").getValue(Seat.OPPOSITE)
            assertFalse(blocked.reason,blocked.observed)
            assertNotNull(blocked.tableRatio); assertTrue(blocked.tableRatio!!<.1f)
            val cleared=guard.evaluate(images[3],TableScene.TABLE,24,empty,"fixture").getValue(Seat.OPPOSITE)
            assertTrue(cleared.reason,cleared.observed)
            assertTrue(cleared.tableRatio!!>.7f)
        } finally { images.forEach { it.recycle() } }
    }
}
