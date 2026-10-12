package com.cyotun.jpq.core

import kotlin.test.*

class LetterboxGeometryTest {
    @Test fun phoneRasterKeepsAnIntegerTopMarginAndTheLongEdge() {
        val box=LetterboxGeometry.fit(2608,1200,1280,640)
        assertEquals(1280,box.width); assertEquals(588,box.height)
        assertEquals(0,box.left); assertEquals(26,box.top)
        assertEquals(1280f/2608,box.scale)
    }
    @Test fun oddRemainderStaysOnTheBottomOrRightWithoutHalfPixelPlacement() {
        val landscape=LetterboxGeometry.fit(1920,882,1280,640)
        assertEquals(588,landscape.height); assertEquals(26,landscape.top)
        val odd=LetterboxGeometry.fit(3,2,8,8)
        assertEquals(5,odd.height); assertEquals(1,odd.top)
        assertEquals(2,8-odd.top-odd.height)
        val upright=LetterboxGeometry.fit(2,3,8,8)
        assertEquals(5,upright.width); assertEquals(1,upright.left)
    }
}
