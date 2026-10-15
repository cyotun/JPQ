package com.cyotun.jpq.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SceneOcrImageTest {
    @Test fun isolatedCopiesIncludeBothMeasuredHeaderAndBodyAndCannotShareASymbolElement() {
        val source=Bitmap.createBitmap(2608,1200,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(source); val paint=android.graphics.Paint()
            canvas.drawColor(Color.rgb(9,108,97))
            listOf(262 to Color.rgb(146,154,149),369 to Color.rgb(194,52,45)).forEach { (left,color) ->
                paint.color=color; canvas.drawRect(left.toFloat(),10f,left+95f,57f,paint)
                paint.color=Color.WHITE; canvas.drawRect(left-5f,59f,left+100f,125f,paint)
            }
            val panels=TencentHudPanels.detect(source)
            assertEquals(2,panels.size)
            SceneOcrImage(source).use { packed ->
                assertEquals(2,packed.hudRegions.size)
                panels.zip(packed.hudRegions).forEach { (panel,region) ->
                    assertTrue(region.source.contains(panel.header)); assertTrue(region.source.contains(panel.body))
                    assertEquals(3,region.scale)
                }
                val first=packed.hudRegions[0].target; val second=packed.hudRegions[1].target
                val child=Rect(second.left+10,second.top+10,second.left+35,second.top+35)
                assertNotNull(packed.toSource(child))
                assertNull(packed.symbolToSource(child,Rect(first.left,first.top,second.right,second.bottom)))
            }
        } finally { source.recycle() }
    }
    @Test fun hudCopyHasSourceColourAndSceneKeepsItsOriginalScale() {
        val source=Bitmap.createBitmap(2608,1200,Bitmap.Config.ARGB_8888)
        try {
            Canvas(source).apply { drawColor(Color.GREEN); drawRect(143f,0f,600f,156f,android.graphics.Paint().apply { color=Color.RED }) }
            SceneOcrImage(source).use { packed ->
                assertEquals(1280,packed.sceneWidth); assertEquals(588,packed.sceneHeight)
                val region=packed.hudRegions.single()
                assertEquals(Color.RED,packed.bitmap.getPixel(region.target.left+20,region.target.top+20))
                assertEquals(Color.GREEN,packed.bitmap.getPixel(900,300))
                assertEquals(Color.BLACK,packed.bitmap.getPixel(20,packed.sceneHeight+8))
                assertEquals(Rect(253,60,283,100),packed.toSource(Rect(region.target.left+220,region.target.top+120,region.target.left+280,region.target.top+200)))
            }
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    @Test fun paddingAndBoxesSpanningSceneAndHudNeverBecomePositionedEvidence() {
        val source=Bitmap.createBitmap(2608,1200,Bitmap.Config.ARGB_8888)
        try { SceneOcrImage(source).use { packed ->
            val target=packed.hudRegions.single().target
            assertNull(packed.toSource(Rect(0,packed.sceneHeight+2,50,target.top+2)))
            assertNull(packed.toSource(Rect(0,packed.sceneHeight-4,50,target.top+4)))
            assertNull(packed.toSource(Rect(target.right+1,target.top,target.right+20,target.top+20)))
            assertNull(packed.toSource(Rect(-2,0,10,10)))
            assertNull(packed.toSource(Rect(0,0,0,10)))
            val symbol=Rect(target.left+10,target.top+10,target.left+25,target.top+35)
            assertNotNull(packed.toSource(symbol))
            assertNull(packed.symbolToSource(symbol,Rect(0,packed.sceneHeight-4,target.right,target.bottom)))
            assertNull(packed.symbolToSource(symbol,Rect(symbol.left+2,symbol.top,symbol.right,symbol.bottom)))
            assertEquals(packed.toSource(symbol),packed.symbolToSource(symbol,Rect(target)))
        } } finally { source.recycle() }
    }

    @Test fun sceneBoxesMapBackToFullPhysicalResolutionAndPortraitHasNoHudDuplicate() {
        val landscape=Bitmap.createBitmap(2560,1200,Bitmap.Config.ARGB_8888)
        val portrait=Bitmap.createBitmap(1200,2608,Bitmap.Config.ARGB_8888)
        try {
            SceneOcrImage(landscape).use { assertEquals(Rect(200,100,300,200),it.toSource(Rect(100,50,150,100))) }
            SceneOcrImage(portrait).use {
                assertEquals(it.sceneHeight,it.bitmap.height)
                assertNull(it.toSource(Rect(0,it.sceneHeight+32,10,it.sceneHeight+52)))
            }
        } finally { landscape.recycle(); portrait.recycle() }
    }
}
