package com.cyotun.jpq.vision

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyotun.jpq.ConfigStore
import com.cyotun.jpq.AppState
import com.cyotun.jpq.core.DisplayConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DisplayConfigPersistenceTest {
    @Test fun oldBackupKeepsItsHistoryAndPositionMeaning() {
        check(!AppState.running) { "Do not modify configuration during capture or a real match" }
        val store=ConfigStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val original=store.load()
        try {
            val old=JSONObject().put("schemaVersion",1).put("display",JSONObject()
                .put("history",false).put("reset",false).put("x",.25).put("y",.5))
            val imported=store.import(old.toString())
            assertFalse(imported.showHistory)
            assertTrue(imported.showHistoryDetails)
            assertFalse(imported.positionIsScreenRelative)
            assertEquals(.25f,imported.x,0f); assertEquals(.5f,imported.y,0f)
            assertEquals(imported,store.load())
        } finally { store.save(original) }
    }

    @Test fun countsOnlyAndScreenPositionSurviveBackupAndDraftRoundTrips() {
        check(!AppState.running) { "Do not modify configuration during capture or a real match" }
        val store=ConfigStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val original=store.load()
        try {
            val expected=DisplayConfig(showHistory=true,showHistoryDetails=false,
                positionIsScreenRelative=true,x=.25f,y=.5f)
            store.save(expected)
            val exported=store.export()
            assertTrue(JSONObject(exported).getJSONObject("display").getBoolean("history"))
            assertFalse(JSONObject(exported).getJSONObject("display").getBoolean("historyDetails"))
            store.save(DisplayConfig())
            assertEquals(expected,store.import(exported))
            assertEquals(expected,store.load())
            assertEquals(expected,store.readDraft(store.draftJson(expected)))
        } finally { store.save(original) }
    }
}
