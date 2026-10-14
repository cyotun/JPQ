package com.cyotun.jpq.core

import kotlin.test.*

class OverlayPositionTest {
    @Test fun aPlayingBarStopRestoresTheSameAnchorWithANarrowerWaitingBanner() {
        val saved=OverlayPosition.save(267,36,2608,1200)
        assertEquals(OverlayPoint(267,36),OverlayPosition.restore(saved.x,saved.y,true,2608,1200,1254,138))
    }

    @Test fun hiddenAndPlayingWindowsUseTheSameScreenAnchor() {
        val saved=OverlayPosition.save(320,205,2608,1200)
        for(width in listOf(132,714,1704)) {
            assertEquals(OverlayPoint(320,205),OverlayPosition.restore(saved.x,saved.y,true,2608,1200,width,138))
        }
    }

    @Test fun legacyAvailableTravelPositionsMigrateWithoutAnInitialJump() {
        val legacy=OverlayPosition.restore(.4f,.25f,false,2608,1200,1254,138)
        assertEquals(OverlayPoint(542,266),legacy)
        val migrated=OverlayPosition.save(legacy.x,legacy.y,2608,1200)
        assertEquals(legacy,OverlayPosition.restore(migrated.x,migrated.y,true,2608,1200,1254,138))
        assertEquals(legacy,OverlayPosition.restore(migrated.x,migrated.y,true,2608,1200,714,132))
    }

    @Test fun restoringAnExpandedBarClampsTheAnchorAtThePhysicalEdge() {
        val saved=OverlayPosition.save(2476,1068,2608,1200)
        assertEquals(OverlayPoint(2476,1068),OverlayPosition.restore(saved.x,saved.y,true,2608,1200,132,132))
        assertEquals(OverlayPoint(904,1062),OverlayPosition.restore(saved.x,saved.y,true,2608,1200,1704,138))
        val stopped=OverlayPosition.save(904,1062,2608,1200)
        assertEquals(OverlayPoint(904,1062),OverlayPosition.restore(stopped.x,stopped.y,true,2608,1200,1254,138))
    }

    @Test fun rotationUsesTheScreenProportionAndClampsAVeryWideWindow() {
        val saved=OverlayPosition.save(652,300,2608,1200)
        assertEquals(OverlayPoint(300,652),OverlayPosition.restore(saved.x,saved.y,true,1200,2608,714,138))
        assertEquals(OverlayPoint(0,652),OverlayPosition.restore(saved.x,saved.y,true,1200,2608,1704,138))
    }

    @Test fun invalidGeometryCannotCreateAPersistedOffscreenAnchor() {
        assertEquals(OverlayPoint(0,0),OverlayPosition.clamp(-30,-4,1200,2608,1704,3000))
        assertEquals(ScreenRelativePosition(0f,1f),OverlayPosition.save(-30,3000,1200,2608))
        assertFailsWith<IllegalArgumentException> { OverlayPosition.restore(Float.NaN,0f,true,1200,2608,10,10) }
        assertFailsWith<IllegalArgumentException> { OverlayPosition.save(0,0,0,2608) }
    }

    @Test fun portraitStartupClampCannotMoveTheNextGameAnchor() {
        val saved=OverlayPosition.save(650,64,2608,1200)
        val memory=OverlayPositionMemory()
        val portrait=OverlayPosition.restore(saved.x,saved.y,true,1200,2608,1110,138)
        assertEquals(90,portrait.x)
        memory.observe(portrait,1200,2608)
        assertNull(memory.onStop(portrait,1200,2608))
        assertEquals(OverlayPoint(650,64),OverlayPosition.restore(saved.x,saved.y,true,2608,1200,1254,138))
    }

    @Test fun returningToPortraitToStopSavesTheLastActualGamePosition() {
        val saved=OverlayPosition.save(650,64,2608,1200)
        val memory=OverlayPositionMemory()
        // Launch portrait, return to a normal game and then open the portrait
        // counter app to stop. The 90px portrait clamp is only presentation.
        memory.observe(OverlayPosition.restore(saved.x,saved.y,true,1200,2608,1110,138),1200,2608)
        val game=OverlayPosition.restore(saved.x,saved.y,true,2608,1200,1704,138)
        memory.observe(game,2608,1200)
        val portrait=OverlayPosition.restore(saved.x,saved.y,true,1200,2608,1110,138)
        memory.observe(portrait,1200,2608)
        val stopped=assertNotNull(memory.onStop(portrait,1200,2608))
        assertEquals(OverlayPoint(650,64),OverlayPosition.restore(stopped.x,stopped.y,true,2608,1200,1254,138))
    }

    @Test fun aGameWidthClampIsSavedAtStopRatherThanThePortraitClamp() {
        val saved=OverlayPosition.save(1200,64,2608,1200)
        val memory=OverlayPositionMemory()
        val game=OverlayPosition.restore(saved.x,saved.y,true,2608,1200,1704,138)
        assertEquals(904,game.x)
        memory.observe(game,2608,1200)
        val portrait=OverlayPosition.restore(saved.x,saved.y,true,1200,2608,1110,138)
        val stopped=assertNotNull(memory.onStop(portrait,1200,2608))
        assertEquals(OverlayPoint(904,64),OverlayPosition.restore(stopped.x,stopped.y,true,2608,1200,1254,138))
    }

    @Test fun anIntentionalPortraitDragReplacesTheOldGamePosition() {
        val memory=OverlayPositionMemory()
        memory.observe(OverlayPoint(650,64),2608,1200)
        val moved=OverlayPoint(45,100)
        memory.userMoved(moved,1200,2608)
        // The drag saves its own screen-relative point; stop must not restore a
        // stale landscape point over that explicit movement.
        assertNull(memory.onStop(moved,1200,2608))
        val saved=OverlayPosition.save(moved.x,moved.y,1200,2608)
        assertEquals(OverlayPoint(45,100),OverlayPosition.restore(saved.x,saved.y,true,1200,2608,1110,138))
    }
}
